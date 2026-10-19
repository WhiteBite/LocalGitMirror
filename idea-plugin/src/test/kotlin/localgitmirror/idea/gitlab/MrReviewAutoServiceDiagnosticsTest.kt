package localgitmirror.idea.gitlab

import localgitmirror.idea.diagnostics.AutomationFailureDeduper
import localgitmirror.idea.diagnostics.failureSignature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class MrReviewAutoServiceDiagnosticsTest {

  @Test
  fun `forced cycle failure surfaces exactly one diagnostic - not silence, not a flood`() {
    val deduper = AutomationFailureDeduper()
    val signature = failureSignature(IOException("postbox unreachable"))
    assertEquals(signature, deduper.failure(signature))
    repeat(120) { cycle ->
      assertNull(deduper.failure(signature), "cycle $cycle after the first report must stay silent")
    }
  }

  @Test
  fun `recovered cycle re-arms the failure so a recurrence surfaces again`() {
    val deduper = AutomationFailureDeduper()
    val signature = failureSignature(RuntimeException("gitlab 502"))
    assertNotNull(deduper.failure(signature))
    deduper.recovered()
    assertEquals(signature, deduper.failure(signature))
  }

  @Test
  fun `a different failure replaces the active one and surfaces immediately`() {
    val deduper = AutomationFailureDeduper()
    assertNotNull(deduper.failure("IOException: timeout"))
    assertEquals("IllegalStateException: parse", deduper.failure("IllegalStateException: parse"))
    assertNull(deduper.failure("IllegalStateException: parse"))
  }

  @Test
  fun `failure signature is stable for identical errors and separates different ones`() {
    assertEquals(failureSignature(IOException("boom")), failureSignature(IOException("boom")))
    assertTrue(failureSignature(IOException("a")) != failureSignature(IllegalStateException("a")))
    assertTrue(failureSignature(IOException("a")) != failureSignature(IOException("b")))
    assertFalse(failureSignature(IOException("a\nb")).contains('\n'))
  }

  @Test
  fun `dirty worktree skip becomes a visible event exactly once per dirty period`() {
    val deduper = AutomationFailureDeduper()
    assertNotNull(autoSendSkipDiagnostic(deduper, worktreeClean = false))
    assertNull(autoSendSkipDiagnostic(deduper, worktreeClean = false))
    assertNull(autoSendSkipDiagnostic(deduper, worktreeClean = true))
    assertNotNull(autoSendSkipDiagnostic(deduper, worktreeClean = false))
  }
}
