package localgitmirror.idea.actions

import localgitmirror.idea.gitlab.GitLabApi
import kotlin.test.Test
import kotlin.test.assertEquals

class GitLabMrSenderMrsTargetsTest {

  @Test
  fun `mrTargetsFromMrs maps open MRs to ordered branch iid pairs`() {
    val mrs = listOf(
      mr(iid = 50, branch = "agent_mode"),
      mr(iid = 47, branch = "develop"),
    )
    assertEquals(
      listOf("agent_mode" to 50, "develop" to 47),
      GitLabMrSender.mrTargetsFromMrs(mrs),
    )
  }

  @Test
  fun `mrTargetsFromMrs drops MR with blank source branch`() {
    assertEquals(
      listOf("develop" to 47),
      GitLabMrSender.mrTargetsFromMrs(listOf(mr(iid = 51, branch = ""), mr(iid = 47, branch = "develop"))),
    )
  }

  @Test
  fun `mrTargetsFromMrs returns empty for empty list`() {
    assertEquals(emptyList(), GitLabMrSender.mrTargetsFromMrs(emptyList()))
  }

  private fun mr(iid: Int, branch: String) =
    GitLabApi.MrInfo(iid = iid, title = "", sourceBranch = branch, updatedAt = "")
}
