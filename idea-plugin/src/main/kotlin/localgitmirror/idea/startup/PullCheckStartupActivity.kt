package localgitmirror.idea.startup

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.WindowManager
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.SyncLogger
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.SwingUtilities

class PullCheckStartupActivity : ProjectActivity {

  companion object {
    // Minimum seconds between checks (avoid spam on rapid alt-tabs)
    private const val COOLDOWN_SEC = 120L
  }

  override suspend fun execute(project: Project) {
    val settings = service<MirrorSettingsService>().state
    if (!settings.autoCheckPullOnStartup) return
    if (settings.baseUrl.isBlank()) return

    val baseDir = project.basePath ?: return
    val dir = File(baseDir)
    if (!dir.exists()) return

    val repoName = settings.repo.ifBlank { project.name }

    // Single check on startup
    checkForUpdates(project, dir, settings, repoName)

    // Register window focus listener for subsequent checks
    SwingUtilities.invokeLater {
      val frame = WindowManager.getInstance().getFrame(project) ?: return@invokeLater
      frame.addWindowFocusListener(object : WindowAdapter() {
        @Volatile
        private var lastCheckEpoch = System.currentTimeMillis() / 1000

        override fun windowGainedFocus(e: WindowEvent?) {
          val now = System.currentTimeMillis() / 1000
          if (now - lastCheckEpoch < COOLDOWN_SEC) return
          lastCheckEpoch = now

          // Re-read settings in case they changed
          val s = service<MirrorSettingsService>().state
          if (!s.autoCheckPullOnStartup) return
          if (s.baseUrl.isBlank()) return

          val repo = s.repo.ifBlank { project.name }

          // Run check on background thread to not block UI
          Thread({
            try {
              checkForUpdates(project, dir, s, repo)
            } catch (_: Exception) {}
          }, "sync-focus-check").start()
        }
      })
    }
  }

  private fun checkForUpdates(
    project: Project,
    dir: File,
    settings: MirrorSettingsService.State,
    repoName: String
  ) {
    val refsResult = MirrorApi.getRefs(
      baseUrl = settings.baseUrl,
      apiKey = SecretsStore.mirrorApiKey,
      repo = repoName,
      insecureTls = settings.mirrorInsecureTls
    )

    if (refsResult.code !in 200..299 || refsResult.refs == null) return
    val remoteRefs = refsResult.refs
    if (remoteRefs.isEmpty()) return

    val currentBranch = GitLocal.currentBranch(project, dir) ?: return
    val remoteHash = remoteRefs[currentBranch] ?: return
    val localHash = GitLocal.headHash(project, dir) ?: return

    if (remoteHash.equals(localHash, ignoreCase = true)) return

    SyncLogger.log(dir, "[FOCUS-CHECK] Branch '$currentBranch' updated on Mirror: local=${localHash.take(12)} remote=${remoteHash.take(12)}")

    val notification = NotificationGroupManager.getInstance()
      .getNotificationGroup("LocalGitMirror")
      .createNotification(
        "Branch '$currentBranch' updated on Mirror",
        "Remote: ${remoteHash.take(12)}  Local: ${localHash.take(12)}",
        NotificationType.INFORMATION
      )
      .addAction(NotificationAction.createSimpleExpiring("Pull from Mirror") {
        localgitmirror.idea.actions.PullFromMirrorAction().actionPerformed(
          com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
            "SyncFocusCheck",
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
