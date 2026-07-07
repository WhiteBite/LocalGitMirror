package localgitmirror.idea.workkit

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies protocol v3 (hybrid ECIES) byte-for-byte against the Python backend.
 *
 * The cross-language vector below was produced by backend/app/core/hybrid_crypto.py
 * (see tests/test_hybrid_crypto.py) with fixed private keys:
 *   server private = bytes(0..31), ephemeral private = bytes(32..63)
 * If this test passes, the Kotlin and Python sides agree on X25519 (incl. the
 * raw<->key endianness conversion) and HKDF-SHA256 labelling — the two riskiest
 * interop points.
 */
class HybridCryptoTest {

  private val INFO_ENV_REQ = "lgm/v3/env/req".toByteArray(Charsets.US_ASCII)
  private val INFO_ENV_RESP = "lgm/v3/env/resp".toByteArray(Charsets.US_ASCII)
  private val INFO_BUNDLE_REQ = "lgm/v3/bundle/req".toByteArray(Charsets.US_ASCII)
  private val INFO_BUNDLE_RESP = "lgm/v3/bundle/resp".toByteArray(Charsets.US_ASCII)

  private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
  private fun unhex(s: String) = ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
  private fun privFromRaw(raw: ByteArray) =
    KeyFactory.getInstance("XDH").generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, raw))

  @Test
  fun `cross-language KAT matches python vector`() {
    val serverPubRaw = unhex("8f40c5adb68f25624ae5b214ea767a6ec94d829d3d7b5e1ad1ba6f3e2138285f")
    val ephPubRaw = unhex("358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254")
    val expectedShared = "9663aa1da97e848a914a436d04163dfbb89178f107f1b5b77ed3854203382854"
    val expectedKEnvReq = "3b8fd5623045b9bad264dbdb76e6f325b40d16e5093771a72f53b34a26eeac4e"
    val expectedKBundleResp = "3cad686191ef90c06dd6700dd2b46c91b7df1ba4266ed13b8b48c0f150c5f143"

    val ephPriv = privFromRaw(ByteArray(32) { (it + 32).toByte() })

    // Client-side ECDH: ephemeral private × server public.
    val ka = KeyAgreement.getInstance("XDH")
    ka.init(ephPriv)
    ka.doPhase(HybridCrypto.rawToPublicKey(serverPubRaw), true)
    val shared = ka.generateSecret()
    assertEquals(expectedShared, hex(shared), "X25519 shared secret must match Python")

    assertEquals(expectedKEnvReq, hex(HybridCrypto.hkdf(shared, ephPubRaw, INFO_ENV_REQ)))
    assertEquals(expectedKBundleResp, hex(HybridCrypto.hkdf(shared, ephPubRaw, INFO_BUNDLE_RESP)))
  }

  @Test
  fun `raw to key conversion round-trips`() {
    val pubRaw = unhex("8f40c5adb68f25624ae5b214ea767a6ec94d829d3d7b5e1ad1ba6f3e2138285f")
    val back = HybridCrypto.publicKeyToRaw(HybridCrypto.rawToPublicKey(pubRaw) as XECPublicKey)
    assertEquals(hex(pubRaw), hex(back))
  }

  @Test
  fun `full session round-trips against a simulated server`() {
    val kpg = KeyPairGenerator.getInstance("XDH").apply { initialize(NamedParameterSpec.X25519) }
    val serverKp = kpg.generateKeyPair()
    val serverPubRaw = HybridCrypto.publicKeyToRaw(serverKp.public as XECPublicKey)

    val session = HybridCrypto.Session.create(serverPubRaw)

    // Server derives the same shared secret from the ephemeral public it received.
    val ka = KeyAgreement.getInstance("XDH")
    ka.init(serverKp.private)
    ka.doPhase(HybridCrypto.rawToPublicKey(session.ephemeralPub), true)
    val shared = ka.generateSecret()

    // client -> server envelope
    val reqJson = "{\"repo\":\"secret\"}"
    val kReq = HybridCrypto.hkdf(shared, session.ephemeralPub, INFO_ENV_REQ)
    assertEquals(reqJson, String(openGcm(kReq, Base64.getDecoder().decode(session.sealEnvelope(reqJson)))))

    // server -> client envelope
    val respJson = "{\"ok\":true}"
    val kResp = HybridCrypto.hkdf(shared, session.ephemeralPub, INFO_ENV_RESP)
    assertEquals(respJson, session.openEnvelope(Base64.getEncoder().encodeToString(sealGcm(kResp, respJson.toByteArray()))))

    // bundle both directions
    val data = ByteArray(500) { it.toByte() }
    val kBReq = HybridCrypto.hkdf(shared, session.ephemeralPub, INFO_BUNDLE_REQ)
    assertTrue(openGcm(kBReq, session.sealBundle(data)).contentEquals(data))
    val kBResp = HybridCrypto.hkdf(shared, session.ephemeralPub, INFO_BUNDLE_RESP)
    assertTrue(session.openBundle(sealGcm(kBResp, data)).contentEquals(data))
  }

  @Test
  fun `epk is url-safe base64 without padding`() {
    val kpg = KeyPairGenerator.getInstance("XDH").apply { initialize(NamedParameterSpec.X25519) }
    val serverPubRaw = HybridCrypto.publicKeyToRaw(kpg.generateKeyPair().public as XECPublicKey)
    val epk = HybridCrypto.Session.create(serverPubRaw).epkB64
    assertTrue(!epk.contains('+') && !epk.contains('/') && !epk.contains('='))
    // Server-side decoder accepts it back to 32 bytes.
    assertEquals(32, HybridCrypto.decodeServerPub(epk).size)
  }

  // — local AES-256-GCM mirror of HybridCrypto's wire (nonce[12] || ct) —
  private fun sealGcm(key: ByteArray, pt: ByteArray): ByteArray {
    val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    return nonce + c.doFinal(pt)
  }

  private fun openGcm(key: ByteArray, blob: ByteArray): ByteArray {
    val nonce = blob.copyOfRange(0, 12)
    val ct = blob.copyOfRange(12, blob.size)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    return c.doFinal(ct)
  }
}
