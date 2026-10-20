package localgitmirror.idea.actions

import localgitmirror.idea.gitlab.GitLabApi
import localgitmirror.idea.gitlab.MrReplies
import localgitmirror.idea.gitlab.MrReviewService
import localgitmirror.idea.gitlab.MrSendBlockReason
import localgitmirror.idea.gitlab.MrSendRefusalReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadMrRepliesGateTest {

  private fun row(
    state: String = "open",
    discussions: List<GitLabApi.MrDiscussion> = emptyList(),
  ) = MrReviewService.MrRowItem(
    iid = 24, title = "", sourceBranch = "", updatedAt = "",
    unresolved = 0, totalThreads = 0, discussions = discussions,
    source = MrReviewService.Source.CACHE, receivedMarkdown = "notes", state = state,
  )

  private fun discussion(id: String) =
    GitLabApi.MrDiscussion(id, false, listOf(GitLabApi.MrNote("a", "2026-10-19", false, "b", false, null, null)))

  private fun repliesFile(vararg sections: String): MrReplies.RepliesFile =
    MrReplies.parse(sections.joinToString("\n\n"))

  @Test
  fun `closed MR is refused with the existing blocked-closed key`() {
    val decision = uploadRepliesGateDecision(
      row(state = "closed", discussions = listOf(discussion("abc"))),
      repliesFile("## thread abc\n\nbody"),
    )
    assertEquals(MrSendBlockReason.CLOSED_MR, decision.blockReason)
    assertTrue(decision.allowed.isEmpty())
    assertTrue(decision.refusals.isEmpty())
    assertEquals("mrview.send.blocked.closed", mrSendBlockedKey(decision.blockReason!!))
  }

  @Test
  fun `notes unavailable refuses verifiable replies without force`() {
    val decision = uploadRepliesGateDecision(null, repliesFile("## thread abc\n\nbody"))
    assertEquals(MrSendBlockReason.NOTES_UNAVAILABLE, decision.blockReason)
    assertTrue(decision.allowed.isEmpty())
  }

  @Test
  fun `unknown thread id in the file is refused while other sections proceed`() {
    val decision = uploadRepliesGateDecision(
      row(discussions = listOf(discussion("abc"))),
      repliesFile("## thread abc\n\nbody", "## thread zzz\n\nbody", "## new\n\ngeneral note"),
    )
    assertNull(decision.blockReason)
    assertEquals(2, decision.allowed.size)
    assertEquals(1, decision.refusals.size)
    assertEquals(MrSendRefusalReason.UNKNOWN_THREAD, decision.refusals.single().reason)
    assertEquals("zzz", decision.refusals.single().threadId)
  }

  @Test
  fun `clean file is allowed`() {
    val file = repliesFile("## thread abc\n\nbody", "## new\n\ngeneral note")
    val decision = uploadRepliesGateDecision(row(discussions = listOf(discussion("abc"))), file)
    assertNull(decision.blockReason)
    assertEquals(file.replies, decision.allowed)
    assertTrue(decision.refusals.isEmpty())
  }

  @Test
  fun `closed and notes-unavailable blocks share one confirmable shape`() {
    val closed = uploadRepliesGateDecision(
      row(state = "closed", discussions = listOf(discussion("abc"))),
      repliesFile("## thread abc\n\nbody"),
    )
    val unavailable = uploadRepliesGateDecision(null, repliesFile("## thread abc\n\nbody"))
    for (d in listOf(closed, unavailable)) {
      assertTrue(d.blockReason != null)
      assertTrue(d.allowed.isEmpty())
      assertTrue(d.refusals.isEmpty())
    }
  }
}
