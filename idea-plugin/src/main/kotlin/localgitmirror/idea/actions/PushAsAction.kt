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
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File

/**
 * "Push as…" action: sends the current HEAD to the Mirror under a different branch name.
 *
 * Flow:
 *   1. Show dialog — user types target branch name (e.g. TASK-123)
 *   2. Create a temporary local branch with that name from HEAD
 *   3. Check out the temp branch
 *   4. Run full sync (bundle + upload) — the bundle will contain refs/heads/TASK-123
 *   5. Restore original branch and delete the temp branch
 *
 * On the remote side, "Pull from Mirror" will automatically create the branch.
 */
class PushAsAction : AnAction() {

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabled = project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val baseDir = project.basePath
    if (baseDir.isNullOrBlank()) {
      notify(project, LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
      return
    }
    val projectDir = File(baseDir)
    val settings = service<MirrorSettingsService>().state
    val syncFacade = project.getService(SyncFacadeService::class.java)

    if (settings.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) {
      notify(project, LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    if (!GitLocal.isCleanWorkTree(project, projectDir)) {
      notify(project, LocalGitMirrorBundle.message("notify.worktree.dirty"), NotificationType.WARNING)
      return
    }

    val currentBranch = GitLocal.currentBranch(project, projectDir)
    if (currentBranch.isNullOrBlank()) {
      notify(project, LocalGitMirrorBundle.message("notify.currentBranch.missing"), NotificationType.WARNING)
      return
    }

    // Show dialog: user enters the target branch name
    val targetBranch = Messages.showInputDialog(
      project,
      LocalGitMirrorBundle.message("dialog.pushAs.prompt", currentBranch),
      LocalGitMirrorBundle.message("dialog.pushAs.title"),
      null,
      "",
      null
    )
    if (targetBranch.isNullOrBlank()) return

    // Validate branch name (basic git rules)
    if (!isValidBranchName(targetBranch)) {
      notify(project, LocalGitMirrorBundle.message("action.pushAs.invalidBranch", targetBranch), NotificationType.ERROR)
      return
    }

    // Check if a local branch with that name already exists
    val localBranches = GitLocal.localBranches(project, projectDir)
    if (localBranches.contains(targetBranch)) {
      notify(project, LocalGitMirrorBundle.message("notify.send.branch.alreadyExists", targetBranch), NotificationType.WARNING)
      return
    }

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Push as '$targetBranch'", false) {
      override fun run(indicator: ProgressIndicator) {
        indicator.text = "Creating temporary branch '$targetBranch'"
        val create = GitLocal.checkoutNew(project, projectDir, targetBranch, "HEAD")
        if (!create.ok()) {
          notify(project, LocalGitMirrorBundle.message("notify.createBranchFailed", create.stderr), NotificationType.ERROR)
          return
        }

        try {
          indicator.text = "Syncing '$targetBranch' to Mirror"
          val syncRes = syncFacade.runFullSync(projectDir, settings)
          val result = syncRes.step
          if (!result.ok) {
            notify(project, "[trace=${syncRes.traceId}] repo='${syncRes.repo ?: settings.repo}' ${result.message}. ${result.details}", NotificationType.ERROR)
            return
          }

          if (settings.offlineGenerateOnly) {
            notify(project, "[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: settings.repo}'", NotificationType.INFORMATION)
            return
          }

          notify(project, "[trace=${syncRes.traceId}] ${LocalGitMirrorBundle.message("notify.send.pushAs.ok", targetBranch, syncRes.repo ?: settings.repo)}", NotificationType.INFORMATION)
        } finally {
          indicator.text = "Restoring original branch"
          if (!currentBranch.isNullOrBlank()) {
            GitLocal.checkout(project, projectDir, currentBranch)
          }
          GitLocal.deleteLocalBranch(project, projectDir, targetBranch, force = true)
        }
      }
    })
  }

  private fun isValidBranchName(name: String): Boolean {
    if (name.isBlank()) return false
    if (name.startsWith(".") || name.startsWith("-")) return false
    if (name.contains("..") || name.contains("@{")) return false
    if (name.any { it in setOf(' ', '~', '^', ':', '?', '*', '[', '\\') }) return false
    if (name.endsWith(".lock") || name.endsWith("/")) return false
    // Must not contain consecutive slashes
    if (name.contains("//")) return false
    return true
  }

  private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("LocalGitMirror")
      .createNotification(message, type)
      .notify(project)
  }
}
