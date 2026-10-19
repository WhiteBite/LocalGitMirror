package localgitmirror.idea.startup

import localgitmirror.idea.diagnostics.AutomationFailureDeduper
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.IOException

class PullCheckStartupDiagnosticsTest {

  @Test
  fun `swallowed startup check failure surfaces exactly once - not silence, not a flood`() {
    val deduper = AutomationFailureDeduper()
    val failure = IOException("mirror unreachable")
    assertNotNull(checkOutcomeDiagnostic(deduper, failure))
    assertNull(checkOutcomeDiagnostic(deduper, failure))
  }

  @Test
  fun `healthy check re-arms so the next failure surfaces again`() {
    val deduper = AutomationFailureDeduper()
    assertNotNull(checkOutcomeDiagnostic(deduper, IOException("boom")))
    assertNull(checkOutcomeDiagnostic(deduper, null))
    assertNotNull(checkOutcomeDiagnostic(deduper, IOException("boom")))
  }

  @Test
  fun `update and deps check failures are independent streams`() {
    val updateDeduper = AutomationFailureDeduper()
    val depsDeduper = AutomationFailureDeduper()
    assertNotNull(checkOutcomeDiagnostic(updateDeduper, IOException("refs fetch failed")))
    assertNotNull(checkOutcomeDiagnostic(depsDeduper, IOException("refs fetch failed")))
  }
}
