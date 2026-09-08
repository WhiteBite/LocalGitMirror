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
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File

class DryRunSyncAction : AnAction() {
  override fun update(e: AnActionEvent) {
    LocalGitMirrorBundle.localizePresentation(e, "LocalGitMirror.DryRun")
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val baseDir = project.basePath ?: return
    val dir = File(baseDir)
    val settings = service<MirrorSettingsService>().state
    val facade = project.getService(SyncFacadeService::class.java)

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Dry-run", false) {
      override fun run(indicator: ProgressIndicator) {
        val history = service<OperationsHistoryService>()
        val report = facade.runDryRun(dir, settings)
        val summary = if (report.ok) {
          LocalGitMirrorBundle.message("action.dryRun.summary", report.predictedMode, report.commitCount, report.targetRepo.orEmpty().ifBlank { "(none)" })
        } else {
          LocalGitMirrorBundle.message("action.dryRun.failed")
        }
        notify(project, summary, if (report.ok) NotificationType.INFORMATION else NotificationType.WARNING)
        history.add(LocalGitMirrorBundle.message("history.op.dryRunSend"), report.ok,
          if (report.ok) "mode=${report.predictedMode} commits=${report.commitCount} repo=${report.targetRepo ?: "?"}"
          else "err=${report.targetRepo ?: "fail"}")
      }
    })
  }

  private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("DocCache").createNotification(message, type).notify(project)
  }
}
