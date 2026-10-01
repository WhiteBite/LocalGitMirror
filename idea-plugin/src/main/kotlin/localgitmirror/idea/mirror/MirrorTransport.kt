package localgitmirror.idea.mirror

import java.io.File
import java.io.OutputStreamWriter
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import localgitmirror.idea.net.HttpClient

data class HttpResult(val code: Int, val body: String, val bytes: ByteArray? = null)

data class DownloadResult(
  val code: Int,
  val file: File?,
  val message: String,
  val head: String? = null,
  val repo: String? = null,
  /** True when the file already holds plaintext (v3 relay opened server-side re-seal). */
  val decrypted: Boolean = false
)

internal object MirrorTransport {
  fun rid(repoName: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(("lgm-repo-id:" + repoName).toByteArray(StandardCharsets.UTF_8))
    val hex = StringBuilder(bytes.size * 2)
    for (b in bytes) hex.append("%02x".format(b))
    return hex.toString().take(16)
  }

  fun parseDepsList(body: String): List<MirrorDepsApi.DepsItem> {
    return try {
      val el = Json.parseToJsonElement(body)
      val arr = when (el) {
        is JsonArray -> el
        is JsonObject -> el["items"] as? JsonArray ?: return emptyList()
        else -> return emptyList()
      }
      arr.mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val size = o["size"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val mtime = o["mtime"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        MirrorDepsApi.DepsItem(id, size, mtime)
      }
    } catch (_: Throwable) {
      emptyList()
    }
  }

  fun multipartUpload(
    baseUrl: String,
    apiKey: String,
    insecureTls: Boolean,
    path: String,
    fields: Map<String, String>,
    fileFieldName: String,
    fileName: String,
    fileBytes: ByteArray
  ): HttpResult {
    return try {
      val boundary = "----FormBoundary${UUID.randomUUID()}"
      val url = URL("${baseUrl.trimEnd('/')}$path")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 60_000
      conn.readTimeout = 600_000  // big archives may take a while
      conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")

      conn.outputStream.use { os ->
        val writer = OutputStreamWriter(os, StandardCharsets.UTF_8)
        for ((name, value) in fields) {
          writer.write("--$boundary\r\n")
          writer.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
          writer.write(value); writer.write("\r\n"); writer.flush()
        }
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"\r\n")
        writer.write("Content-Type: application/octet-stream\r\n\r\n")
        writer.flush()
        os.write(fileBytes); os.flush()
        writer.write("\r\n--$boundary--\r\n"); writer.flush()
      }
      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  fun multipartUploadFile(
    baseUrl: String,
    apiKey: String,
    insecureTls: Boolean,
    path: String,
    fields: Map<String, String>,
    fileFieldName: String,
    fileName: String,
    file: File,
    onProgress: ((sent: Long, total: Long) -> Unit)? = null
  ): HttpResult {
    return try {
      val boundary = "----FormBoundary${UUID.randomUUID()}"
      val url = URL("${baseUrl.trimEnd('/')}$path")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 60_000
      conn.readTimeout = 600_000
      conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")

      conn.outputStream.use { os ->
        val writer = OutputStreamWriter(os, StandardCharsets.UTF_8)
        for ((name, value) in fields) {
          writer.write("--$boundary\r\n")
          writer.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
          writer.write(value); writer.write("\r\n"); writer.flush()
        }
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"\r\n")
        writer.write("Content-Type: application/octet-stream\r\n\r\n")
        writer.flush()
        file.inputStream().use { input ->
          val buf = ByteArray(1024 * 1024)
          val total = file.length()
          var sent = 0L
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            os.write(buf, 0, n)
            sent += n
            onProgress?.invoke(sent, total)
          }
        }
        os.flush()
        writer.write("\r\n--$boundary--\r\n"); writer.flush()
      }
      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  /** Read a JSON array of strings, tolerating missing/null/non-array fields. */
  fun stringList(obj: JsonObject, field: String): List<String> {
    val el = obj[field] ?: return emptyList()
    if (el !is JsonArray) return emptyList()
    return el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
  }
}
