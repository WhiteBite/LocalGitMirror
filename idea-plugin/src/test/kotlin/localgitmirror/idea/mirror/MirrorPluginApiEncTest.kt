package localgitmirror.idea.mirror

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MirrorPluginApiEncTest {

  private class Stub(val server: HttpServer, val requests: MutableList<String>) {
    fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"
    fun stop() = server.stop(0)
  }

  private fun startStub(status: Int = 404, body: ByteArray = ByteArray(0)): Stub {
    val requests = mutableListOf<String>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val handler: (HttpExchange) -> Unit = { ex ->
      try {
        requests.add(ex.requestURI.rawQuery ?: "")
        ex.sendResponseHeaders(status, if (body.isEmpty()) -1L else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
      } finally {
        ex.close()
      }
    }
    server.createContext("/api/plugin/info", handler)
    server.createContext("/api/plugin/latest", handler)
    server.start()
    return Stub(server, requests)
  }

  @Test
  fun `info url always carries enc=1 regardless of password`() {
    assertEquals("?enc=1", MirrorPluginApi.pluginInfoQuery())
  }

  @Test
  fun `download url always carries enc=1 regardless of password`() {
    assertEquals("?enc=1", MirrorPluginApi.pluginDownloadQuery())
  }

  @Test
  fun `present password passes the precheck`() {
    assertNull(MirrorPluginApi.requireSyncPassword("pw", "x"))
  }

  @Test
  fun `blank sync password fails with the typed precheck error`() {
    for (pw in listOf("", "   ")) {
      val msg = assertNotNull(MirrorPluginApi.requireSyncPassword(pw, "pluginInfo"))
      assertTrue(msg.startsWith(MirrorPluginApi.SYNC_PASSWORD_REQUIRED_PREFIX),
        "expected typed prefix, got: $msg")
      assertTrue(msg.contains("sync password"), "message must name the missing setting: $msg")
    }
  }

  @Test
  fun `pluginInfo with blank password never issues a request`() {
    val stub = startStub()
    try {
      val info = MirrorPluginApi.pluginInfo(stub.baseUrl(), "", false, "")
      assertEquals(0, info.code)
      assertTrue(!info.available)
      assertTrue(info.message.startsWith(MirrorPluginApi.SYNC_PASSWORD_REQUIRED_PREFIX),
        "expected typed precheck error, got: ${info.message}")
      assertEquals(emptyList(), stub.requests, "no HTTP request may leave with a blank password")
    } finally {
      stub.stop()
    }
  }

  @Test
  fun `pluginDownload with blank password never issues a plaintext request`() {
    val stub = startStub(status = 200, body = byteArrayOf('P'.code.toByte(), 'K'.code.toByte()))
    val out = File.createTempFile("lgm-enc-test", ".zip")
    try {
      val res = MirrorPluginApi.pluginDownload(stub.baseUrl(), "", false, out, "")
      assertEquals(0, res.code)
      assertNull(res.file)
      assertTrue(res.message.startsWith(MirrorPluginApi.SYNC_PASSWORD_REQUIRED_PREFIX),
        "expected typed precheck error, got: ${res.message}")
      assertEquals(emptyList(), stub.requests, "no HTTP request may leave with a blank password")
    } finally {
      out.delete()
      stub.stop()
    }
  }

  @Test
  fun `pluginInfo with a password requests enc=1 and parses the envelope`() {
    val pw = "test-sync-password"
    val plain = """{"available":true,"version":"0.9.9","filename":"f.zip","size":12}"""
    val e = localgitmirror.idea.workkit.EnvelopeCrypto.encrypt(plain, pw)
    val stub = startStub(status = 200, body = """{"e":"$e"}""".toByteArray(Charsets.UTF_8))
    try {
      val info = MirrorPluginApi.pluginInfo(stub.baseUrl(), "", false, pw)
      assertEquals(200, info.code)
      assertTrue(info.available)
      assertEquals("0.9.9", info.version)
      assertEquals("enc=1", stub.requests.single(), "server must only ever see enc=1")
    } finally {
      stub.stop()
    }
  }

  @Test
  fun `pluginDownload with a password requests enc=1 and decrypts the bundle`() {
    val pw = "test-sync-password"
    val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte()) + ByteArray(60) { (it % 7).toByte() }
    val sealed = localgitmirror.idea.workkit.BundleCrypto.encryptBundleBytes(zip, pw)
    val stub = startStub(status = 200, body = sealed)
    val out = File.createTempFile("lgm-enc-test", ".zip")
    try {
      val res = MirrorPluginApi.pluginDownload(stub.baseUrl(), "", false, out, pw)
      assertEquals(200, res.code)
      assertNotNull(res.file)
      assertTrue(zip.contentEquals(out.readBytes()), "downloaded file must be the decrypted archive")
      assertEquals("enc=1", stub.requests.single(), "server must only ever see enc=1")
    } finally {
      out.delete()
      stub.stop()
    }
  }
}
