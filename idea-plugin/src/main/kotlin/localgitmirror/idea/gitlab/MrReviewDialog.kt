package localgitmirror.idea.gitlab

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.deps.MachineRole
import localgitmirror.idea.deps.RoleDetector
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorProjectSettingsService
import localgitmirror.idea.settings.MirrorSettingsService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextArea
import javax.swing.JTextField

/** One discussion window for the two-sided review loop: home edits draft replies and ships the remainder to the work postbox; work checks the arrived replies and publishes only that subset to GitLab. */
class MrReviewDialog private constructor(
  private val project: Project,
  private var row: MrReviewService.MrRowItem,
  private var pending: MrReplyPushService.PendingReplies?,
  private var localReplies: MrReplies.RepliesFile?,
) : DialogWrapper(project, false) {

  private val isWork: Boolean =
    RoleDetector.detect(service<MirrorSettingsService>().state) == MachineRole.WORK

  private lateinit var contentHolder: JPanel
  private val sentMarkers = mutableSetOf<String>()
  private val controls = mutableMapOf<String, JCheckBox>()
  private var countLabel: JLabel? = null
  private var publishButton: JButton? = null

  private var composerThread: GitLabApi.MrDiscussion? = null
  private lateinit var targetThread: JRadioButton
  private lateinit var targetAnchored: JRadioButton
  private lateinit var targetGeneral: JRadioButton
  private lateinit var threadHintLabel: JLabel
  private lateinit var anchorRow: JPanel
  private lateinit var anchorFileField: JTextField
  private lateinit var anchorLineField: JTextField
  private lateinit var resolveCheck: JCheckBox
  private lateinit var composerArea: JTextArea
  private lateinit var composerBox: JPanel

  init {
    title = LocalGitMirrorBundle.message("mrview.title", row.iid)
    if (!isWork) {
      sentMarkers.addAll(project.getService(MirrorProjectSettingsService::class.java).state.mrHomeSent)
    }
    init()
  }

  override fun createActions(): Array<javax.swing.Action> = emptyArray()

  override fun createCenterPanel(): JComponent {
    val panel = JPanel(BorderLayout())
    contentHolder = JPanel(BorderLayout())
    contentHolder.add(buildContent(), BorderLayout.CENTER)
    panel.add(contentHolder, BorderLayout.CENTER)
    val south = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
    }
    if (!isWork) south.add(buildComposer())
    south.add(buildFooter())
    panel.add(south, BorderLayout.SOUTH)
    panel.preferredSize = Dimension(JBUI.scale(860), JBUI.scale(640))
    return panel
  }

  private fun replies(): List<MrReplies.Reply> =
    (pending?.parsed?.replies ?: localReplies?.replies ?: emptyList())

  private fun buildContent(): JComponent {
    val outer = JPanel(BorderLayout()).apply { isOpaque = false }
    outer.add(headerPanel(), BorderLayout.NORTH)
    val matched = MrReplies.matchThreads(row.discussions, replies())
    val list = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      border = JBUI.Borders.empty(0, 8, 8, 8)
    }
    if (replies().isEmpty() && row.discussions.isNotEmpty()) {
      list.add(JLabel(LocalGitMirrorBundle.message(if (isWork) "mrview.waiting.work" else "mrview.waiting.home", row.iid)).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        alignmentX = JLabel.LEFT_ALIGNMENT
      })
      list.add(Box.createRigidArea(Dimension(0, JBUI.scale(8))))
    }
    for (d in row.discussions.filter { it.notes.any { n -> !n.system } }) {
      list.add(threadCard(d, matched.byThread[d.id]))
      list.add(Box.createRigidArea(Dimension(0, JBUI.scale(8))))
    }
    if (matched.newSections.isNotEmpty()) {
      if (isWork) {
        list.add(JLabel(LocalGitMirrorBundle.message("mrview.new")).apply {
          font = JBUI.Fonts.smallFont().asBold()
          alignmentX = JLabel.LEFT_ALIGNMENT
        })
        list.add(Box.createRigidArea(Dimension(0, JBUI.scale(4))))
      }
      for (r in matched.newSections) {
        list.add(newSectionCard(r))
        list.add(Box.createRigidArea(Dimension(0, JBUI.scale(8))))
      }
    }
    if (row.discussions.isEmpty() && matched.newSections.isEmpty()) {
      list.add(JLabel(LocalGitMirrorBundle.message("mrview.none")).apply {
        foreground = UIUtil.getContextHelpForeground()
        alignmentX = JLabel.LEFT_ALIGNMENT
      })
    }
    list.add(Box.createVerticalGlue())
    outer.add(JBScrollPane(list).apply {
      border = BorderFactory.createEmptyBorder()
      viewportBorder = BorderFactory.createEmptyBorder()
    }, BorderLayout.CENTER)
    return outer
  }

  private fun headerPanel(): JComponent {
    val panel = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      border = JBUI.Borders.empty(4, 10, 8, 10)
    }
    val title = if (row.title.isBlank()) "MR !${row.iid}" else "MR !${row.iid} \u2014 ${row.title}"
    panel.add(JLabel(title).apply {
      font = font.deriveFont(Font.BOLD, JBUI.scale(14).toFloat())
      alignmentX = JLabel.LEFT_ALIGNMENT
    })
    val parts = mutableListOf<Pair<String, Color>>()
    if (isWork) {
      parts.add(LocalGitMirrorBundle.message("mrview.head.fromHome", replies().size) to AMBER)
    } else {
      if (row.unresolved > 0) {
        parts.add(LocalGitMirrorBundle.message("mrview.head.unresolved", row.unresolved) to AMBER)
      }
      parts.add(LocalGitMirrorBundle.message("mrview.head.drafts", replies().size) to NOTE_FG)
    }
    if (row.sourceBranch.isNotBlank()) {
      parts.add(LocalGitMirrorBundle.message("mrview.head.branch", row.sourceBranch) to MUTED)
    }
    val meta = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
      isOpaque = false
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    parts.forEachIndexed { i, (text, color) ->
      if (i > 0) meta.add(JLabel(" \u00b7 ").apply { foreground = MUTED; font = JBUI.Fonts.smallFont() })
      meta.add(JLabel(text).apply { foreground = color; font = JBUI.Fonts.smallFont() })
    }
    panel.add(meta)
    return panel
  }

  private fun threadCard(d: GitLabApi.MrDiscussion, reply: MrReplies.Reply?): JComponent {
    val card = JPanel(BorderLayout()).apply {
      isOpaque = true
      background = CARD_BG
      border = JBUI.Borders.empty(8)
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    val head = JPanel(BorderLayout()).apply { isOpaque = false }
    val stateText = if (d.resolved) LocalGitMirrorBundle.message("mrview.res") else LocalGitMirrorBundle.message("mrview.unres")
    head.add(JLabel(stateText).apply {
      font = JBUI.Fonts.smallFont().asBold()
      foreground = if (d.resolved) GREEN_ICON else AMBER
    }, BorderLayout.WEST)
    d.anchorFile?.let { f ->
      head.add(anchorLink(f, d.anchorLine), BorderLayout.EAST)
    }
    card.add(head, BorderLayout.NORTH)

    val body = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      border = JBUI.Borders.empty(4, 0, 0, 0)
    }
    codeSnippet(d)?.let { body.add(it) }
    for (n in d.notes.filter { !it.system }) {
      body.add(JLabel("${n.author} \u00b7 ${n.createdAt}").apply {
        font = JBUI.Fonts.smallFont().asBold()
        foreground = MUTED
        alignmentX = JLabel.LEFT_ALIGNMENT
      })
      body.add(JTextArea(n.body).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        font = JBUI.Fonts.smallFont()
        rows = n.body.lines().size.coerceIn(1, 8)
        alignmentX = JTextArea.LEFT_ALIGNMENT
      })
      body.add(Box.createRigidArea(Dimension(0, JBUI.scale(4))))
    }
    if (reply != null) {
      body.add(if (isWork) incomingBlock(reply) else draftBlock(reply))
      body.add(Box.createRigidArea(Dimension(0, JBUI.scale(4))))
    }
    if (!isWork) {
      val acts = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        alignmentX = JPanel.LEFT_ALIGNMENT
      }
      acts.add(smallButton(LocalGitMirrorBundle.message("mrview.reply"), d.id.isNotBlank()) { startReply(d, false) })
      acts.add(smallButton(LocalGitMirrorBundle.message("mrview.replyResolve"), d.id.isNotBlank()) { startReply(d, true) })
      body.add(acts)
    }
    card.add(body, BorderLayout.CENTER)
    card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height + JBUI.scale(4))
    return card
  }

  private fun newSectionCard(r: MrReplies.Reply): JComponent {
    val card = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    val label = when (r.kind) {
      MrReplies.Kind.NEW_ANCHORED -> LocalGitMirrorBundle.message("mrview.draft.newAnchored", r.file, r.line)
      MrReplies.Kind.NEW_GENERAL -> LocalGitMirrorBundle.message("mrview.draft.newGeneral")
      MrReplies.Kind.THREAD -> LocalGitMirrorBundle.message("mrreview.kind.thread", r.threadId)
    }
    card.add(JLabel(label).apply {
      font = JBUI.Fonts.smallFont()
      foreground = MUTED
      alignmentX = JLabel.LEFT_ALIGNMENT
    })
    card.add(Box.createRigidArea(Dimension(0, JBUI.scale(4))))
    card.add(if (isWork) incomingBlock(r) else draftBlock(r))
    card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height + JBUI.scale(4))
    return card
  }

  private fun draftBlock(reply: MrReplies.Reply): JComponent {
    val sent = sentMarkers.contains(replyKey(reply))
    val box = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = true
      background = DRAFT_BG
      border = BorderFactory.createCompoundBorder(
        JBUI.Borders.customLine(ACCENT, 0, 3, 0, 0),
        JBUI.Borders.empty(6, 8),
      )
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    box.add(JLabel(LocalGitMirrorBundle.message(if (sent) "mrview.draft.labelSent" else "mrview.draft.label")).apply {
      font = JBUI.Fonts.smallFont().asBold()
      foreground = if (sent) GREEN_ICON else ACCENT
      alignmentX = JLabel.LEFT_ALIGNMENT
    })
    box.add(bodyArea(reply.body))
    val acts = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
      isOpaque = false
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    acts.add(linkButton(LocalGitMirrorBundle.message("mrview.draft.edit"), MUTED) { editDraft(reply) })
    acts.add(linkButton(LocalGitMirrorBundle.message("mrview.draft.delete"), ERROR_FG) { deleteDraft(reply) })
    box.add(acts)
    box.maximumSize = Dimension(Int.MAX_VALUE, box.preferredSize.height)
    return box
  }

  private fun incomingBlock(reply: MrReplies.Reply): JComponent {
    val key = replyKey(reply)
    val sent = sentMarkers.contains(key)
    val box = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = true
      background = INCOMING_BG
      border = BorderFactory.createCompoundBorder(
        JBUI.Borders.customLine(AMBER, 0, 3, 0, 0),
        JBUI.Borders.empty(6, 8),
      )
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    box.add(JLabel(LocalGitMirrorBundle.message("mrview.incoming.label")).apply {
      font = JBUI.Fonts.smallFont().asBold()
      foreground = AMBER
      alignmentX = JLabel.LEFT_ALIGNMENT
    })
    box.add(bodyArea(reply.body))
    if (sent) {
      box.add(JLabel(LocalGitMirrorBundle.message("mrview.incoming.posted")).apply {
        font = JBUI.Fonts.smallFont()
        foreground = GREEN_ICON
        alignmentX = JLabel.LEFT_ALIGNMENT
      })
    } else {
      val acts = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
        isOpaque = false
        alignmentX = JPanel.LEFT_ALIGNMENT
      }
      val check = JCheckBox(LocalGitMirrorBundle.message("mrview.incoming.send"), true).apply {
        isOpaque = false
        font = JBUI.Fonts.smallFont().asBold()
      }
      check.addItemListener { updateFooter() }
      acts.add(check)
      acts.add(linkButton(LocalGitMirrorBundle.message("mrview.incoming.skip"), MUTED) { check.isSelected = false })
      controls[key] = check
      box.add(acts)
    }
    box.maximumSize = Dimension(Int.MAX_VALUE, box.preferredSize.height)
    return box
  }

  private fun bodyArea(text: String): JComponent = JTextArea(text).apply {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    isOpaque = false
    font = JBUI.Fonts.smallFont()
    foreground = NOTE_FG
    rows = text.lines().size.coerceIn(1, 8)
    alignmentX = JTextArea.LEFT_ALIGNMENT
  }

  private fun buildComposer(): JComponent {
    val outer = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      border = JBUI.Borders.empty(0, 8, 0, 8)
    }
    composerBox = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = true
      background = CARD_BG
      border = BorderFactory.createCompoundBorder(
        JBUI.Borders.customLine(BORDER, 1),
        JBUI.Borders.empty(8),
      )
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    val radioRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
      isOpaque = false
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    targetThread = JRadioButton(LocalGitMirrorBundle.message("mrview.composer.thread"))
    targetAnchored = JRadioButton(LocalGitMirrorBundle.message("mrview.composer.anchored"))
    targetGeneral = JRadioButton(LocalGitMirrorBundle.message("mrview.composer.general"))
    listOf(targetThread, targetAnchored, targetGeneral).forEach {
      it.font = JBUI.Fonts.smallFont()
      it.isOpaque = false
      it.addActionListener { updateComposerState() }
    }
    ButtonGroup().apply { add(targetThread); add(targetAnchored); add(targetGeneral) }
    targetGeneral.isSelected = true
    threadHintLabel = JLabel("").apply { font = JBUI.Fonts.smallFont(); foreground = MUTED; isVisible = false }
    radioRow.add(targetThread)
    radioRow.add(threadHintLabel)
    radioRow.add(targetAnchored)
    radioRow.add(targetGeneral)
    composerBox.add(radioRow)

    anchorRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
      isOpaque = false
      alignmentX = JPanel.LEFT_ALIGNMENT
      isVisible = false
    }
    anchorFileField = JTextField().apply {
      preferredSize = Dimension(JBUI.scale(360), JBUI.scale(24))
      toolTipText = LocalGitMirrorBundle.message("mrview.composer.file")
    }
    anchorLineField = JTextField().apply {
      preferredSize = Dimension(JBUI.scale(60), JBUI.scale(24))
      toolTipText = LocalGitMirrorBundle.message("mrview.composer.line")
    }
    anchorRow.add(anchorFileField)
    anchorRow.add(JLabel(":").apply { foreground = MUTED })
    anchorRow.add(anchorLineField)
    composerBox.add(anchorRow)

    composerArea = JTextArea(3, 40).apply {
      lineWrap = true
      wrapStyleWord = true
      font = JBUI.Fonts.smallFont()
      toolTipText = LocalGitMirrorBundle.message("mrview.composer.placeholder")
    }
    composerBox.add(JBScrollPane(composerArea).apply {
      alignmentX = JPanel.LEFT_ALIGNMENT
      border = JBUI.Borders.customLine(BORDER, 1)
    })
    composerBox.add(Box.createRigidArea(Dimension(0, JBUI.scale(6))))

    val sendRow = JPanel(BorderLayout()).apply {
      isOpaque = false
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    resolveCheck = JCheckBox(LocalGitMirrorBundle.message("mrview.composer.resolve")).apply {
      font = JBUI.Fonts.smallFont()
      isOpaque = false
      isEnabled = false
    }
    sendRow.add(resolveCheck, BorderLayout.WEST)
    sendRow.add(JButton(LocalGitMirrorBundle.message("mrview.composer.send")).apply {
      putClientProperty("JButton.buttonType", "default")
      addActionListener { addDraftFromComposer() }
    }, BorderLayout.EAST)
    composerBox.add(sendRow)
    composerBox.maximumSize = Dimension(Int.MAX_VALUE, composerBox.preferredSize.height)
    outer.add(composerBox)
    outer.add(Box.createRigidArea(Dimension(0, JBUI.scale(6))))
    return outer
  }

  private fun updateComposerState() {
    if (!::composerBox.isInitialized) return
    anchorRow.isVisible = targetAnchored.isSelected
    resolveCheck.isEnabled = targetThread.isSelected
    val thread = composerThread
    threadHintLabel.isVisible = targetThread.isSelected && thread != null
    threadHintLabel.text = thread?.let { d ->
      val anchor = d.anchorFile?.let { f -> "$f:${d.anchorLine ?: "?"}" }
      LocalGitMirrorBundle.message("mrview.composer.threadHint", anchor ?: d.id.take(8))
    }.orEmpty()
    composerBox.revalidate()
    composerBox.repaint()
  }

  private fun startReply(d: GitLabApi.MrDiscussion, resolve: Boolean) {
    composerThread = d
    targetThread.isSelected = true
    resolveCheck.isSelected = resolve
    updateComposerState()
    composerArea.requestFocusInWindow()
  }

  private fun addDraftFromComposer() {
    val text = composerArea.text.trim()
    if (text.isEmpty()) {
      notify(LocalGitMirrorBundle.message("mrview.composer.empty"), NotificationType.WARNING)
      return
    }
    val reply = when {
      targetThread.isSelected -> {
        val d = composerThread
        if (d == null || d.id.isBlank()) {
          notify(LocalGitMirrorBundle.message("mrview.composer.noThread"), NotificationType.WARNING)
          return
        }
        MrReplies.Reply(MrReplies.Kind.THREAD, d.id, "", 0, resolveCheck.isSelected, text)
      }
      targetAnchored.isSelected -> {
        val file = anchorFileField.text.trim()
        val line = anchorLineField.text.trim().toIntOrNull()
        if (file.isEmpty() || line == null || line <= 0) {
          notify(LocalGitMirrorBundle.message("mrview.composer.badAnchor"), NotificationType.WARNING)
          return
        }
        MrReplies.Reply(MrReplies.Kind.NEW_ANCHORED, "", file, line, false, text)
      }
      else -> MrReplies.Reply(MrReplies.Kind.NEW_GENERAL, "", "", 0, false, text)
    }
    if (!persistDrafts(replies() + reply)) return
    composerArea.text = ""
    rebuildContent()
  }

  private fun editDraft(reply: MrReplies.Reply) {
    val editor = DraftEditor(reply.body)
    if (!editor.showAndGet()) return
    val newText = editor.draftText()
    if (newText.isEmpty() || newText == reply.body) return
    val updated = replies().toMutableList()
    val idx = updated.indexOfFirst { it == reply }
    if (idx < 0) return
    updated[idx] = reply.copy(body = newText)
    if (!persistDrafts(updated)) return
    rebuildContent()
  }

  private fun deleteDraft(reply: MrReplies.Reply) {
    if (!persistDrafts(replies().filterNot { it == reply })) return
    rebuildContent()
  }

  /** Rewrites `.mr-notes/replies-!N.md` with exactly [updated]; false when the file could not be written. */
  private fun persistDrafts(updated: List<MrReplies.Reply>): Boolean {
    val base = project.basePath
    if (base == null) {
      notify(LocalGitMirrorBundle.message("mrview.draft.writeFail", "no project base path"), NotificationType.ERROR)
      return false
    }
    val md = MrReplies.render(row.iid, renderBranch(), updated)
    return runCatching {
      val dir = File(base, ".mr-notes")
      dir.mkdirs()
      File(dir, "replies-!${row.iid}.md").writeText(md, Charsets.UTF_8)
      localReplies = MrReplies.parse(md)
      true
    }.getOrElse {
      notify(LocalGitMirrorBundle.message("mrview.draft.writeFail", it.message ?: "error"), NotificationType.ERROR)
      false
    }
  }

  private fun renderBranch(): String = row.sourceBranch.ifBlank { localReplies?.branch.orEmpty() }

  private fun rebuildContent() {
    contentHolder.removeAll()
    contentHolder.add(buildContent(), BorderLayout.CENTER)
    contentHolder.revalidate()
    contentHolder.repaint()
    updateFooter()
  }

  private fun anchorLink(file: String, line: Int?): JComponent {
    val text = "$file:${line ?: "?"}"
    return JLabel("<html><a href=''>$text</a></html>").apply {
      foreground = JBColor(0x1565C0, 0x548AF7)
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mouseClicked(e: java.awt.event.MouseEvent) = openAnchor(file, line)
      })
    }
  }

  private fun openAnchor(file: String, line: Int?) {
    val base = project.basePath ?: return
    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(base, file)) ?: return
    FileEditorManager.getInstance(project).openFile(vf, true)
    if (line != null) OpenFileDescriptor(project, vf, line - 1, 0).navigate(true)
  }

  private fun codeSnippet(d: GitLabApi.MrDiscussion): JComponent? {
    val file = d.anchorFile ?: return null
    val line = d.anchorLine ?: return null
    val base = project.basePath ?: return null
    val f = File(base, file)
    if (!f.isFile) {
      return JLabel(LocalGitMirrorBundle.message("mrview.codeMissing")).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        alignmentX = JLabel.LEFT_ALIGNMENT
      }
    }
    val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrNull() ?: return null
    val start = (line - 2).coerceAtLeast(1)
    val end = (line + 2).coerceAtMost(lines.size)
    val sb = StringBuilder()
    for (i in start..end) {
      sb.appendLine((if (i == line) ">" else " ") + "$i: " + lines.getOrNull(i - 1).orEmpty())
    }
    return JTextArea(sb.toString().trimEnd()).apply {
      isEditable = false
      isOpaque = false
      font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(11))
      rows = (end - start + 1).coerceAtLeast(1)
      alignmentX = JTextArea.LEFT_ALIGNMENT
    }
  }

  private fun linkButton(text: String, color: Color, onClick: () -> Unit): JButton =
    JButton(text).apply {
      isFocusPainted = false
      isBorderPainted = false
      isContentAreaFilled = false
      foreground = color
      font = JBUI.Fonts.smallFont()
      margin = JBUI.insets(0, 2)
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      addActionListener { onClick() }
    }

  private fun smallButton(text: String, enabled: Boolean, onClick: () -> Unit): JButton =
    JButton(text).apply {
      font = JBUI.Fonts.smallFont()
      margin = JBUI.insets(2, 8)
      isEnabled = enabled
      addActionListener { onClick() }
    }

  private fun subtleButton(text: String, onClick: () -> Unit): JButton =
    JButton(text).apply {
      isFocusPainted = false
      isBorderPainted = false
      isContentAreaFilled = false
      foreground = MUTED
      addActionListener { onClick() }
    }

  private fun replyKey(r: MrReplies.Reply): String = MrReplyPushService.marker(row.iid, r)

  private fun approvedReplies(): List<MrReplies.Reply> =
    replies().filter { r ->
      val key = replyKey(r)
      val c = controls[key]
      c != null && c.isSelected && !sentMarkers.contains(key)
    }

  private fun buildFooter(): JComponent {
    val panel = JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.empty(8, 8, 4, 8)
    }
    countLabel = JLabel("").apply { foreground = if (isWork) MUTED else AMBER }
    panel.add(countLabel, BorderLayout.WEST)
    val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
    right.add(subtleButton(LocalGitMirrorBundle.message("mrview.refresh")) { reload() })
    if (isWork) {
      right.add(JButton(LocalGitMirrorBundle.message("mrview.reject.selected")).apply {
        addActionListener { rejectSelected() }
      })
      publishButton = JButton(LocalGitMirrorBundle.message("mrview.publish.selected", 0)).apply {
        putClientProperty("JButton.buttonType", "default")
        addActionListener { publishSelected() }
      }
      right.add(publishButton)
    } else {
      right.add(subtleButton(LocalGitMirrorBundle.message("mrview.save")) {
        MrNotesWriter.writeForAgent(project, row)
        MrNotesWriter.writeIndex(project, project.getService(MrReviewService::class.java).cachedRows())
      })
      right.add(JButton(LocalGitMirrorBundle.message("mrview.send.remainder")).apply {
        putClientProperty("JButton.buttonType", "default")
        addActionListener { sendRemainder() }
      })
    }
    panel.add(right, BorderLayout.EAST)
    updateFooter()
    return panel
  }

  private fun updateFooter() {
    if (isWork) {
      val total = replies().size
      val selected = approvedReplies().size
      countLabel?.text = LocalGitMirrorBundle.message("mrview.footer.publishCount", selected, total)
      publishButton?.text = LocalGitMirrorBundle.message("mrview.publish.selected", selected)
    } else {
      countLabel?.text = LocalGitMirrorBundle.message("mrview.footer.drafts", replies().size)
    }
  }

  private fun rejectSelected() {
    controls.values.forEach { if (it.isEnabled) it.isSelected = false }
    updateFooter()
  }

  private fun sendRemainder() {
    val remainder = replies()
    if (remainder.isEmpty()) {
      notify(LocalGitMirrorBundle.message("mrview.send.noDrafts"), NotificationType.WARNING)
      return
    }
    val md = MrReplies.render(row.iid, renderBranch(), remainder)
    ApplicationManager.getApplication().executeOnPooledThread {
      val outcome = MrRepliesTransport.uploadMarkdownResult(project, row.iid, md)
      ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        when (outcome) {
          is MrUploadResult.Ok -> {
            notify(LocalGitMirrorBundle.message("mrview.sent", remainder.size), NotificationType.INFORMATION)
            markSent(remainder, true)
          }
          is MrUploadResult.Failed -> notify(
            LocalGitMirrorBundle.message("mrreview.sent.fail", outcome.reason),
            NotificationType.ERROR,
          )
        }
      }
    }
  }

  private fun publishSelected() {
    val selected = approvedReplies()
    if (selected.isEmpty()) {
      notify(LocalGitMirrorBundle.message("mrreview.dialog.noneApproved"), NotificationType.WARNING)
      return
    }
    ApplicationManager.getApplication().executeOnPooledThread {
      val target = pending
      if (target == null) {
        notify(LocalGitMirrorBundle.message("mrreview.dialog.empty", row.iid), NotificationType.WARNING)
        return@executeOnPooledThread
      }
      val report = MrReplyPushService(project).pushApproved(target, selected)
      ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        MrReplyPushService(project).notifySummary(listOf(report))
        markSent(selected, report.failed == 0)
      }
    }
  }

  private fun markSent(sent: List<MrReplies.Reply>, ok: Boolean) {
    if (!ok) return
    val homeSent = if (isWork) null else project.getService(MirrorProjectSettingsService::class.java).state.mrHomeSent
    for (r in sent) {
      val key = replyKey(r)
      sentMarkers.add(key)
      homeSent?.let { ledger ->
        if (key !in ledger) {
          ledger.add(key)
          while (ledger.size > LEDGER_CAP) ledger.removeAt(0)
        }
      }
      controls[key]?.let { check ->
        check.isSelected = false
        check.isEnabled = false
      }
    }
    if (isWork) {
      updateFooter()
      contentHolder.revalidate()
      contentHolder.repaint()
    } else {
      rebuildContent()
    }
  }

  private fun reload() {
    val svc = project.getService(MrReviewService::class.java)
    svc.refreshInBackground(notify = false) { rows ->
      row = rows.firstOrNull { it.iid == row.iid } ?: row
      ApplicationManager.getApplication().executeOnPooledThread {
        if (isWork) {
          when (val fetched = MrReplyPushService(project).fetchPendingReplies()) {
            is MrReplyPushService.PendingRepliesResult.Available ->
              pending = fetched.items.firstOrNull { it.parsed.iid == row.iid }
            is MrReplyPushService.PendingRepliesResult.Unavailable -> {
              pending = null
              notify(LocalGitMirrorBundle.message("mrreplies.list.fail", fetched.reason), NotificationType.WARNING)
            }
          }
        } else {
          localReplies = readLocalReplies(row.iid)
        }
        ApplicationManager.getApplication().invokeLater {
          if (project.isDisposed) return@invokeLater
          rebuildContent()
        }
      }
    }
  }

  private fun readLocalReplies(iid: Int): MrReplies.RepliesFile? =
    project.basePath
      ?.let { File(it, ".mr-notes/replies-!$iid.md") }
      ?.takeIf { it.isFile }
      ?.let { MrReplies.parse(it.readText(Charsets.UTF_8)) }

  private fun notify(content: String, type: NotificationType) {
    ApplicationManager.getApplication().invokeLater {
      if (project.isDisposed) return@invokeLater
      NotificationGroupManager.getInstance()
        .getNotificationGroup("DocCache")
        .createNotification(content, type)
        .notify(project)
    }
  }

  private inner class DraftEditor(initial: String) : DialogWrapper(project, false) {
    private val area = JTextArea(initial).apply {
      lineWrap = true
      wrapStyleWord = true
      rows = 10
      font = JBUI.Fonts.smallFont()
    }

    init {
      title = LocalGitMirrorBundle.message("mrview.draft.editTitle")
      init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(area).apply {
      preferredSize = Dimension(JBUI.scale(560), JBUI.scale(260))
      border = BorderFactory.createEmptyBorder()
    }

    fun draftText(): String = area.text.trim()
  }

  companion object {
    private const val LEDGER_CAP = 500

    private val AMBER = JBColor(Color(0xE3, 0xAE, 0x4D), Color(0xE3, 0xAE, 0x4D))
    private val GREEN_ICON = JBColor(Color(0x5F, 0xAD, 0x65), Color(0x5F, 0xAD, 0x65))
    private val MUTED = JBColor(Color(0x6F, 0x72, 0x77), Color(0x6F, 0x72, 0x77))
    private val NOTE_FG = JBColor(Color(0xCE, 0xD0, 0xD6), Color(0xCE, 0xD0, 0xD6))
    private val ERROR_FG = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
    private val ACCENT = JBColor(Color(0x35, 0x74, 0xF0), Color(0x35, 0x74, 0xF0))
    private val CARD_BG = JBColor(Color(0xF7, 0xF8, 0xFA), Color(0x2B, 0x2D, 0x30))
    private val DRAFT_BG = JBColor(Color(0xEC, 0xF2, 0xFD), Color(0x23, 0x26, 0x2B))
    private val INCOMING_BG = JBColor(Color(0xFA, 0xF3, 0xE3), Color(0x23, 0x26, 0x2B))
    private val BORDER = JBColor(Color(0xC9, 0xCC, 0xD1), Color(0x39, 0x3B, 0x40))

    fun openFor(project: Project, row: MrReviewService.MrRowItem) {
      val isWork = RoleDetector.detect(service<MirrorSettingsService>().state) == MachineRole.WORK
      if (!isWork) {
        val base = project.basePath
        val file = base?.let { File(it, ".mr-notes/replies-!${row.iid}.md") }
        val local = file?.takeIf { it.isFile }?.let { MrReplies.parse(it.readText(Charsets.UTF_8)) }
        MrReviewDialog(project, row, null, local).show()
        return
      }
      ApplicationManager.getApplication().executeOnPooledThread {
        val fetched = MrReplyPushService(project).fetchPendingReplies()
        val pending = (fetched as? MrReplyPushService.PendingRepliesResult.Available)
          ?.items?.firstOrNull { it.parsed.iid == row.iid }
        ApplicationManager.getApplication().invokeLater {
          if (project.isDisposed) return@invokeLater
          if (fetched is MrReplyPushService.PendingRepliesResult.Unavailable) {
            NotificationGroupManager.getInstance()
              .getNotificationGroup("DocCache")
              .createNotification(
                LocalGitMirrorBundle.message("mrreplies.list.fail", fetched.reason),
                NotificationType.WARNING,
              )
              .notify(project)
          }
          MrReviewDialog(project, row, pending, null).show()
        }
      }
    }
  }
}
