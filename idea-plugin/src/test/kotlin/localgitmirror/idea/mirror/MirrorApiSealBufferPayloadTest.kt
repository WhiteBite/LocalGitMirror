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
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import localgitmirror.idea.workkit.BundleCrypto
import localgitmirror.idea.workkit.HybridCrypto

class MirrorApiSealBufferPayloadTest {

  private val INFO_RELAY_REQ = "lgm/v3/relay/req".toByteArray(Charsets.US_ASCII)

  @Test
  fun `v3 seal opens on the server side with buffer aad`() {
    val serverKp = newX25519()
    val serverPub = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)
    val body = ByteArray(700) { (it * 13).toByte() }

    val sealed = MirrorCrypto.sealRelayPayload(body, "unused", serverPub, HybridCrypto.RELAY_AAD_BUFFER)

    assertTrue(sealed.epkB64 != null)
    val epk = HybridCrypto.decodeServerPub(sealed.epkB64!!)
    val key = HybridCrypto.hkdf(serverShared(serverKp, epk), epk, INFO_RELAY_REQ)
    assertTrue(openGcmAad(key, sealed.bytes, HybridCrypto.RELAY_AAD_BUFFER).contentEquals(body))
  }

  @Test
  fun `v3 seal fails to open under a foreign aad`() {
    val serverKp = newX25519()
    val serverPub = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)

    val sealed = MirrorCrypto.sealRelayPayload(ByteArray(32), "unused", serverPub, HybridCrypto.RELAY_AAD_BUFFER)

    val epk = HybridCrypto.decodeServerPub(sealed.epkB64!!)
    val key = HybridCrypto.hkdf(serverShared(serverKp, epk), epk, INFO_RELAY_REQ)
    assertFailsWith<GeneralSecurityException> {
      openGcmAad(key, sealed.bytes, HybridCrypto.RELAY_AAD_POSTBOX)
    }
  }

  @Test
  fun `null server pub falls back to the password bundle`() {
    val body = ByteArray(64) { it.toByte() }

    val sealed = MirrorCrypto.sealRelayPayload(body, "pw", null, HybridCrypto.RELAY_AAD_BUFFER)

    assertNull(sealed.epkB64)
    assertTrue(BundleCrypto.decryptDumpBytes(sealed.bytes, "pw").contentEquals(body))
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
