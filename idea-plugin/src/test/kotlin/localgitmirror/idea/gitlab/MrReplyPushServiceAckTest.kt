package localgitmirror.idea.gitlab

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MrReplyPushServiceAckTest {

  private val fileReplies = listOf(
    MrReplies.Reply(MrReplies.Kind.THREAD, "t1", "", 0, false, "a"),
    MrReplies.Reply(MrReplies.Kind.THREAD, "t2", "", 0, false, "b"),
    MrReplies.Reply(MrReplies.Kind.NEW_GENERAL, "", "", 0, false, "c"),
  )

  private fun report(failed: Int = 0, parseErrors: Int = 0, skipped: Int = 0) =
    MrReplyPushService.FileReport(
      path = "mr-replies/mr-!42.md",
      posted = 3,
      dupSkipped = 0,
      skipped = skipped,
      failed = failed,
      details = emptyList(),
      parseErrors = parseErrors,
    )

  @Test
  fun `partial approval does not ack`() {
    assertFalse(MrReplyPushService.shouldAck(report(), fileReplies.drop(1), fileReplies))
  }

  @Test
  fun `approval of every parsed reply acks`() {
    assertTrue(MrReplyPushService.shouldAck(report(), fileReplies, fileReplies))
  }

  @Test
  fun `full push with failures does not ack`() {
    assertFalse(MrReplyPushService.shouldAck(report(failed = 1), fileReplies, fileReplies))
  }

  @Test
  fun `full push with parse errors does not ack`() {
    assertFalse(MrReplyPushService.shouldAck(report(parseErrors = 1), fileReplies, fileReplies))
  }

  @Test
  fun `thread-gone skips do not block the ack`() {
    assertTrue(MrReplyPushService.shouldAck(report(skipped = 2), fileReplies, fileReplies))
  }
}
