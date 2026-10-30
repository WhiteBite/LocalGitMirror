package localgitmirror.idea.gitlab

import java.awt.Component
import java.awt.Container
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import javax.swing.JList
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class MrReviewBrowserTest {
  @Test
  fun `draft selection keeps full body and thread anchor separate from discussion inventory`() = onEdt {
    val received = mutableListOf<MrReviewEntry>()
    val browser = MrReviewBrowser({ JTextArea(it.text) }, received::add)
    val thread = GitLabApi.MrDiscussion("t1", false,
      listOf(GitLabApi.MrNote("Reviewer", "2026-10-28", false, "Original question", false, "src/Foo.java", 42)))
    val body = "Long AI finding with code `foo()`\n".repeat(40)
    val reply = MrReplies.Reply(MrReplies.Kind.THREAD, "t1", "", 0, false, body)

    browser.update(listOf(reply), listOf(thread))

    val selected = received.last()
    assertEquals(body, selected.text)
    assertEquals("src/Foo.java", selected.path)
    assertEquals(42, selected.line)
    assertEquals(reply, selected.reply)
    assertTrue(browser.descendants().filterIsInstance<JTextArea>().any { it.text == body })
    browser.descendants().filterIsInstance<JTabbedPane>().single().selectedIndex = 1
    assertEquals("Original question", received.last().text)
  }

  @Test
  fun `refresh retains selected draft rather than jumping to first finding`() = onEdt {
    val received = mutableListOf<MrReviewEntry>()
    val browser = MrReviewBrowser({ JTextArea(it.text) }, received::add)
    val first = MrReplies.Reply(MrReplies.Kind.NEW_ANCHORED, "", "A.java", 1, false, "First")
    val second = first.copy(file = "B.java", body = "Second")
    browser.update(listOf(first, second), emptyList())
    browser.descendants().filterIsInstance<JList<*>>().first().selectedIndex = 1

    browser.update(listOf(first, second, first.copy(file = "C.java")), emptyList())

    assertEquals("B.java", received.last().path)
  }

  @Test
  fun `files remain reviewable when no comments exist`() = onEdt {
    val received = mutableListOf<MrReviewEntry>()
    val browser = MrReviewBrowser({ JTextArea(it.text) }, received::add)
    browser.update(emptyList(), emptyList())

    browser.files(listOf("src/Changed.java", "src/Added.java"))

    assertEquals("src/Changed.java", received.last().path)
    assertEquals(2, browser.descendants().filterIsInstance<JTabbedPane>().single().selectedIndex)
  }

  @Test
  fun `batch entries get their own tab and the general kind stays visible`() = onEdt {
    val received = mutableListOf<MrReviewEntry>()
    val browser = MrReviewBrowser({ JTextArea(it.text) }, received::add)
    browser.update(emptyList(), emptyList())
    val tabs = browser.descendants().filterIsInstance<JTabbedPane>().single()
    assertEquals(3, tabs.tabCount)

    browser.updateBatches(listOf(
      MrReviewEntry("batch:b1:f1:1", "Anchored finding", "src/A.kt", 12, badge = "anchored",
        batchEntry = MrBatchEntryPreview("b1", "f1", 1, "anchored", null, false, "src/A.kt", 12, "new", "Anchored finding")),
      MrReviewEntry("batch:b1:f2:1", "General finding", badge = "general",
        batchEntry = MrBatchEntryPreview("b1", "f2", 1, "general", null, false, null, null, null, "General finding")),
    ))

    assertEquals(4, tabs.tabCount)
    assertEquals(3, tabs.selectedIndex)
    assertEquals("Anchored finding", received.last().text)
    val list = (tabs.getComponentAt(3) as javax.swing.JScrollPane).viewport.view as JList<*>
    assertEquals(2, list.model.size)
    list.selectedIndex = 1
    assertEquals("general", received.last().badge)
    assertEquals("General finding", received.last().text)

    browser.updateBatches(emptyList())
    assertEquals(3, tabs.tabCount)
  }

  @Test
  fun `drafts stay on their tab when batches arrive`() = onEdt {
    val received = mutableListOf<MrReviewEntry>()
    val browser = MrReviewBrowser({ JTextArea(it.text) }, received::add)
    val reply = MrReplies.Reply(MrReplies.Kind.NEW_GENERAL, "", "", 0, false, "Draft body")
    browser.update(listOf(reply), emptyList())
    assertEquals(0, browser.descendants().filterIsInstance<JTabbedPane>().single().selectedIndex)

    browser.updateBatches(listOf(
      MrReviewEntry("batch:b1:f1:1", "Finding", badge = "general",
        batchEntry = MrBatchEntryPreview("b1", "f1", 1, "general", null, false, null, null, null, "Finding")),
    ))

    val tabs = browser.descendants().filterIsInstance<JTabbedPane>().single()
    assertEquals(4, tabs.tabCount)
    assertEquals(0, tabs.selectedIndex)
    assertEquals("Draft body", received.last().text)
  }

  private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

  private fun Component.descendants(): Sequence<Component> = sequence {
    yield(this@descendants)
    if (this@descendants is Container) for (child in components) yieldAll(child.descendants())
  }
}
