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
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import java.io.File

class PullBackFromRemoteAction : AnAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val baseDir = project.basePath
    if (baseDir.isNullOrBlank()) {
      notify(project, LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
      return
    }
    val dir = File(baseDir)
    val s = service<MirrorSettingsService>().state
    val remote = s.gitRemoteName.ifBlank { "origin" }

    val branches = GitLocal.remoteBranches(project, dir, remote)
    if (branches.isEmpty()) {
      notify(project, LocalGitMirrorBundle.message("notify.noRemoteBranches", remote), NotificationType.WARNING)
      return
    }

    val selectedRemoteRef = Messages.showEditableChooseDialog(
      LocalGitMirrorBundle.message("action.pullBack.selectBranch"),
      LocalGitMirrorBundle.message("action.pullBack.dialogTitle"),
      null,
      branches.toTypedArray(),
      branches.firstOrNull(),
      null
    ) ?: return

    val mode = Messages.showEditableChooseDialog(
      LocalGitMirrorBundle.message("action.pullBack.modePrompt"),
      LocalGitMirrorBundle.message("action.pullBack.dialogTitle"),
      null,
      arrayOf("new-branch", "ff-only"),
      s.pullBackDefaultMode,
      null
    )?.trim()?.lowercase().orEmpty()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("action.pullBack.progress"), false) {
      override fun run(indicator: ProgressIndicator) {
        val fetch = GitLocal.fetch(project, dir, remote)
        if (!fetch.ok()) {
          notify(project, LocalGitMirrorBundle.message("notify.gitFetchFailed", fetch.stderr), NotificationType.ERROR)
          return
        }

        when (mode) {
          "ff-only" -> {
            val current = GitLocal.currentBranch(project, dir)
            if (current.isNullOrBlank()) {
              notify(project, LocalGitMirrorBundle.message("notify.currentBranch.missing"), NotificationType.ERROR)
              return
            }
            val pull = GitLocal.pullFfOnly(project, dir, remote, current)
            if (!pull.ok()) {
              notify(project, LocalGitMirrorBundle.message("notify.gitPullFailed", pull.stderr), NotificationType.ERROR)
              return
            }
            notify(project, LocalGitMirrorBundle.message("notify.pullBack.ok.ffonly"), NotificationType.INFORMATION)
          }

          else -> {
            val defaultName = "pullback-${System.currentTimeMillis()}"
            val localName = Messages.showInputDialog(
              project,
              LocalGitMirrorBundle.message("action.pullBack.localBranchName"),
              LocalGitMirrorBundle.message("action.pullBack.dialogTitle"),
              null,
              defaultName,
              null
            )?.trim().orEmpty()
            if (localName.isBlank()) return

            val co = GitLocal.checkoutNew(project, dir, localName, selectedRemoteRef)
            if (!co.ok()) {
              notify(project, LocalGitMirrorBundle.message("notify.createBranchFailed", co.stderr), NotificationType.ERROR)
              return
            }
            notify(project, LocalGitMirrorBundle.message("notify.pullBack.ok.newBranch", localName), NotificationType.INFORMATION)
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
