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
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.settings.MirrorSettingsService
import java.io.File

class PullBackFromRemoteAction : AnAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val baseDir = project.basePath
    if (baseDir.isNullOrBlank()) {
      notify(project, "Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val dir = File(baseDir)
    val s = service<MirrorSettingsService>().state
    val remote = s.gitRemoteName.ifBlank { "origin" }

    val branches = GitLocal.remoteBranches(project, dir, remote)
    if (branches.isEmpty()) {
      notify(project, "No remote branches found for '$remote'", NotificationType.WARNING)
      return
    }

    val selectedRemoteRef = Messages.showEditableChooseDialog(
      "Select remote branch to pull from",
      "LocalGitMirror: Pull back",
      null,
      branches.toTypedArray(),
      branches.firstOrNull(),
      null
    ) ?: return

    val mode = Messages.showEditableChooseDialog(
      "Pull-back mode",
      "LocalGitMirror: Pull back",
      null,
      arrayOf("new-branch", "ff-only"),
      s.pullBackDefaultMode,
      null
    )?.trim()?.lowercase().orEmpty()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Pull back", false) {
      override fun run(indicator: ProgressIndicator) {
        val fetch = GitLocal.fetch(project, dir, remote)
        if (!fetch.ok()) {
          notify(project, "git fetch failed: ${fetch.stderr}", NotificationType.ERROR)
          return
        }

        when (mode) {
          "ff-only" -> {
            val current = GitLocal.currentBranch(project, dir)
            if (current.isNullOrBlank()) {
              notify(project, "Cannot determine current branch", NotificationType.ERROR)
              return
            }
            val pull = GitLocal.pullFfOnly(project, dir, remote, current)
            if (!pull.ok()) {
              notify(project, "git pull --ff-only failed: ${pull.stderr}", NotificationType.ERROR)
              return
            }
            notify(project, "Pull back completed (ff-only)", NotificationType.INFORMATION)
          }

          else -> {
            val defaultName = "pullback-${System.currentTimeMillis()}"
            val localName = Messages.showInputDialog(
              project,
              "Local branch name",
              "LocalGitMirror: Pull back",
              null,
              defaultName,
              null
            )?.trim().orEmpty()
            if (localName.isBlank()) return

            val co = GitLocal.checkoutNew(project, dir, localName, selectedRemoteRef)
            if (!co.ok()) {
              notify(project, "Failed to create local branch: ${co.stderr}", NotificationType.ERROR)
              return
            }
            notify(project, "Pull back completed into new branch '$localName'", NotificationType.INFORMATION)
          }
        }
      }
    })
  }

  private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("LocalGitMirror")
      .createNotification(message, type)
      .notify(project)
  }
}
