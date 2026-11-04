package localgitmirror.idea.ui.exchange

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class ExchangeStoreTest {

  @TempDir
  lateinit var root: File

  private fun item(displayPath: String, size: Long): ExchangeItem = ExchangeItem(
    kind = ExchangeItem.Kind.FILE,
    id = "id-1",
    ts = 0.0,
    size = size,
    title = displayPath.substringAfterLast('/'),
    pinned = false,
    displayPath = displayPath,
    repo = "repo",
  )

  private fun materialize(name: String, bytes: ByteArray): File {
    val dir = File(root, ".doccache/exchange")
    dir.mkdirs()
    return File(dir, name).apply { writeBytes(bytes) }
  }

  @Test
  fun `exchangeFilePath is null for null or blank base path`() {
    val it = item("file.bin", 3)
    assertNull(exchangeFilePath(null, it))
    assertNull(exchangeFilePath("", it))
    assertNull(exchangeFilePath("   ", it))
  }

  @Test
  fun `existingExchangeFile returns the materialized copy when size matches`() {
    val bytes = byteArrayOf(1, 2, 3)
    val file = materialize("file.bin", bytes)

    assertEquals(file, existingExchangeFile(root.absolutePath, item("file.bin", bytes.size.toLong())))
  }

  @Test
  fun `existingExchangeFile is null when the copy is absent`() {
    assertNull(existingExchangeFile(root.absolutePath, item("file.bin", 3)))
  }

  @Test
  fun `existingExchangeFile is null when the on-disk size differs`() {
    materialize("file.bin", byteArrayOf(1, 2, 3))

    assertNull(existingExchangeFile(root.absolutePath, item("file.bin", 4)))
  }

  @Test
  fun `existingExchangeFile never serves a stale mr-notes copy`() {
    val bytes = "# notes\n".toByteArray(Charsets.UTF_8)
    materialize("mr-!7.md", bytes)

    assertNull(existingExchangeFile(root.absolutePath, item("mr-notes/mr-!7.md", bytes.size.toLong())))
  }
}
