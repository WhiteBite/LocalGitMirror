package localgitmirror.idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.gitlab.MrReplies
import localgitmirror.idea.gitlab.MrRepliesTransport
import localgitmirror.idea.gitlab.MrReviewService
import localgitmirror.idea.gitlab.MrSendBlockReason
import localgitmirror.idea.gitlab.MrSendGateDecision
import localgitmirror.idea.gitlab.MrSendRefusal
import localgitmirror.idea.gitlab.MrSendRefusalReason
import localgitmirror.idea.gitlab.MrUploadResult
import localgitmirror.idea.gitlab.sendRemainderDecision
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import java.io.File

internal fun uploadRepliesGateDecision(row: MrReviewService.MrRowItem?, file: MrReplies.RepliesFile): MrSendGateDecision =
  sendRemainderDecision(
    mrState = row?.state ?: "open",
    notesAvailable = row != null && (row.source == MrReviewService.Source.GITLAB || row.receivedMarkdown != null),
    discussions = row?.discussions ?: emptyList(),
    replies = file.replies,
    force = false,
  )

internal fun mrSendBlockedKey(reason: MrSendBlockReason): String =
  if (reason == MrSendBlockReason.CLOSED_MR) "mrview.send.blocked.closed" else "mrview.send.blocked.notes"

/**
 * Home machine: upload the agent's reply file `.mr-notes/replies-!N.md` to the
 * Cache postbox so the work machine can push it to GitLab.
 */
class UploadMrRepliesAction : AnAction() {

  override fun update(e: AnActionEvent) {
    LocalGitMirrorBundle.localizePresentation(e, "LocalGitMirror.UploadMrReplies")
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val base = project.basePath ?: return
    val files = File(base, ".mr-notes")
      .listFiles { f -> f.isFile && f.name.startsWith("replies-!") && f.name.endsWith(".md") }
      ?.sortedBy { it.name }
      .orEmpty()
    if (files.isEmpty()) {
      notify(project, LocalGitMirrorBundle.message("mrreplies.upload.none"), NotificationType.WARNING)
      return
    }
    val chosen: File = if (files.size == 1) {
      files[0]
    } else {
      val names = files.map { it.name }.toTypedArray()
      val picked = Messages.showEditableChooseDialog(
        LocalGitMirrorBundle.message("mrreplies.upload.choose"),
        LocalGitMirrorBundle.message("mrreplies.upload.chooseTitle"),
        null, names, names[0], null,
      ) ?: return
      files.firstOrNull { it.name == picked } ?: return
    }

    ApplicationManager.getApplication().executeOnPooledThread {
      val iid = Regex("^replies-!(\\d+)\\.md$").find(chosen.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
      if (iid == null) {
        notify(project, LocalGitMirrorBundle.message("mrreplies.upload.fail", chosen.name, "bad file name"), NotificationType.ERROR)
        return@executeOnPooledThread
      }
      val text = chosen.readText(Charsets.UTF_8)
      val parsed = MrReplies.parse(text)
      val row = project.getService(MrReviewService::class.java).cachedRows().firstOrNull { it.iid == iid }
      val decision = uploadRepliesGateDecision(row, parsed)
      if (decision.blockReason != null) {
        val blocked = LocalGitMirrorBundle.message(mrSendBlockedKey(decision.blockReason), iid)
        val confirmed = UIUtil.invokeAndWaitIfNeeded<Boolean> {
          Messages.showYesNoDialog(
            project,
            blocked,
            LocalGitMirrorBundle.message("mrview.send.confirmTitle"),
            Messages.getWarningIcon(),
          ) == Messages.YES
        }
        if (!confirmed) return@executeOnPooledThread
      }
      var toUpload = text
      if (decision.blockReason == null && decision.refusals.isNotEmpty()) {
        if (decision.allowed.isEmpty()) {
          notify(project, partialSendText(0, parsed.replies.size, decision), NotificationType.WARNING)
          return@executeOnPooledThread
        }
        toUpload = MrReplies.render(iid, parsed.branch, decision.allowed)
      }
      val outcome = MrRepliesTransport.uploadMarkdownResult(project, iid, toUpload)
      when (outcome) {
        is MrUploadResult.Ok ->
          if (decision.refusals.isEmpty()) {
            notify(project, LocalGitMirrorBundle.message("mrreplies.upload.ok", chosen.name), NotificationType.INFORMATION)
          } else {
            notify(project, partialSendText(decision.allowed.size, parsed.replies.size, decision), NotificationType.WARNING)
          }
        is MrUploadResult.Failed ->
          notify(project, LocalGitMirrorBundle.message("mrreplies.upload.fail", chosen.name, outcome.reason), NotificationType.ERROR)
      }
    }
  }

  private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("DocCache")
      .createNotification(message, type)
      .notify(project)
  }
}

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
