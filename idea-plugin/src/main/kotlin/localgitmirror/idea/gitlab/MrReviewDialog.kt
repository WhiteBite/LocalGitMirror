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
import localgitmirror.idea.settings.MirrorSettingsService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants

/**
 * One window for the whole review loop: reviewer threads with code context on
 * top, the agent's reply for each thread below it, a per-reply send button and
 * a single "send all approved" action in the footer. Home ships approved
 * replies to the work postbox, work publishes them to GitLab.
 */
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
  private val controls = mutableMapOf<String, Pair<JCheckBox, JButton>>()

  init {
    title = LocalGitMirrorBundle.message("mrview.title", row.iid)
    init()
  }

  override fun createActions(): Array<javax.swing.Action> = emptyArray()

  override fun createCenterPanel(): JComponent {
    val panel = JPanel(BorderLayout())
    contentHolder = JPanel(BorderLayout())
    contentHolder.add(buildContent(), BorderLayout.CENTER)
    panel.add(contentHolder, BorderLayout.CENTER)
    panel.add(buildFooter(), BorderLayout.SOUTH)
    panel.preferredSize = Dimension(JBUI.scale(860), JBUI.scale(620))
    return panel
  }

  private fun replies(): List<MrReplies.Reply> =
    (pending?.parsed?.replies ?: localReplies?.replies ?: emptyList())

  private fun buildContent(): JComponent {
    val matched = MrReplies.matchThreads(row.discussions, replies())
    val list = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      border = JBUI.Borders.empty(8)
    }
    for (d in row.discussions.filter { it.notes.any { n -> !n.system } }) {
      list.add(threadCard(d, matched.byThread[d.id]))
      list.add(Box.createRigidArea(Dimension(0, JBUI.scale(8))))
    }
    if (matched.newSections.isNotEmpty()) {
      list.add(JLabel(LocalGitMirrorBundle.message("mrview.new")).apply {
        font = JBUI.Fonts.smallFont().asBold()
        alignmentX = JLabel.LEFT_ALIGNMENT
      })
      list.add(Box.createRigidArea(Dimension(0, JBUI.scale(4))))
      for ((i, r) in matched.newSections.withIndex()) {
        list.add(replyCard(replyKey(r), r, headerFor(r)))
        list.add(Box.createRigidArea(Dimension(0, JBUI.scale(8))))
      }
    }
    if (row.discussions.isEmpty() && matched.newSections.isEmpty()) {
      list.add(JLabel(LocalGitMirrorBundle.message("mrview.none")).apply {
        foreground = UIUtil.getContextHelpForeground()
        alignmentX = JLabel.LEFT_ALIGNMENT
      })
    }
    return JBScrollPane(list).apply {
      border = javax.swing.BorderFactory.createEmptyBorder()
      viewportBorder = javax.swing.BorderFactory.createEmptyBorder()
    }
  }

  private fun headerFor(r: MrReplies.Reply): String = when (r.kind) {
    MrReplies.Kind.THREAD -> LocalGitMirrorBundle.message("mrreview.kind.thread", r.threadId)
    MrReplies.Kind.NEW_ANCHORED -> LocalGitMirrorBundle.message("mrreview.kind.anchored", r.file, r.line)
    MrReplies.Kind.NEW_GENERAL -> LocalGitMirrorBundle.message("mrreview.kind.general")
  }

  private fun threadCard(d: GitLabApi.MrDiscussion, reply: MrReplies.Reply?): JComponent {
    val card = JPanel(BorderLayout()).apply {
      isOpaque = true
      background = JBColor(0xF7F8FA, 0x2B2D30)
      border = JBUI.Borders.empty(8)
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    val head = JPanel(BorderLayout()).apply { isOpaque = false }
    val stateText = if (d.resolved) LocalGitMirrorBundle.message("mrview.res") else LocalGitMirrorBundle.message("mrview.unres")
    head.add(JLabel(stateText).apply {
      font = JBUI.Fonts.smallFont().asBold()
      foreground = if (d.resolved) JBColor(0x2E7D32, 0x66BB6A) else JBColor(0xB8860B, 0xE3AE4D)
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
      body.add(JLabel("${n.author} · ${n.createdAt}").apply {
        font = JBUI.Fonts.smallFont().asBold()
        foreground = JBColor(0x6F7277, 0x8C8F94)
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
      body.add(replyCard(replyKey(reply), reply, LocalGitMirrorBundle.message("mrview.agent")))
    }
    card.add(body, BorderLayout.CENTER)
    card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height + JBUI.scale(4))
    return card
  }

  private fun replyKey(r: MrReplies.Reply): String = MrReplyPushService.marker(row.iid, r)

  private fun replyCard(key: String, reply: MrReplies.Reply, caption: String): JComponent {
    val box = JPanel(BorderLayout()).apply {
      isOpaque = true
      background = JBColor(0xEAF3EA, 0x2E3A2E)
      border = JBUI.Borders.empty(6, 8)
      alignmentX = JPanel.LEFT_ALIGNMENT
    }
    val top = JPanel(BorderLayout()).apply { isOpaque = false }
    val check = JCheckBox(caption, !sentMarkers.contains(key))
    check.isOpaque = false
    check.font = JBUI.Fonts.smallFont().asBold()
    top.add(check, BorderLayout.WEST)
    val send = JButton(LocalGitMirrorBundle.message("mrview.send")).apply {
      isEnabled = !sentMarkers.contains(key)
      addActionListener { send(listOf(reply)) }
    }
    top.add(send, BorderLayout.EAST)
    box.add(top, BorderLayout.NORTH)
    box.add(JTextArea(reply.body).apply {
      isEditable = false
      lineWrap = true
      wrapStyleWord = true
      isOpaque = false
      font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))
      rows = reply.body.lines().size.coerceIn(1, 8)
      border = JBUI.Borders.emptyLeft(24)
    }, BorderLayout.CENTER)
    controls[key] = check to send
    box.maximumSize = Dimension(Int.MAX_VALUE, box.preferredSize.height + JBUI.scale(4))
    return box
  }

  private fun anchorLink(file: String, line: Int?): JComponent {
    val text = "$file:${line ?: "?"}"
    return JLabel("<html><a href=''>$text</a></html>").apply {
      foreground = JBColor(0x1565C0, 0x548AF7)
      cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
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

  private fun approvedReplies(): List<MrReplies.Reply> =
    replies().filter { r ->
      val key = replyKey(r)
      val c = controls[key]?.first
      c != null && c.isSelected && !sentMarkers.contains(key)
    }

  private fun buildFooter(): JComponent {
    val panel = JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.empty(8, 8, 4, 8)
    }
    val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false }
    val sendAll = JButton(
      LocalGitMirrorBundle.message(if (isWork) "mrview.sendAll.work" else "mrview.sendAll.home")
    ).apply {
      putClientProperty("JButton.buttonType", "default")
      addActionListener { send(approvedReplies()) }
    }
    left.add(sendAll)
    left.add(JButton(LocalGitMirrorBundle.message("mrview.refresh")).apply {
      isFocusPainted = false
      isBorderPainted = false
      isContentAreaFilled = false
      foreground = JBColor(0x6F7277, 0x8C8F94)
      addActionListener { reload() }
    })
    if (!isWork) {
      left.add(JButton(LocalGitMirrorBundle.message("mrview.save")).apply {
        isFocusPainted = false
        isBorderPainted = false
        isContentAreaFilled = false
        foreground = JBColor(0x6F7277, 0x8C8F94)
        addActionListener {
          MrNotesWriter.writeForAgent(project, row)
          MrNotesWriter.writeIndex(project, project.getService(MrReviewService::class.java).cachedRows())
        }
      })
    }
    panel.add(left, BorderLayout.WEST)
    panel.add(JLabel(LocalGitMirrorBundle.message("mrview.count", approvedReplies().size)).apply {
      foreground = JBColor(0x6F7277, 0x8C8F94)
      horizontalAlignment = SwingConstants.RIGHT
    }, BorderLayout.EAST)
    return panel
  }

  private fun send(selected: List<MrReplies.Reply>) {
    if (selected.isEmpty()) {
      notify(LocalGitMirrorBundle.message("mrreview.dialog.noneApproved"), NotificationType.WARNING)
      return
    }
    ApplicationManager.getApplication().executeOnPooledThread {
      if (isWork) {
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
      } else {
        val md = MrReplies.render(row.iid, row.sourceBranch, selected)
        val ok = MrRepliesTransport.uploadMarkdown(project, row.iid, md)
        ApplicationManager.getApplication().invokeLater {
          if (project.isDisposed) return@invokeLater
          notify(
            if (ok) LocalGitMirrorBundle.message("mrview.sent", selected.size)
            else LocalGitMirrorBundle.message("mrreview.sent.fail"),
            if (ok) NotificationType.INFORMATION else NotificationType.ERROR,
          )
          if (ok) markSent(selected, true)
        }
      }
    }
  }

  private fun markSent(replies: List<MrReplies.Reply>, ok: Boolean) {
    if (!ok) return
    for (r in replies) {
      val key = replyKey(r)
      sentMarkers.add(key)
      controls[key]?.let { (check, btn) ->
        check.isSelected = false
        check.isEnabled = false
        btn.isEnabled = false
      }
    }
    contentHolder.revalidate()
    contentHolder.repaint()
  }

  private fun reload() {
    val svc = project.getService(MrReviewService::class.java)
    svc.refreshInBackground(notify = false) { rows ->
      row = rows.firstOrNull { it.iid == row.iid } ?: row
      ApplicationManager.getApplication().executeOnPooledThread {
        if (isWork) {
          pending = MrReplyPushService(project).fetchPendingReplies().firstOrNull { it.parsed.iid == row.iid }
        }
        ApplicationManager.getApplication().invokeLater {
          if (project.isDisposed) return@invokeLater
          contentHolder.removeAll()
          contentHolder.add(buildContent(), BorderLayout.CENTER)
          contentHolder.revalidate()
          contentHolder.repaint()
        }
      }
    }
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

  companion object {
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
        val pending = MrReplyPushService(project).fetchPendingReplies().firstOrNull { it.parsed.iid == row.iid }
        ApplicationManager.getApplication().invokeLater {
          if (project.isDisposed) return@invokeLater
          MrReviewDialog(project, row, pending, null).show()
        }
      }
    }
  }
}
