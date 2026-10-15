package localgitmirror.idea.actions

import localgitmirror.idea.gitlab.MrReviewService
import localgitmirror.idea.mirror.MirrorSyncApi
import kotlin.test.Test
import kotlin.test.assertEquals

class GitLabMrSenderPlanTest {

  @Test
  fun `mrTargetsFrom maps open MR rows to branch iid targets`() {
    val rows = listOf(
      mrRow(iid = 50, branch = "agent_mode"),
      mrRow(iid = 47, branch = "develop"),
      mrRow(iid = 51, branch = ""),
    )
    assertEquals(
      listOf("agent_mode" to 50, "develop" to 47),
      GitLabMrSender.mrTargetsFrom(rows),
    )
  }

  @Test
  fun `mrTargetsFrom returns empty when no MR carries a branch`() {
    assertEquals(emptyList(), GitLabMrSender.mrTargetsFrom(listOf(mrRow(iid = 9, branch = ""))))
  }

  private fun mrRow(iid: Int, branch: String) = MrReviewService.MrRowItem(
    iid = iid, title = "", sourceBranch = branch, updatedAt = "",
    unresolved = 0, totalThreads = 0, discussions = emptyList(),
    source = MrReviewService.Source.GITLAB, receivedMarkdown = null,
  )

  @Test
  fun `tip-equal branch is skipped`() {
    val plan = GitLabMrSender.decideMrSend(
      mirrorRefs = mapOf("feat" to "aaaa1111"),
      localTips = mapOf("feat" to "aaaa1111"),
      branches = listOf("feat"),
    )
    assertEquals(emptyList<String>(), plan.sent)
    assertEquals(listOf("feat"), plan.skipped)
  }

  @Test
  fun `branch with a new tip is sent`() {
    val plan = GitLabMrSender.decideMrSend(
      mirrorRefs = mapOf("feat" to "aaaa1111"),
      localTips = mapOf("feat" to "bbbb2222"),
      branches = listOf("feat"),
    )
    assertEquals(listOf("feat"), plan.sent)
    assertEquals(emptyList<String>(), plan.skipped)
  }

  @Test
  fun `branch missing from mirror refs is sent`() {
    val plan = GitLabMrSender.decideMrSend(
      mirrorRefs = emptyMap(),
      localTips = mapOf("feat" to "aaaa1111"),
      branches = listOf("feat"),
    )
    assertEquals(listOf("feat"), plan.sent)
    assertEquals(emptyList<String>(), plan.skipped)
  }

  @Test
  fun `unresolvable tip never drops the branch`() {
    val plan = GitLabMrSender.decideMrSend(
      mirrorRefs = mapOf("feat" to "aaaa1111"),
      localTips = mapOf("feat" to "aaaa1111"),
      branches = listOf("feat", "ghost"),
    )
    assertEquals(listOf("ghost"), plan.sent)
    assertEquals(listOf("feat"), plan.skipped)
  }

  @Test
  fun `mirror refs failure degrades to send-everything`() {
    assertEquals(emptyMap<String, String>(), GitLabMrSender.mirrorRefsFrom(null))
    assertEquals(emptyMap<String, String>(), GitLabMrSender.mirrorRefsFrom(MirrorSyncApi.RefsResult(500, "err", null, null)))
    assertEquals(emptyMap<String, String>(), GitLabMrSender.mirrorRefsFrom(MirrorSyncApi.RefsResult(200, "OK", null, null)))
  }

  @Test
  fun `mirror refs result maps branch to sha`() {
    val refs = mapOf(
      "feat" to MirrorSyncApi.RefInfo("aaaa1111", "", false),
      "old" to MirrorSyncApi.RefInfo("", "", false),
    )
    assertEquals(
      mapOf("feat" to "aaaa1111", "old" to ""),
      GitLabMrSender.mirrorRefsFrom(MirrorSyncApi.RefsResult(200, "OK", null, refs))
    )
  }
}
