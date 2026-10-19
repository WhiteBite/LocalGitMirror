package localgitmirror.idea.gitlab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitLabApiPagingTest {

  @Test
  fun `mrsPageUrl keeps the opened-MR query and adds the page number`() {
    val url = GitLabApi.mrsPageUrl("https://gitlab.example.com/", "group/name", 1)
    assertTrue(url.startsWith("https://gitlab.example.com/api/v4/projects/group%2Fname/merge_requests?"))
    assertTrue(url.contains("state=opened"))
    assertTrue(url.contains("order_by=updated_at"))
    assertTrue(url.contains("sort=desc"))
    assertTrue(url.contains("per_page=50"))
    assertTrue(url.contains("page=1"))
  }

  @Test
  fun `mrsPageUrl embeds the requested page`() {
    assertTrue(GitLabApi.mrsPageUrl("https://gl", "p", 3).contains("page=3"))
  }

  @Test
  fun `nextPageOf parses a positive page number`() {
    assertEquals(2, GitLabApi.nextPageOf("2"))
    assertEquals(3, GitLabApi.nextPageOf(" 3 "))
  }

  @Test
  fun `nextPageOf yields null for blank, missing, non-numeric or non-positive values`() {
    assertNull(GitLabApi.nextPageOf(null))
    assertNull(GitLabApi.nextPageOf(""))
    assertNull(GitLabApi.nextPageOf("   "))
    assertNull(GitLabApi.nextPageOf("abc"))
    assertNull(GitLabApi.nextPageOf("0"))
    assertNull(GitLabApi.nextPageOf("-1"))
  }

  @Test
  fun `shouldFetchPage follows the next page until the 10-page cap`() {
    assertTrue(GitLabApi.shouldFetchPage(2, fetched = 1))
    assertTrue(GitLabApi.shouldFetchPage(10, fetched = 9))
    assertFalse(GitLabApi.shouldFetchPage(null, fetched = 1))
    assertFalse(GitLabApi.shouldFetchPage(11, fetched = 10))
  }
}
