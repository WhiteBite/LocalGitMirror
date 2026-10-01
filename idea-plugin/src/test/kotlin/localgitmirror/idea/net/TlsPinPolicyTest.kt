package localgitmirror.idea.net

import kotlin.test.Test
import kotlin.test.assertEquals

class TlsPinPolicyTest {

  private val leafAbc = "abc".toByteArray()
  private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  private val emptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  @Test
  fun `empty pin returns tofu with computed hash`() {
    assertEquals(
      TlsPinPolicy.Verdict.TrustOnFirstUse(abcSha256),
      TlsPinPolicy.evaluate(leafAbc, "")
    )
  }

  @Test
  fun `whitespace only pin returns tofu`() {
    assertEquals(
      TlsPinPolicy.Verdict.TrustOnFirstUse(abcSha256),
      TlsPinPolicy.evaluate(leafAbc, "   ")
    )
  }

  @Test
  fun `matching pin accepts`() {
    assertEquals(TlsPinPolicy.Verdict.Match, TlsPinPolicy.evaluate(leafAbc, abcSha256))
  }

  @Test
  fun `colon separated uppercase pin accepts`() {
    val formatted = abcSha256.chunked(2).joinToString(":").uppercase()
    assertEquals(TlsPinPolicy.Verdict.Match, TlsPinPolicy.evaluate(leafAbc, formatted))
  }

  @Test
  fun `mismatching pin rejects with both hashes`() {
    assertEquals(
      TlsPinPolicy.Verdict.Mismatch(abcSha256, emptySha256),
      TlsPinPolicy.evaluate(ByteArray(0), abcSha256)
    )
  }
}
