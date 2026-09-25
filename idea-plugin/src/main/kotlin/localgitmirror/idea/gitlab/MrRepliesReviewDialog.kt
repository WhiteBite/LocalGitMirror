package localgitmirror.idea.gitlab

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import localgitmirror.idea.deps.MachineRole
import localgitmirror.idea.deps.RoleDetector
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Human gate for the agent's MR replies: shows every section of the replies
 * file next to the thread it answers, lets the user approve or reject each,
 * then ships only the approved ones — to the work postbox (home) or straight
 * to GitLab (work).
 */
class MrRepliesReviewDialog private constructor(
  private val project: Project,
  private val iid: Int,
  private val parsed: MrReplies.RepliesFile,
  private val pending: MrReplyPushService.PendingReplies?,
) : DialogWrapper(project, true) {

  private data class Row(val reply: MrReplies.Reply, val context: String, val approved: Boolean = true)

  private val isWork: Boolean =
    RoleDetector.detect(service<MirrorSettingsService>().state) == MachineRole.WORK
  private val rows = parsed.replies.map { Row(it, contextFor(it)) }
  private val boxes = mutableListOf<JCheckBox>()

  init {
    title = LocalGitMirrorBundle.message("mrreview.dialog.title", iid)
    setOKButtonText(
      LocalGitMirrorBundle.message(if (isWork) "mrreview.dialog.ok.work" else "mrreview.dialog.ok.home")
    )
    init()
  }

  override fun createCenterPanel(): JComponent {
    val list = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      border = JBUI.Borders.empty(8)
    }
    for ((i, row) in rows.withIndex()) {
      list.add(sectionCard(i, row))
      list.add(Box.createRigidArea(Dimension(0, JBUI.scale(8))))
    }
    return JBScrollPane(list).apply {
      preferredSize = Dimension(JBUI.scale(760), JBUI.scale(520))
      border = javax.swing.BorderFactory.createEmptyBorder()
      viewportBorder = javax.swing.BorderFactory.createEmptyBorder()
    }
  }

  private fun sectionCard(index: Int, row: Row): JComponent {
    val card = JPanel(BorderLayout()).apply {
      isOpaque = true
      background = JBColor(0xF2F3F5, 0x2E3033)
      border = JBUI.Borders.empty(8)
    }
    val header = when (row.reply.kind) {
      MrReplies.Kind.THREAD -> LocalGitMirrorBundle.message("mrreview.kind.thread", row.reply.threadId)
      MrReplies.Kind.NEW_ANCHORED -> LocalGitMirrorBundle.message("mrreview.kind.anchored", row.reply.file, row.reply.line)
      MrReplies.Kind.NEW_GENERAL -> LocalGitMirrorBundle.message("mrreview.kind.general")
    } + if (row.reply.resolve) " · resolve" else ""
    val box = JCheckBox(header, row.approved).apply {
      isOpaque = false
      font = JBUI.Fonts.smallFont().asBold()
    }
    boxes.add(box)
    card.add(box, BorderLayout.NORTH)

    val body = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      border = JBUI.Borders.emptyLeft(24)
    }
    if (row.context.isNotBlank()) {
      body.add(JLabel(row.context).apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor(0x6F7277, 0x8C8F94)
        alignmentX = JLabel.LEFT_ALIGNMENT
      })
      body.add(Box.createRigidArea(Dimension(0, JBUI.scale(4))))
    }
    body.add(JTextArea(row.reply.body).apply {
      isEditable = false
      lineWrap = true
      wrapStyleWord = true
      isOpaque = false
      font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))
      rows = row.reply.body.lines().size.coerceIn(2, 10)
      alignmentX = JTextArea.LEFT_ALIGNMENT
    })
    card.add(body, BorderLayout.CENTER)
    card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height + JBUI.scale(4))
    return card
  }

  private fun contextFor(reply: MrReplies.Reply): String {
    val row = project.getService(MrReviewService::class.java).cachedRows().firstOrNull { it.iid == iid } ?: return ""
    if (reply.kind != MrReplies.Kind.THREAD) return ""
    row.discussions.firstOrNull { it.id == reply.threadId }?.let { d ->
      val note = d.notes.firstOrNull { !it.system } ?: return@let null
      val anchor = d.anchorFile?.let { "$it:${d.anchorLine ?: "?"}" }?.let { " · $it" } ?: ""
      return "${note.author}: ${note.body.lineSequence().firstOrNull().orEmpty().take(120)}$anchor"
    }
    val md = row.receivedMarkdown ?: return ""
    val lines = md.lines()
    val mark = lines.indexOfFirst { it.contains("lgm-thread: ${reply.threadId}") }
    if (mark < 0) return ""
    val head = lines.take(mark).lastOrNull { it.startsWith("## ") }.orEmpty()
    val place = lines.drop(mark + 1).firstOrNull { it.startsWith("**Место:**") || it.startsWith("**Location:**") }.orEmpty()
    return (head + " " + place).trim()
  }

  override fun doOKAction() {
    val approved = rows.zip(boxes).filter { it.second.isSelected }.map { it.first.reply }
    if (approved.isEmpty()) {
      notify(LocalGitMirrorBundle.message("mrreview.dialog.noneApproved"), NotificationType.WARNING)
      return
    }
    super.doOKAction()
    ApplicationManager.getApplication().executeOnPooledThread {
      if (isWork) {
        val target = pending
        if (target == null) {
          notify(LocalGitMirrorBundle.message("mrreview.dialog.empty", iid), NotificationType.WARNING)
          return@executeOnPooledThread
        }
        val report = MrReplyPushService(project).pushApproved(target, approved)
        ApplicationManager.getApplication().invokeLater {
          if (!project.isDisposed) MrReplyPushService(project).notifySummary(listOf(report))
        }
      } else {
        val md = MrReplies.render(iid, parsed.branch, approved)
        val ok = MrRepliesTransport.uploadMarkdown(project, iid, md)
        notify(
          if (ok) LocalGitMirrorBundle.message("mrreview.sent.ok", approved.size)
          else LocalGitMirrorBundle.message("mrreview.sent.fail"),
          if (ok) NotificationType.INFORMATION else NotificationType.ERROR,
        )
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
    /** Opens the review dialog for [iid]: home reads the local replies file, work downloads the pending postbox item. */
    fun openFor(project: Project, iid: Int) {
      val isWork = RoleDetector.detect(service<MirrorSettingsService>().state) == MachineRole.WORK
      if (!isWork) {
        val base = project.basePath ?: return
        val file = File(base, ".mr-notes/replies-!$iid.md")
        if (!file.isFile) {
          notifyStatic(project, LocalGitMirrorBundle.message("mrreview.dialog.empty", iid), NotificationType.WARNING)
          return
        }
        val parsed = MrReplies.parse(file.readText(Charsets.UTF_8))
        if (parsed.replies.isEmpty()) {
          notifyStatic(project, LocalGitMirrorBundle.message("mrreview.dialog.empty", iid), NotificationType.WARNING)
          return
        }
        MrRepliesReviewDialog(project, iid, parsed, null).show()
        return
      }
      ApplicationManager.getApplication().executeOnPooledThread {
        val pending = MrReplyPushService(project).fetchPendingReplies().firstOrNull { it.parsed.iid == iid }
        ApplicationManager.getApplication().invokeLater {
          if (project.isDisposed) return@invokeLater
          if (pending == null || pending.parsed.replies.isEmpty()) {
            notifyStatic(project, LocalGitMirrorBundle.message("mrreview.dialog.empty", iid), NotificationType.WARNING)
            return@invokeLater
          }
          MrRepliesReviewDialog(project, iid, pending.parsed, pending).show()
        }
      }
    }

    private fun notifyStatic(project: Project, content: String, type: NotificationType) {
      NotificationGroupManager.getInstance()
        .getNotificationGroup("DocCache")
        .createNotification(content, type)
        .notify(project)
    }
  }
}
