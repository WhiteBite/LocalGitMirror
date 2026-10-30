package localgitmirror.idea.gitlab

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
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
import javax.swing.JSplitPane
import javax.swing.JToggleButton

internal class MrReviewDialog(
  private val project: Project,
  cachedRow: MrReviewService.MrRowItem,
) : JPanel(BorderLayout()), Disposable {

  private var pending: MrReplyPushService.PendingReplies? = null
  private var localReplies: MrReplies.RepliesFile? = readLocalRepliesFile(project, cachedRow.iid)
  private lateinit var browser: MrReviewBrowser
  private lateinit var codeDiff: MrReviewDiff
  private lateinit var workspace: JSplitPane
  private lateinit var composerContainer: JComponent
  private var disposed = false

  private val isWork: Boolean =
    RoleDetector.detect(service<MirrorSettingsService>().state) == MachineRole.WORK

  private var loadState: MrDialogLoadState = MrDialogLoadState.opened(cachedRow)
  private val row: MrReviewService.MrRowItem get() = loadState.row

  private lateinit var contentHolder: JPanel
  private val sentMarkers = mutableSetOf<String>()
  private val controls = mutableMapOf<String, JCheckBox>()
  private var countLabel: JLabel? = null
  private var publishButton: JButton? = null
  private var batchCountLabel: JLabel? = null
  private var pendingBatches: List<MrReplyPushService.PendingBatch> = emptyList()
  private var batchStates: List<MrBatchPreviewState> = emptyList()
  private val batchControls = mutableMapOf<String, JCheckBox>()

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
    if (!isWork) {
      sentMarkers.addAll(project.getService(MirrorProjectSettingsService::class.java).state.mrHomeSent)
    }
    add(createCenterPanel(), BorderLayout.CENTER)
    codeDiff.load()
    startBackgroundLoad()
  }

  private fun createCenterPanel(): JComponent {
    val panel = JPanel(BorderLayout())
    contentHolder = JPanel(BorderLayout())
    contentHolder.add(buildContent(), BorderLayout.CENTER)
    panel.add(contentHolder, BorderLayout.CENTER)
    val south = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
    }
    if (!isWork) {
      composerContainer = buildComposer().apply { isVisible = false }
      south.add(JToggleButton(LocalGitMirrorBundle.message("mrworkspace.compose")).apply {
        addActionListener { composerContainer.isVisible = isSelected }
      })
      south.add(composerContainer)
    }
    south.add(buildFooter())
    panel.add(south, BorderLayout.SOUTH)
    panel.preferredSize = Dimension(JBUI.scale(860), JBUI.scale(640))
    return panel
  }

  private fun replies(): List<MrReplies.Reply> =
    (pending?.parsed?.replies ?: localReplies?.replies ?: emptyList())

  private fun buildContent(): JComponent {
    if (!::browser.isInitialized) {
      codeDiff = MrReviewDiff(project, row) { paths -> if (::browser.isInitialized) browser.files(paths) }
      Disposer.register(this, codeDiff)
      browser = MrReviewBrowser(::entryDetail) { entry -> codeDiff.showPath(entry.path, entry.line) }
      workspace = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, browser, codeDiff).apply {
        resizeWeight = 0.32
        setDividerLocation(0.32)
        border = JBUI.Borders.empty()
        leftComponent.minimumSize = Dimension(0, 0)
        rightComponent.minimumSize = Dimension(0, 0)
      }
    }
    val keys = replies().mapTo(HashSet()) { replyKey(it) }
    controls.keys.retainAll(keys)
    for (reply in replies()) {
      val key = replyKey(reply)
      controls.getOrPut(key) {
        JCheckBox(LocalGitMirrorBundle.message(if (isWork) "mrview.incoming.send" else "mrworkspace.include"),
          key !in sentMarkers).apply {
          isOpaque = false
          isEnabled = key !in sentMarkers
          addItemListener { updateFooter() }
        }
      }
    }
    browser.update(replies(), row.discussions)
    browser.updateBatches(batchEntries())
    val outer = JPanel(BorderLayout()).apply { isOpaque = false }
    outer.add(headerPanel(), BorderLayout.NORTH)
    outer.add(workspace, BorderLayout.CENTER)
    return outer
  }

  private fun entryDetail(entry: MrReviewEntry): JComponent = when {
    entry.batchEntry != null -> batchEntryCard(entry.batchEntry)
    entry.discussion != null -> threadCard(entry.discussion, entry.reply)
    entry.reply != null -> newSectionCard(entry.reply)
    else -> bodyArea(entry.path.orEmpty())
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
    if (isWork) {
      batchStates.forEach { st -> panel.add(batchRow(st)) }
    } else {
      panel.add(JLabel(LocalGitMirrorBundle.message("mrview.home.legacy")).apply {
        foreground = MUTED
        font = JBUI.Fonts.smallFont()
        alignmentX = JLabel.LEFT_ALIGNMENT
      })
    }
    if (loadState.refreshing) {
      panel.add(JLabel(AnimatedIcon.Default.INSTANCE).apply { alignmentX = JLabel.LEFT_ALIGNMENT })
    }
    loadState.loadError?.let { err ->
      panel.add(JLabel(LocalGitMirrorBundle.message("review.fetch.fail", err)).apply {
        foreground = ERROR_FG
        font = JBUI.Fonts.smallFont()
        alignmentX = JLabel.LEFT_ALIGNMENT
      })
    }
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
    for (n in d.notes.filter { !it.system }) {
      body.add(JLabel("${n.author} \u00b7 ${n.createdAt}").apply {
        font = JBUI.Fonts.smallFont().asBold()
        foreground = MUTED
        alignmentX = JLabel.LEFT_ALIGNMENT
      })
      body.add(bodyArea(n.body))
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
    if (isWork && r.kind == MrReplies.Kind.NEW_ANCHORED) {
      card.add(JLabel(LocalGitMirrorBundle.message("mrview.incoming.fallbackWarn")).apply {
        font = JBUI.Fonts.smallFont()
        foreground = AMBER
        alignmentX = JLabel.LEFT_ALIGNMENT
      })
    }
    card.add(Box.createRigidArea(Dimension(0, JBUI.scale(4))))
    card.add(if (isWork) incomingBlock(r) else draftBlock(r))
    return card
  }

  private fun draftBlock(reply: MrReplies.Reply): JComponent {
    val sent = sentMarkers.contains(replyKey(reply))
    val box = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = true
      background = DRAFT_BG
      border = JBUI.Borders.empty(8)
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    box.add(JLabel(LocalGitMirrorBundle.message(if (sent) "mrview.draft.labelSent" else "mrview.draft.label")).apply {
      font = JBUI.Fonts.smallFont().asBold()
      foreground = if (sent) GREEN_ICON else ACCENT
      alignmentX = JLabel.LEFT_ALIGNMENT
    })
    box.add(bodyArea(reply.body))
    box.add(controls.getValue(replyKey(reply)))
    val acts = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
      isOpaque = false
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    acts.add(linkButton(LocalGitMirrorBundle.message("mrview.draft.edit"), MUTED) { editDraft(reply) })
    acts.add(linkButton(LocalGitMirrorBundle.message("mrview.draft.delete"), ERROR_FG) { deleteDraft(reply) })
    box.add(acts)
    return box
  }

  private fun incomingBlock(reply: MrReplies.Reply): JComponent {
    val key = replyKey(reply)
    val sent = sentMarkers.contains(key)
    val box = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = true
      background = INCOMING_BG
      border = JBUI.Borders.empty(8)
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
      val check = controls.getValue(key)
      acts.add(check)
      acts.add(linkButton(LocalGitMirrorBundle.message("mrview.incoming.skip"), MUTED) { check.isSelected = false })
      box.add(acts)
    }
    return box
  }

  private fun bodyArea(text: String): JComponent = MrReviewText(text).apply {
    font = JBUI.Fonts.label()
    foreground = NOTE_FG
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
    composerContainer.isVisible = true
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
    codeDiff.showPath(file, line)
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
      foreground = JBColor(0x1565C0, 0x548AF7)
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
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
    val north = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    north.add(countLabel.apply { alignmentX = JPanel.LEFT_ALIGNMENT })
    batchCountLabel = JLabel("").apply {
      foreground = MUTED
      font = JBUI.Fonts.smallFont()
      isVisible = false
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    north.add(batchCountLabel)
    panel.add(north, BorderLayout.NORTH)
    val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
    right.add(subtleButton(LocalGitMirrorBundle.message("mrview.refresh")) { reload() })
    if (isWork) {
      right.add(subtleButton(LocalGitMirrorBundle.message("mrview.prepare.snapshot")) { prepareSnapshot() })
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
    panel.add(right, BorderLayout.CENTER)
    updateFooter()
    return panel
  }

  private fun updateFooter() {
    if (isWork) {
      val total = replies().size
      val selected = approvedReplies().size
      countLabel?.text = LocalGitMirrorBundle.message("mrview.footer.publishCount", selected, total)
      val plan = batchPublishPlan(batchStates, selectedBatchIds())
      batchCountLabel?.isVisible = batchStates.isNotEmpty()
      batchCountLabel?.text = LocalGitMirrorBundle.message(
        "mrview.footer.batches", plan.publishIds.size, batchStates.size,
        plan.publishEntries, plan.totalEntries, plan.remainingEntries,
      )
      batchCountLabel?.toolTipText = LocalGitMirrorBundle.message("mrview.batch.whole")
      publishButton?.text = LocalGitMirrorBundle.message("mrview.publish.selected", selected + plan.publishIds.size)
      publishButton?.isEnabled = selected + plan.publishIds.size > 0
    } else {
      val selected = approvedReplies().size
      countLabel?.text = LocalGitMirrorBundle.message("mrworkspace.sendCount", selected, replies().size)
    }
  }

  private fun selectedBatchIds(): Set<String> =
    batchControls.filterValues { it.isSelected }.keys.toSet()

  private fun rejectSelected() {
    controls.values.forEach { if (it.isEnabled) it.isSelected = false }
    updateFooter()
  }

  private fun sendRemainder() {
    val remainder = approvedReplies()
    if (remainder.isEmpty()) {
      notify(LocalGitMirrorBundle.message("mrview.send.noDrafts"), NotificationType.WARNING)
      return
    }
    val decision = sendRemainderDecision(row.state, notesAvailable(), row.discussions, remainder, force = false)
    var toSend = remainder
    if (decision.blockReason != null) {
      val blocked = if (decision.blockReason == MrSendBlockReason.CLOSED_MR)
        LocalGitMirrorBundle.message("mrview.send.blocked.closed", row.iid)
      else
        LocalGitMirrorBundle.message("mrview.send.blocked.notes", row.iid)
      val confirmed = Messages.showYesNoDialog(
        project,
        blocked,
        LocalGitMirrorBundle.message("mrview.send.confirmTitle"),
        Messages.getWarningIcon(),
      )
      if (confirmed != Messages.YES) return
    } else if (decision.refusals.isNotEmpty()) {
      toSend = decision.allowed
      if (toSend.isEmpty()) {
        notify(partialSendText(0, remainder.size, decision), NotificationType.WARNING)
        return
      }
    }
    val md = MrReplies.render(row.iid, renderBranch(), toSend)
    ApplicationManager.getApplication().executeOnPooledThread {
      val outcome = MrRepliesTransport.uploadMarkdownResult(project, row.iid, md)
      ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        when (outcome) {
          is MrUploadResult.Ok -> {
            if (decision.refusals.isEmpty()) {
              notify(LocalGitMirrorBundle.message("mrview.sent", toSend.size), NotificationType.INFORMATION)
            } else {
              notify(partialSendText(toSend.size, remainder.size, decision), NotificationType.WARNING)
            }
            markSent(toSend, true)
          }
          is MrUploadResult.Failed -> notify(
            LocalGitMirrorBundle.message("mrreview.sent.fail", outcome.reason),
            NotificationType.ERROR,
          )
        }
      }
    }
  }

  private fun notesAvailable(): Boolean =
    row.source == MrReviewService.Source.GITLAB || row.receivedMarkdown != null

  private fun partialSendText(sent: Int, total: Int, decision: MrSendGateDecision): String =
    LocalGitMirrorBundle.message("mrview.send.partial", sent, total, decision.refusals.size) +
      decision.refusals.joinToString("") { "\n- " + refusalText(it) }

  private fun refusalText(refusal: MrSendRefusal): String =
    if (refusal.reason == MrSendRefusalReason.UNKNOWN_THREAD)
      LocalGitMirrorBundle.message("mrview.send.refused.unknownThread", refusal.threadId)
    else
      LocalGitMirrorBundle.message(
        "mrview.send.refused.duplicateAnchor",
        refusal.reply.file, refusal.reply.line, refusal.threadId, refusal.anchor,
      )

  private fun publishSelected() {
    val selected = approvedReplies()
    val plan = batchPublishPlan(batchStates, selectedBatchIds())
    if (selected.isEmpty() && plan.publishIds.isEmpty()) {
      notify(LocalGitMirrorBundle.message("mrreview.dialog.noneApproved"), NotificationType.WARNING)
      return
    }
    publishButton?.isEnabled = false
    ApplicationManager.getApplication().executeOnPooledThread {
      val service = MrReplyPushService(project)
      val reports = mutableListOf<MrReplyPushService.FileReport>()
      var legacyOk = false
      if (selected.isNotEmpty()) {
        val target = pending
        if (target == null) {
          notify(LocalGitMirrorBundle.message("mrreview.dialog.empty", row.iid), NotificationType.WARNING)
        } else {
          val report = service.pushApproved(target, selected)
          reports.add(report)
          legacyOk = report.failed == 0
        }
      }
      if (plan.publishIds.isNotEmpty()) {
        reports += service.pushSelectedBatches(pendingBatches.filter { it.batchId in plan.publishIds })
      }
      ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed || disposed) return@invokeLater
        if (reports.isNotEmpty()) service.notifySummary(reports)
        applyBatchResults(reports.mapNotNull { it.batch })
        if (selected.isNotEmpty() && legacyOk) markSent(selected, true) else rebuildContent()
        startBackgroundLoad()
      }
    }
  }

  private fun applyBatchResults(results: List<MrReviewBatchPublisher.BatchResult>) {
    if (results.isEmpty()) return
    val byId = results.associateBy { it.batchId }
    batchStates = batchStates.map { st ->
      val r = byId[st.batchId] ?: return@map st
      val gate = if (r.stale != null) MrBatchGate.Stale(r.stale.drift) else st.gate
      st.copy(result = r, gate = gate)
    }
  }

  private fun prepareSnapshot() {
    MrReviewSnapshotAction.prepareInBackground(project, listOf(row.iid)) { reload() }
  }

  internal fun applyBatchStates(states: List<MrBatchPreviewState>) {
    batchStates = states
    batchControls.keys.retainAll(states.mapTo(HashSet()) { it.batchId })
    rebuildContent()
  }

  private fun buildBatchStates(batches: List<MrReplyPushService.PendingBatch>): List<MrBatchPreviewState> {
    if (batches.isEmpty()) return emptyList()
    val conf = GitLabConfig.resolve(project)
    val hasApi = GitLabConfig.hasApi(conf)
    val repo = runCatching { MrReplyPushService(project).repoName() }.getOrNull()
    val base = project.basePath
    val priorStates = batchStates
    return batches.map { b ->
      val gate = when {
        !hasApi -> MrBatchGate.Unverified(LocalGitMirrorBundle.message("mrview.batch.noApi"))
        else -> runCatching {
          val pc = MrReviewBatchPublisher.precheck(conf, b.parsed)
          when {
            pc.error != null -> MrBatchGate.Unverified(pc.error)
            pc.stale != null -> MrBatchGate.Stale(pc.stale.drift)
            else -> MrBatchGate.Ready
          }
        }.getOrElse { MrBatchGate.Unverified(it.message ?: "error") }
      }
      val posted = if (repo != null && base != null) {
        runCatching {
          MrReviewJournal.load(File(MrReviewJournal.journalDir(base, repo), "mr-!${b.iid}-${b.batchId}.journal.json"))
            .takeIf { it.readable }?.postedEntries()
        }.getOrNull() ?: emptyMap()
      } else {
        emptyMap()
      }
      batchPreviewState(b, gate, posted, priorStates.firstOrNull { it.batchId == b.batchId }?.result)
    }
  }

  private fun batchEntries(): List<MrReviewEntry> = batchStates.flatMap { st ->
    st.entries.map { e ->
      MrReviewEntry(
        key = batchEntryKey(st.batchId, e.findingId, e.revision),
        text = e.body,
        path = e.anchorPath,
        line = e.anchorLine,
        badge = e.kind,
        batchEntry = e,
      )
    }
  }

  private fun batchRow(st: MrBatchPreviewState): JComponent {
    val rowPanel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
      isOpaque = false
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    val ready = st.gate == MrBatchGate.Ready
    val check = batchControls.getOrPut(st.batchId) {
      JCheckBox(LocalGitMirrorBundle.message("mrview.batch.row", st.batchId.take(8), st.entries.size), ready).apply {
        font = JBUI.Fonts.smallFont()
        isOpaque = false
        addItemListener { updateFooter() }
      }
    }
    check.isEnabled = ready
    if (!ready) check.isSelected = false
    rowPanel.add(check, BorderLayout.WEST)
    when (val g = st.gate) {
      MrBatchGate.Ready -> {
        val text = buildString {
          append(LocalGitMirrorBundle.message(
            "mrview.batch.ready",
            st.identity["snapshot_id"]?.take(8).orEmpty(),
            st.identity["version_fingerprint"]?.take(8).orEmpty(),
          ))
          st.result?.let { r ->
            append(" \u00b7 ").append(LocalGitMirrorBundle.message("mrview.batch.result", r.posted, r.duplicates, r.blocked))
          }
        }
        rowPanel.add(wrapLabel(text, when {
          st.result == null -> MUTED
          st.result.blocked > 0 -> AMBER
          else -> GREEN_ICON
        }), BorderLayout.CENTER)
      }
      is MrBatchGate.Stale -> {
        rowPanel.add(
          wrapLabel(LocalGitMirrorBundle.message("mrview.batch.stale", g.drift.joinToString(", ")), ERROR_FG),
          BorderLayout.CENTER,
        )
        rowPanel.add(subtleButton(LocalGitMirrorBundle.message("mrview.batch.prepare")) { prepareSnapshot() }, BorderLayout.EAST)
      }
      is MrBatchGate.Unverified -> {
        rowPanel.add(wrapLabel(LocalGitMirrorBundle.message("mrview.batch.unverified", g.reason), ERROR_FG), BorderLayout.CENTER)
      }
    }
    return rowPanel
  }

  private fun wrapLabel(text: String, color: Color): JComponent =
    JLabel("<html>${text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</html>").apply {
      font = JBUI.Fonts.smallFont()
      foreground = color
      verticalAlignment = JLabel.TOP
    }

  private fun batchEntryCard(e: MrBatchEntryPreview): JComponent {
    val card = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = true
      background = CARD_BG
      border = JBUI.Borders.empty(8)
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    val kindText = when (e.kind) {
      "anchored" -> LocalGitMirrorBundle.message(
        "mrreview.v2.kind.anchored",
        e.anchorPath ?: "?", e.anchorLine?.toString() ?: "?", e.anchorSide ?: "?",
      )
      "thread" -> LocalGitMirrorBundle.message("mrreview.v2.kind.thread", e.threadId.orEmpty()) +
        (if (e.resolve) " \u00b7 " + LocalGitMirrorBundle.message("mrreview.v2.resolves") else "")
      else -> LocalGitMirrorBundle.message("mrreview.v2.kind.general")
    }
    card.add(JLabel(kindText).apply {
      font = JBUI.Fonts.smallFont().asBold()
      foreground = ACCENT
      alignmentX = JLabel.LEFT_ALIGNMENT
    })
    card.add(wrapLabel(
      LocalGitMirrorBundle.message("mrreview.v2.finding", e.findingId, e.revision, e.batchId.take(8)),
      MUTED,
    ))
    card.add(bodyArea(e.body))
    if (e.kind == "anchored") {
      card.add(wrapLabel(LocalGitMirrorBundle.message("mrreview.v2.noFallback"), MUTED))
    }
    val st = batchStates.firstOrNull { it.batchId == e.batchId }
    if (st != null) {
      when (val s = entryStatus(st, e)) {
        is MrBatchEntryStatus.Posted -> {
          card.add(wrapLabel(LocalGitMirrorBundle.message("mrreview.v2.status.posted"), GREEN_ICON))
          s.url?.let { card.add(noteLink(it, s.noteId)) }
        }
        is MrBatchEntryStatus.Recovered -> {
          card.add(wrapLabel(LocalGitMirrorBundle.message("mrreview.v2.status.recovered"), GREEN_ICON))
          s.url?.let { card.add(noteLink(it, s.noteId)) }
        }
        is MrBatchEntryStatus.Blocked -> card.add(wrapLabel(
          if (s.code.isBlank()) LocalGitMirrorBundle.message("mrreview.v2.status.blocked", s.detail)
          else LocalGitMirrorBundle.message("mrreview.v2.status.blocked.code", s.code, s.detail),
          ERROR_FG,
        ))
        MrBatchEntryStatus.Pending -> card.add(wrapLabel(
          LocalGitMirrorBundle.message("mrreview.v2.status.pending"), MUTED,
        ))
      }
    }
    return card
  }

  private fun noteLink(url: String, noteId: Long?): JComponent =
    JLabel("<html><a href=''>${LocalGitMirrorBundle.message("mrreview.v2.noteLink", noteId ?: 0)}</a></html>").apply {
      foreground = JBColor(0x1565C0, 0x548AF7)
      font = JBUI.Fonts.smallFont()
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      alignmentX = JLabel.LEFT_ALIGNMENT
      addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mouseClicked(e: java.awt.event.MouseEvent) = BrowserUtil.browse(url)
      })
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
    rebuildContent()
  }

  private fun reload() {
    applyLoadState(loadState.refreshStarted())
    codeDiff.load()
    startBackgroundLoad()
  }

  private fun startBackgroundLoad() {
    val iid = row.iid
    val stamp = row.updatedAt
    ApplicationManager.getApplication().executeOnPooledThread {
      var pendingFailure: String? = null
      var freshPending: MrReplyPushService.PendingReplies? = null
      var freshLocal: MrReplies.RepliesFile? = null
      var freshBatches: List<MrReplyPushService.PendingBatch> = emptyList()
      if (isWork) {
        when (val fetched = MrReplyPushService(project).fetchPendingReplies()) {
          is MrReplyPushService.PendingRepliesResult.Available -> {
            freshPending = fetched.items.firstOrNull { it.parsed.iid == iid }
            freshBatches = fetched.batches.filter { it.iid == iid }
          }
          is MrReplyPushService.PendingRepliesResult.Unavailable -> pendingFailure = fetched.reason
        }
      } else {
        freshLocal = readLocalRepliesFile(project, iid)
      }
      val freshBatchStates = if (isWork) buildBatchStates(freshBatches) else emptyList()
      val discussions = project.getService(MrReviewService::class.java).discussionsFor(iid, stamp)
      ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed || disposed) return@invokeLater
        pendingFailure?.let { notify(LocalGitMirrorBundle.message("mrreplies.list.fail", it), NotificationType.WARNING) }
        if (isWork) {
          pending = freshPending
          pendingBatches = freshBatches
          batchStates = freshBatchStates
          batchControls.keys.retainAll(freshBatchStates.mapTo(HashSet()) { it.batchId })
        } else {
          localReplies = freshLocal
        }
        val next = discussions.error?.let { loadState.refreshFailed(it) }
          ?: loadState.refreshed(rowWithDiscussions(discussions.discussions))
        applyLoadState(next)
      }
    }
  }

  private fun rowWithDiscussions(discussions: List<GitLabApi.MrDiscussion>): MrReviewService.MrRowItem =
    row.copy(
      discussions = discussions,
      unresolved = discussions.count { d -> !d.resolved && d.notes.any { !it.system } },
      totalThreads = discussions.size,
    )

  private fun applyLoadState(next: MrDialogLoadState) {
    loadState = next
    rebuildContent()
  }

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

  override fun dispose() { disposed = true }

  companion object {
    private const val LEDGER_CAP = 500

    private val AMBER = JBColor(Color(0x8A, 0x57, 0x00), Color(0xE3, 0xAE, 0x4D))
    private val GREEN_ICON = JBColor(Color(0x2E, 0x7D, 0x32), Color(0x5F, 0xAD, 0x65))
    private val MUTED = UIUtil.getContextHelpForeground()
    private val NOTE_FG = UIUtil.getLabelForeground()
    private val ERROR_FG = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x53, 0x50))
    private val ACCENT = JBColor(Color(0x35, 0x74, 0xF0), Color(0x35, 0x74, 0xF0))
    private val CARD_BG = UIUtil.getPanelBackground()
    private val DRAFT_BG = UIUtil.getPanelBackground()
    private val INCOMING_BG = UIUtil.getPanelBackground()
    private val BORDER = JBColor(Color(0xC9, 0xCC, 0xD1), Color(0x39, 0x3B, 0x40))

    fun openFor(project: Project, row: MrReviewService.MrRowItem) {
      openMrReviewEditor(project, row)
    }
  }
}

