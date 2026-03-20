package localgitmirror.idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File

class SyncCurrentBranchToMirrorAction : AnAction() {
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

    val repoInfo = syncFacade.describeRepoTarget(projectDir, settings)

    val branch = GitLocal.currentBranch(project, projectDir) ?: "(unknown)"

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Sync current branch", false) {
      override fun run(indicator: ProgressIndicator) {
        indicator.text = "Syncing current branch"
        notify(project, "Starting sync: $repoInfo", NotificationType.INFORMATION)
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

        notify(project, "[trace=${syncRes.traceId}] Synced branch $branch to Mirror repo '${syncRes.repo ?: settings.repo}'. ${syncRes.http?.body?.take(500) ?: ""}", NotificationType.INFORMATION)
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
