package localgitmirror.idea.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import localgitmirror.idea.settings.MirrorSettingsService
import java.io.File
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Paths
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class HttpClientTlsPinTest {

  @TempDir
  lateinit var tempDir: File

  private lateinit var server: HttpsServer
  private lateinit var baseUrl: String
  private val rootHits = AtomicInteger(0)
  private val targetHits = AtomicInteger(0)

  @BeforeTest
  fun startServer() {
    val sslContext = serverSslContext(generateSelfSignedKeystore(tempDir))
    server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.httpsConfigurator = object : HttpsConfigurator(sslContext) {
      override fun configure(params: HttpsParameters) {
        params.needClientAuth = false
        params.cipherSuites = sslContext.createSSLEngine().enabledCipherSuites
        params.protocols = sslContext.createSSLEngine().enabledProtocols
        params.setSSLParameters(sslContext.defaultSSLParameters)
      }
    }
    server.createContext("/") { ex ->
      rootHits.incrementAndGet()
      respond(ex, 200, "ok")
    }
    server.createContext("/redirect") { ex ->
      ex.responseHeaders.add("Location", "/target")
      respond(ex, 302, "")
    }
    server.createContext("/target") { ex ->
      targetHits.incrementAndGet()
      respond(ex, 200, "target")
    }
    server.start()
    baseUrl = "https://127.0.0.1:${server.address.port}"
  }

  @AfterTest
  fun stopServer() {
    HttpClient.stateProvider = HttpClient.defaultStateProvider
    server.stop(0)
  }

  private fun installState(
    pinEnabled: Boolean = true,
    mirrorInsecure: Boolean = false,
    storedPin: String = "",
    base: String = baseUrl
  ): MirrorSettingsService.State {
    val state = MirrorSettingsService.State()
    state.baseUrl = base
    state.tlsPinEnabled = pinEnabled
    state.mirrorInsecureTls = mirrorInsecure
    state.serverCertSha256 = storedPin
    HttpClient.stateProvider = { state }
    return state
  }

  private fun get(path: String, insecureTls: Boolean = false): Int {
    val conn = HttpClient.open(URL("$baseUrl$path"), insecureTls)
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000
    return conn.responseCode
  }

  @Test
  fun `tofu connect stores observed pin`() {
    val state = installState(storedPin = "")
    assertEquals(200, get("/"))
    assertTrue(state.serverCertSha256.matches(Regex("[0-9a-f]{64}")))
    assertEquals(1, rootHits.get())
  }

  @Test
  fun `connect succeeds with stored pin`() {
    val state = installState(storedPin = "")
    assertEquals(200, get("/"))
    val stored = state.serverCertSha256
    assertEquals(200, get("/"))
    assertEquals(stored, state.serverCertSha256)
  }

  @Test
  fun `tampered pin aborts handshake before request reaches server`() {
    installState(storedPin = "00".repeat(32))
    val t = assertFailsWith<Exception> { get("/") }
    assertEquals("tls-pin", HttpClient.classifyError(t).type)
    assertEquals(0, rootHits.get())
  }

  @Test
  fun `mirror insecure tls wins over enabled pin and stores nothing`() {
    val state = installState(pinEnabled = true, mirrorInsecure = true, storedPin = "00".repeat(32))
    assertEquals(200, get("/"))
    assertEquals("00".repeat(32), state.serverCertSha256)
  }

  @Test
  fun `non mirror host honors caller insecure flag`() {
    installState(base = "https://127.0.0.1:1")
    assertEquals(200, get("/", insecureTls = true))
  }

  @Test
  fun `redirect is not followed`() {
    installState(mirrorInsecure = true)
    assertEquals(302, get("/redirect"))
    assertEquals(0, targetHits.get())
  }

  @Test
  fun `mirror host match ignores host case`() {
    val input = decisionInput(baseUrl = "https://LOCALHOST:8443", storedPin = "ab")
    assertEquals(TlsDecision.Pinned("ab"), TlsDecision.resolve(URL("https://localhost:8443/x"), input))
  }

  @Test
  fun `mirror host match equates default and explicit port`() {
    val input = decisionInput(baseUrl = "https://localhost", storedPin = "ab")
    assertEquals(TlsDecision.Pinned("ab"), TlsDecision.resolve(URL("https://localhost:443/"), input))
  }

  @Test
  fun `different port is not mirror`() {
    val input = decisionInput(baseUrl = "https://localhost:8443", callerInsecure = true)
    assertEquals(TlsDecision.TrustAll, TlsDecision.resolve(URL("https://localhost/"), input))
  }

  @Test
  fun `mirror decision insecure wins over pin`() {
    val input = decisionInput(mirrorInsecure = true, storedPin = "ab")
    assertEquals(TlsDecision.TrustAll, TlsDecision.resolve(URL("https://localhost:8443/"), input))
  }

  @Test
  fun `mirror decision without pin and insecure stays default`() {
    val input = decisionInput(pinEnabled = false, callerInsecure = true)
    assertEquals(TlsDecision.Default, TlsDecision.resolve(URL("https://localhost:8443/"), input))
  }

  @Test
  fun `non mirror decision without insecure stays default`() {
    val input = decisionInput(storedPin = "ab")
    assertEquals(TlsDecision.Default, TlsDecision.resolve(URL("https://gitlab.example.com/"), input))
  }

  @Test
  fun `blank base url is never mirror`() {
    val input = decisionInput(baseUrl = "", mirrorInsecure = true, callerInsecure = true)
    assertEquals(TlsDecision.TrustAll, TlsDecision.resolve(URL("https://localhost:8443/"), input))
  }

  @Test
  fun `http url stays default`() {
    val input = decisionInput(baseUrl = "http://localhost:8080", storedPin = "ab")
    assertEquals(TlsDecision.Default, TlsDecision.resolve(URL("http://localhost:8080/"), input))
  }

  @Test
  fun `gitlab ssl error advises gitlab toggle`() {
    val info = HttpClient.classifyError(SSLException("cert"), isMirror = false)
    assertEquals("ssl", info.type)
    assertTrue(info.message.contains("GitLab"))
  }

  @Test
  fun `mirror ssl error advises clearing pin`() {
    val info = HttpClient.classifyError(SSLException("cert"))
    assertEquals("ssl", info.type)
    assertTrue(info.message.contains("pin"))
    assertFalse(info.message.contains("insecure", ignoreCase = true))
  }

  private fun decisionInput(
    baseUrl: String = "https://localhost:8443",
    pinEnabled: Boolean = true,
    mirrorInsecure: Boolean = false,
    callerInsecure: Boolean = false,
    storedPin: String = ""
  ) = TlsDecision.Input(baseUrl, pinEnabled, mirrorInsecure, callerInsecure, storedPin)

  private fun respond(ex: HttpExchange, code: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    if (bytes.isEmpty()) {
      ex.sendResponseHeaders(code, -1)
      ex.close()
    } else {
      ex.sendResponseHeaders(code, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
  }

  private fun generateSelfSignedKeystore(dir: File): File {
    val os = System.getProperty("os.name").lowercase()
    val keytool = Paths.get(System.getProperty("java.home"), "bin", if ("win" in os) "keytool.exe" else "keytool")
    val ks = File(dir, "server.jks")
    val proc = ProcessBuilder(
      keytool.toString(), "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
      "-validity", "3650", "-storetype", "JKS", "-keystore", ks.absolutePath,
      "-storepass", STORE_PASS, "-keypass", STORE_PASS,
      "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1"
    ).redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader().readText()
    check(proc.waitFor(60, TimeUnit.SECONDS) && proc.exitValue() == 0) { "keytool failed: $out" }
    return ks
  }

  private fun serverSslContext(keystore: File): SSLContext {
    val ks = KeyStore.getInstance("JKS")
    keystore.inputStream().use { ks.load(it, STORE_PASS.toCharArray()) }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, STORE_PASS.toCharArray())
    return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
  }

  private companion object {
    const val STORE_PASS = "changeit"
  }
}
