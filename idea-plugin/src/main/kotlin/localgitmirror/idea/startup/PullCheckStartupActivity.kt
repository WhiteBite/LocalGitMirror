package localgitmirror.idea.startup

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.delay
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File

class PullCheckStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    val settings = service<MirrorSettingsService>().state
    if (!settings.autoCheckPullOnStartup) return
    if (settings.baseUrl.isBlank()) return

    val baseDir = project.basePath ?: return
    val dir = File(baseDir)
    if (!dir.exists()) return

    // Let IDE settle before background network call.
    delay(3_000)

    val facade = project.getService(SyncFacadeService::class.java) ?: return
    val report = facade.runPullDryRun(dir, settings)

    if (!report.ok || !report.hasUpdates) return

    val notification = NotificationGroupManager.getInstance()
      .getNotificationGroup("LocalGitMirror")
      .createNotification(
        "LocalGitMirror: incoming changes",
        "Mirror has updates (${report.reason}). Remote HEAD: ${report.remoteHead?.take(12) ?: "?"}",
        NotificationType.INFORMATION
      )
      .addAction(NotificationAction.createSimpleExpiring("Stealth Pull Back") {
        localgitmirror.idea.actions.StealthPullBackFromMirrorAction().actionPerformed(
          com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
            "LGMStartupPullCheck",
            null,
            com.intellij.openapi.actionSystem.DataContext { dataId ->
              if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(dataId)) project else null
            }
          )
        )
      })

    notification.notify(project)
  }
}
