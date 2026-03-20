package localgitmirror.idea.workkit

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object NativeStealthDump {
  val MAGIC: ByteArray = "LGMSTRL1".toByteArray(Charsets.US_ASCII)
  private const val SALT_SIZE = 16
  private const val NONCE_SIZE = 12
  private const val PBKDF2_ITERATIONS = 200_000
  private const val KEY_SIZE_BYTES = 32
  private const val GCM_TAG_BITS = 128

  fun encryptBundleBytes(
    bundleBytes: ByteArray,
    password: String,
    salt: ByteArray? = null,
    nonce: ByteArray? = null
  ): ByteArray {
    require(password.isNotBlank()) { "Password cannot be empty" }

    val s = salt ?: ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
    val n = nonce ?: ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
    require(s.size == SALT_SIZE) { "Invalid salt size" }
    require(n.size == NONCE_SIZE) { "Invalid nonce size" }

    val key = deriveKey(password, s)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, n))
    val ciphertext = cipher.doFinal(bundleBytes)

    val len = ByteBuffer.allocate(8).putLong(ciphertext.size.toLong()).array()
    return MAGIC + s + n + len + ciphertext
  }

  fun decryptDumpBytes(dumpBytes: ByteArray, password: String): ByteArray {
    require(password.isNotBlank()) { "Password cannot be empty" }
    val minLen = MAGIC.size + SALT_SIZE + NONCE_SIZE + 8 + 16
    require(dumpBytes.size >= minLen) { "Dump file too small" }

    var cursor = 0
    val magic = dumpBytes.copyOfRange(cursor, cursor + MAGIC.size)
    cursor += MAGIC.size
    require(magic.contentEquals(MAGIC)) { "Unsupported dump format" }

    val salt = dumpBytes.copyOfRange(cursor, cursor + SALT_SIZE)
    cursor += SALT_SIZE
    val nonce = dumpBytes.copyOfRange(cursor, cursor + NONCE_SIZE)
    cursor += NONCE_SIZE

    val payloadLen = ByteBuffer.wrap(dumpBytes, cursor, 8).long
    cursor += 8
    require(payloadLen >= 16) { "Corrupted dump payload" }
    require(cursor + payloadLen <= dumpBytes.size) { "Corrupted dump payload" }
    val ciphertext = dumpBytes.copyOfRange(cursor, cursor + payloadLen.toInt())

    val key = deriveKey(password, salt)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    return cipher.doFinal(ciphertext)
  }

  private fun deriveKey(password: String, salt: ByteArray): ByteArray {
    val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BYTES * 8)
    return keyFactory.generateSecret(spec).encoded
  }
}
