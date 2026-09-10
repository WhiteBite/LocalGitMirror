package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import localgitmirror.idea.gitlab.GitLabApi
import localgitmirror.idea.gitlab.MrNotesWriter
import localgitmirror.idea.gitlab.MrReviewService
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.Box
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JToggleButton
import javax.swing.border.LineBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.SwingUtilities

class MrNotesDialog(
  private val project: Project,
  private var row: MrReviewService.MrRowItem,
) : DialogWrapper(project, false) {

  private enum class FilterMode { ALL, UNRESOLVED, RESOLVED }

  private lateinit var contentHolder: JPanel
  private lateinit var threadListContainer: JPanel
  private lateinit var savedBanner: JLabel
  private var filterMode: FilterMode = FilterMode.ALL
  private var searchQuery: String = ""

  init {
    title = LocalGitMirrorBundle.message("mrdialog.title")
    init()
  }

  override fun createActions(): Array<javax.swing.Action> = emptyArray()

  override fun createCenterPanel(): JComponent {
    val panel = JPanel(BorderLayout())
    contentHolder = JPanel(BorderLayout())
    contentHolder.add(buildContent(row), BorderLayout.CENTER)
    panel.add(contentHolder, BorderLayout.CENTER)
    panel.add(buildFooter(), BorderLayout.SOUTH)
    panel.preferredSize = Dimension(JBUI.scale(820), JBUI.scale(580))
    return panel
  }

  private fun buildContent(row: MrReviewService.MrRowItem): JComponent =
    if (row.discussions.isNotEmpty()) buildStructuredView(row) else buildMarkdownView(row)

  private fun buildStructuredView(row: MrReviewService.MrRowItem): JComponent {
    val container = JPanel(BorderLayout())
    val top = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      add(buildHeader(row))
      add(Box.createRigidArea(Dimension(0, JBUI.scale(4))))
      add(buildFilterRow(row))
    }
    container.add(top, BorderLayout.NORTH)
    container.add(buildThreadList(), BorderLayout.CENTER)
    return container
  }

  private fun buildMarkdownView(row: MrReviewService.MrRowItem): JComponent {
    val area = JTextArea(row.receivedMarkdown ?: "").apply {
      isEditable = false
      lineWrap = true
      wrapStyleWord = true
      font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))
      caretPosition = 0
    }
    return JBScrollPane(area).apply {
      border = BorderFactory.createEmptyBorder()
      viewportBorder = BorderFactory.createEmptyBorder()
      preferredSize = Dimension(JBUI.scale(760), JBUI.scale(440))
    }
  }

  private fun buildHeader(row: MrReviewService.MrRowItem): JComponent {
    val panel = JPanel(BorderLayout())
    panel.isOpaque = false
    panel.border = JBUI.Borders.empty(8, 8, 2, 8)

    val badge = JLabel("!${row.iid}").apply {
      font = Font("JetBrains Mono", Font.BOLD, JBUI.scale(13))
      foreground = AMBER
      background = AMBER_BG
      isOpaque = true
      border = BorderFactory.createEmptyBorder(JBUI.scale(3), JBUI.scale(7), JBUI.scale(3), JBUI.scale(7))
    }
    panel.add(badge, BorderLayout.WEST)

    val titleAndMeta = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      border = JBUI.Borders.emptyLeft(8)
      add(JLabel(row.title).apply { font = font.deriveFont(Font.BOLD) })
      add(Box.createRigidArea(Dimension(0, JBUI.scale(2))))
      add(buildMetaLine(row))
    }
    panel.add(titleAndMeta, BorderLayout.CENTER)
    return panel
  }

  private fun buildMetaLine(row: MrReviewService.MrRowItem): JComponent {
    val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
    panel.add(chipLabel(row.sourceBranch))
    panel.add(dotSep())
    panel.add(coloredLabel(LocalGitMirrorBundle.message("mrdialog.open"), GREEN))
    panel.add(dotSep())
    panel.add(plainLabel(LocalGitMirrorBundle.message("mrdialog.threads", row.totalThreads, row.unresolved)))
    if (row.updatedAt.isNotBlank()) {
      panel.add(dotSep())
      panel.add(plainLabel(LocalGitMirrorBundle.message("mrdialog.updated", row.updatedAt)))
    }
    return panel
  }

  private fun buildFilterRow(row: MrReviewService.MrRowItem): JComponent {
    val panel = JPanel(BorderLayout())
    panel.isOpaque = false
    panel.border = JBUI.Borders.empty(0, 8, 4, 8)

    val real = row.discussions.filter { it.notes.any { n -> !n.system } }
    val unresCount = real.count { !it.resolved }
    val resCount = real.count { it.resolved }

    val seg = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
    val group = ButtonGroup()
    val allBtn = segToggle(LocalGitMirrorBundle.message("mrdialog.filter.all") + " (${row.discussions.size})")
    val unresBtn = segToggle(LocalGitMirrorBundle.message("mrdialog.filter.unres") + " ($unresCount)").apply {
      foreground = AMBER
    }
    val resBtn = segToggle(LocalGitMirrorBundle.message("mrdialog.filter.res") + " ($resCount)")
    group.add(allBtn); group.add(unresBtn); group.add(resBtn)
    seg.add(allBtn); seg.add(unresBtn); seg.add(resBtn)
    allBtn.isSelected = true
    allBtn.addActionListener { filterMode = FilterMode.ALL; rebuildThreads() }
    unresBtn.addActionListener { filterMode = FilterMode.UNRESOLVED; rebuildThreads() }
    resBtn.addActionListener { filterMode = FilterMode.RESOLVED; rebuildThreads() }
    panel.add(seg, BorderLayout.WEST)

    val search = SearchTextField(false).apply {
      textEditor.emptyText.text = LocalGitMirrorBundle.message("mrdialog.search")
      preferredSize = Dimension(JBUI.scale(200), JBUI.scale(28))
    }
    search.addDocumentListener(object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent?) { searchQuery = search.text; rebuildThreads() }
      override fun removeUpdate(e: DocumentEvent?) { searchQuery = search.text; rebuildThreads() }
      override fun changedUpdate(e: DocumentEvent?) { searchQuery = search.text; rebuildThreads() }
    })
    panel.add(search, BorderLayout.EAST)
    return panel
  }

  private fun buildThreadList(): JComponent {
    threadListContainer = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      border = JBUI.Borders.empty(4, 8)
    }
    rebuildThreads()
    return JBScrollPane(threadListContainer).apply {
      border = BorderFactory.createEmptyBorder()
      viewportBorder = BorderFactory.createEmptyBorder()
      preferredSize = Dimension(JBUI.scale(760), JBUI.scale(360))
      horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
  }

  private fun rebuildThreads() {
    val row = this.row
    val container = threadListContainer
    container.removeAll()
    val discussions = row.discussions
    val systemOnly = discussions.filter { it.notes.all { n -> n.system } }
    val real = discussions.filter { it.notes.any { n -> !n.system } }
    val unresolved = real.filter { !it.resolved }
    val resolved = real.filter { it.resolved }

    val q = searchQuery.trim()
    fun matches(d: GitLabApi.MrDiscussion): Boolean =
      q.isBlank() || d.notes.any { it.body.contains(q, true) || it.author.contains(q, true) }

    when (filterMode) {
      FilterMode.ALL -> {
        unresolved.filter(::matches).forEach { addCard(buildThreadCard(it)) }
        resolved.filter(::matches).forEach { addCard(buildThreadCard(it)) }
        if (systemOnly.any(::matches)) addCard(buildSystemCard(systemOnly))
      }
      FilterMode.UNRESOLVED -> unresolved.filter(::matches).forEach { addCard(buildThreadCard(it)) }
      FilterMode.RESOLVED -> resolved.filter(::matches).forEach { addCard(buildThreadCard(it)) }
    }
    container.add(Box.createVerticalGlue())
    container.revalidate()
    container.repaint()
  }

  private fun addCard(card: JComponent) {
    threadListContainer.add(card)
    threadListContainer.add(Box.createRigidArea(Dimension(0, JBUI.scale(4))))
  }

  private fun buildThreadCard(discussion: GitLabApi.MrDiscussion): JComponent {
    val unresolved = !discussion.resolved
    val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }
    left.add(buildGlyph(unresolved, false))
    left.add(buildLocation(discussion))
    val body = buildCardBody(discussion)
    val border = if (unresolved) AMBER_BORDER else GRAY_BORDER
    return CollapsibleCard(left, buildTag(unresolved, false), body, unresolved, border).build()
  }

  private fun buildSystemCard(systemDiscussions: List<GitLabApi.MrDiscussion>): JComponent {
    val count = systemDiscussions.sumOf { it.notes.size }
    val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }
    left.add(buildGlyph(false, true))
    left.add(plainLabel(LocalGitMirrorBundle.message("mrdialog.loc.system", count)).apply { foreground = MUTED })
    val body = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
    }
    systemDiscussions.flatMap { it.notes }.forEach { body.add(buildNote(it, 0)) }
    return CollapsibleCard(left, buildTag(false, true), body, false, GRAY_BORDER).build()
  }

  private fun buildCardBody(discussion: GitLabApi.MrDiscussion): JComponent {
    val panel = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
    }
    buildCodeBlock(discussion.anchorFile, discussion.anchorLine)?.let { panel.add(it) }
    discussion.notes.forEachIndexed { idx, note ->
      panel.add(buildNote(note, if (idx == 0) 0 else JBUI.scale(22)))
    }
    return panel
  }

  private fun buildCodeBlock(anchorFile: String?, anchorLine: Int?): JComponent? {
    if (anchorFile == null || anchorLine == null) return null
    val base = project.basePath ?: return null
    val file = File(base, anchorFile)
    if (!file.isFile) return null
    val lines = runCatching { file.readLines() }.getOrNull() ?: return null
    if (anchorLine !in 1..lines.size) return null
    val from = (anchorLine - 2).coerceAtLeast(1)
    val to = (anchorLine + 2).coerceAtMost(lines.size)
    val panel = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      border = JBUI.Borders.empty(4, 4, 4, 8)
    }
    val mono = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(11))
    for (i in from..to) {
      val isTarget = i == anchorLine
      val row = JPanel(BorderLayout()).apply {
        isOpaque = isTarget
        if (isTarget) background = CODE_HL
      }
      val prefix = if (isTarget) ">" else " "
      val text = lines[i - 1].replace("\t", "    ")
      row.add(JLabel("$prefix ${"%4d".format(i)}: $text").apply {
        font = mono
        foreground = if (isTarget) AMBER else MUTED
      }, BorderLayout.WEST)
      panel.add(row)
    }
    return panel
  }

  private fun buildNote(note: GitLabApi.MrNote, indent: Int): JComponent {
    val panel = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      border = BorderFactory.createEmptyBorder(2, indent, 2, 0)
    }
    panel.add(JLabel("<html><b>${esc(note.author)}</b> <font color='#6F7277'>(${esc(note.createdAt)})</font></html>"))
    panel.add(JTextArea(note.body).apply {
      isEditable = false
      isOpaque = false
      lineWrap = true
      wrapStyleWord = true
      font = font.deriveFont(if (note.system) Font.ITALIC else Font.PLAIN)
      foreground = if (note.system) SYSTEM_FG else NOTE_FG
    })
    return panel
  }

  private fun buildGlyph(unresolved: Boolean, isSystem: Boolean): JLabel {
    val (ch, color) = when {
      isSystem -> "↻" to MUTED
      unresolved -> "⚠" to AMBER
      else -> "✓" to GREEN_ICON
    }
    return JLabel(ch).apply { foreground = color }
  }

  private fun buildLocation(discussion: GitLabApi.MrDiscussion): JComponent {
    val file = discussion.anchorFile
    val line = discussion.anchorLine
    return if (file != null) {
      val name = File(file).name
      HyperlinkLabel("$name:${line ?: 1}").apply {
        foreground = LINK
        addHyperlinkListener {
          val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(project.basePath ?: ".", file))
          vFile?.let { OpenFileDescriptor(project, it, (line ?: 1) - 1, 0).navigate(true) }
        }
      }
    } else {
      JLabel(LocalGitMirrorBundle.message("mrdialog.loc.general")).apply { foreground = MUTED }
    }
  }

  private fun buildTag(unresolved: Boolean, isSystem: Boolean): JLabel {
    val (text, bg, fg) = when {
      isSystem -> Triple(LocalGitMirrorBundle.message("mrdialog.tag.sys"), GRAY_BORDER, MUTED)
      unresolved -> Triple(LocalGitMirrorBundle.message("mrdialog.tag.unres"), AMBER_BG, AMBER)
      else -> Triple(LocalGitMirrorBundle.message("mrdialog.tag.res"), GRAY_BORDER, MUTED)
    }
    return JLabel(text).apply {
      isOpaque = true
      background = bg
      foreground = fg
      border = BorderFactory.createEmptyBorder(1, JBUI.scale(6), 1, JBUI.scale(6))
      font = font.deriveFont(JBUI.scale(10f).toFloat())
    }
  }

  private fun buildFooter(): JComponent {
    val panel = JPanel(BorderLayout())
    panel.isOpaque = false
    panel.border = JBUI.Borders.empty(8, 8, 4, 8)

    savedBanner = JLabel(LocalGitMirrorBundle.message("mrdialog.saved", row.iid)).apply {
      foreground = GREEN
      background = BANNER_BG
      isOpaque = false
      border = BorderFactory.createCompoundBorder(
        LineBorder(BANNER_BORDER, 1),
        JBUI.Borders.empty(4, 8),
      )
      isVisible = false
    }
    panel.add(savedBanner, BorderLayout.NORTH)

    val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
    val saveBtn = JButton(LocalGitMirrorBundle.message("mrdialog.save")).apply {
      putClientProperty("JButton.buttonType", "default")
      background = SAVE_BG
      foreground = Color.WHITE
      font = font.deriveFont(Font.BOLD)
      addActionListener { doSave() }
    }
    val fetchBtn = JButton(LocalGitMirrorBundle.message("mrdialog.fetch")).apply {
      isFocusPainted = false
      isBorderPainted = false
      isContentAreaFilled = false
      foreground = MUTED
      addActionListener { doFetch() }
    }
    left.add(saveBtn)
    left.add(fetchBtn)
    panel.add(left, BorderLayout.WEST)

    panel.add(JLabel(LocalGitMirrorBundle.message("mrdialog.readonly")).apply { foreground = MUTED }, BorderLayout.EAST)
    return panel
  }

  private fun doSave() {
    MrNotesWriter.writeForAgent(project, row)
    MrNotesWriter.writeIndex(project, project.getService(MrReviewService::class.java).cachedRows())
    if (row.discussions.isNotEmpty()) {
      savedBanner.text = LocalGitMirrorBundle.message("mrdialog.saved", row.iid)
      savedBanner.isOpaque = true
      savedBanner.isVisible = true
      savedBanner.parent?.revalidate()
      savedBanner.parent?.repaint()
    } else {
      NotificationGroupManager.getInstance()
        .getNotificationGroup("DocCache")
        .createNotification(LocalGitMirrorBundle.message("mrdialog.saved", row.iid), NotificationType.INFORMATION)
        .notify(project)
    }
  }

  private fun doFetch() {
    project.getService(MrReviewService::class.java).refreshInBackground(notify = true) { rows ->
      SwingUtilities.invokeLater {
        if (project.isDisposed) return@invokeLater
        rows.firstOrNull { it.iid == row.iid }?.let { fresh ->
          row = fresh
          contentHolder.removeAll()
          contentHolder.add(buildContent(fresh), BorderLayout.CENTER)
          contentHolder.revalidate()
          contentHolder.repaint()
        }
      }
    }
  }

  private inner class CollapsibleCard(
    private val leftContent: JComponent,
    private val tag: JComponent,
    private val body: JComponent,
    startExpanded: Boolean,
    private val borderColor: Color,
  ) {
    private var expanded = startExpanded
    private val bodyPanel = JPanel(BorderLayout())
    private val chevron = JButton(if (expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight)

    fun build(): JComponent {
      val card = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(LineBorder(borderColor, 1), JBUI.Borders.empty(5, 8))
      }
      chevron.apply {
        isFocusPainted = false
        isBorderPainted = false
        isContentAreaFilled = false
        margin = JBUI.insets(1, 2)
        addActionListener { toggle() }
      }
      val header = JPanel(BorderLayout()).apply { isOpaque = false }
      val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }
      left.add(chevron)
      left.add(leftContent)
      header.add(left, BorderLayout.WEST)
      header.add(tag, BorderLayout.EAST)
      card.add(header, BorderLayout.NORTH)
      bodyPanel.isOpaque = false
      bodyPanel.add(body, BorderLayout.CENTER)
      bodyPanel.isVisible = expanded
      card.add(bodyPanel, BorderLayout.CENTER)
      return card
    }

    private fun toggle() {
      expanded = !expanded
      bodyPanel.isVisible = expanded
      chevron.icon = if (expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
      bodyPanel.revalidate()
      bodyPanel.repaint()
    }
  }

  private fun segToggle(text: String): JToggleButton = JToggleButton(text).apply {
    isFocusPainted = false
    font = font.deriveFont(JBUI.scale(11f).toFloat())
    margin = JBUI.insets(2, 8)
    isOpaque = false
  }

  private fun chipLabel(text: String): JLabel = JLabel(text).apply {
    font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(11))
    foreground = NOTE_FG
    background = CHIP_BG
    isOpaque = true
    border = BorderFactory.createEmptyBorder(1, JBUI.scale(6), 1, JBUI.scale(6))
  }

  private fun dotSep(): JLabel = JLabel(" · ").apply { foreground = MUTED }

  private fun plainLabel(text: String): JLabel = JLabel(text).apply { foreground = NOTE_FG }

  private fun coloredLabel(text: String, color: Color): JLabel = JLabel(text).apply { foreground = color }

  private fun esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private companion object {
    val AMBER = JBColor(Color(0xE3, 0xAE, 0x4D), Color(0xE3, 0xAE, 0x4D))
    val AMBER_BG = JBColor(Color(0x3A, 0x33, 0x20), Color(0x3A, 0x33, 0x20))
    val AMBER_BORDER = JBColor(Color(0x52, 0x48, 0x1F), Color(0x52, 0x48, 0x1F))
    val GREEN = JBColor(Color(0x8F, 0xD8, 0x94), Color(0x8F, 0xD8, 0x94))
    val GREEN_ICON = JBColor(Color(0x5F, 0xAD, 0x65), Color(0x5F, 0xAD, 0x65))
    val MUTED = JBColor(Color(0x6F, 0x72, 0x77), Color(0x6F, 0x72, 0x77))
    val NOTE_FG = JBColor(Color(0xCE, 0xD0, 0xD6), Color(0xCE, 0xD0, 0xD6))
    val SYSTEM_FG = JBColor(Color(0x6F, 0x72, 0x77), Color(0x6F, 0x72, 0x77))
    val LINK = JBColor(Color(0x54, 0x8A, 0xF7), Color(0x54, 0x8A, 0xF7))
    val CHIP_BG = JBColor(Color(0x33, 0x36, 0x40), Color(0x33, 0x36, 0x40))
    val GRAY_BORDER = JBColor(Color(0x39, 0x3B, 0x40), Color(0x39, 0x3B, 0x40))
    val CODE_HL = JBColor(Color(0x33, 0x38, 0x3F), Color(0x33, 0x38, 0x3F))
    val SAVE_BG = JBColor(Color(0x4A, 0x9D, 0x54), Color(0x4A, 0x9D, 0x54))
    val BANNER_BG = JBColor(Color(0x20, 0x21, 0x1A), Color(0x20, 0x21, 0x1A))
    val BANNER_BORDER = JBColor(Color(0x2F, 0x4A, 0x32), Color(0x2F, 0x4A, 0x32))
  }
}
