package localgitmirror.idea.workkit

import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object RepoFileSyncCrypto {
  private const val FORMAT_VERSION: Byte = 0x01
  private const val SALT_SIZE = 16
  private const val NONCE_SIZE = 12
  private const val PBKDF2_ITERATIONS = 200_000
  private const val KEY_SIZE_BYTES = 32
  private const val GCM_TAG_BITS = 128
  private const val BUFFER_SIZE = 1024 * 1024

  fun encryptFile(input: File, output: File, password: String, onProgress: ((Long, Long) -> Unit)? = null) {
    require(password.isNotBlank()) { "Password cannot be empty" }
    val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
    val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
    val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, nonce)
    val total = input.length()
    output.outputStream().use { rawOut ->
      rawOut.write(byteArrayOf(FORMAT_VERSION))
      rawOut.write(salt)
      rawOut.write(nonce)
      rawOut.write(ByteBuffer.allocate(8).putLong(total).array())
      CipherOutputStream(rawOut, cipher).use { out ->
        input.inputStream().use { inputStream ->
          val buf = ByteArray(BUFFER_SIZE)
          var done = 0L
          while (true) {
            val n = inputStream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            onProgress?.invoke(done, total)
          }
        }
      }
    }
  }

  fun decryptFile(input: File, output: File, password: String, onProgress: ((Long, Long) -> Unit)? = null) {
    require(password.isNotBlank()) { "Password cannot be empty" }
    input.inputStream().use { rawIn ->
      val version = rawIn.read()
      require(version == FORMAT_VERSION.toInt()) { "Unsupported file container version" }
      val salt = rawIn.readNBytes(SALT_SIZE)
      val nonce = rawIn.readNBytes(NONCE_SIZE)
      val plainSizeBytes = rawIn.readNBytes(8)
      require(salt.size == SALT_SIZE && nonce.size == NONCE_SIZE && plainSizeBytes.size == 8) { "Corrupted file container" }
      val total = ByteBuffer.wrap(plainSizeBytes).long
      require(total >= 0) { "Corrupted file container" }
      val cipher = cipher(Cipher.DECRYPT_MODE, password, salt, nonce)
      CipherInputStream(rawIn, cipher).use { inputStream ->
        output.outputStream().use { out ->
          val buf = ByteArray(BUFFER_SIZE)
          var done = 0L
          while (true) {
            val n = inputStream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            onProgress?.invoke(done, total)
          }
        }
      }
    }
  }

  private fun cipher(mode: Int, password: String, salt: ByteArray, nonce: ByteArray): Cipher {
    val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE_BYTES * 8)
    val key = keyFactory.generateSecret(spec).encoded
    return Cipher.getInstance("AES/GCM/NoPadding").also {
      it.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    }
  }
}
