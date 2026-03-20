package localgitmirror.idea.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import localgitmirror.idea.sync.v2.SyncPlanning

class SyncPlanningTest {

  @Test
  fun `predict no-op when remote already has head`() {
    val p = SyncPlanning.predict(
      head = "abc1234",
      bestBase = null,
      remoteHasHead = true,
      rangeCount = null,
      fullCount = 10
    )
    assertEquals("pointer-only", p.mode)
    assertEquals(0, p.count)
  }

  @Test
  fun `predict incremental when base known`() {
    val p = SyncPlanning.predict(
      head = "def5678",
      bestBase = "abc1234",
      remoteHasHead = false,
      rangeCount = 3,
      fullCount = 10
    )
    assertEquals("incremental", p.mode)
    assertEquals("abc1234..def5678", p.range)
    assertEquals(3, p.count)
  }

  @Test
  fun `predict full when no known base`() {
    val p = SyncPlanning.predict(
      head = "def5678",
      bestBase = null,
      remoteHasHead = false,
      rangeCount = null,
      fullCount = 12
    )
    assertEquals("full", p.mode)
    assertEquals(12, p.count)
  }
}
