package localgitmirror.idea.workkit

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPublicKeySpec
import java.util.Base64 as JavaBase64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hybrid (asymmetric) envelope/bundle crypto — protocol v3 (ECIES).
 *
 * Replaces the shared SYNC_PASSWORD on the UPLOAD path with public-key crypto:
 *   * The home server owns a long-term X25519 static keypair; only its PUBLIC
 *     key is pinned on this (work) machine. A public key is not a secret.
 *   * Each API call uses a fresh throwaway (ephemeral) X25519 keypair, does
 *     ECDH against the pinned server public key, derives the session keys, then
 *     discards the ephemeral private key.
 *
 * Why this matters for an adversary on THIS machine:
 *   * Reading everything here later (config / disk / RAM between syncs) yields
 *     only the server PUBLIC key + spent ephemeral publics — useless to decrypt
 *     any recorded upload. Forward secrecy holds w.r.t. this machine.
 *   * The only thing that can decrypt an upload is the server's STATIC private
 *     key, which never leaves the home PC.
 *   * NOT defended: a memory dump taken DURING an active sync (plaintext + a
 *     live session key must coexist in RAM then — true of any scheme).
 *
 * MUST stay byte-for-byte compatible with backend/app/core/hybrid_crypto.py:
 *   * X25519 ECDH → 32-byte raw shared secret
 *   * HKDF-SHA256(ikm = shared, salt = ephemeral_pub[32], info = label) → 32B
 *   * AES-256-GCM, 12-byte random nonce, wire = nonce[12] || ct(+tag)
 *
 * HKDF info labels (identical to the Python side):
 *   request  envelope (client -> server):  "lgm/v3/env/req"
 *   response envelope (server -> client):  "lgm/v3/env/resp"
 *   request  bundle   (client -> server):  "lgm/v3/bundle/req"
 *   response bundle   (server -> client):  "lgm/v3/bundle/resp"
 */
object HybridCrypto {

  private const val NONCE_SIZE = 12
  private const val GCM_TAG_BITS = 128
  private const val KEY_SIZE = 32
  private const val PUB_SIZE = 32

  private val INFO_ENV_REQ = "lgm/v3/env/req".toByteArray(Charsets.US_ASCII)
  private val INFO_ENV_RESP = "lgm/v3/env/resp".toByteArray(Charsets.US_ASCII)
  private val INFO_BUNDLE_REQ = "lgm/v3/bundle/req".toByteArray(Charsets.US_ASCII)
  private val INFO_BUNDLE_RESP = "lgm/v3/bundle/resp".toByteArray(Charsets.US_ASCII)

  /** Decode a pinned server public key from base64 (standard or url-safe). */
  fun decodeServerPub(b64: String): ByteArray {
    val raw = decodeBase64Flexible(b64)
    require(raw.size == PUB_SIZE) { "Invalid server public key length: ${raw.size}" }
    return raw
  }

  /**
   * One ephemeral key bound to one API call. Seals the request and opens the
   * response with the SAME shared secret (different HKDF labels per direction).
   * Create once per call; the ephemeral private key lives only inside this
   * object and is dropped when it is garbage-collected. Call [wipe] when done.
   */
  class Session private constructor(
    val ephemeralPub: ByteArray,
    private val shared: ByteArray,
  ) {
    /** Ephemeral public key, url-safe base64 (no padding) — sent as "epk"/"k". */
    val epkB64: String get() = JavaBase64.getUrlEncoder().withoutPadding().encodeToString(ephemeralPub)

    fun sealEnvelope(jsonStr: String): String {
      val key = hkdf(shared, ephemeralPub, INFO_ENV_REQ)
      return JavaBase64.getEncoder().encodeToString(seal(key, jsonStr.toByteArray(Charsets.UTF_8)))
    }

    fun openEnvelope(b64: String): String {
      val key = hkdf(shared, ephemeralPub, INFO_ENV_RESP)
      return String(open(key, JavaBase64.getDecoder().decode(b64)), Charsets.UTF_8)
    }

    /** Seal raw bundle bytes for upload (the multipart attachment). */
    fun sealBundle(data: ByteArray): ByteArray {
      val key = hkdf(shared, ephemeralPub, INFO_BUNDLE_REQ)
      return seal(key, data)
    }

    /** Open a bundle returned by the server (export "d"). */
    fun openBundle(blob: ByteArray): ByteArray {
      val key = hkdf(shared, ephemeralPub, INFO_BUNDLE_RESP)
      return open(key, blob)
    }

    /** Best-effort: zero the shared secret. (JVM may keep copies; not guaranteed.) */
    fun wipe() {
      java.util.Arrays.fill(shared, 0)
    }

    companion object {
      fun create(serverPub: ByteArray): Session {
        require(serverPub.size == PUB_SIZE) { "Invalid server public key length" }
        val kpg = KeyPairGenerator.getInstance("XDH")
        kpg.initialize(NamedParameterSpec.X25519)
        val kp = kpg.generateKeyPair()

        val ka = KeyAgreement.getInstance("XDH")
        ka.init(kp.private)
        ka.doPhase(rawToPublicKey(serverPub), true)
        val shared = ka.generateSecret()

        val epk = publicKeyToRaw(kp.public as XECPublicKey)
        return Session(epk, shared)
      }
    }
  }

