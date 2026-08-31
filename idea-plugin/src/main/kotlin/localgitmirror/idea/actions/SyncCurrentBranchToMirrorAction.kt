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
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File

class SyncCurrentBranchToMirrorAction : AnAction() {
  override fun update(e: AnActionEvent) {
    LocalGitMirrorBundle.localizePresentation(e, "LocalGitMirror.SyncCurrentBranch")
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

    if (settings.baseUrl.isBlank()) {
      notify(project, LocalGitMirrorBundle.message("action.syncBranch.urlMissing"), NotificationType.WARNING)
      return
    }
    if (SecretsStore.syncPassword.isBlank()) {
      notify(project, LocalGitMirrorBundle.message("action.syncBranch.syncPasswordMissing"), NotificationType.WARNING)
      return
    }
    if (!GitLocal.isCleanWorkTree(project, projectDir)) {
      notify(project, LocalGitMirrorBundle.message("notify.worktree.dirty"), NotificationType.WARNING)
      return
    }

    val repoInfo = syncFacade.describeRepoTarget(projectDir, settings)

    val branch = GitLocal.currentBranch(project, projectDir) ?: "(unknown)"

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("action.syncCurrent.progress"), false) {
      override fun run(indicator: ProgressIndicator) {
        val history = service<OperationsHistoryService>()
        indicator.text = LocalGitMirrorBundle.message("action.syncCurrent.indicatorText")
        notify(project, LocalGitMirrorBundle.message("action.syncBranch.starting", repoInfo), NotificationType.INFORMATION)
        val syncRes = syncFacade.runFullSync(projectDir, settings)
        val result = syncRes.step
        if (!result.ok) {
          notify(project, "[trace=${syncRes.traceId}] repo='${syncRes.repo ?: "?"}' ${result.message}. ${result.details}", NotificationType.ERROR)
          history.add(LocalGitMirrorBundle.message("history.op.sendCurrent"), false,
            "branch=$branch err=${result.message.take(300)}")
          return
        }

        if (settings.offlineGenerateOnly) {
          notify(project, "[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: "?"}' at ${syncRes.dump?.absolutePath ?: result.details}", NotificationType.INFORMATION)
          history.add(LocalGitMirrorBundle.message("history.op.sendCurrent"), true,
            "offline dump=${syncRes.dump?.absolutePath ?: "?"}")
          return
        }

        notify(project, "[trace=${syncRes.traceId}] Synced branch $branch to Mirror repo '${syncRes.repo ?: "?"}'. ${syncRes.http?.body?.take(500) ?: ""}", NotificationType.INFORMATION)
        history.add(LocalGitMirrorBundle.message("history.op.sendCurrent"), true,
          "branch=$branch repo=${syncRes.repo ?: "?"}")
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
