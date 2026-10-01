package localgitmirror.idea.ui.exchange

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import localgitmirror.idea.workkit.BundleCrypto

class ChatTransferExportTest {

  @Test
  fun `gate allows blank password only when v3 is pinned`() {
    assertTrue(bufferExportAllowed("https://mirror", "pw", v3Pinned = false))
    assertTrue(bufferExportAllowed("https://mirror", "pw", v3Pinned = true))
    assertTrue(bufferExportAllowed("https://mirror", "", v3Pinned = true))
    assertFalse(bufferExportAllowed("https://mirror", "", v3Pinned = false))
    assertFalse(bufferExportAllowed("", "pw", v3Pinned = true))
    assertFalse(bufferExportAllowed("", "", v3Pinned = true))
  }

  @Test
  fun `decrypted buffer body passes through without password decrypt`() {
    val plaintext = "clipboard text".toByteArray(Charsets.UTF_8)
    assertTrue(bufferExportBytes(plaintext, "", decrypted = true).contentEquals(plaintext))
  }

  @Test
  fun `legacy buffer body is decrypted with the password`() {
    val plaintext = "clipboard text".toByteArray(Charsets.UTF_8)
    val sealed = BundleCrypto.encryptBundleBytes(plaintext, "pw")
    assertTrue(bufferExportBytes(sealed, "pw", decrypted = false).contentEquals(plaintext))
  }
}
