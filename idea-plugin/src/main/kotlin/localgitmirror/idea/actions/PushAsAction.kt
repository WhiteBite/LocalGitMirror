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
      notify(project, "Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val projectDir = File(baseDir)
    val settings = service<MirrorSettingsService>().state
    val syncFacade = project.getService(SyncFacadeService::class.java)

    if (settings.baseUrl.isBlank()) {
      notify(project, "Configure Mirror URL in settings", NotificationType.WARNING)
      return
    }
    if (SecretsStore.syncPassword.isBlank()) {
      notify(project, "Configure Sync Password in settings", NotificationType.WARNING)
      return
    }
    if (!GitLocal.isCleanWorkTree(project, projectDir)) {
      notify(project, "Working tree has uncommitted changes. Commit/stash before syncing.", NotificationType.WARNING)
      return
    }

    val currentBranch = GitLocal.currentBranch(project, projectDir)
    if (currentBranch.isNullOrBlank()) {
      notify(project, "Cannot determine current branch", NotificationType.WARNING)
      return
    }

    // Show dialog: user enters the target branch name
    val targetBranch = Messages.showInputDialog(
      project,
      "Current branch: $currentBranch\nEnter the name to push as on Mirror:",
      "Push as…",
      null,
      "",
      null
    )
    if (targetBranch.isNullOrBlank()) return

    // Validate branch name (basic git rules)
    if (!isValidBranchName(targetBranch)) {
      notify(project, "Invalid branch name: '$targetBranch'", NotificationType.ERROR)
      return
    }

    // Check if a local branch with that name already exists
    val localBranches = GitLocal.localBranches(project, projectDir)
    if (localBranches.contains(targetBranch)) {
      notify(project, "Branch '$targetBranch' already exists locally. Use 'Send branch…' to send it, or choose a different name.", NotificationType.WARNING)
      return
    }

    val repoInfo = syncFacade.describeRepoTarget(projectDir, settings)

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Push as '$targetBranch'", false) {
      override fun run(indicator: ProgressIndicator) {
        notify(project, "Starting sync: $repoInfo (as '$targetBranch')", NotificationType.INFORMATION)

        indicator.text = "Creating temporary branch '$targetBranch'"
        val create = GitLocal.checkoutNew(project, projectDir, targetBranch, "HEAD")
        if (!create.ok()) {
          notify(project, "Failed to create branch '$targetBranch': ${create.stderr}", NotificationType.ERROR)
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
            notify(project, "[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: settings.repo}' at ${syncRes.dump?.absolutePath ?: result.details}", NotificationType.INFORMATION)
            return
          }

          notify(project, "[trace=${syncRes.traceId}] Pushed as '$targetBranch' to Mirror repo '${syncRes.repo ?: settings.repo}'. ${syncRes.http?.body?.take(500) ?: ""}", NotificationType.INFORMATION)
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
