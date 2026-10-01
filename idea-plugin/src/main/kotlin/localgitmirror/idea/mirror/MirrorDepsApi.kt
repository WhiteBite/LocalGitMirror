package localgitmirror.idea.mirror

import java.io.File
import java.net.URL

import localgitmirror.idea.net.HttpClient
import localgitmirror.idea.workkit.HybridCrypto

object MirrorDepsApi {
  data class DepsItem(val id: String, val size: Long, val mtime: Long)
  data class DepsListResult(val code: Int, val items: List<DepsItem>, val message: String)
  data class DepsUploadResult(val code: Int, val id: String?, val size: Long, val message: String)

  /** Dome side: post the encrypted manifest. */
  fun depsRequest(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean,
    encryptedManifest: ByteArray,
    epkB64: String? = null
  ): DepsUploadResult {
    val fields = mutableMapOf("rid" to MirrorTransport.rid(repo))
    if (epkB64 != null) fields["k"] = epkB64
    val res = MirrorTransport.multipartUpload(
      baseUrl, apiKey, insecureTls, "/api/documents/submit",
      fields = fields,
      fileFieldName = "attachment",
      fileName = "data.bin",
      fileBytes = encryptedManifest
    )
    if (res.code !in 200..299) return DepsUploadResult(res.code, null, 0, res.body.take(500))
    val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(res.body)?.groupValues?.getOrNull(1)
    val size = Regex(""""size"\s*:\s*(\d+)""").find(res.body)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
    return DepsUploadResult(res.code, id, size, "OK")
  }

  /** Work side: list pending requests. */
  fun depsPending(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean
  ): DepsListResult = depsList(baseUrl, apiKey, repo, insecureTls, path = "/api/documents/queue")

  /** Dome side: list ready responses. */
  fun depsResponses(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean
  ): DepsListResult = depsList(baseUrl, apiKey, repo, insecureTls, path = "/api/documents/ready")

  private fun depsList(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean, path: String
  ): DepsListResult {
    return try {
      val r = MirrorTransport.rid(repo)
      val url = URL("${baseUrl.trimEnd('/')}$path?rid=${java.net.URLEncoder.encode(r, "UTF-8")}")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) return DepsListResult(code, emptyList(), body.take(500))
      DepsListResult(code, MirrorTransport.parseDepsList(body), "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      DepsListResult(0, emptyList(), "${e.type}: ${e.message}")
    }
  }

  /**
   * Download a manifest blob (work side) or response blob (dome side).
   *
   * In v3 mode (pinned server key) the request carries `X-LGM-Epk` and the
   * server re-seals the blob to that ephemeral; it is opened here, so the
   * output file holds PLAINTEXT and [DownloadResult.decrypted] is true.
   * Otherwise the file holds the legacy password blob as before.
   */
  fun depsDownload(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean,
    id: String, kind: DepsKind, outFile: File,
    onProgress: ((read: Long, total: Long) -> Unit)? = null
  ): DownloadResult {
    val pathBase = if (kind == DepsKind.MANIFEST) "/api/documents/queue-item" else "/api/documents/ready-item"
    val aad = if (kind == DepsKind.MANIFEST) HybridCrypto.RELAY_AAD_DEPS_REQ else HybridCrypto.RELAY_AAD_DEPS_RESP
    val session = MirrorCrypto.pinnedServerPub()?.let { HybridCrypto.Session.create(it) }
    return try {
      val r = MirrorTransport.rid(repo)
      val url = URL(
        "${baseUrl.trimEnd('/')}$pathBase?rid=${java.net.URLEncoder.encode(r, "UTF-8")}" +
          "&id=${java.net.URLEncoder.encode(id, "UTF-8")}"
      )
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 60_000
      conn.readTimeout = 600_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      session?.let { conn.setRequestProperty("X-LGM-Epk", it.epkB64) }
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
      if (session != null) {
        outFile.writeBytes(session.openRelay(outFile.readBytes(), aad))
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

  enum class DepsKind { MANIFEST, RESPONSE }

  /** Work side: upload encrypted archive in response to a pending manifest. */
  fun depsRespond(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean,
    requestId: String, encryptedArchive: ByteArray,
    epkB64: String? = null
  ): DepsUploadResult {
    val fields = mutableMapOf("rid" to MirrorTransport.rid(repo), "request_id" to requestId)
    if (epkB64 != null) fields["k"] = epkB64
    val res = MirrorTransport.multipartUpload(
      baseUrl, apiKey, insecureTls, "/api/documents/fulfill",
      fields = fields,
      fileFieldName = "attachment",
      fileName = "data.bin",
      fileBytes = encryptedArchive
    )
    if (res.code !in 200..299) return DepsUploadResult(res.code, null, 0, res.body.take(500))
    val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(res.body)?.groupValues?.getOrNull(1)
    val size = Regex(""""size"\s*:\s*(\d+)""").find(res.body)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
    return DepsUploadResult(res.code, id, size, "OK")
  }

  /** Dome side: confirm response applied; server will delete it. */
  fun depsAck(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean, id: String
  ): HttpResult {
    return try {
      val r = MirrorTransport.rid(repo)
      val url = URL(
        "${baseUrl.trimEnd('/')}/api/documents/ack?rid=${java.net.URLEncoder.encode(r, "UTF-8")}" +
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
