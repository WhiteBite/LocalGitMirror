package localgitmirror.idea.mirror

import com.sun.net.httpserver.HttpServer
import localgitmirror.idea.workkit.BundleCrypto
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BufferBytesRoundTripTest {

  private fun startStubBuffer(store: HashMap<String, ByteArray>): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/buffer") { ex ->
      try {
        when (ex.requestMethod) {
          "POST" -> {
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val b64 = body.substringAfter("\"ciphertext_b64\":\"").substringBefore('"')
            val id = "buf-${store.size + 1}"
            store[id] = Base64.getDecoder().decode(b64)
            val resp = """{"id":"$id","ts":1.0}""".toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
          }
          "GET" -> {
            val id = ex.requestURI.path.substringAfterLast('/')
            val bytes = store[id]
            if (bytes == null) {
              ex.sendResponseHeaders(404, -1)
            } else {
              ex.sendResponseHeaders(200, bytes.size.toLong())
              ex.responseBody.use { it.write(bytes) }
            }
          }
          else -> ex.sendResponseHeaders(405, -1)
        }
      } finally {
        ex.close()
      }
    }
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
  fun `legacy password round trip returns exact bytes with decrypted false`() {
    val store = HashMap<String, ByteArray>()
    val server = startStubBuffer(store)
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val payload = "chat body строка with mixed content".toByteArray(Charsets.UTF_8)
      val ciphertext = BundleCrypto.encryptBundleBytes(payload, "pw")
      val put = MirrorBufferApi.bufferPut(base, "", false, ciphertext, "")
      assertTrue(put.code in 200..299, "put failed: ${put.code} ${put.message}")
      val id = assertNotNull(put.id)

      val get = MirrorBufferApi.bufferGetBytes(base, "", false, id)
      assertEquals(200, get.code)
      assertFalse(get.decrypted)
      assertEquals("OK", get.message)
      val container = assertNotNull(get.bytes)
      assertTrue(container.contentEquals(ciphertext), "legacy path must return the raw container bytes")
      val roundTripped = BundleCrypto.decryptDumpBytes(container, "pw")
      assertTrue(roundTripped.contentEquals(payload))
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `bufferGetBytes creates no lgm or tmp bin files in the temp dir`() {
    val store = HashMap<String, ByteArray>()
    val server = startStubBuffer(store)
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val ciphertext = BundleCrypto.encryptBundleBytes("edr churn probe".toByteArray(Charsets.UTF_8), "pw")
      val put = MirrorBufferApi.bufferPut(base, "", false, ciphertext, "")
      val id = assertNotNull(put.id)

      val before = tempBinFiles()
      val get = MirrorBufferApi.bufferGetBytes(base, "", false, id)
      assertEquals(200, get.code)
      assertNotNull(get.bytes)
      val leaked = tempBinFiles() - before
      assertEquals(emptySet(), leaked, "bufferGetBytes must not create temp files: $leaked")
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `missing entry returns 404 with null bytes and no temp file`() {
    val store = HashMap<String, ByteArray>()
    val server = startStubBuffer(store)
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val before = tempBinFiles()
      val get = MirrorBufferApi.bufferGetBytes(base, "", false, "no-such-id")
      assertEquals(404, get.code)
      assertNull(get.bytes)
      assertFalse(get.decrypted)
      val leaked = tempBinFiles() - before
      assertEquals(emptySet(), leaked, "404 path must not create temp files: $leaked")
    } finally {
      server.stop(0)
    }
  }
}
