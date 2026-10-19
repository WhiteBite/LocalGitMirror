package localgitmirror.idea.gitlab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MrReviewDialogSendGateTest {

  private fun threadReply(id: String) =
    MrReplies.Reply(MrReplies.Kind.THREAD, id, "", 0, false, "body")

  private fun anchoredReply(file: String, line: Int) =
    MrReplies.Reply(MrReplies.Kind.NEW_ANCHORED, "", file, line, false, "body")

  private fun generalReply() =
    MrReplies.Reply(MrReplies.Kind.NEW_GENERAL, "", "", 0, false, "body")

  private fun discussion(id: String, resolved: Boolean = false, file: String? = null, line: Int? = null) =
    GitLabApi.MrDiscussion(id, resolved, listOf(GitLabApi.MrNote("a", "2026-10-19", false, "b", false, file, line)))

  @Test
  fun `closed MR is refused`() {
    val decision = sendRemainderDecision(
      "closed", true, listOf(discussion("abc")), listOf(threadReply("abc")), force = false,
    )
    assertEquals(MrSendBlockReason.CLOSED_MR, decision.blockReason)
    assertTrue(decision.allowed.isEmpty())
    assertTrue(decision.refusals.isEmpty())
  }

  @Test
  fun `closed MR with explicit force still sends`() {
    val replies = listOf(threadReply("abc"))
    val decision = sendRemainderDecision(
      "closed", true, listOf(discussion("abc")), replies, force = true,
    )
    assertNull(decision.blockReason)
    assertEquals(replies, decision.allowed)
  }

  @Test
  fun `notes unavailable refuses verifiable replies without force`() {
    val decision = sendRemainderDecision(
      "open", false, emptyList(), listOf(threadReply("abc"), anchoredReply("F.kt", 10)), force = false,
    )
    assertEquals(MrSendBlockReason.NOTES_UNAVAILABLE, decision.blockReason)
    assertTrue(decision.allowed.isEmpty())
  }

  @Test
  fun `notes unavailable with explicit force sends`() {
    val replies = listOf(threadReply("abc"))
    val decision = sendRemainderDecision(
      "open", false, emptyList(), replies, force = true,
    )
    assertNull(decision.blockReason)
    assertEquals(replies, decision.allowed)
  }

  @Test
  fun `notes unavailable does not refuse general-only replies`() {
    val replies = listOf(generalReply())
    val decision = sendRemainderDecision("open", false, emptyList(), replies, force = false)
    assertNull(decision.blockReason)
    assertEquals(replies, decision.allowed)
  }

  @Test
  fun `unknown thread id is refused while the remaining sections still send`() {
    val good = threadReply("abc")
    val bad = threadReply("zzz")
    val note = generalReply()
    val decision = sendRemainderDecision(
      "open", true, listOf(discussion("abc")), listOf(good, bad, note), force = false,
    )
    assertNull(decision.blockReason)
    assertEquals(listOf(good, note), decision.allowed)
    assertEquals(1, decision.refusals.size)
    assertEquals(MrSendRefusalReason.UNKNOWN_THREAD, decision.refusals.single().reason)
    assertEquals("zzz", decision.refusals.single().threadId)
  }

  @Test
  fun `duplicate anchor is refused`() {
    val unresolved = discussion("t1", resolved = false, file = "F.kt", line = 10)
    val resolvedThread = discussion("t2", resolved = true, file = "F.kt", line = 11)
    val colliding = anchoredReply("F.kt", 13)
    val far = anchoredReply("F.kt", 14)
    val otherFile = anchoredReply("G.kt", 10)
    val decision = sendRemainderDecision(
      "open", true, listOf(unresolved, resolvedThread), listOf(colliding, far, otherFile), force = false,
    )
    assertNull(decision.blockReason)
    assertEquals(listOf(far, otherFile), decision.allowed)
    val refusal = decision.refusals.single()
    assertEquals(MrSendRefusalReason.DUPLICATE_ANCHOR, refusal.reason)
    assertEquals("t1", refusal.threadId)
    assertEquals("F.kt:10", refusal.anchor)
  }

  @Test
  fun `happy path sends everything`() {
    val replies = listOf(threadReply("abc"), anchoredReply("Other.kt", 1), generalReply())
    val decision = sendRemainderDecision(
      "open", true, listOf(discussion("abc", false, "F.kt", 10)), replies, force = false,
    )
    assertNull(decision.blockReason)
    assertEquals(replies, decision.allowed)
    assertTrue(decision.refusals.isEmpty())
  }

  @Test
  fun `explicit force includes refused sections`() {
    val bad = threadReply("zzz")
    val decision = sendRemainderDecision(
      "open", true, listOf(discussion("abc")), listOf(bad), force = true,
    )
    assertNull(decision.blockReason)
    assertEquals(listOf(bad), decision.allowed)
    assertTrue(decision.refusals.isEmpty())
  }
}
