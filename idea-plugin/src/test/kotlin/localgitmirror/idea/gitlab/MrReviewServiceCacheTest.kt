package localgitmirror.idea.gitlab

import localgitmirror.idea.mirror.MirrorPostboxApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MrReviewServiceCacheTest {

  private fun item(id: String, path: String, mtime: Long) =
    MirrorPostboxApi.FileSyncItem(id = id, path = path, size = 1, plainSize = 1, mtime = mtime)

  @Test
  fun `newestByMtime keeps the newest version per iid`() {
    val old = item("a", "mr-notes/mr-!7.md", mtime = 100)
    val new = item("b", "mr-notes/mr-!7.md", mtime = 200)
    val other = item("c", "mr-notes/mr-!9.md", mtime = 50)
    val out = MrReviewService.newestByMtime(listOf(old, new, other))
    assertEquals(mapOf(7 to new, 9 to other), out)
  }

  @Test
  fun `newestByMtime keeps the first listed on equal mtime`() {
    val first = item("a", "mr-notes/mr-!7.closed.md", mtime = 100)
    val second = item("b", "mr-notes/mr-!7.closed.md", mtime = 100)
    assertEquals(first, MrReviewService.newestByMtime(listOf(first, second))[7])
  }

  @Test
  fun `newestByMtime skips paths without an MR iid`() {
    val junk = item("x", "mr-notes/readme.md", mtime = 1)
    assertEquals(emptyMap(), MrReviewService.newestByMtime(listOf(junk)))
  }

  @Test
  fun `closedByMtime is open when no marker exists`() {
    assertFalse(MrReviewService.closedByMtime(noteMtime = 100, markerMtime = null))
    assertFalse(MrReviewService.closedByMtime(noteMtime = null, markerMtime = null))
  }

  @Test
  fun `closedByMtime is closed when only the marker exists`() {
    assertTrue(MrReviewService.closedByMtime(noteMtime = null, markerMtime = 100))
  }

  @Test
  fun `closedByMtime compares mtimes and the marker wins ties`() {
    assertTrue(MrReviewService.closedByMtime(noteMtime = 100, markerMtime = 200))
    assertFalse(MrReviewService.closedByMtime(noteMtime = 200, markerMtime = 100))
    assertTrue(MrReviewService.closedByMtime(noteMtime = 100, markerMtime = 100))
  }

  @Test
  fun `shouldUseCachedDiscussions accepts equal non-blank stamps with a cache entry`() {
    assertTrue(MrReviewService.shouldUseCachedDiscussions("2026-10-19T10:00:00Z", "2026-10-19T10:00:00Z", hasCache = true))
  }

  @Test
  fun `shouldUseCachedDiscussions is false without a cache entry`() {
    assertFalse(MrReviewService.shouldUseCachedDiscussions("2026-10-19T10:00:00Z", "2026-10-19T10:00:00Z", hasCache = false))
  }

  @Test
  fun `shouldUseCachedDiscussions is false for blank or missing stamps`() {
    assertFalse(MrReviewService.shouldUseCachedDiscussions("", "", hasCache = true))
    assertFalse(MrReviewService.shouldUseCachedDiscussions(" ", " ", hasCache = true))
    assertFalse(MrReviewService.shouldUseCachedDiscussions(null, "2026-10-19T10:00:00Z", hasCache = true))
    assertFalse(MrReviewService.shouldUseCachedDiscussions("2026-10-19T10:00:00Z", "", hasCache = true))
    assertFalse(MrReviewService.shouldUseCachedDiscussions("2026-10-19T10:00:00Z", null, hasCache = true))
  }

  @Test
  fun `shouldUseCachedDiscussions is false when the MR changed`() {
    assertFalse(MrReviewService.shouldUseCachedDiscussions("2026-10-19T10:00:00Z", "2026-10-20T09:00:00Z", hasCache = true))
  }

  @Test
  fun `updatedAtOfMarkdown extracts the stamp MrNotesWriter rendered`() {
    val md = MrNotesWriter.renderMarkdown(
      iid = 5, title = "Fix login", sourceBranch = "feature/login", updatedAt = "2026-10-19T10:00:00.000Z",
      unresolved = 1, totalThreads = 2, discussions = emptyList(),
    )
    assertEquals("2026-10-19T10:00:00.000Z", MrReviewService.updatedAtOfMarkdown(md))
  }

  @Test
  fun `updatedAtOfMarkdown returns null without a stamp line`() {
    assertNull(MrReviewService.updatedAtOfMarkdown("# MR !5 — Fix login\n\n- **Ветка:** `feature/login`\n"))
  }
}
