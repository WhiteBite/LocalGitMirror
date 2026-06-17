package localgitmirror.idea.net

import com.intellij.util.net.HttpConfigurable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.SSLException
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object HttpClient {
  data class Response(val code: Int, val body: String)
  data class ErrorInfo(val type: String, val message: String)

  private fun trustAllSslSocketFactory(): SSLSocketFactory {
    val trustAll = arrayOf<TrustManager>(
      object : X509TrustManager {
        override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
      }
    )
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, trustAll, java.security.SecureRandom())
    return ctx.socketFactory
  }

  private val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }

  private fun openWithIdeProxy(url: URL): HttpURLConnection {
    return try {
      // Use IntelliJ HTTP stack so plugin requests honor IDE proxy settings
      // (manual/auto proxy + auth configured in IDE).
      HttpConfigurable.getInstance().openConnection(url.toExternalForm()) as HttpURLConnection
    } catch (_: Throwable) {
      // Fallback for environments where IDE service is unavailable.
      url.openConnection() as HttpURLConnection
    }
  }

  fun open(url: URL, insecureTls: Boolean): HttpURLConnection {
    val conn = openWithIdeProxy(url)
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
    if (insecureTls && conn is HttpsURLConnection) {
      conn.sslSocketFactory = trustAllSslSocketFactory()
      conn.hostnameVerifier = trustAllHostnameVerifier
    }
    return conn
  }

  fun readBody(conn: HttpURLConnection): String {
    return readBodyWithProgress(conn, null)
  }

  /**
   * Read the response body, optionally reporting download progress.
   *
   * @param conn     the open connection (responseCode already triggered)
   * @param onProgress callback receiving (bytesRead, totalBytes).
   *                   totalBytes is -1 when Content-Length is unknown.
   *                   Called on the current thread — do not do heavy work in it.
   */
  fun readBodyWithProgress(
    conn: HttpURLConnection,
    onProgress: ((read: Long, total: Long) -> Unit)?
  ): String {
    val code = conn.responseCode
    val stream: InputStream? = try {
      if (code in 200..299) conn.inputStream else conn.errorStream
    } catch (_: Exception) {
      null
    }
    if (stream == null) return ""

    if (onProgress == null) {
      return stream.bufferedReader(Charsets.UTF_8).readText()
    }

    val total = conn.contentLengthLong   // -1 if unknown
    val buf = ByteArray(8 * 1024)
    val out = java.io.ByteArrayOutputStream()
    var read = 0L
    var lastReportAt = 0L

    stream.use { s ->
      while (true) {
        val n = s.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        read += n
        // Report every ~200 KB or when total changes by ≥ 2%
        if (read - lastReportAt >= 200 * 1024) {
          onProgress(read, total)
          lastReportAt = read
        }
      }
    }
    onProgress(read, total)   // final
    return out.toString(Charsets.UTF_8.name())
  }

  fun classifyError(t: Throwable): ErrorInfo {
    return when (t) {
      is ConnectException -> ErrorInfo("connect", "Cannot connect to server. Check URL/port and that server is running.")
      is UnknownHostException -> ErrorInfo("dns", "Host not found. Check server address.")
      is SocketTimeoutException -> ErrorInfo("timeout", "Connection timed out. Check network/server load.")
      is SSLException -> ErrorInfo("ssl", "TLS/SSL handshake failed. Try enabling insecure TLS for self-signed certs.")
      else -> ErrorInfo("network", t.message ?: "Network error")
    }
  }
}
