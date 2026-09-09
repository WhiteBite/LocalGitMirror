package localgitmirror.idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.gitlab.GitLabApi
import localgitmirror.idea.gitlab.GitLabConfig
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.OperationsHistoryService

/**
 * «Send MR branch to Mirror…» — transfer a GitLab MR's source branch to Mirror.
 *
 * Flow:
 *  1. Resolve the GitLab instance: explicit settings override, else
 *     auto-detect from the project's default git remote (base =
 *     scheme://host[:port], project = remote path minus .git).
 *  2. When a token is available (PasswordSafe or GITLAB_TOKEN env), fetch the
 *     open MRs; on failure warn and continue with an empty list.
 *  3. Editable chooser on the EDT: pick an MR ("!iid  title  (branch)"), type
 *     "!123" (resolved via the API), or type a plain branch name (works
 *     without a token).
 *  4. `git fetch origin <branch>`, then the same send-branch pipeline as
 *     [SyncBranchToMirrorAction] for a specific branch: checkout → full sync
 *     → restore the original branch. The branch lands on Mirror under its
 *     own name.
 */
class SendGitLabMrAction : AnAction() {

  override fun update(e: AnActionEvent) {
    LocalGitMirrorBundle.localizePresentation(e, "LocalGitMirror.SendGitLabMr")
    e.presentation.isEnabled = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val baseDir = project.basePath
    if (baseDir.isNullOrBlank()) {
      notify(project, LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
      return
    }
    if (!GitLabMrSender.precheck(project)) return

    // GitLab side: settings override or auto-detect from the git remote.
    val conf = GitLabConfig.resolve(project)
    if (conf.url.isBlank() || conf.project.isBlank()) {
      notify(project, LocalGitMirrorBundle.message("gitlab.notify.notDetected"), NotificationType.ERROR)
      return
    }

    ProgressManager.getInstance().run(
      object : Task.Backgroundable(project, LocalGitMirrorBundle.message("gitlab.task.title"), true) {
        override fun run(indicator: ProgressIndicator) {
          val history = service<OperationsHistoryService>()

          // 1. Open MRs — only when a token is available. On failure: warn and
          //    continue with an empty list; the user can still type a branch.
          var mrs: List<GitLabApi.MrInfo> = emptyList()
          if (GitLabConfig.hasApi(conf)) {
            indicator.text = LocalGitMirrorBundle.message("gitlab.progress.fetchMrs")
            val res = GitLabApi.listOpenMrs(conf)
            if (res.code in 200..299) {
              mrs = res.mrs
            } else {
              notify(
                project,
                LocalGitMirrorBundle.message("gitlab.notify.mrListFailed", res.code, res.message),
                NotificationType.WARNING
              )
            }
          }
          if (indicator.isCanceled) return

          // 2. Chooser on the EDT: MR items + a free-text hint entry (always).
          val hintItem = LocalGitMirrorBundle.message("gitlab.chooser.hint")
          val options = mrs.map { mrItemText(it) }.toMutableList()
          options.add(hintItem)
          val chosen = UIUtil.invokeAndWaitIfNeeded<String> {
            Messages.showEditableChooseDialog(
              LocalGitMirrorBundle.message("gitlab.chooser.prompt"),
              LocalGitMirrorBundle.message("gitlab.chooser.title"),
              null,
              options.toTypedArray(),
              options.firstOrNull() ?: hintItem,
              null
            ) ?: ""
          }
          if (chosen.isBlank() || chosen == hintItem) return
          if (indicator.isCanceled) return

          // 3. Resolve the choice into (branch, iid).
          val trimmed = chosen.trim()
          val picked = mrs.firstOrNull { mrItemText(it) == chosen }
          val typedIid = IID_RE.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
          val branch: String
          val iid: Int?
          when {
            picked != null && picked.sourceBranch.isNotBlank() -> {
              branch = picked.sourceBranch
              iid = picked.iid
            }
            (picked != null || typedIid != null) && GitLabConfig.hasApi(conf) -> {
              // Picked item with a blank branch, or typed "!123" — resolve via API.
              val n = picked?.iid ?: typedIid!!
              val br = GitLabApi.getMrSourceBranch(conf, n)
              if (br.code !in 200..299 || br.branch.isNullOrBlank()) {
                notify(
                  project,
                  LocalGitMirrorBundle.message("gitlab.notify.sourceBranchFailed", br.code, br.message),
                  NotificationType.ERROR
                )
                history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), false,
                  "iid=$n err=${br.message.take(300)}")
                return
              }
              branch = br.branch
              iid = n
            }
            typedIid != null -> {
              // "!N" typed but no token — the number cannot be resolved.
              notify(project, LocalGitMirrorBundle.message("gitlab.notify.tokenMissingForIid"), NotificationType.ERROR)
              return
            }
            else -> {
              // Free text = branch name (works without a token).
              branch = trimmed
              iid = null
            }
          }

          // 4-5. Fetch from origin + send-branch pipeline (shared with the MR list dialog).
          GitLabMrSender.send(project, conf, branch, iid)
        }
      }
    )
  }

  private fun mrItemText(mr: GitLabApi.MrInfo): String =
    "!${mr.iid}  ${mr.title}  (${mr.sourceBranch})"

  private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("DocCache")
      .createNotification(message, type)
      .notify(project)
  }

  companion object {
    /** Free-typed MR reference: "!123" (optional space after '!'). */
    private val IID_RE = Regex("^!\\s*(\\d+)$")
  }
}
