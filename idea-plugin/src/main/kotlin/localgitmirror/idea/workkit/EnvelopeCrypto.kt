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
 *   PBKDF2-HMAC-SHA256(password, random_salt[16], iterations=200_000) → 32 bytes
 *   Fresh random salt per message — no key caching.
 *
 * Encryption:
 *   AES-256-GCM, fresh 12-byte random nonce per message.
 *
 * Wire format:
 *   base64( salt[16] | nonce[12] | ciphertext_with_gcm_tag )
 *
 * No version prefix. Must stay byte-for-byte compatible with
 * backend/app/core/envelope_crypto.py.
 */
object EnvelopeCrypto {

  private const val ITERATIONS   = 200_000
  private const val SALT_SIZE    = 16
  private const val KEY_SIZE_BITS = 256
  private const val NONCE_SIZE   = 12
  private const val GCM_TAG_BITS = 128

  private fun deriveKey(password: String, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_SIZE_BITS)
    return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
  }

  /**
   * Encrypt [jsonStr] → base64( salt[16] | nonce[12] | AES-GCM-ciphertext ).
   */
  fun encrypt(jsonStr: String, password: String): String {
    require(password.isNotBlank()) { "Sync password not configured" }
    val salt  = ByteArray(SALT_SIZE).also  { SecureRandom().nextBytes(it) }
    val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
    val key   = deriveKey(password, salt)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    val ct = cipher.doFinal(jsonStr.toByteArray(Charsets.UTF_8))
    return JavaBase64.getEncoder().encodeToString(salt + nonce + ct)
  }

  /**
   * Decrypt base64 envelope → original JSON string.
   * Throws on wrong password or tampered data.
   */
  fun decrypt(b64: String, password: String): String {
    require(password.isNotBlank()) { "Sync password not configured" }
    val raw = JavaBase64.getDecoder().decode(b64)
    require(raw.size >= SALT_SIZE + NONCE_SIZE + 16) { "Envelope too short" }
    val salt  = raw.copyOfRange(0, SALT_SIZE)
    val nonce = raw.copyOfRange(SALT_SIZE, SALT_SIZE + NONCE_SIZE)
    val ct    = raw.copyOfRange(SALT_SIZE + NONCE_SIZE, raw.size)
    val key   = deriveKey(password, salt)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    return String(cipher.doFinal(ct), Charsets.UTF_8)
  }

  /** Encrypt a [JsonObject]. Returns base64 envelope string. */
  fun encryptJson(obj: JsonObject, password: String): String = encrypt(obj.toString(), password)

  /** Decrypt base64 envelope string → [JsonObject]. */
  fun decryptJson(b64: String, password: String): JsonObject =
    Json.parseToJsonElement(decrypt(b64, password)).jsonObject
}
