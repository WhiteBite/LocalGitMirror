package localgitmirror.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import localgitmirror.idea.gitlab.MrReplyPushService
import localgitmirror.idea.i18n.LocalGitMirrorBundle

/**
 * Work machine: pull the agent's reply files (`mr-replies/mr-!N.md`) from the
 * Cache postbox and post them to GitLab (thread replies, new anchored
 * discussions, general notes). See [MrReplyPushService].
 */
class PushMrRepliesAction : AnAction() {

  override fun update(e: AnActionEvent) {
    LocalGitMirrorBundle.localizePresentation(e, "LocalGitMirror.PushMrReplies")
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    MrReplyPushService(project).pushInBackground { reports ->
      if (reports.isEmpty()) return@pushInBackground
      val lines = reports.flatMap { r ->
        listOf("[${r.path}] posted=${r.posted} dup=${r.dupSkipped} skipped=${r.skipped} failed=${r.failed}") +
          r.details.map { "    $it" }
      }
      Messages.showDialog(
        project,
        lines.joinToString("\n"),
        LocalGitMirrorBundle.message("mrreplies.report.title"),
        arrayOf("OK"),
        0,
        null,
      )
    }
  }
}
