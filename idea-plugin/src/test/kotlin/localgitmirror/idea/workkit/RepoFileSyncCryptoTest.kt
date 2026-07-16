package localgitmirror.idea.workkit

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class RepoFileSyncCryptoTest {

  @Test
  fun `encrypt and decrypt file roundtrip`() {
    val dir = Files.createTempDirectory("lgm-file-sync-test").toFile()
    try {
      val plain = dir.resolve("plain.bin")
      val encrypted = dir.resolve("payload.lgm")
      val decrypted = dir.resolve("decrypted.bin")
      val payload = ByteArray(1024 * 1024 + 17) { i -> (i % 251).toByte() }
      plain.writeBytes(payload)

      RepoFileSyncCrypto.encryptFile(plain, encrypted, "sync-password")
      RepoFileSyncCrypto.decryptFile(encrypted, decrypted, "sync-password")

      assertContentEquals(payload, decrypted.readBytes())
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `decrypt fails on wrong password`() {
    val dir = Files.createTempDirectory("lgm-file-sync-test").toFile()
    try {
      val plain = dir.resolve("plain.txt")
      val encrypted = dir.resolve("payload.lgm")
      val decrypted = dir.resolve("decrypted.txt")
      plain.writeText("repo file payload")

      RepoFileSyncCrypto.encryptFile(plain, encrypted, "sync-password")

      assertFailsWith<Exception> {
        RepoFileSyncCrypto.decryptFile(encrypted, decrypted, "wrong-password")
      }
    } finally {
      dir.deleteRecursively()
    }
  }
}
