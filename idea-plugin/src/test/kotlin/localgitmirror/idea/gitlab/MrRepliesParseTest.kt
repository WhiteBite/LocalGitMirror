package localgitmirror.idea.gitlab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MrRepliesParseTest {

  private val sample = """
    <!-- lgm-replies v1 -->
    # MR !42 — answers
    - branch: refactor/x

    ## thread 8f2e4c1d
    resolve: yes
    Moved reset before fetch, added regression test.

    ## new backend/app/routers/sync.py:386
    Detach here strands HEAD on early returns.

    ## new
    Overall looks good, one question below.

    ## thread a0b177d9
  """.trimIndent()

  @Test
  fun `parses header branch and three valid sections`() {
    val f = MrReplies.parse(sample)
    assertEquals(42, f.iid)
    assertEquals("refactor/x", f.branch)
    assertEquals(3, f.replies.size)

    val first = f.replies[0]
    assertEquals(MrReplies.Kind.THREAD, first.kind)
    assertEquals("8f2e4c1d", first.threadId)
    assertTrue(first.resolve)
    assertTrue(first.body.startsWith("Moved reset"))

    val second = f.replies[1]
    assertEquals(MrReplies.Kind.NEW_ANCHORED, second.kind)
    assertEquals("backend/app/routers/sync.py", second.file)
    assertEquals(386, second.line)
    assertTrue(!second.resolve)

    val third = f.replies[2]
    assertEquals(MrReplies.Kind.NEW_GENERAL, third.kind)
  }

  @Test
  fun `empty body becomes error not reply`() {
    val f = MrReplies.parse(sample)
    assertEquals(1, f.errors.size)
    assertTrue(f.errors.any { it.contains("empty body") })
  }

  @Test
  fun `resolve on non-thread section is ignored`() {
    val md = """
      # MR !7
      ## new a.kt:1
      resolve: yes
      text
    """.trimIndent()
    val f = MrReplies.parse(md)
    assertEquals(1, f.replies.size)
    assertTrue(!f.replies[0].resolve)
  }

  @Test
  fun `garbage without sections yields nothing`() {
    val f = MrReplies.parse("just some text\nno structure")
    assertEquals(0, f.iid)
    assertTrue(f.replies.isEmpty())
    assertTrue(f.errors.isEmpty())
  }

  @Test
  fun `render of approved subset round-trips through parse`() {
    val parsed = MrReplies.parse(sample)
    val approved = parsed.replies.filter { it.kind != MrReplies.Kind.NEW_GENERAL }
    val md = MrReplies.render(42, "refactor/x", approved)
    val again = MrReplies.parse(md)
    assertEquals(42, again.iid)
    assertEquals("refactor/x", again.branch)
    assertEquals(approved.size, again.replies.size)
    assertEquals(approved.map { it.body }, again.replies.map { it.body })
    assertEquals(approved.map { it.resolve }, again.replies.map { it.resolve })
    assertEquals(emptyList<String>(), again.errors)
  }

  @Test
  fun `marker is stable and distinct per target`() {
    val a = MrReplies.Reply(MrReplies.Kind.THREAD, "t1", "", 0, false, "body")
    val b = MrReplies.Reply(MrReplies.Kind.THREAD, "t2", "", 0, false, "body")
    val a2 = MrReplies.Reply(MrReplies.Kind.THREAD, "t1", "", 0, false, "body")
    assertEquals(MrReplyPushService.marker(42, a), MrReplyPushService.marker(42, a2))
    assertTrue(MrReplyPushService.marker(42, a) != MrReplyPushService.marker(42, b))
    assertTrue(MrReplyPushService.marker(42, a) != MrReplyPushService.marker(43, a))
    assertTrue(MrReplyPushService.marker(42, a).startsWith("<!-- lgm:"))
  }
}
