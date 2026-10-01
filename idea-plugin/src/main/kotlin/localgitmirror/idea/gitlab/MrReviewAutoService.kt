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
import java.util.concurrent.ThreadLocalRandom

/**
 * Background driver of the MR review loop, gated by `autoMrReview`:
 *  - WORK: pushes reply files to GitLab, re-uploads changed mr-notes ([MrNotesSync]);
 *  - HOME: writes incoming `mr-notes/mr-!N.md` into `.mr-notes/` so agents on
 *    this machine see fresh review notes without opening the IDE panel.
 *
 * Deliberately simple: one daemon thread on a jittered 60 s cadence; every pass
 * re-reads role and settings so toggles apply without a restart. All push
 * safety (marker dedup, ack only on a clean full-file push) lives in [MrReplyPushService].
 */
class MrReviewAutoService(private val project: Project) : Disposable {

  private val settings get() = service<MirrorSettingsService>().state

  @Volatile private var disposed = false
  @Volatile private var started = false
  private var lastWrittenSignature = ""
  private var lastPushSignature = ""
  private var lastPendingSignature = ""
  private var lastNotesSignature = ""
  private var lastHomeErrorSignature = ""

  fun start() {
    if (started) return
    started = true
    Thread({
      while (!disposed && !Thread.currentThread().isInterrupted) {
        try {
          Thread.sleep(nextSleepMs())
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
    pollNotesSync()
    val service = MrReplyPushService(project)
    if (settings.autoPushReplies) {
      val reports = service.pushOnce()
      if (reports.isEmpty()) return
      val signature = reports.joinToString(";") { "${it.posted}/${it.dupSkipped}/${it.skipped}/${it.failed}/${it.parseErrors}/${it.details.joinToString()}" }
      if (signature == lastPushSignature) return
      lastPushSignature = signature
      UIUtil.invokeLaterIfNeeded {
        if (!project.isDisposed) service.notifySummary(reports)
      }
      return
    }
    val pending = runCatching { service.countPending() }
      .getOrElse { MrReplyPushService.PendingCount.Unavailable(it.message ?: "error") }
    when (pending) {
      is MrReplyPushService.PendingCount.Available -> {
        if (pending.count == 0) {
          lastPendingSignature = ""
          return
        }
        if (pending.count.toString() == lastPendingSignature) return
        lastPendingSignature = pending.count.toString()
        notify(LocalGitMirrorBundle.message("mrreview.pending", pending.count), NotificationType.INFORMATION)
      }
      is MrReplyPushService.PendingCount.Unavailable -> {
        if (pending.reason == lastPendingSignature) return
        lastPendingSignature = pending.reason
        notify(LocalGitMirrorBundle.message("mrreplies.list.fail", pending.reason), NotificationType.WARNING)
      }
    }
  }

  private fun notify(content: String, type: NotificationType) {
    UIUtil.invokeLaterIfNeeded {
      if (!project.isDisposed) {
        NotificationGroupManager.getInstance()
          .getNotificationGroup("DocCache")
          .createNotification(content, type)
          .notify(project)
      }
    }
  }

  private fun pollNotesSync() {
    val report = runCatching { MrNotesSync.sync(project) }
      .getOrElse { MrNotesSync.Report(emptyList(), 0, listOf(it.message ?: "error")) }
    val signature = "${report.uploaded}|${report.failures}"
    if (signature == lastNotesSignature) return
    lastNotesSignature = signature
    if (report.uploaded.isNotEmpty()) {
      notify(LocalGitMirrorBundle.message("mrnotes.sync.auto", report.uploaded.size), NotificationType.INFORMATION)
    }
    if (report.failures.isNotEmpty()) {
      notify(
        LocalGitMirrorBundle.message("mrnotes.sync.fail", report.failures.joinToString("; ").take(300)),
        NotificationType.WARNING,
      )
    }
  }

  private fun pollHome() {
    val review = project.getService(MrReviewService::class.java)
    review.refreshInBackground(notify = false) { rows ->
      ApplicationManager.getApplication().executeOnPooledThread {
        if (project.isDisposed) return@executeOnPooledThread
        val cacheRows = rows.filter { it.source == MrReviewService.Source.CACHE && it.receivedMarkdown != null }
        if (cacheRows.isEmpty()) {
          val error = review.cachedError()
          if (error == null) {
            lastHomeErrorSignature = ""
          } else if (error != lastHomeErrorSignature) {
            lastHomeErrorSignature = error
            notify(LocalGitMirrorBundle.message("mrreview.home.fetch.fail", error), NotificationType.WARNING)
          }
          return@executeOnPooledThread
        }
        lastHomeErrorSignature = ""
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

    private fun nextSleepMs(): Long {
      val min = (POLL_MS * 0.7).toLong()
      val max = (POLL_MS * 1.3).toLong()
      return ThreadLocalRandom.current().nextLong(min, max + 1)
    }
  }
}
