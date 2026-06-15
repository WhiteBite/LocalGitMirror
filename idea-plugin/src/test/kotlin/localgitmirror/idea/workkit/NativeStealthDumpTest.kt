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

    // v2 format: first byte is version 0x01 (no magic bytes)
    assertTrue(dump[0] == 0x01.toByte(), "First byte should be format version 0x01")

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
  fun `decrypt rejects garbage data`() {
    val bad = "not-native-dump".toByteArray()
    assertFailsWith<IllegalArgumentException> {
      NativeStealthDump.decryptDumpBytes(bad, password = "dandan")
    }
  }
}