private fun readLocalRepliesFile(project: Project, iid: Int): MrReplies.RepliesFile? =
  project.basePath
    ?.let { File(it, ".mr-notes/replies-!$iid.md") }
    ?.takeIf { it.isFile }
    ?.let { MrReplies.parse(it.readText(Charsets.UTF_8)) }

internal enum class MrSendBlockReason { CLOSED_MR, NOTES_UNAVAILABLE }

/** Dialog content decision: cached row at open, fresh row when the background fetch lands, cache + reason on failure. */
internal data class MrDialogLoadState(
  val row: MrReviewService.MrRowItem,
  val refreshing: Boolean,
  val loadError: String? = null,
) {
  fun refreshStarted(): MrDialogLoadState = copy(refreshing = true, loadError = null)

  fun refreshed(row: MrReviewService.MrRowItem): MrDialogLoadState = MrDialogLoadState(row, refreshing = false)

  fun refreshFailed(reason: String): MrDialogLoadState = copy(refreshing = false, loadError = reason)

  companion object {
    fun opened(cachedRow: MrReviewService.MrRowItem): MrDialogLoadState = MrDialogLoadState(cachedRow, refreshing = true)
  }
}

internal enum class MrSendRefusalReason { UNKNOWN_THREAD, DUPLICATE_ANCHOR }

