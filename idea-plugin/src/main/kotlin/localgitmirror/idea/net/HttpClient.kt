package localgitmirror.idea.net

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.util.net.HttpConfigurable
import localgitmirror.idea.settings.MirrorSettingsService
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.security.cert.CertificateException
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

  private fun trustAllSslContext(): SSLContext {
    val trustAll = arrayOf<TrustManager>(
      object : X509TrustManager {
        override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
      }
    )
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, trustAll, java.security.SecureRandom())
    return ctx
  }

  private fun pinnedSslContext(storedHex: String): SSLContext {
    val pinChecking = arrayOf<TrustManager>(
      object : X509TrustManager {
        override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
          val leaf = chain.firstOrNull() ?: throw CertificateException("Server presented no certificate")
          when (val verdict = TlsPinPolicy.evaluate(leaf.encoded, storedHex)) {
            is TlsPinPolicy.Verdict.TrustOnFirstUse -> storeServerPin(leaf.encoded)
            TlsPinPolicy.Verdict.Match -> {}
            is TlsPinPolicy.Verdict.Mismatch -> throw TlsPinMismatchException(verdict.expectedHex, verdict.actualHex)
          }
        }
      }
    )
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, pinChecking, java.security.SecureRandom())
    return ctx
  }

  private fun trustAllSslSocketFactory(): SSLSocketFactory = trustAllSslContext().socketFactory

  private fun pinnedSslSocketFactory(storedHex: String): SSLSocketFactory = pinnedSslContext(storedHex).socketFactory

  /** TLS policy as a raw SSLContext for non-URLConnection transports (e.g. java.net.http WebSocket). */
  fun sslContextFor(url: URL, insecureTls: Boolean): SSLContext? {
    if (url.protocol != "https") return null
    return when (val decision = TlsDecision.resolve(url, loadTlsInput(insecureTls))) {
      is TlsDecision.Pinned -> pinnedSslContext(decision.storedHex)
      TlsDecision.TrustAll -> trustAllSslContext()
      TlsDecision.Default -> null
    }
  }

  internal val defaultStateProvider: () -> MirrorSettingsService.State? = {
    try {
      service<MirrorSettingsService>().state
    } catch (_: Throwable) {
      null
    }
  }

  // test seam; production reads the IntelliJ service
  internal var stateProvider: () -> MirrorSettingsService.State? = defaultStateProvider

  private fun loadTlsInput(callerInsecure: Boolean): TlsDecision.Input {
    val state = stateProvider() ?: return TlsDecision.Input(
      baseUrl = "", pinEnabled = false, mirrorInsecure = false, callerInsecure = callerInsecure, storedPin = ""
    )
    return TlsDecision.Input(
      baseUrl = state.baseUrl,
      pinEnabled = state.tlsPinEnabled,
      mirrorInsecure = state.mirrorInsecureTls,
      callerInsecure = callerInsecure,
      storedPin = state.serverCertSha256
    )
  }

  private val pinStoreLock = Any()

  private fun storeServerPin(leafDer: ByteArray) {
    synchronized(pinStoreLock) {
      val state = stateProvider() ?: return
      when (val verdict = TlsPinPolicy.evaluate(leafDer, state.serverCertSha256)) {
        is TlsPinPolicy.Verdict.TrustOnFirstUse -> state.serverCertSha256 = verdict.sha256Hex
        TlsPinPolicy.Verdict.Match -> {}
        is TlsPinPolicy.Verdict.Mismatch -> throw TlsPinMismatchException(verdict.expectedHex, verdict.actualHex)
      }
    }
  }

  private val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }

  private val userAgent: String by lazy {
    val version = try {
      PluginManagerCore.getPlugin(PluginId.getId("localgitmirror.idea.orchestrator"))?.version
    } catch (_: Throwable) {
      null
    }
    "DocCache/${version ?: "0.0.0"}"
  }

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
    conn.instanceFollowRedirects = false
    conn.setRequestProperty("User-Agent", userAgent)
    if (conn is HttpsURLConnection) {
      when (val decision = TlsDecision.resolve(url, loadTlsInput(insecureTls))) {
        is TlsDecision.Pinned -> {
          conn.sslSocketFactory = pinnedSslSocketFactory(decision.storedHex)
          conn.hostnameVerifier = trustAllHostnameVerifier
        }
        TlsDecision.TrustAll -> {
          conn.sslSocketFactory = trustAllSslSocketFactory()
          conn.hostnameVerifier = trustAllHostnameVerifier
        }
        TlsDecision.Default -> {}
      }
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

  fun readBodyToFileWithProgress(
    conn: HttpURLConnection,
    outFile: java.io.File,
    onProgress: ((read: Long, total: Long) -> Unit)?
  ): Long {
    val code = conn.responseCode
    val stream: InputStream? = try {
      if (code in 200..299) conn.inputStream else conn.errorStream
    } catch (_: Exception) {
      null
    }
    if (stream == null) return 0L

    val total = conn.contentLengthLong   // -1 if unknown
    val buf = ByteArray(1024 * 1024)
    var read = 0L

    java.io.BufferedInputStream(stream).use { s ->
      outFile.outputStream().use { out ->
        while (true) {
          val n = s.read(buf)
          if (n < 0) break
          out.write(buf, 0, n)
          read += n
          onProgress?.invoke(read, total)
        }
      }
    }
    return read
  }

  fun classifyError(t: Throwable, isMirror: Boolean = true): ErrorInfo {
    findPinMismatch(t)?.let { return ErrorInfo("tls-pin", it.message ?: "TLS certificate pin mismatch") }
    return when (t) {
      is ConnectException -> ErrorInfo("connect", "Cannot connect to server. Check URL/port and that server is running.")
      is UnknownHostException -> ErrorInfo("dns", "Host not found. Check server address.")
      is SocketTimeoutException -> ErrorInfo("timeout", "Connection timed out. Check network/server load.")
      is SSLException -> ErrorInfo("ssl", if (isMirror) MIRROR_SSL_ADVICE else GITLAB_SSL_ADVICE)
      else -> ErrorInfo("network", t.message ?: "Network error")
    }
  }

  private const val MIRROR_SSL_ADVICE =
    "TLS/SSL handshake failed. If the mirror certificate was renewed, clear the stored TLS pin in DocCache settings."
  private const val GITLAB_SSL_ADVICE =
    "TLS/SSL handshake failed. Enable the GitLab insecure TLS toggle in settings for self-signed certificates."

  private fun findPinMismatch(t: Throwable): TlsPinMismatchException? {
    var current: Throwable? = t
    while (current != null) {
      if (current is TlsPinMismatchException) return current
      current = current.cause?.takeIf { it !== current }
    }
    return null
  }
}

class TlsPinMismatchException(val expectedHex: String, val actualHex: String) : CertificateException(
  "Server TLS certificate does not match the pinned fingerprint (expected $expectedHex, got $actualHex). " +
    "If the server certificate was renewed intentionally, clear the pin in DocCache settings."
)
