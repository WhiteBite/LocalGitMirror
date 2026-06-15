package localgitmirror.idea.workkit

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object NativeStealthDump {
  // Format: VERSION(1) + SALT(16) + NONCE(12) + LEN(8) + CIPHERTEXT
  // No magic bytes — file looks like pure random noise to scanners
  private const val FORMAT_VERSION: Byte = 0x01
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
    return byteArrayOf(FORMAT_VERSION) + s + n + len + ciphertext
  }

  fun decryptDumpBytes(dumpBytes: ByteArray, password: String): ByteArray {
    require(password.isNotBlank()) { "Password cannot be empty" }
    val minLen = 1 + SALT_SIZE + NONCE_SIZE + 8 + 16
    require(dumpBytes.size >= minLen) { "File too small" }

    var cursor = 0
    val version = dumpBytes[cursor]
    cursor += 1

    // Support legacy magic format (LGMSTRL1) for backwards compatibility
    if (version == 'L'.code.toByte()) {
      return decryptLegacy(dumpBytes, password)
    }

    require(version == FORMAT_VERSION) { "Unsupported format version" }

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

  /** Decrypt files created with the old LGMSTRL1 magic format. */
  private fun decryptLegacy(data: ByteArray, password: String): ByteArray {
    val legacyMagicSize = 8 // "LGMSTRL1"
    var cursor = legacyMagicSize
    val salt = data.copyOfRange(cursor, cursor + SALT_SIZE)
    cursor += SALT_SIZE
    val nonce = data.copyOfRange(cursor, cursor + NONCE_SIZE)
    cursor += NONCE_SIZE
    val payloadLen = ByteBuffer.wrap(data, cursor, 8).long
    cursor += 8
    require(payloadLen >= 16) { "Corrupted payload" }
    require(cursor + payloadLen <= data.size) { "Corrupted payload" }
    val ciphertext = data.copyOfRange(cursor, cursor + payloadLen.toInt())
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
