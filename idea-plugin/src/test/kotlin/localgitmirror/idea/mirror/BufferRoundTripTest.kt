package localgitmirror.idea.mirror

import com.sun.net.httpserver.HttpServer
import localgitmirror.idea.workkit.BundleCrypto
import localgitmirror.idea.workkit.ExchangeCrypto
import localgitmirror.idea.workkit.ExchangeMeta
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Long chat messages must reach the server and come back byte-complete:
 * the "message got cut" report is a rendering issue, not a wire issue.
 * Runs the real plugin path — BundleCrypto + MirrorApi.bufferPut/bufferGet —
 * against a stub buffer store.
 */
class BufferRoundTripTest {

  private fun longMixedText(): String = buildString {
    for (i in 1..320) {
      when (i % 3) {
        0 -> appendLine("    val строка$i = kryptonite.onfluence.service.sync$i; // комментарий $i")
        1 -> appendLine("fun метод$i(): String = \"текст $i\"")
        else -> appendLine("import ru.company.module$i.sub$i")
      }
    }
  }

  @Test
  fun `320-line mixed code and cyrillic text survives put-get round trip complete`() {
    val store = HashMap<String, ByteArray>()
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
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val text = longMixedText()
      assertTrue(text.lines().size > 300, "test text must exceed 300 lines")

      val ciphertext = BundleCrypto.encryptBundleBytes(text.toByteArray(Charsets.UTF_8), "pw")
      val hintEnc = ExchangeCrypto.encryptHint(ExchangeMeta.hintJson(text), "pw")
      val put = MirrorApi.bufferPut(base, "", false, ciphertext, hintEnc)
      assertTrue(put.code in 200..299, "put failed: ${put.code} ${put.message}")
      val id = assertNotNull(put.id)

      val get = MirrorApi.bufferGet(base, "", false, id)
      assertEquals(200, get.code)
      val file = assertNotNull(get.file)
      try {
        val roundTripped = String(BundleCrypto.decryptDumpBytes(file.readBytes(), "pw"), Charsets.UTF_8)
        assertEquals(text, roundTripped)
        assertEquals(text.length, roundTripped.length)
        assertEquals(text.lines(), roundTripped.lines())
      } finally {
        file.delete()
      }

      val meta = ExchangeMeta.parseHint(ExchangeCrypto.decryptHint(hintEnc, "pw"))
      assertEquals(ExchangeMeta.SIDE_PLUGIN, meta.side)
      assertEquals(text.lineSequence().first().trim().take(80), meta.text)
    } finally {
      server.stop(0)
    }
  }
}
