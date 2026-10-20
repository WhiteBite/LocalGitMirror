package localgitmirror.idea.gitlab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MrReplyPushServiceHistoryTest {

  @Test
  fun `clean summary becomes a success history row`() {
    val row = MrReplyPushService.pushSummaryHistory(
      listOf(MrReplyPushService.FileReport("mr-replies/mr-!24.md", 19, 2, 0, 0, emptyList()))
    )
    assertTrue(row.success)
    assertEquals("posted=19 dup=2 skip=0 fail=0 files=1", row.details)
  }

  @Test
  fun `any failure marks the history row failed`() {
    val row = MrReplyPushService.pushSummaryHistory(
      listOf(
        MrReplyPushService.FileReport("mr-replies/mr-!24.md", 3, 0, 0, 1, listOf("reply 4: HTTP 500")),
        MrReplyPushService.FileReport("mr-replies/mr-!27.md", 0, 0, 0, 0, emptyList()),
      )
    )
    assertFalse(row.success)
    assertEquals("posted=3 dup=0 skip=0 fail=1 files=2", row.details)
  }

  @Test
  fun `empty report list is a success no-op row`() {
    val row = MrReplyPushService.pushSummaryHistory(emptyList())
    assertTrue(row.success)
    assertEquals("posted=0 dup=0 skip=0 fail=0 files=0", row.details)
  }
}
