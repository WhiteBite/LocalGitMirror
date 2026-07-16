package localgitmirror.idea.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BranchSelectorModelTest {

  @Test
  fun `merge keeps local branches first and adds Mirror-only branches`() {
    val choices = BranchSelectorModel.merge(
      localBranches = listOf("master", "develop_2", "master", ""),
      mirrorBranches = listOf("mcp_front", "develop_2", "task/new", "mcp_front", " ")
    )

    assertEquals(
      listOf(
        BranchChoice("develop_2", isLocal = true),
        BranchChoice("master", isLocal = true),
        BranchChoice("mcp_front", isLocal = false),
        BranchChoice("task/new", isLocal = false)
      ),
      choices
    )
    assertEquals("★ mcp_front (Mirror)", choices[2].toString())
  }

  @Test
  fun `preferred selection preserves a selected Mirror-only branch`() {
    val choices = BranchSelectorModel.merge(
      localBranches = listOf("develop"),
      mirrorBranches = listOf("mcp_front")
    )

    assertEquals(
      "mcp_front",
      BranchSelectorModel.preferredSelection("mcp_front", "develop", choices)
    )
  }

  @Test
  fun `preferred selection falls back to current local then first available`() {
    val choices = BranchSelectorModel.merge(
      localBranches = listOf("master", "develop"),
      mirrorBranches = listOf("mcp_front")
    )

    assertEquals("develop", BranchSelectorModel.preferredSelection("deleted", "develop", choices))
    assertEquals("develop", BranchSelectorModel.preferredSelection(null, null, choices))
  }

  @Test
  fun `merge replaces stale Mirror-only branches after a later refresh`() {
    val firstResponse = BranchSelectorModel.merge(
      localBranches = listOf("develop"),
      mirrorBranches = listOf("mcp_front", "obsolete")
    )
    val refreshed = BranchSelectorModel.merge(
      localBranches = listOf("develop"),
      mirrorBranches = listOf("mcp_front", "release")
    )

    assertEquals(listOf("develop", "mcp_front", "obsolete"), firstResponse.map(BranchChoice::name))
    assertEquals(listOf("develop", "mcp_front", "release"), refreshed.map(BranchChoice::name))
  }
}
