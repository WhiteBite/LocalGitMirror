package localgitmirror.idea.gitlab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MrReplyPushServiceAckTest {

  private val fileReplies = listOf(
    MrReplies.Reply(MrReplies.Kind.THREAD, "t1", "", 0, false, "a"),
    MrReplies.Reply(MrReplies.Kind.THREAD, "t2", "", 0, false, "b"),
    MrReplies.Reply(MrReplies.Kind.NEW_GENERAL, "", "", 0, false, "c"),
  )

  private fun report(posted: Int = 3, dupSkipped: Int = 0, failed: Int = 0, parseErrors: Int = 0, skipped: Int = 0) =
    MrReplyPushService.FileReport(
      path = "mr-replies/mr-!42.md",
      posted = posted,
      dupSkipped = dupSkipped,
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

  @Test
  fun `resolve failure on an already-posted reply blocks the ack`() {
    assertFalse(MrReplyPushService.shouldAck(report(posted = 0, failed = 1), fileReplies, fileReplies))
  }

  @Test
  fun `resolve retry success on already-posted replies acks`() {
    assertTrue(MrReplyPushService.shouldAck(report(posted = 0, dupSkipped = 3), fileReplies, fileReplies))
  }

  @Test
  fun `partial approval with failures records posted subset and keeps file outstanding`() {
    val five = fileReplies + listOf(
      MrReplies.Reply(MrReplies.Kind.NEW_GENERAL, "", "", 0, false, "d"),
      MrReplies.Reply(MrReplies.Kind.NEW_GENERAL, "", "", 0, false, "e"),
    )
    val approved = five.take(5)
    val outcomes = mapOf(
      approved[0] to MrReplyPushService.Outcome.POSTED,
      approved[1] to MrReplyPushService.Outcome.POSTED,
      approved[2] to MrReplyPushService.Outcome.POSTED,
      approved[3] to MrReplyPushService.Outcome.FAILED,
      approved[4] to MrReplyPushService.Outcome.FAILED,
    )
    val r = report(posted = 3, failed = 2).copy(outcomes = outcomes)
    assertFalse(MrReplyPushService.shouldAck(r, approved, five))
    assertEquals(3, r.outcomes.values.count { it == MrReplyPushService.Outcome.POSTED })
    assertEquals(2, r.outcomes.values.count { it == MrReplyPushService.Outcome.FAILED })
  }

  @Test
  fun `retry pass where remaining replies post acks despite earlier failures`() {
    val outcomes = fileReplies.associateWith { MrReplyPushService.Outcome.POSTED }
    assertTrue(MrReplyPushService.shouldAck(report(posted = 3).copy(outcomes = outcomes), fileReplies, fileReplies))
  }
}
