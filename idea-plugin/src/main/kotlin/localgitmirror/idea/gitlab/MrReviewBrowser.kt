package localgitmirror.idea.gitlab

import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import localgitmirror.idea.i18n.LocalGitMirrorBundle.message
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.ListSelectionModel
import javax.swing.Scrollable

internal data class MrReviewEntry(
  val key: String,
  val text: String,
  val path: String? = null,
  val line: Int? = null,
  val reply: MrReplies.Reply? = null,
  val discussion: GitLabApi.MrDiscussion? = null,
)

internal class MrReviewBrowser(
  private val render: (MrReviewEntry) -> JComponent,
  private val selected: (MrReviewEntry) -> Unit,
) : JPanel(BorderLayout()) {
  private val tabs = JTabbedPane()
  private val detail = ReviewDetailPanel()
  private val drafts = entryList()
  private val discussions = entryList()
  private val files = entryList()
  private var changing = false
  private var selectedKey: String? = null

  init {
    listOf(drafts, discussions, files).forEach { list ->
      tabs.addTab("", JBScrollPane(list))
      list.addListSelectionListener { if (!changing && !it.valueIsAdjusting) showSelected(list) }
    }
    tabs.addChangeListener { if (!changing) showSelected(activeList()) }
    add(JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, JBScrollPane(detail).apply {
      horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }).apply {
      resizeWeight = 0.4
      setDividerLocation(0.4)
      border = JBUI.Borders.empty()
    }, BorderLayout.CENTER)
  }

  fun update(replies: List<MrReplies.Reply>, threads: List<GitLabApi.MrDiscussion>) {
    val entries = replies.map { reply ->
      val discussion = threads.firstOrNull { it.id == reply.threadId && reply.kind == MrReplies.Kind.THREAD }
      MrReviewEntry(MrReplyPushService.marker(0, reply), reply.body,
        reply.file.takeIf { it.isNotBlank() } ?: discussion?.anchorFile,
        reply.line.takeIf { it > 0 } ?: discussion?.anchorLine, reply, discussion)
    }
    val existing = threads.filter { it.notes.any { note -> !note.system } }.map { thread ->
      MrReviewEntry("thread:${thread.id}", thread.notes.first { !it.system }.body,
        thread.anchorFile, thread.anchorLine, discussion = thread)
    }
    changing = true
    drafts.setListData(entries.toTypedArray())
    discussions.setListData(existing.toTypedArray())
    tabs.setTitleAt(0, message("mrworkspace.drafts", entries.size))
    tabs.setTitleAt(1, message("mrworkspace.discussions", existing.size))
    if (selectedKey == null && entries.isEmpty() && existing.isNotEmpty()) tabs.selectedIndex = 1
    val active = activeList()
    val index = (0 until active.model.size).firstOrNull { active.model.getElementAt(it).key == selectedKey }
    if (active.model.size > 0) active.selectedIndex = index ?: 0
    changing = false
    showSelected(active)
  }

  fun files(paths: List<String>) {
    changing = true
    files.setListData(paths.map { MrReviewEntry("file:$it", it, it) }.toTypedArray())
    tabs.setTitleAt(2, message("mrworkspace.files", paths.size))
    if (files.model.size > 0) files.selectedIndex = 0
    if (selectedKey == null && drafts.model.size == 0 && discussions.model.size == 0 && paths.isNotEmpty()) {
      tabs.selectedIndex = 2
    }
    changing = false
    if (tabs.selectedIndex == 2) showSelected(files)
  }

  private fun showSelected(list: JList<MrReviewEntry>) {
    if (list.selectedIndex < 0 && list.model.size > 0) {
      changing = true
      list.selectedIndex = 0
      changing = false
    }
    detail.removeAll()
    val entry = list.selectedValue
    if (entry == null) {
      detail.add(JLabel(message("mrworkspace.empty")).apply { border = JBUI.Borders.empty(12) }, BorderLayout.NORTH)
      selected(MrReviewEntry("empty", ""))
    } else {
      selectedKey = entry.key
      detail.add(render(entry), BorderLayout.NORTH)
      selected(entry)
    }
    detail.revalidate()
    detail.repaint()
  }

  private fun activeList(): JList<MrReviewEntry> = when (tabs.selectedIndex) {
    1 -> discussions
    2 -> files
    else -> drafts
  }

  private fun entryList(): JBList<MrReviewEntry> = object : JBList<MrReviewEntry>() {
    override fun getScrollableTracksViewportWidth(): Boolean = true
  }.apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    emptyText.text = message("mrworkspace.empty")
    cellRenderer = object : DefaultListCellRenderer() {
      override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component {
        val label = super.getListCellRendererComponent(list, value, index, selected, focus) as JLabel
        val entry = value as MrReviewEntry
        val location = entry.path?.let { it.substringAfterLast('/') + (entry.line?.let { n -> ":$n" } ?: "") }
        label.text = (location?.let { "$it  " }.orEmpty() + entry.text.lineSequence().firstOrNull().orEmpty()).take(180)
        label.toolTipText = entry.path ?: message("mrworkspace.general")
        label.border = JBUI.Borders.empty(8)
        return label
      }
    }
  }
}

private class ReviewDetailPanel : JPanel(BorderLayout()), Scrollable {
  override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
  override fun getScrollableTracksViewportWidth(): Boolean = true
  override fun getScrollableTracksViewportHeight(): Boolean = false
  override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
  override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
    (visibleRect.height - JBUI.scale(16)).coerceAtLeast(1)
}
