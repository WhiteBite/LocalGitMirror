package localgitmirror.idea.workkit

import java.util.Base64

/**
 * Encrypted metadata (buffer hints, postbox paths) uses the same v2 bundle
 * container as sync dumps, base64-wrapped so it survives JSON/multipart
 * transport as an opaque string.
 */
object ExchangeCrypto {
  fun encryptHint(text: String, password: String): String =
    Base64.getEncoder().encodeToString(
      BundleCrypto.encryptBundleBytes(text.toByteArray(Charsets.UTF_8), password)
    )

  fun decryptHint(encoded: String, password: String): String =
    String(BundleCrypto.decryptDumpBytes(Base64.getDecoder().decode(encoded), password), Charsets.UTF_8)
}