  // ───────────────────────────── primitives ─────────────────────────────────

  private fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
    val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    return nonce + cipher.doFinal(plaintext)
  }

  private fun open(key: ByteArray, blob: ByteArray): ByteArray {
    require(blob.size >= NONCE_SIZE + 16) { "ciphertext too short" }
    val nonce = blob.copyOfRange(0, NONCE_SIZE)
    val ct = blob.copyOfRange(NONCE_SIZE, blob.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    return cipher.doFinal(ct)
  }

  /**
   * HKDF-SHA256 → 32-byte key. salt = ephemeral public key, info = label.
   * One expand block is enough for a 32-byte output. Matches Python's
   * cryptography.hazmat HKDF(salt=epk, info=label, length=32).
   */
  internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    // Extract: PRK = HMAC(salt, ikm)
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    val prk = mac.doFinal(ikm)
    // Expand: T(1) = HMAC(PRK, info || 0x01); OKM = T(1)[0..32)
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    mac.update(info)
    mac.update(0x01.toByte())
    val t1 = mac.doFinal()
    return t1.copyOf(KEY_SIZE)
  }

  // ───────────────── X25519 raw <-> JCA key conversions ─────────────────────
  //
  // Stock JDK 17 has no raw X25519 byte API, so we convert through the
  // u-coordinate BigInteger. RFC 7748 uses little-endian; XECPublicKey.getU()
  // returns the numeric value. Valid u < 2^255, so no high-bit ambiguity.

  internal fun rawToPublicKey(raw: ByteArray): java.security.PublicKey {
    // little-endian -> BigInteger u (mask high bit per RFC 7748 on decode)
    val le = raw.copyOf(PUB_SIZE)
    le[PUB_SIZE - 1] = (le[PUB_SIZE - 1].toInt() and 0x7f).toByte()
    val be = ByteArray(PUB_SIZE)
    for (i in 0 until PUB_SIZE) be[i] = le[PUB_SIZE - 1 - i]
    val u = BigInteger(1, be)
    val spec = XECPublicKeySpec(NamedParameterSpec.X25519, u)
    return KeyFactory.getInstance("XDH").generatePublic(spec)
  }

  internal fun publicKeyToRaw(pub: XECPublicKey): ByteArray {
    val u = pub.u
    var be = u.toByteArray() // big-endian, may have sign byte / be shorter
    if (be.size > PUB_SIZE) be = be.copyOfRange(be.size - PUB_SIZE, be.size)
    val beFixed = ByteArray(PUB_SIZE)
    System.arraycopy(be, 0, beFixed, PUB_SIZE - be.size, be.size)
    val le = ByteArray(PUB_SIZE)
    for (i in 0 until PUB_SIZE) le[i] = beFixed[PUB_SIZE - 1 - i]
    return le
  }

  private fun decodeBase64Flexible(s: String): ByteArray {
    val t = s.trim()
    return try {
      if (t.contains('-') || t.contains('_')) {
        JavaBase64.getUrlDecoder().decode(t.padEnd((t.length + 3) / 4 * 4, '='))
      } else {
        JavaBase64.getDecoder().decode(t)
      }
    } catch (_: IllegalArgumentException) {
      // Last resort: url-decode after normalizing.
      JavaBase64.getUrlDecoder().decode(t.replace('+', '-').replace('/', '_'))
    }
  }
}
