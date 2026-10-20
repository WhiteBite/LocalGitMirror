package localgitmirror.idea.gitlab

import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MrReplyPushServiceNotAnchoredTest {

  private val project: Project = java.lang.reflect.Proxy.newProxyInstance(
    Project::class.java.classLoader,
    arrayOf(Project::class.java)
  ) { _, _, _ -> null } as Project

  private fun newReply(file: String, line: Int) =
    MrReplies.Reply(MrReplies.Kind.NEW_ANCHORED, "", file, line, false, "text")

  @Test
  fun `no compare data means no verdict`() {
    assertNull(MrReplyPushService.anchorabilityReason(newReply("Foo.kt", 5), null))
  }

  @Test
  fun `file missing from the diff is reported`() {
    assertEquals(
      "file not in MR diff",
      MrReplyPushService.anchorabilityReason(newReply("Gone.kt", 5), mapOf("Foo.kt" to listOf(1..10))),
    )
  }

  @Test
  fun `line outside every hunk is reported`() {
    assertEquals(
      "line outside diff hunks",
      MrReplyPushService.anchorabilityReason(newReply("Foo.kt", 11), mapOf("Foo.kt" to listOf(1..10))),
    )
  }

  @Test
  fun `line inside any hunk is anchorable`() {
    assertNull(
      MrReplyPushService.anchorabilityReason(newReply("Foo.kt", 15), mapOf("Foo.kt" to listOf(1..10, 12..20))),
    )
  }

  @Test
  fun `status markdown carries the not-anchored count and reasons`() {
    val report = MrReplyPushService.FileReport(
      path = "mr-replies/mr-!24.md", posted = 14, dupSkipped = 0, skipped = 0, failed = 0,
      details = emptyList(),
      notAnchored = listOf(
        "reply 3: Foo.kt:120 — file not in MR diff",
        "reply 7: Bar.kt:9 — line outside diff hunks",
      ),
    )
    val md = MrReplyPushService(project).renderStatus(24, report)
    assertTrue(md.contains("- not anchored: 2"), md)
    assertTrue(md.contains("reply 3: Foo.kt:120 — file not in MR diff"), md)
    assertTrue(md.contains("reply 7: Bar.kt:9 — line outside diff hunks"), md)
  }

  @Test
  fun `status markdown round-trips into the row-state report`() {
    val report = MrReplyPushService.FileReport(
      path = "mr-replies/mr-!24.md", posted = 19, dupSkipped = 0, skipped = 0, failed = 0,
      details = emptyList(),
      notAnchored = listOf("reply 2: Gone.kt:5 — file not in MR diff"),
    )
    val md = MrReplyPushService(project).renderStatus(24, report)
    val st = MrReviewService(project).parseStatus(md)
    assertEquals(19, st.posted)
    assertEquals(0, st.failed)
    assertEquals(1, st.notAnchored)
  }

  @Test
  fun `parseStatus tolerates status markdown without the not-anchored line`() {
    val md = "# MR !46 — status\n- posted: 3\n- duplicates: 1\n- skipped: 0\n- failed: 2\n- at: x\n"
    assertEquals(0, MrReviewService(project).parseStatus(md).notAnchored)
  }
}
