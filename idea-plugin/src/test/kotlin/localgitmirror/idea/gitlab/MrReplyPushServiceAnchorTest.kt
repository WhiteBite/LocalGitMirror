package localgitmirror.idea.gitlab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MrReplyPushServiceAnchorTest {

  private fun discussion(id: String, file: String?, line: Int?, resolved: Boolean = false) =
    GitLabApi.MrDiscussion(id = id, resolved = resolved, notes = listOf(
      GitLabApi.MrNote("reviewer", "2026-10-02T10:00:00Z", false, "body", false, file, line)))

  private fun newReply(file: String, line: Int) =
    MrReplies.Reply(MrReplies.Kind.NEW_ANCHORED, "", file, line, false, "text")

  @Test
  fun `exact line of an unresolved thread clashes`() {
    val d = discussion("t1", "Foo.java", 75)
    assertEquals("t1", MrReplyPushService.anchorClash(newReply("Foo.java", 75), listOf(d))?.id)
  }

  @Test
  fun `off by one within the window clashes`() {
    val d = discussion("t1", "Foo.java", 106)
    assertEquals("t1", MrReplyPushService.anchorClash(newReply("Foo.java", 105), listOf(d))?.id)
    assertEquals("t1", MrReplyPushService.anchorClash(newReply("Foo.java", 109), listOf(d))?.id)
  }

  @Test
  fun `beyond the window does not clash`() {
    val d = discussion("t1", "Foo.java", 106)
    assertNull(MrReplyPushService.anchorClash(newReply("Foo.java", 110), listOf(d)))
    assertNull(MrReplyPushService.anchorClash(newReply("Foo.java", 217), listOf(d)))
  }

  @Test
  fun `resolved thread does not clash`() {
    val d = discussion("t1", "Foo.java", 75, resolved = true)
    assertNull(MrReplyPushService.anchorClash(newReply("Foo.java", 75), listOf(d)))
  }

  @Test
  fun `different file does not clash`() {
    val d = discussion("t1", "Bar.java", 75)
    assertNull(MrReplyPushService.anchorClash(newReply("Foo.java", 75), listOf(d)))
  }

  @Test
  fun `unanchored discussion never clashes`() {
    val d = discussion("t1", null, null)
    assertNull(MrReplyPushService.anchorClash(newReply("Foo.java", 75), listOf(d)))
  }
}
