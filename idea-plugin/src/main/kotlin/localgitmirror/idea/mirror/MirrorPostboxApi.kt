package localgitmirror.idea.mirror

import java.io.File
import java.net.URL
import java.util.Base64 as JavaBase64

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.net.HttpClient
import localgitmirror.idea.workkit.HybridCrypto

object MirrorPostboxApi {
  data class FileSyncItem(val id: String, val path: String, val size: Long, val plainSize: Long, val mtime: Long)
  data class FileSyncListResult(val code: Int, val items: List<FileSyncItem>, val message: String)
  data class FileSyncUploadResult(val code: Int, val id: String?, val path: String?, val size: Long, val message: String)

  /** v3-only upload: multipart fields `rid`, `k` (url-safe b64 epk), `meta` (b64 of relay-sealed {"path","plain_size"}) and the relay-sealed `attachment`; no cleartext path fields. */
  fun fileSyncUpload(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean,
    epkB64: String, metaSealed: ByteArray, encryptedFile: File,
    onProgress: ((sent: Long, total: Long) -> Unit)? = null
  ): FileSyncUploadResult {
    val fields = mapOf(
      "rid" to MirrorTransport.rid(repo),
      "k" to epkB64,
      "meta" to JavaBase64.getEncoder().encodeToString(metaSealed),
    )
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
        FileSyncItem(id, p, size, plain, mtime)
      } ?: emptyList()
      FileSyncListResult(code, items, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      FileSyncListResult(0, emptyList(), "${e.type}: ${e.message}")
    }
  }

  /** v3-only: carries X-LGM-Epk; the server re-seals to it, so the output file holds plaintext ([DownloadResult.decrypted] is true). */
  fun fileSyncDownload(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean,
    id: String, outFile: File, onProgress: ((read: Long, total: Long) -> Unit)? = null
  ): DownloadResult {
    val pub = MirrorCrypto.pinnedServerPub()
      ?: return DownloadResult(0, null, "v3 server key not pinned")
    val session = HybridCrypto.Session.create(pub)
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
      conn.setRequestProperty("X-LGM-Epk", session.epkB64)
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
      outFile.writeBytes(session.openRelay(outFile.readBytes(), HybridCrypto.RELAY_AAD_POSTBOX))
      DownloadResult(code, outFile, "OK", decrypted = true)
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      DownloadResult(0, null, "${e.type}: ${e.message}")
    } finally {
      session.wipe()
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
