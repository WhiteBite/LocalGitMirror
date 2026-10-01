package localgitmirror.idea.mirror

import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyPair
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

class MirrorApiSealVaultPublicationTest {

  private val INFO_RELAY_REQ = "lgm/v3/relay/req".toByteArray(Charsets.US_ASCII)

  @Test
  fun `v3 seal opens on the server side with vault aad`() {
    val serverKp = newX25519()
    val serverPub = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)
    val zip = ByteArray(600) { (it * 5).toByte() }

    val sealed = MirrorCrypto.sealRelayPayload(zip, "unused", serverPub, HybridCrypto.RELAY_AAD_VAULT)

    assertTrue(sealed.epkB64 != null)
    val epk = HybridCrypto.decodeServerPub(sealed.epkB64!!)
    val key = HybridCrypto.hkdf(serverShared(serverKp, epk), epk, INFO_RELAY_REQ)
    assertTrue(openGcmAad(key, sealed.bytes, HybridCrypto.RELAY_AAD_VAULT).contentEquals(zip))
  }

  @Test
  fun `v3 seal fails to open under a foreign aad`() {
    val serverKp = newX25519()
    val serverPub = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)

    val sealed = MirrorCrypto.sealRelayPayload(ByteArray(32), "unused", serverPub, HybridCrypto.RELAY_AAD_VAULT)

    val epk = HybridCrypto.decodeServerPub(sealed.epkB64!!)
    val key = HybridCrypto.hkdf(serverShared(serverKp, epk), epk, INFO_RELAY_REQ)
    assertFailsWith<GeneralSecurityException> {
      openGcmAad(key, sealed.bytes, HybridCrypto.RELAY_AAD_BUFFER)
    }
  }

  @Test
  fun `null server pub falls back to the password bundle`() {
    val zip = ByteArray(48) { it.toByte() }

    val sealed = MirrorCrypto.sealRelayPayload(zip, "pw", null, HybridCrypto.RELAY_AAD_VAULT)

    assertNull(sealed.epkB64)
    assertTrue(BundleCrypto.decryptDumpBytes(sealed.bytes, "pw").contentEquals(zip))
  }

  @Test
  fun `v3 deps req seal opens on the server side`() {
    val serverKp = newX25519()
    val serverPub = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)
    val manifest = ByteArray(300) { (it * 3).toByte() }

    val sealed = MirrorCrypto.sealRelayPayload(manifest, "unused", serverPub, HybridCrypto.RELAY_AAD_DEPS_REQ)

    val epk = HybridCrypto.decodeServerPub(sealed.epkB64!!)
    val key = HybridCrypto.hkdf(serverShared(serverKp, epk), epk, INFO_RELAY_REQ)
    assertTrue(openGcmAad(key, sealed.bytes, HybridCrypto.RELAY_AAD_DEPS_REQ).contentEquals(manifest))
  }

  @Test
  fun `v3 deps resp seal opens on the server side`() {
    val serverKp = newX25519()
    val serverPub = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)
    val zip = ByteArray(500) { (it * 7).toByte() }

    val sealed = MirrorCrypto.sealRelayPayload(zip, "unused", serverPub, HybridCrypto.RELAY_AAD_DEPS_RESP)

    val epk = HybridCrypto.decodeServerPub(sealed.epkB64!!)
    val key = HybridCrypto.hkdf(serverShared(serverKp, epk), epk, INFO_RELAY_REQ)
    assertTrue(openGcmAad(key, sealed.bytes, HybridCrypto.RELAY_AAD_DEPS_RESP).contentEquals(zip))
  }

  @Test
  fun `deps seal under the wrong aad fails closed`() {
    val serverKp = newX25519()
    val serverPub = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)

    val sealed = MirrorCrypto.sealRelayPayload(ByteArray(32), "unused", serverPub, HybridCrypto.RELAY_AAD_DEPS_REQ)

    val epk = HybridCrypto.decodeServerPub(sealed.epkB64!!)
    val key = HybridCrypto.hkdf(serverShared(serverKp, epk), epk, INFO_RELAY_REQ)
    assertFailsWith<GeneralSecurityException> {
      openGcmAad(key, sealed.bytes, HybridCrypto.RELAY_AAD_DEPS_RESP)
    }
  }

  @Test
  fun `deps password fallback round-trips for both aads`() {
    for (aad in listOf(HybridCrypto.RELAY_AAD_DEPS_REQ, HybridCrypto.RELAY_AAD_DEPS_RESP)) {
      val payload = ByteArray(48) { it.toByte() }

      val sealed = MirrorCrypto.sealRelayPayload(payload, "pw", null, aad)

      assertNull(sealed.epkB64)
      assertTrue(BundleCrypto.decryptDumpBytes(sealed.bytes, "pw").contentEquals(payload))
    }
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
