package localgitmirror.idea.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressControllerScopeTest {

  @Test
  fun `tracked operation keeps cancel active`() {
    assertEquals(CancelAffordance.ACTIVE, cancelAffordance(isSyncing = true, cancellableTracked = true))
  }

  @Test
  fun `syncing without a cancellable indicator disables cancel instead of faking it`() {
    assertEquals(CancelAffordance.DISABLED, cancelAffordance(isSyncing = true, cancellableTracked = false))
  }

  @Test
  fun `idle panel hides cancel even with a stale indicator`() {
    assertEquals(CancelAffordance.HIDDEN, cancelAffordance(isSyncing = false, cancellableTracked = true))
    assertEquals(CancelAffordance.HIDDEN, cancelAffordance(isSyncing = false, cancellableTracked = false))
  }

  @Test
  fun `active affordance always implies a cancellable indicator is tracked`() {
    for (syncing in booleanArrayOf(true, false)) {
      for (tracked in booleanArrayOf(true, false)) {
        val a = cancelAffordance(syncing, tracked)
        assertTrue(a != CancelAffordance.ACTIVE || tracked, "ACTIVE without tracked indicator (syncing=$syncing tracked=$tracked)")
      }
    }
  }
}
