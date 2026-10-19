package localgitmirror.idea.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelBundleActionsSummaryTest {

  @Test
  fun allFailedIsNeverSuccess() {
    val outcomes = listOf(
      MultiBranchOutcome("feature-a", false, "HTTP 500"),
      MultiBranchOutcome("feature-b", false, "branch not found")
    )
    val summary = multiBranchSummary("Pull", "myrepo", outcomes)
    assertNotEquals(MultiBranchSummaryKind.SUCCESS, summary.kind)
    assertEquals(MultiBranchSummaryKind.FAILED, summary.kind)
  }

  @Test
  fun allFailedNamesRepoBranchesAndErrors() {
    val outcomes = listOf(
      MultiBranchOutcome("feature-a", false, "HTTP 500"),
      MultiBranchOutcome("feature-b", false, "branch not found")
    )
    val message = multiBranchSummary("Pull", "myrepo", outcomes).message
    assertTrue(message.contains("myrepo"), message)
    assertTrue(message.contains("feature-a"), message)
    assertTrue(message.contains("feature-b"), message)
    assertTrue(message.contains("HTTP 500"), message)
  }

  @Test
  fun mixedCountsAreHonestAndNameFailures() {
    val outcomes = listOf(
      MultiBranchOutcome("feature-a", true),
      MultiBranchOutcome("feature-b", true),
      MultiBranchOutcome("feature-c", false, "diverged")
    )
    val summary = multiBranchSummary("Pull", "myrepo", outcomes)
    assertEquals(MultiBranchSummaryKind.PARTIAL, summary.kind)
    assertTrue(summary.message.contains("2"), summary.message)
    assertTrue(summary.message.contains("3"), summary.message)
    assertTrue(summary.message.contains("feature-c"), summary.message)
    assertTrue(summary.message.contains("myrepo"), summary.message)
  }

  @Test
  fun allOkIsSuccessWithRepoAndBranches() {
    val outcomes = listOf(
      MultiBranchOutcome("feature-a", true),
      MultiBranchOutcome("feature-b", true)
    )
    val summary = multiBranchSummary("Send", "myrepo", outcomes)
    assertEquals(MultiBranchSummaryKind.SUCCESS, summary.kind)
    assertTrue(summary.message.contains("myrepo"), summary.message)
    assertTrue(summary.message.contains("feature-a"), summary.message)
    assertTrue(summary.message.contains("feature-b"), summary.message)
  }
}
