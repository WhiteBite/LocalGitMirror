package localgitmirror.idea.mirror

import java.io.File
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64 as JavaBase64

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.net.HttpClient
import localgitmirror.idea.workkit.HybridCrypto

object MirrorBufferApi {
  data class BufferItem(
    val id: String,
    val ts: Double,
    val size: Long,
    val hint: String,
    val hintEnc: String = "",
    val pinned: Boolean = false
  )
  data class BufferListResult(val code: Int, val items: List<BufferItem>, val message: String)
  data class BufferPutResult(val code: Int, val id: String?, val ts: Double, val message: String)

  /** Preview travels as [hintEnc] with a password; without one (v3 pinned) it degrades to the plaintext [hint]. */
  fun bufferPut(
    baseUrl: String,
    apiKey: String,
    insecureTls: Boolean,
    ciphertext: ByteArray,
    hintEnc: String,
    pinned: Boolean = false,
    epkB64: String? = null,
    hint: String = ""
  ): BufferPutResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/buffer")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 30_000
      conn.readTimeout = 60_000
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")

      val payload = MirrorCrypto.buildBufferPutJson(
        JavaBase64.getEncoder().encodeToString(ciphertext), hintEnc, pinned, epkB64, hint)
      conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }

      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) return BufferPutResult(code, null, 0.0, body.take(500))
      val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1)
      val ts = Regex(""""ts"\s*:\s*([0-9.]+)""").find(body)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
      BufferPutResult(code, id, ts, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      BufferPutResult(0, null, 0.0, "${e.type}: ${e.message}")
    }
  }

  /** Fetch metadata for the latest entries (newest first). */
  fun bufferList(baseUrl: String, apiKey: String, insecureTls: Boolean): BufferListResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/buffer")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 15_000
      conn.readTimeout = 15_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) return BufferListResult(code, emptyList(), body.take(500))

      val items = mutableListOf<BufferItem>()
      val root = Json.parseToJsonElement(body).jsonObject
      val arr = root["items"]
      if (arr != null && arr is kotlinx.serialization.json.JsonArray) {
        for (el in arr) {
          val o = el.jsonObject
          items.add(
            BufferItem(
              id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue,
              ts = o["ts"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
              size = o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
              hint = o["hint"]?.jsonPrimitive?.contentOrNull ?: "",
              hintEnc = o["hint_enc"]?.jsonPrimitive?.contentOrNull ?: "",
              pinned = o["pinned"]?.jsonPrimitive?.booleanOrNull ?: false
            )
          )
        }
      }
      BufferListResult(code, items, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      BufferListResult(0, emptyList(), "${e.type}: ${e.message}")
    }
  }

  /**
   * Fetch a single entry body.
   *
   * In v3 mode (pinned server key) the request carries `X-LGM-Epk` and the
   * server re-seals the blob to that ephemeral; it is opened here, so the
   * returned file holds PLAINTEXT and [DownloadResult.decrypted] is true.
   * Otherwise the file holds the legacy password container as before.
   */
  fun bufferGet(baseUrl: String, apiKey: String, insecureTls: Boolean, id: String): DownloadResult {
    val session = MirrorCrypto.pinnedServerPub()?.let { HybridCrypto.Session.create(it) }
    return try {
      val safeId = java.net.URLEncoder.encode(id, "UTF-8")
      val url = URL("${baseUrl.trimEnd('/')}/api/buffer/$safeId")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 30_000
      conn.readTimeout = 60_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      session?.let { conn.setRequestProperty("X-LGM-Epk", it.epkB64) }
      val code = conn.responseCode
      if (code !in 200..299) return DownloadResult(code, null, HttpClient.readBody(conn).take(500))
      val bytes = conn.inputStream.use { it.readBytes() }
      val plain = if (session != null) session.openRelay(bytes, HybridCrypto.RELAY_AAD_BUFFER) else bytes
      val tmp = File.createTempFile("tmp-", ".bin").apply { writeBytes(plain); deleteOnExit() }
      DownloadResult(code, tmp, "OK", decrypted = session != null)
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      DownloadResult(0, null, "${e.type}: ${e.message}")
    } finally {
      session?.wipe()
    }
  }

  fun bufferPin(baseUrl: String, apiKey: String, insecureTls: Boolean, id: String, pinned: Boolean): HttpResult {
    return try {
      val safeId = java.net.URLEncoder.encode(id, "UTF-8")
      val url = URL("${baseUrl.trimEnd('/')}/api/buffer/$safeId/pin")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 15_000
      conn.readTimeout = 30_000
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      conn.outputStream.use { it.write("""{"pinned":$pinned}""".toByteArray(StandardCharsets.UTF_8)) }
      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  fun bufferDelete(baseUrl: String, apiKey: String, insecureTls: Boolean, id: String): HttpResult {
    return try {
      val safeId = java.net.URLEncoder.encode(id, "UTF-8")
      val url = URL("${baseUrl.trimEnd('/')}/api/buffer/$safeId")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "DELETE"
      conn.connectTimeout = 15_000
      conn.readTimeout = 30_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  fun bufferClear(baseUrl: String, apiKey: String, insecureTls: Boolean): HttpResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/buffer")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "DELETE"
      conn.connectTimeout = 15_000
      conn.readTimeout = 30_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }
}
