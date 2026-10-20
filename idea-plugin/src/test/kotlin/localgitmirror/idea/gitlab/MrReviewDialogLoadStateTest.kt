package localgitmirror.idea.gitlab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MrReviewDialogLoadStateTest {

  private fun row(iid: Int, discussions: List<GitLabApi.MrDiscussion> = emptyList()) =
    MrReviewService.MrRowItem(
      iid = iid,
      title = "Fix login",
      sourceBranch = "feature/login",
      updatedAt = "2026-10-19T10:00:00Z",
      unresolved = discussions.count { d -> !d.resolved && d.notes.any { !it.system } },
      totalThreads = discussions.size,
      discussions = discussions,
      source = MrReviewService.Source.GITLAB,
      receivedMarkdown = null,
    )

  private fun discussion(id: String) =
    GitLabApi.MrDiscussion(id, resolved = false, listOf(GitLabApi.MrNote("a", "2026-10-19", false, "b", false)))

  @Test
  fun `open shows the cached row and marks refreshing`() {
    val cached = row(5)
    val state = MrDialogLoadState.opened(cached)
    assertEquals(cached, state.row)
    assertTrue(state.refreshing)
    assertNull(state.loadError)
  }

  @Test
  fun `a landed fetch swaps the row and clears the indicator`() {
    val fresh = row(5, listOf(discussion("abc")))
    val state = MrDialogLoadState.opened(row(5)).refreshed(fresh)
    assertEquals(fresh, state.row)
    assertFalse(state.refreshing)
    assertNull(state.loadError)
  }

  @Test
  fun `a failed fetch keeps the cached row and surfaces the error`() {
    val cached = row(5)
    val state = MrDialogLoadState.opened(cached).refreshFailed("HTTP 500")
    assertEquals(cached, state.row)
    assertFalse(state.refreshing)
    assertEquals("HTTP 500", state.loadError)
  }

  @Test
  fun `a failure after a successful refresh keeps the last good row`() {
    val fresh = row(5, listOf(discussion("abc")))
    val state = MrDialogLoadState.opened(row(5)).refreshed(fresh).refreshFailed("HTTP 500")
    assertEquals(fresh, state.row)
    assertEquals("HTTP 500", state.loadError)
  }

  @Test
  fun `restart clears the previous failure and re-marks refreshing`() {
    val state = MrDialogLoadState.opened(row(5)).refreshFailed("HTTP 500").refreshStarted()
    assertTrue(state.refreshing)
    assertNull(state.loadError)
  }
}
