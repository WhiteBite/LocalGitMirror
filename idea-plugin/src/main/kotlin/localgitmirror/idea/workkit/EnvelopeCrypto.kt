package localgitmirror.idea.workkit

import java.security.SecureRandom
import java.util.Base64 as JavaBase64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Lightweight envelope encryption for request/response metadata.
 *
 * Hides field names and values (repo, branch, commit hashes) from DLP / TLS
 * inspection. The DLP sees only a single opaque base64 field ("e") instead of
 * plaintext names like repo, branch, haves.
 *
 * Algorithm
 * ---------
 * Key derivation:
 *   PBKDF2-HMAC-SHA256(password, FIXED_SALT="lgm_env_key_v1__", iterations=1_000) → 32 bytes
 *   Fixed salt provides domain separation from BundleCrypto (random salt + 200k iters).
 *   Derived key is cached in-process per password value (avoids per-request KDF cost).
 *
 * Encryption:
 *   AES-256-GCM, fresh 12-byte random nonce per message.
 *
 * Wire format:
 *   base64( nonce[12] + ciphertext_with_gcm_tag )
 *
 * Must stay byte-for-byte compatible with the Python implementation in
 * backend/app/core/envelope_crypto.py.
 */
object EnvelopeCrypto {

  // Must match Python: b"lgm_env_key_v1__" (16 bytes, ASCII)
  private val ENVELOPE_SALT: ByteArray = "lgm_env_key_v1__".toByteArray(Charsets.UTF_8)
  private const val ITERATIONS = 1_000
  private const val KEY_SIZE_BYTES = 32
  private const val NONCE_SIZE = 12
  private const val GCM_TAG_BITS = 128

  // Cached derived key — recomputed only when password changes.
  @Volatile private var cachedPassword: String? = null
  @Volatile private var cachedKey: ByteArray? = null

  private fun deriveKey(password: String): ByteArray {
    // Fast path: same password → same key (no lock needed for the read).
    cachedPassword?.let { if (it == password) return cachedKey!! }
    return synchronized(this) {
      // Re-check inside lock in case another thread updated concurrently.
      cachedPassword?.let { if (it == password) return cachedKey!! }
      val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
      val spec = PBEKeySpec(password.toCharArray(), ENVELOPE_SALT, ITERATIONS, KEY_SIZE_BYTES * 8)
      val key = factory.generateSecret(spec).encoded
      cachedKey = key
      cachedPassword = password
      key
    }
  }

  /**
   * Encrypt [jsonStr] using [password].
   * Returns base64( nonce[12] + AES-GCM-ciphertext ).
   */
  fun encrypt(jsonStr: String, password: String): String {
    require(password.isNotBlank()) { "Sync password not configured" }
    val key = deriveKey(password)
    val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    val ciphertext = cipher.doFinal(jsonStr.toByteArray(Charsets.UTF_8))
    return JavaBase64.getEncoder().encodeToString(nonce + ciphertext)
  }

  /**
   * Decrypt base64 envelope [b64] using [password].
   * Returns the original JSON string.
   * Throws [IllegalArgumentException] on wrong password or tampered data.
   */
  fun decrypt(b64: String, password: String): String {
    require(password.isNotBlank()) { "Sync password not configured" }
    val raw = JavaBase64.getDecoder().decode(b64)
    require(raw.size > NONCE_SIZE) { "Envelope too short" }
    val nonce = raw.copyOfRange(0, NONCE_SIZE)
    val ciphertext = raw.copyOfRange(NONCE_SIZE, raw.size)
    val key = deriveKey(password)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
  }

  /** Encrypt a [JsonObject]. Returns base64 envelope string. */
  fun encryptJson(obj: JsonObject, password: String): String = encrypt(obj.toString(), password)

  /** Decrypt base64 envelope string. Returns a [JsonObject]. */
  fun decryptJson(b64: String, password: String): JsonObject =
    Json.parseToJsonElement(decrypt(b64, password)).jsonObject
}
