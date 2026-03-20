package localgitmirror.idea.workkit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeStealthDumpTest {

  @Test
  fun `encrypt and decrypt roundtrip`() {
    val payload = "hello-native-stealth".toByteArray()
    val dump = NativeStealthDump.encryptBundleBytes(payload, password = "dandan")

    assertTrue(dump.copyOfRange(0, 8).contentEquals(NativeStealthDump.MAGIC))

    val plain = NativeStealthDump.decryptDumpBytes(dump, password = "dandan")
    assertContentEquals(payload, plain)
  }

  @Test
  fun `decrypt fails on wrong password`() {
    val payload = "hello-native-stealth".toByteArray()
    val dump = NativeStealthDump.encryptBundleBytes(payload, password = "dandan")

    assertFailsWith<Exception> {
      NativeStealthDump.decryptDumpBytes(dump, password = "wrong")
    }
  }

  @Test
  fun `decrypt rejects non-native magic`() {
    val bad = "not-native-dump".toByteArray()
    assertFailsWith<IllegalArgumentException> {
      NativeStealthDump.decryptDumpBytes(bad, password = "dandan")
    }
  }
}
