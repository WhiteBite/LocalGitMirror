package localgitmirror.idea.mirror

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import localgitmirror.idea.workkit.BundleCrypto
import localgitmirror.idea.workkit.HybridCrypto

class MirrorApiSealPostboxPayloadTest {

  private val INFO_RELAY_REQ = "lgm/v3/relay/req".toByteArray(Charsets.US_ASCII)

  @Test
  fun `v3 seal opens on the server side with postbox aad`() {
    val serverKp = newX25519()
    val serverPub = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)
    val body = ByteArray(700) { (it * 11).toByte() }

    val sealed = MirrorCrypto.sealRelayPayload(body, "unused", serverPub, HybridCrypto.RELAY_AAD_POSTBOX)

    assertTrue(sealed.epkB64 != null)
    val epk = HybridCrypto.decodeServerPub(sealed.epkB64!!)
    val key = HybridCrypto.hkdf(serverShared(serverKp, epk), epk, INFO_RELAY_REQ)
    assertTrue(openGcmAad(key, sealed.bytes, HybridCrypto.RELAY_AAD_POSTBOX).contentEquals(body))
  }

  @Test
  fun `v3 seal fails to open under a foreign aad`() {
    val serverKp = newX25519()
    val serverPub = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)

    val sealed = MirrorCrypto.sealRelayPayload(ByteArray(32), "unused", serverPub, HybridCrypto.RELAY_AAD_POSTBOX)

    val epk = HybridCrypto.decodeServerPub(sealed.epkB64!!)
    val key = HybridCrypto.hkdf(serverShared(serverKp, epk), epk, INFO_RELAY_REQ)
    assertFailsWith<GeneralSecurityException> {
      openGcmAad(key, sealed.bytes, HybridCrypto.RELAY_AAD_VAULT)
    }
  }

  @Test
  fun `null server pub falls back to the password bundle`() {
    val body = ByteArray(64) { it.toByte() }

    val sealed = MirrorCrypto.sealRelayPayload(body, "pw", null, HybridCrypto.RELAY_AAD_POSTBOX)

    assertNull(sealed.epkB64)
    assertTrue(BundleCrypto.decryptDumpBytes(sealed.bytes, "pw").contentEquals(body))
  }

  @Test
  fun `v3 postbox seal carries routing metadata inside the seal`() {
    val serverKp = newX25519()
    val serverPub = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)
    val body = ByteArray(64) { it.toByte() }

    val sealed = MirrorCrypto.sealPostboxPayload(body, serverPub, "mr-replies/mr-!42.md", 1234L)

    val epk = HybridCrypto.decodeServerPub(sealed.epkB64)
    val key = HybridCrypto.hkdf(serverShared(serverKp, epk), epk, INFO_RELAY_REQ)
    val metaJson = String(openGcmAad(key, sealed.meta, HybridCrypto.RELAY_AAD_POSTBOX))
    assertEquals("""{"path":"mr-replies/mr-!42.md","plain_size":1234}""", metaJson)
    assertTrue(openGcmAad(key, sealed.bytes, HybridCrypto.RELAY_AAD_POSTBOX).contentEquals(body))
  }

  @Test
  fun `v3 postbox seal metadata fails to open under a foreign aad`() {
    val serverKp = newX25519()
    val serverPub = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)

    val sealed = MirrorCrypto.sealPostboxPayload(ByteArray(8), serverPub, "a/b.md", 1L)

    val epk = HybridCrypto.decodeServerPub(sealed.epkB64)
    val key = HybridCrypto.hkdf(serverShared(serverKp, epk), epk, INFO_RELAY_REQ)
    assertFailsWith<GeneralSecurityException> {
      openGcmAad(key, sealed.meta, HybridCrypto.RELAY_AAD_VAULT)
    }
  }

  @Test
  fun `upload wire carries only rid k meta and the sealed attachment`() {
    val serverKp = newX25519()
    val serverPub = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)
    val body = ByteArray(300) { (it * 7).toByte() }
    val sealed = MirrorCrypto.sealPostboxPayload(body, serverPub, "mr-notes/mr-!7.md", 300L)

    val fields = mutableMapOf<String, String>()
    val files = mutableListOf<ByteArray>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/documents/attachment-upload") { ex ->
      try {
        val (capturedFields, capturedFiles) = parseMultipart(ex.requestBody.readBytes())
        fields.putAll(capturedFields)
        files.addAll(capturedFiles)
        val resp = """{"success":true,"repo":"myrepo","id":"abc123","path":"mr-notes/mr-!7.md","size":300}"""
        ex.sendResponseHeaders(200, resp.length.toLong())
        ex.responseBody.use { it.write(resp.toByteArray(Charsets.UTF_8)) }
      } finally {
        ex.close()
      }
    }
    server.start()
    try {
      val enc = File.createTempFile("postbox-wire-", ".bin")
      try {
        enc.writeBytes(sealed.bytes)
        val res = MirrorPostboxApi.fileSyncUpload(
          "http://127.0.0.1:${server.address.port}", "", "myrepo", false,
          sealed.epkB64, sealed.meta, enc,
        )
        assertTrue(res.code in 200..299, "upload failed: ${res.code} ${res.message}")
        assertEquals("abc123", res.id)
        assertEquals("mr-notes/mr-!7.md", res.path)
      } finally {
        runCatching { enc.delete() }
      }

      assertEquals(setOf("rid", "k", "meta"), fields.keys)
      assertEquals(MirrorTransport.rid("myrepo"), fields["rid"])
      assertEquals(sealed.epkB64, fields["k"])
      assertEquals(java.util.Base64.getEncoder().encodeToString(sealed.meta), fields["meta"])
      assertEquals(1, files.size)
      assertTrue(files[0].contentEquals(sealed.bytes))
    } finally {
      server.stop(0)
    }
  }

  private fun parseMultipart(body: ByteArray): Pair<Map<String, String>, List<ByteArray>> {
    val text = String(body, Charsets.ISO_8859_1)
    val boundary = text.substringBefore("\r\n").removePrefix("--")
    val fields = mutableMapOf<String, String>()
    val files = mutableListOf<ByteArray>()
    for (raw in text.split("--$boundary")) {
      val part = raw.removePrefix("\r\n").removeSuffix("\r\n")
      if (part.isBlank() || part == "--") continue
      val headerEnd = part.indexOf("\r\n\r\n")
      if (headerEnd < 0) continue
      val headers = part.substring(0, headerEnd)
      val content = part.substring(headerEnd + 4)
      val name = Regex("name=\"([^\"]+)\"").find(headers)?.groupValues?.getOrNull(1) ?: continue
      if (headers.contains("filename=")) {
        files.add(content.toByteArray(Charsets.ISO_8859_1))
      } else {
        fields[name] = content
      }
    }
    return fields to files
  }

  private fun newX25519(): KeyPair =
    KeyPairGenerator.getInstance("XDH").apply { initialize(NamedParameterSpec.X25519) }.generateKeyPair()

  private fun serverShared(serverKp: KeyPair, epk: ByteArray): ByteArray {
    val ka = KeyAgreement.getInstance("XDH")
    ka.init(serverKp.private)
    ka.doPhase(HybridCrypto.rawToPublicKey(epk), true)
    return ka.generateSecret()
  }

  private fun openGcmAad(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
    val nonce = blob.copyOfRange(0, 12)
    val ct = blob.copyOfRange(12, blob.size)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    c.updateAAD(aad)
    return c.doFinal(ct)
  }
}
