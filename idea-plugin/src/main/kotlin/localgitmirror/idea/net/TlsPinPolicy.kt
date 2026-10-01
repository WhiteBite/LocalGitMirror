package localgitmirror.idea.net

import java.security.MessageDigest

object TlsPinPolicy {

  sealed interface Verdict {
    data class TrustOnFirstUse(val sha256Hex: String) : Verdict
    object Match : Verdict
    data class Mismatch(val expectedHex: String, val actualHex: String) : Verdict
  }

  fun evaluate(leafDer: ByteArray, storedHex: String): Verdict {
    val actual = sha256Hex(leafDer)
    val stored = normalizeHex(storedHex)
    return when {
      stored.isEmpty() -> Verdict.TrustOnFirstUse(actual)
      stored == actual -> Verdict.Match
      else -> Verdict.Mismatch(stored, actual)
    }
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun normalizeHex(raw: String): String =
    raw.filter { it != ':' && !it.isWhitespace() }.lowercase()
}
