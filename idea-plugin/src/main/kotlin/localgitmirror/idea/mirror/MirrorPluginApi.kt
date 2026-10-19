package localgitmirror.idea.mirror

import java.io.File
import java.net.URL

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.net.HttpClient
import localgitmirror.idea.workkit.BundleCrypto
import localgitmirror.idea.workkit.EnvelopeCrypto

object MirrorPluginApi {

  const val SYNC_PASSWORD_REQUIRED_PREFIX = "sync-password-required:"

  /** Server refuses enc=0 outright (typed 400 enc_required); every request must carry enc=1. */
  internal fun pluginInfoQuery(): String = "?enc=1"

  internal fun pluginDownloadQuery(): String = "?enc=1"

  /** Null when the password is usable; otherwise a typed message for the UI to surface. */
  internal fun requireSyncPassword(syncPassword: String, op: String): String? {
    if (syncPassword.isNotBlank()) return null
    return "$SYNC_PASSWORD_REQUIRED_PREFIX $op needs a sync password configured in DocCache settings; unencrypted plugin transfer is disabled on the server."
  }

  data class PluginInfo(
    val code: Int,
    val available: Boolean,
    val version: String?,
    val filename: String?,
    val size: Long,
    val builtAt: String?,
    val message: String,
    /** SHA-256 of the archive for download integrity verification (null on old servers). */
    val sha256: String? = null
  )

  /** Query metadata of the freshest plugin build on the server (auth-gated).
    *  Metadata travels inside the encrypted envelope — the plain JSON would leak
    *  the tool's filename. Old servers answer plain JSON to the same request
    *  (unknown query param), which we still parse. */
  fun pluginInfo(baseUrl: String, apiKey: String, insecureTls: Boolean, syncPassword: String = ""): PluginInfo {
    requireSyncPassword(syncPassword, "pluginInfo")?.let {
      return PluginInfo(0, false, null, null, 0L, null, it)
    }
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/plugin/info${pluginInfoQuery()}")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 15_000
      conn.readTimeout = 15_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) {
        return PluginInfo(code, false, null, null, 0L, null, body.take(500))
      }
      var root = Json.parseToJsonElement(body).jsonObject
      root["e"]?.jsonPrimitive?.contentOrNull?.let { e ->
        root = runCatching { EnvelopeCrypto.decryptJson(e, syncPassword) }.getOrNull()
          ?: return PluginInfo(0, false, null, null, 0L, null, "envelope decrypt failed")
      }
      PluginInfo(
        code = code,
        available = root["available"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true,
        version = root["version"]?.jsonPrimitive?.contentOrNull,
        filename = root["filename"]?.jsonPrimitive?.contentOrNull,
        size = root["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
        builtAt = root["built_at"]?.jsonPrimitive?.contentOrNull,
        message = "OK",
        sha256 = root["sha256"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length == 64 }
      )
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      PluginInfo(0, false, null, null, 0L, null, "${e.type}: ${e.message}")
    }
  }

  /**
    * Stream the newest plugin .zip into [outFile]. Reports progress in the same
    * shape as [MirrorSyncApi.exportDump]/[MirrorDepsApi.depsDownload] so the caller can drive a progress bar.
    */
  fun pluginDownload(
    baseUrl: String,
    apiKey: String,
    insecureTls: Boolean,
    outFile: File,
    syncPassword: String = "",
    onProgress: ((read: Long, total: Long) -> Unit)? = null
  ): DownloadResult {
    requireSyncPassword(syncPassword, "pluginDownload")?.let {
      return DownloadResult(0, null, it)
    }
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/plugin/latest${pluginDownloadQuery()}")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 30_000
      conn.readTimeout = 300_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      if (code !in 200..299) {
        return DownloadResult(code, null, HttpClient.readBody(conn).take(500))
      }
      val total = conn.contentLengthLong
      conn.inputStream.use { input ->
        outFile.outputStream().use { out ->
          val buf = ByteArray(64 * 1024)
          var read = 0L
          var lastReport = 0L
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            read += n
            if (onProgress != null && read - lastReport >= 200 * 1024) {
              onProgress(read, total)
              lastReport = read
            }
          }
          onProgress?.invoke(read, total)
        }
      }
      val head = outFile.inputStream().use { it.readNBytes(2) }
      // PK magic = a legacy server ignored ?enc=1 and sent the plain zip.
      val isPlainZip = head.size == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
      if (!isPlainZip) {
        outFile.writeBytes(BundleCrypto.decryptDumpBytes(outFile.readBytes(), syncPassword))
      }
      DownloadResult(code, outFile, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      DownloadResult(0, null, "${e.type}: ${e.message}")
    }
  }
}
