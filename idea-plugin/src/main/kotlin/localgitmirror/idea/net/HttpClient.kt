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
    if (insecureTls && conn is HttpsURLConnection) {
      conn.sslSocketFactory = trustAllSslSocketFactory()
      conn.hostnameVerifier = trustAllHostnameVerifier
    }
    return conn
  }

  fun readBody(conn: HttpURLConnection): String {
    val code = conn.responseCode
    val stream: InputStream? = try {
      if (code in 200..299) conn.inputStream else conn.errorStream
    } catch (_: Exception) {
      null
    }
    if (stream == null) return ""
    return stream.bufferedReader().readText()
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
