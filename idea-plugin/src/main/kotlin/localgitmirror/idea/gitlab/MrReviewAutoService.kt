package localgitmirror.idea.gitlab

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.deps.MachineRole
import localgitmirror.idea.deps.RoleDetector
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import java.io.File

/**
 * Background driver of the MR review loop, gated by `autoMrReview`:
 *  - WORK (plugin-only machine): pushes the agent's reply files from the
 *    postbox to GitLab as soon as they arrive — no human click involved;
 *  - HOME: writes incoming `mr-notes/mr-!N.md` into `.mr-notes/` so agents on
 *    this machine see fresh review notes without opening the IDE panel.
 *
 * Deliberately simple: one daemon thread on a fixed 60 s cadence; every pass
 * re-reads role and settings so toggles apply without a restart. All push
 * safety (marker dedup, ack-only-on-clean) lives in [MrReplyPushService].
 */
class MrReviewAutoService(private val project: Project) : Disposable {

  private val settings get() = service<MirrorSettingsService>().state

  @Volatile private var disposed = false
  @Volatile private var started = false
  private var lastWrittenSignature = ""

  fun start() {
    if (started) return
    started = true
    Thread({
      while (!disposed && !Thread.currentThread().isInterrupted) {
        try {
          Thread.sleep(POLL_MS)
        } catch (_: InterruptedException) {
          break
        }
        if (disposed) break
        if (!settings.autoMrReview || settings.baseUrl.isBlank()) continue
        try {
          when (RoleDetector.detect(settings)) {
            MachineRole.WORK -> pollWork()
            else -> pollHome()
          }
        } catch (_: Throwable) {
          // background best-effort loop: never kill the poller on a bad pass
        }
      }
    }, "DocCache-MrReviewAuto").apply { isDaemon = true }.start()
  }

  private fun pollWork() {
    val conf = GitLabConfig.resolve(project)
    if (!GitLabConfig.hasApi(conf)) return
    MrReplyPushService(project).pushInBackground()
  }

  private fun pollHome() {
    val review = project.getService(MrReviewService::class.java)
    review.refreshInBackground(notify = false) { rows ->
      ApplicationManager.getApplication().executeOnPooledThread {
        if (project.isDisposed) return@executeOnPooledThread
        val cacheRows = rows.filter { it.source == MrReviewService.Source.CACHE && it.receivedMarkdown != null }
        if (cacheRows.isEmpty()) return@executeOnPooledThread
        val signature = cacheRows.joinToString(";") { "${it.iid}:${it.receivedMarkdown.hashCode()}" }
        if (signature == lastWrittenSignature) return@executeOnPooledThread
        lastWrittenSignature = signature
        val base = project.basePath ?: return@executeOnPooledThread
        if (!File(base).exists()) return@executeOnPooledThread
        cacheRows.forEach { MrNotesWriter.writeForAgent(project, it) }
        MrNotesWriter.writeIndex(project, rows)
        UIUtil.invokeLaterIfNeeded {
          if (!project.isDisposed) {
            NotificationGroupManager.getInstance()
              .getNotificationGroup("DocCache")
              .createNotification(
                LocalGitMirrorBundle.message("mrreview.auto.saved", cacheRows.size),
                NotificationType.INFORMATION,
              )
              .notify(project)
          }
        }
      }
    }
  }

  override fun dispose() {
    disposed = true
  }

  companion object {
    private const val POLL_MS = 60_000L
  }
}
