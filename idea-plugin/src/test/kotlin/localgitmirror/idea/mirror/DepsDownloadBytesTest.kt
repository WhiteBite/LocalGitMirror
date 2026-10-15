package localgitmirror.idea.mirror

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DepsDownloadBytesTest {

  private fun startStubDeps(
    store: HashMap<String, ByteArray>,
    errors: Map<String, Pair<Int, String>> = emptyMap()
  ): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val handler = HttpHandler { ex ->
      try {
        val id = ex.requestURI.rawQuery?.split('&')
          ?.firstOrNull { it.startsWith("id=") }
          ?.removePrefix("id=")
        val err = errors[id]
        val bytes = store[id]
        when {
          err != null -> {
            val resp = err.second.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(err.first, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
          }
          bytes == null -> ex.sendResponseHeaders(404, -1)
          else -> {
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
          }
        }
      } finally {
        ex.close()
      }
    }
    server.createContext("/api/documents/queue-item", handler)
    server.createContext("/api/documents/ready-item", handler)
    server.start()
    return server
  }

  private fun tempBinFiles(): Set<String> {
    val names = File(System.getProperty("java.io.tmpdir")).list { _, name ->
      (name.startsWith("tmp-") || name.startsWith("lgm-")) && name.endsWith(".bin")
    }
    return (names ?: emptyArray()).toSet()
  }

  @Test
  fun `small manifest body returns exact bytes`() {
    val body = "manifest-v3 {\"items\":[]} строка".toByteArray(Charsets.UTF_8)
    val store = hashMapOf("m1" to body)
    val server = startStubDeps(store)
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val res = MirrorDepsApi.depsDownloadBytes(
        base, "", "repo", false, "m1", MirrorDepsApi.DepsKind.MANIFEST
      )
      assertEquals(200, res.code)
      assertEquals("OK", res.message)
      assertFalse(res.decrypted)
      assertTrue(body.contentEquals(assertNotNull(res.bytes)))
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `depsDownloadBytes creates no lgm or tmp bin files in the temp dir`() {
    val store = hashMapOf("r1" to ByteArray(50_000) { it.toByte() })
    val server = startStubDeps(store)
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val before = tempBinFiles()
      val res = MirrorDepsApi.depsDownloadBytes(
        base, "", "repo", false, "r1", MirrorDepsApi.DepsKind.RESPONSE
      )
      assertEquals(200, res.code)
      assertNotNull(res.bytes)
      val leaked = tempBinFiles() - before
      assertEquals(emptySet(), leaked, "depsDownloadBytes must not create temp files: $leaked")
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `body larger than maxBytes fails with explicit too-large error`() {
    val store = hashMapOf("big" to ByteArray(100 * 1024) { 0xAB.toByte() })
    val server = startStubDeps(store)
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val res = MirrorDepsApi.depsDownloadBytes(
        base, "", "repo", false, "big", MirrorDepsApi.DepsKind.RESPONSE, maxBytes = 1024
      )
      assertNull(res.bytes, "oversize body must not be buffered")
      assertTrue(res.message.startsWith("TOO_LARGE"), "expected TOO_LARGE error, got: ${res.message}")
      assertTrue(res.message.contains("depsDownload"), "error must point at the file variant: ${res.message}")
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `http error code propagates with server message`() {
    val server = startStubDeps(hashMapOf(), errors = mapOf("gone" to (410 to "blob purged")))
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val res = MirrorDepsApi.depsDownloadBytes(
        base, "", "repo", false, "gone", MirrorDepsApi.DepsKind.MANIFEST
      )
      assertEquals(410, res.code)
      assertNull(res.bytes)
      assertFalse(res.decrypted)
      assertTrue(res.message.contains("blob purged"), "server error body should surface: ${res.message}")
    } finally {
      server.stop(0)
    }
  }
}
