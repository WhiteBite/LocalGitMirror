package localgitmirror.idea.gitlab

import com.intellij.openapi.project.Project
import localgitmirror.idea.settings.MrPublishReceipt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MrReviewReceiptTest {

  private val project: Project = java.lang.reflect.Proxy.newProxyInstance(
    Project::class.java.classLoader,
    arrayOf(Project::class.java)
  ) { _, _, _ -> null } as Project

  private fun receipt(posted: Int, failed: Int = 0, at: String = "2026-10-19 14:02") =
    MrPublishReceipt(posted = posted, failed = failed, at = at)

  @Test
  fun `postbox receipt wins over the persisted one`() {
    val postbox = receipt(posted = 19, at = "2026-10-20 10:00")
    val persisted = receipt(posted = 3, at = "2026-10-18 09:00")
    assertEquals(postbox, MrReviewService.receiptFor(24, postbox, persisted))
  }

  @Test
  fun `postbox gone falls back to the persisted receipt with its date`() {
    val persisted = receipt(posted = 19, at = "2026-10-19 14:02")
    val chosen = MrReviewService.receiptFor(24, null, persisted)
    assertEquals(persisted, chosen)
    assertEquals("2026-10-19 14:02", chosen?.at)
  }

  @Test
  fun `both absent means no badge`() {
    assertNull(MrReviewService.receiptFor(24, null, null))
  }

  @Test
  fun `freshness is unknown when either tip is missing`() {
    assertEquals(MrReviewService.BranchFreshness.UNKNOWN, MrReviewService.branchFreshness(null, "abc123"))
    assertEquals(MrReviewService.BranchFreshness.UNKNOWN, MrReviewService.branchFreshness("abc123", null))
    assertEquals(MrReviewService.BranchFreshness.UNKNOWN, MrReviewService.branchFreshness("", "abc123"))
    assertEquals(MrReviewService.BranchFreshness.UNKNOWN, MrReviewService.branchFreshness("abc123", " "))
  }

  @Test
  fun `freshness is ok when tips match`() {
    assertEquals(MrReviewService.BranchFreshness.OK, MrReviewService.branchFreshness("abc123", "abc123"))
    assertEquals(MrReviewService.BranchFreshness.OK, MrReviewService.branchFreshness("ABC123", "abc123"))
  }

  @Test
  fun `freshness is ahead when the local tip is an ancestor of the mirror tip`() {
    assertEquals(MrReviewService.BranchFreshness.AHEAD, MrReviewService.branchFreshness("abc123", "def456", localAncestorOfMirror = true))
  }

  @Test
  fun `freshness is stale when tips differ and local is not behind`() {
    assertEquals(MrReviewService.BranchFreshness.STALE, MrReviewService.branchFreshness("abc123", "def456", localAncestorOfMirror = false))
    assertEquals(MrReviewService.BranchFreshness.STALE, MrReviewService.branchFreshness("abc123", "def456"))
  }

  @Test
  fun `tooltip lists only failed reply labels`() {
    val details = listOf(
      "reply 1: duplicate, already in GitLab",
      "reply 2: line not in MR diff, posted as general note",
      "reply 3: HTTP 500 Internal Server Error",
      "reply 4: posted, resolve failed HTTP 400",
      "reply 5: thread 123 no longer exists",
      "parse: bad line",
    )
    assertEquals(
      listOf("reply 3: HTTP 500 Internal Server Error", "reply 4: posted, resolve failed HTTP 400"),
      MrReviewService.failedReplyTooltipLines(details),
    )
  }

  @Test
  fun `parseReceipt reads the status markdown the push renders`() {
    val report = MrReplyPushService.FileReport(
      path = "mr-replies/mr-!24.md", posted = 19, dupSkipped = 2, skipped = 1, failed = 4,
      details = listOf(
        "reply 3: HTTP 500 Internal Server Error",
        "reply 1: duplicate, already in GitLab",
      ),
      notAnchored = listOf("reply 7: Foo.kt:9 — line outside diff hunks"),
    )
    val md = MrReplyPushService(project).renderStatus(24, report)
    val parsed = MrReviewService(project).parseReceipt(md)
    assertEquals(19, parsed.posted)
    assertEquals(4, parsed.failed)
    assertEquals(1, parsed.skipped)
    assertEquals(1, parsed.notAnchored)
    assertTrue(parsed.at.isNotBlank(), md)
    assertEquals(
      listOf(
        "reply 3: HTTP 500 Internal Server Error",
        "reply 1: duplicate, already in GitLab",
        "reply 7: Foo.kt:9 — line outside diff hunks",
      ),
      parsed.details,
    )
  }
}
