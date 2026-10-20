package localgitmirror.idea.gitlab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitLabApiCompareRangesTest {

  @Test
  fun `parseHunkNewRanges reads every hunk of a multi-hunk diff`() {
    val diff = """
      @@ -10,7 +10,9 @@
       context
      -old
      +new
      @@ -40,3 +42,1 @@
      -old
      +new
    """.trimIndent()
    assertEquals(listOf(10..18, 42..42), GitLabApi.parseHunkNewRanges(diff))
  }

  @Test
  fun `missing count means a single line`() {
    assertEquals(listOf(7..7), GitLabApi.parseHunkNewRanges("@@ -1 +7 @@\n+x"))
  }

  @Test
  fun `zero count yields a range that contains nothing`() {
    val ranges = GitLabApi.parseHunkNewRanges("@@ -5,3 +9,0 @@\n-a\n-b")
    assertEquals(1, ranges.size)
    assertTrue(9 !in ranges[0])
  }

  @Test
  fun `garbage and content lines are ignored`() {
    val diff = "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1,5 +1,5 @@\n context\n-removed\n+added\nrandom text\n@@@ broken\n"
    assertEquals(listOf(1..5), GitLabApi.parseHunkNewRanges(diff))
  }

  @Test
  fun `parseCompareRanges maps new_path to its hunk ranges`() {
    val body = """
      {"commit":{},"diffs":[
        {"old_path":"a/Foo.kt","new_path":"Foo.kt","diff":"@@ -1,4 +2,5 @@\n+x"},
        {"old_path":"b/Bar.kt","new_path":"Bar.kt","diff":"@@ -30 +30 @@\n+y"}
      ]}
    """.trimIndent()
    val ranges = GitLabApi.parseCompareRanges(body)
    assertEquals(setOf("Foo.kt", "Bar.kt"), ranges.keys)
    assertEquals(listOf(2..6), ranges["Foo.kt"])
    assertEquals(listOf(30..30), ranges["Bar.kt"])
  }

  @Test
  fun `parseCompareRanges tolerates a body without diffs`() {
    assertTrue(GitLabApi.parseCompareRanges("""{"commit":null}""").isEmpty())
  }

  @Test
  fun `parseMrsBody reads target_branch and defaults it when absent`() {
    val body = """
      [{"iid":24,"title":"T","source_branch":"llm-core","updated_at":"u","target_branch":"format-core"},
       {"iid":25,"title":"T2","source_branch":"b2","updated_at":"u2"}]
    """.trimIndent()
    val mrs = GitLabApi.parseMrsBody(body)
    assertEquals(2, mrs.size)
    assertEquals("format-core", mrs[0].targetBranch)
    assertEquals("", mrs[1].targetBranch)
  }
}
