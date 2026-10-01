package localgitmirror.idea.mirror

import java.net.URL

import localgitmirror.idea.net.HttpClient

object MirrorVaultApi {
  data class MirrorPublishResult(val code: Int, val added: Int, val existed: Int, val conflicts: Int, val rejected: Int, val message: String)

  /** Fetch vault inventory for delta-sync. */
  fun mirrorIndex(
    baseUrl: String, apiKey: String, insecureTls: Boolean
  ): HttpResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/cache/index")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  /** Fetch a single maven artifact from the vault m2 data plane (loopback-only). */
  fun vaultM2Fetch(
    baseUrl: String,
    apiKey: String,
    insecureTls: Boolean,
    mavenPath: String
  ): HttpResult {
    return try {
      val encoded = mavenPath.split("/").joinToString("/") {
        java.net.URLEncoder.encode(it, "UTF-8")
      }
      val url = URL("${baseUrl.trimEnd('/')}/api/cache/m2/$encoded")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 30_000
      conn.readTimeout = 120_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      if (code !in 200..299) {
        return HttpResult(code, HttpClient.readBody(conn))
      }
      val bytes = conn.inputStream.use { it.readBytes() }
      HttpResult(code, "", bytes)
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  /** Fetch vault diagnostics status. */
  fun mirrorStatus(
    baseUrl: String, apiKey: String, insecureTls: Boolean
  ): HttpResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/cache/status")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  /** Upload encrypted publication to vault. */
  fun mirrorPublish(
    baseUrl: String, apiKey: String, insecureTls: Boolean,
    encryptedPublication: ByteArray,
    epkB64: String? = null
  ): MirrorPublishResult {
    val fields = if (epkB64 != null) mapOf("k" to epkB64) else emptyMap()
    val res = MirrorTransport.multipartUpload(
      baseUrl, apiKey, insecureTls, "/api/cache/publish",
      fields = fields,
      fileFieldName = "attachment",
      fileName = "data.bin",
      fileBytes = encryptedPublication
    )
    if (res.code !in 200..299) return MirrorPublishResult(res.code, 0, 0, 0, 0, res.body.take(500))
    val body = res.body
    val added = Regex(""""added"\s*:\s*(\d+)""").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val existed = Regex(""""existed"\s*:\s*(\d+)""").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val conflicts = Regex(""""conflicts"\s*:\s*(\d+)""").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val rejected = Regex(""""rejected"\s*:\s*(\d+)""").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    return MirrorPublishResult(res.code, added, existed, conflicts, rejected, "OK")
  }
}