internal data class MrSendRefusal(
  val reply: MrReplies.Reply,
  val reason: MrSendRefusalReason,
  val threadId: String,
  val anchor: String,
)

internal data class MrSendGateDecision(
  val blockReason: MrSendBlockReason? = null,
  val allowed: List<MrReplies.Reply> = emptyList(),
  val refusals: List<MrSendRefusal> = emptyList(),
)

/** Refuses closed MRs, unknown thread ids and duplicate anchors; fail-closed without a thread inventory. */
internal fun sendRemainderDecision(
  mrState: String,
  notesAvailable: Boolean,
  discussions: List<GitLabApi.MrDiscussion>,
  replies: List<MrReplies.Reply>,
  force: Boolean,
): MrSendGateDecision {
  if (mrState == "closed" && !force) return MrSendGateDecision(blockReason = MrSendBlockReason.CLOSED_MR)
  if (replies.none { it.kind == MrReplies.Kind.THREAD || it.kind == MrReplies.Kind.NEW_ANCHORED }) {
    return MrSendGateDecision(allowed = replies)
  }
  if (!notesAvailable && !force) return MrSendGateDecision(blockReason = MrSendBlockReason.NOTES_UNAVAILABLE)
  if (force) return MrSendGateDecision(allowed = replies)
  val knownIds = discussions.mapTo(HashSet()) { it.id }
  val allowed = mutableListOf<MrReplies.Reply>()
  val refusals = mutableListOf<MrSendRefusal>()
  for (r in replies) {
    val refusal = when {
      r.kind == MrReplies.Kind.THREAD && r.threadId !in knownIds ->
        MrSendRefusal(r, MrSendRefusalReason.UNKNOWN_THREAD, r.threadId, "")
      r.kind == MrReplies.Kind.NEW_ANCHORED -> MrReplyPushService.anchorClash(r, discussions)?.let { d ->
        MrSendRefusal(r, MrSendRefusalReason.DUPLICATE_ANCHOR, d.id, "${d.anchorFile}:${d.anchorLine}")
      }
      else -> null
    }
    if (refusal == null) allowed.add(r) else refusals.add(refusal)
  }
  return MrSendGateDecision(allowed = allowed, refusals = refusals)
}
