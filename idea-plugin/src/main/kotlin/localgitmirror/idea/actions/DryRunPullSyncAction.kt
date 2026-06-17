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
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File

class DryRunPullSyncAction : AnAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val baseDir = project.basePath ?: return
    val dir = File(baseDir)
    val settings = service<MirrorSettingsService>().state
    val facade = project.getService(SyncFacadeService::class.java)

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Dry-run (Pull)", false) {
      override fun run(indicator: ProgressIndicator) {
        val report = facade.runPullDryRun(dir, settings)
        val summary = if (report.ok) {
          LocalGitMirrorBundle.message("action.dryRunPull.summary", report.hasUpdates, report.remoteHead?.take(12) ?: "(empty)", report.reason)
        } else {
          LocalGitMirrorBundle.message("action.dryRunPull.failed")
        }
        notify(project, summary, if (report.ok) NotificationType.INFORMATION else NotificationType.WARNING)
      }
    })
  }

  private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("LocalGitMirror").createNotification(message, type).notify(project)
  }
}
