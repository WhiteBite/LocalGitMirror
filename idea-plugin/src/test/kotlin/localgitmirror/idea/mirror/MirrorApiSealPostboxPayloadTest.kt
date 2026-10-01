package localgitmirror.idea.mirror

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
import localgitmirror.idea.workkit.ExchangeCrypto
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

    val sealed = MirrorCrypto.sealPostboxPayload(body, "unused", serverPub, "mr-replies/mr-!42.md", 1234L)

    assertTrue(sealed.meta != null)
    val epk = HybridCrypto.decodeServerPub(sealed.epkB64!!)
    val key = HybridCrypto.hkdf(serverShared(serverKp, epk), epk, INFO_RELAY_REQ)
    val metaJson = String(openGcmAad(key, sealed.meta!!, HybridCrypto.RELAY_AAD_POSTBOX))
    assertEquals("""{"path":"mr-replies/mr-!42.md","plain_size":1234}""", metaJson)
  }

  @Test
  fun `v3 postbox seal metadata fails to open under a foreign aad`() {
    val serverKp = newX25519()
    val serverPub = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)

    val sealed = MirrorCrypto.sealPostboxPayload(ByteArray(8), "unused", serverPub, "a/b.md", 1L)

    val epk = HybridCrypto.decodeServerPub(sealed.epkB64!!)
    val key = HybridCrypto.hkdf(serverShared(serverKp, epk), epk, INFO_RELAY_REQ)
    assertFailsWith<GeneralSecurityException> {
      openGcmAad(key, sealed.meta!!, HybridCrypto.RELAY_AAD_VAULT)
    }
  }

  @Test
  fun `legacy postbox seal carries no sealed metadata`() {
    val sealed = MirrorCrypto.sealPostboxPayload(ByteArray(8), "pw", null, "a/b.md", 1L)

    assertNull(sealed.epkB64)
    assertNull(sealed.meta)
    assertTrue(BundleCrypto.decryptDumpBytes(sealed.bytes, "pw").contentEquals(ByteArray(8)))
  }

  @Test
  fun `postbox route hides the display path behind path_enc with a password`() {
    val route = MirrorCrypto.postboxRoute("mr-replies/mr-!42.md", "pw")

    assertEquals("x/", route.first.take(2))
    assertTrue(route.second != null)
    assertEquals("mr-replies/mr-!42.md", ExchangeCrypto.decryptHint(route.second!!, "pw"))
  }

  @Test
  fun `postbox route passes the display path through without a password`() {
    val route = MirrorCrypto.postboxRoute("mr-notes/mr-!7.md", "")

    assertEquals("mr-notes/mr-!7.md", route.first)
    assertNull(route.second)
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
