package localgitmirror.idea.workkit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BundleCryptoTest {

  @Test
  fun `encrypt and decrypt roundtrip`() {
    val payload = "hello-native-stealth".toByteArray()
    val dump = BundleCrypto.encryptBundleBytes(payload, password = "***REMOVED***")

    // v2 format: first byte is version 0x01 (no magic bytes)
    assertTrue(dump[0] == 0x01.toByte(), "First byte should be format version 0x01")

    val plain = BundleCrypto.decryptDumpBytes(dump, password = "***REMOVED***")
    assertContentEquals(payload, plain)
  }

  @Test
  fun `decrypt fails on wrong password`() {
    val payload = "hello-native-stealth".toByteArray()
    val dump = BundleCrypto.encryptBundleBytes(payload, password = "***REMOVED***")

    assertFailsWith<Exception> {
      BundleCrypto.decryptDumpBytes(dump, password = "wrong")
    }
  }

  @Test
  fun `decrypt rejects garbage data`() {
    val bad = "not-native-dump".toByteArray()
    assertFailsWith<IllegalArgumentException> {
      BundleCrypto.decryptDumpBytes(bad, password = "***REMOVED***")
    }
  }
}
