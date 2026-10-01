package localgitmirror.idea.mirror

import java.io.File
import java.net.URL
import java.util.Base64 as JavaBase64
import java.util.UUID

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.net.HttpClient
import localgitmirror.idea.workkit.HybridCrypto

object MirrorPostboxApi {
  data class FileSyncItem(val id: String, val path: String, val size: Long, val plainSize: Long, val mtime: Long, val pathEnc: String = "")
  data class FileSyncListResult(val code: Int, val items: List<FileSyncItem>, val message: String)
  data class FileSyncUploadResult(val code: Int, val id: String?, val path: String?, val size: Long, val message: String)

  fun fileSyncUpload(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean,
    relativePath: String, plainSize: Long, encryptedFile: File,
    pathEnc: String? = null,
    epkB64: String? = null,
    metaSealed: ByteArray? = null,
    onProgress: ((sent: Long, total: Long) -> Unit)? = null
  ): FileSyncUploadResult {
    // sealed metadata on the wire means the clear path/plain_size fields must stay neutral
    val wirePath = if (metaSealed == null) relativePath else "x/${UUID.randomUUID().toString().take(8)}"
    val wireSize = if (metaSealed == null) plainSize else 0L
    val fields = mutableMapOf("rid" to MirrorTransport.rid(repo), "path" to wirePath, "plain_size" to wireSize.toString())
    if (pathEnc != null) fields["path_enc"] = pathEnc
    if (epkB64 != null) fields["k"] = epkB64
    if (metaSealed != null) fields["meta"] = JavaBase64.getEncoder().encodeToString(metaSealed)
    val res = MirrorTransport.multipartUploadFile(
      baseUrl, apiKey, insecureTls, "/api/documents/attachment-upload",
      fields = fields,
      fileFieldName = "attachment",
      fileName = "data.bin",
      file = encryptedFile,
      onProgress = onProgress
    )
    if (res.code !in 200..299) return FileSyncUploadResult(res.code, null, null, 0, res.body.take(500))
    val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(res.body)?.groupValues?.getOrNull(1)
    val pathValue = Regex(""""path"\s*:\s*"([^"]+)"""").find(res.body)?.groupValues?.getOrNull(1)
    val size = Regex(""""size"\s*:\s*(\d+)""").find(res.body)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
    return FileSyncUploadResult(res.code, id, pathValue, size, "OK")
  }

  fun fileSyncList(baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean): FileSyncListResult {
    return try {
      val r = MirrorTransport.rid(repo)
      val url = URL("${baseUrl.trimEnd('/')}/api/documents/attachment-list?rid=${java.net.URLEncoder.encode(r, "UTF-8")}")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) return FileSyncListResult(code, emptyList(), body.take(500))
      val root = Json.parseToJsonElement(body).jsonObject
      val items = root["items"]?.jsonArray?.mapNotNull { el ->
        val o = el.jsonObject
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val p = o["path"]?.jsonPrimitive?.contentOrNull ?: ""
        val size = o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val plain = o["plain_size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val mtime = o["mtime"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val pathEnc = o["path_enc"]?.jsonPrimitive?.contentOrNull ?: ""
        FileSyncItem(id, p, size, plain, mtime, pathEnc)
      } ?: emptyList()
      FileSyncListResult(code, items, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      FileSyncListResult(0, emptyList(), "${e.type}: ${e.message}")
    }
  }

  /**
   * Download a postbox blob.
   *
   * In v3 mode (pinned server key) the request carries `X-LGM-Epk` and the
   * server re-seals the blob to that ephemeral; it is opened here, so the
   * output file holds PLAINTEXT and [DownloadResult.decrypted] is true.
   * Otherwise the file holds the legacy password container as before.
   */
  fun fileSyncDownload(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean,
    id: String, outFile: File, onProgress: ((read: Long, total: Long) -> Unit)? = null
  ): DownloadResult {
    val session = MirrorCrypto.pinnedServerPub()?.let { HybridCrypto.Session.create(it) }
    return try {
      val r = MirrorTransport.rid(repo)
      val url = URL(
        "${baseUrl.trimEnd('/')}/api/documents/attachment-get?rid=${java.net.URLEncoder.encode(r, "UTF-8")}" +
          "&id=${java.net.URLEncoder.encode(id, "UTF-8")}"
      )
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 60_000
      conn.readTimeout = 600_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      session?.let { conn.setRequestProperty("X-LGM-Epk", it.epkB64) }
      val code = conn.responseCode
      if (code !in 200..299) return DownloadResult(code, null, HttpClient.readBody(conn).take(500))
      val total = conn.contentLengthLong
      conn.inputStream.use { input ->
        outFile.outputStream().use { out ->
          val buf = ByteArray(1024 * 1024)
          var read = 0L
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            read += n
            onProgress?.invoke(read, total)
          }
        }
      }
      if (session != null) {
        outFile.writeBytes(session.openRelay(outFile.readBytes(), HybridCrypto.RELAY_AAD_POSTBOX))
        DownloadResult(code, outFile, "OK", decrypted = true)
      } else {
        DownloadResult(code, outFile, "OK")
      }
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      DownloadResult(0, null, "${e.type}: ${e.message}")
    } finally {
      session?.wipe()
    }
  }

  fun fileSyncAck(baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean, id: String): HttpResult {
    return try {
      val r = MirrorTransport.rid(repo)
      val url = URL(
        "${baseUrl.trimEnd('/')}/api/documents/attachment-ack?rid=${java.net.URLEncoder.encode(r, "UTF-8")}" +
          "&id=${java.net.URLEncoder.encode(id, "UTF-8")}"
      )
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "DELETE"
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }
}
