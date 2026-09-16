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
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.gitlab.GitLabApi
import localgitmirror.idea.gitlab.GitLabConfig
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.ui.MrMultiSelectDialog

/**
 * «Send MR branch to Mirror…» — transfer GitLab MR source branches to Mirror.
 *
 * Flow:
 *  1. Resolve the GitLab instance: explicit settings override, else
 *     auto-detect from the project's default git remote (base =
 *     scheme://host[:port], project = remote path minus .git).
 *  2. When a token is available (PasswordSafe or GITLAB_TOKEN env), fetch the
 *     open MRs; on failure warn and continue with an empty list.
 *  3. Multi-select chooser on the EDT: check any number of MRs
 *     ("!iid  title  (branch)"), type "!123" (resolved via the API), or type
 *     a plain branch name (works without a token).
 *  4. One `git fetch origin <branches...>` for all targets, then per MR the
 *     same send-branch pipeline as [SyncBranchToMirrorAction]: checkout →
 *     full sync → notes; the original branch is restored once at the end.
 *     Each branch lands on Mirror under its own name.
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

          // 2. Multi-select chooser on the EDT: MR checkboxes + a free-text entry.
          val choice = UIUtil.invokeAndWaitIfNeeded<Pair<List<GitLabApi.MrInfo>, String>?> {
            val dialog = MrMultiSelectDialog(mrs)
            if (dialog.showAndGet()) Pair(dialog.selectedMrs, dialog.typedText) else null
          }
          if (choice == null) return
          if (indicator.isCanceled) return

          // 3. Resolve the choice into (branch, iid) targets.
          val targets = mutableListOf<Pair<String, Int?>>()
          for (mr in choice.first) {
            if (mr.sourceBranch.isNotBlank()) {
              targets.add(mr.sourceBranch to mr.iid)
              continue
            }
            if (!GitLabConfig.hasApi(conf)) continue
            val br = GitLabApi.getMrSourceBranch(conf, mr.iid)
            if (br.code in 200..299 && !br.branch.isNullOrBlank()) {
              targets.add(br.branch to mr.iid)
            } else {
              notify(
                project,
                LocalGitMirrorBundle.message("gitlab.notify.sourceBranchFailed", br.code, br.message),
                NotificationType.ERROR
              )
              history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), false,
                "iid=${mr.iid} err=${br.message.take(300)}")
            }
          }

          val typed = choice.second
          if (typed.isNotBlank()) {
            val typedIid = IID_RE.find(typed)?.groupValues?.get(1)?.toIntOrNull()
            when {
              typedIid != null && GitLabConfig.hasApi(conf) -> {
                val br = GitLabApi.getMrSourceBranch(conf, typedIid)
                if (br.code in 200..299 && !br.branch.isNullOrBlank()) {
                  targets.add(br.branch to typedIid)
                } else {
                  notify(
                    project,
                    LocalGitMirrorBundle.message("gitlab.notify.sourceBranchFailed", br.code, br.message),
                    NotificationType.ERROR
                  )
                  history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), false,
                    "iid=$typedIid err=${br.message.take(300)}")
                }
              }
              typedIid != null -> {
                // "!N" typed but no token — the number cannot be resolved.
                notify(project, LocalGitMirrorBundle.message("gitlab.notify.tokenMissingForIid"), NotificationType.ERROR)
              }
              else -> {
                // Free text = branch name (works without a token).
                targets.add(typed to null)
              }
            }
          }

          val distinct = targets.distinct()
          if (distinct.isEmpty()) return
          if (indicator.isCanceled) return

          // 4-5. Fetch from origin + send-branch pipeline (shared with the MR list dialog).
          if (distinct.size == 1) {
            GitLabMrSender.send(project, conf, distinct[0].first, distinct[0].second)
          } else {
            GitLabMrSender.sendAll(project, conf, distinct)
          }
        }
      }
    )
  }

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
