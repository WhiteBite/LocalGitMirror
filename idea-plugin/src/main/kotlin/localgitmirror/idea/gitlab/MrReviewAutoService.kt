package localgitmirror.idea.gitlab

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.actions.GitLabMrSender
import localgitmirror.idea.deps.MachineRole
import localgitmirror.idea.deps.RoleDetector
import localgitmirror.idea.diagnostics.AutomationFailureDeduper
import localgitmirror.idea.diagnostics.failureSignature
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import java.io.File
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

private const val AUTO_SEND_DIRTY = "worktree dirty"

/** Auto MR branch send skip decision: a dirty tree surfaces once per dirty period, a clean pass re-arms it. */
internal fun autoSendSkipDiagnostic(deduper: AutomationFailureDeduper, worktreeClean: Boolean): String? {
  if (worktreeClean) {
    deduper.recovered()
    return null
  }
  return deduper.failure(AUTO_SEND_DIRTY)
}

/** MR review loop driver. HOME: jittered 60s background poll; WORK: no background traffic (stealth) — [triggerWorkCycle] only. */
class MrReviewAutoService(private val project: Project) : Disposable {

  private val settings get() = service<MirrorSettingsService>().state

  @Volatile private var disposed = false
  @Volatile private var started = false
  private var lastWrittenSignature = ""
  private var lastPushSignature = ""
  private var lastPendingSignature = ""
  private var lastNotesSignature = ""
  private var lastHomeErrorSignature = ""
  private val homeCycleDeduper = AutomationFailureDeduper()
  private val workCycleDeduper = AutomationFailureDeduper()
  private val autoSendSkipDeduper = AutomationFailureDeduper()
  private val workCycleRunning = AtomicBoolean(false)

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
          if (RoleDetector.detect(settings) != MachineRole.WORK) {
            pollHome()
          }
          homeCycleDeduper.recovered()
        } catch (t: Throwable) {
          reportCycleFailure(homeCycleDeduper, t)
        }
      }
    }, "DocCache-MrReviewAuto").apply { isDaemon = true }.start()
  }

  fun triggerWorkCycle() {
    if (disposed || !settings.autoMrReview || settings.baseUrl.isBlank()) return
    if (RoleDetector.detect(settings) != MachineRole.WORK) return
    if (!workCycleRunning.compareAndSet(false, true)) return
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        if (!disposed && !project.isDisposed) {
          pollWork()
          workCycleDeduper.recovered()
        }
      } catch (t: Throwable) {
        reportCycleFailure(workCycleDeduper, t)
      } finally {
        workCycleRunning.set(false)
      }
    }
  }

  private fun reportCycleFailure(deduper: AutomationFailureDeduper, t: Throwable) {
    val signature = failureSignature(t)
    if (deduper.failure(signature) == null) return
    service<OperationsHistoryService>().add(
      LocalGitMirrorBundle.message("history.op.autoMrReview"), false,
      "err=$signature"
    )
    notify(LocalGitMirrorBundle.message("mrreview.auto.cycle.fail", signature), NotificationType.WARNING)
  }

  private fun pollWork() {
    val conf = GitLabConfig.resolve(project)
    if (!GitLabConfig.hasApi(conf)) return
    pollNotesSync()
    autoSendBranches()
    val service = MrReplyPushService(project)
    if (settings.autoPushReplies) {
      val reports = service.pushOnce()
      if (reports.isEmpty()) return
      val signature = reports.joinToString(";") { "${it.posted}/${it.dupSkipped}/${it.skipped}/${it.failed}/${it.parseErrors}/${it.details.joinToString()}" }
      if (signature == lastPushSignature) return
      lastPushSignature = signature
      val visible = reports.filter { !it.quiet }
      if (visible.isEmpty()) return
      UIUtil.invokeLaterIfNeeded {
        if (!project.isDisposed) service.notifySummary(visible)
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
        service<OperationsHistoryService>().add(
          LocalGitMirrorBundle.message("history.op.pendingMrReplies"), true,
          "count=${pending.count}"
        )
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

  private fun autoSendBranches() {
    if (!settings.autoMrBranches || disposed || project.isDisposed) return
    val baseDir = project.basePath ?: return
    if (!File(baseDir).exists()) return
    val conf = GitLabConfig.resolve(project)
    if (!GitLabConfig.hasApi(conf)) return
    val res = runCatching { GitLabApi.listOpenMrs(conf) }.getOrNull() ?: return
    if (res.code !in 200..299) return
    val targets = GitLabMrSender.mrTargetsFromMrs(res.mrs)
    if (targets.isEmpty()) return
    val skip = autoSendSkipDiagnostic(autoSendSkipDeduper, GitLocal.isCleanWorkTree(project, File(baseDir)))
    if (skip != null) {
      service<OperationsHistoryService>().add(
        LocalGitMirrorBundle.message("history.op.autoMrReview"), false,
        "err=auto MR branch send skipped: $skip"
      )
      notify(LocalGitMirrorBundle.message("mrreview.auto.branchSkip.dirty"), NotificationType.WARNING)
      return
    }
    GitLabMrSender.sendAll(project, targets, quiet = true)
  }

  private fun pollNotesSync() {
    val report = runCatching { MrNotesSync.sync(project) }
      .getOrElse { MrNotesSync.Report(emptyList(), 0, listOf(it.message ?: "error")) }
    val signature = "${report.uploaded}|${report.failures}"
    if (signature == lastNotesSignature) return
    lastNotesSignature = signature
    service<OperationsHistoryService>().add(
      LocalGitMirrorBundle.message("history.op.autoMrNotes"),
      report.failures.isEmpty(),
      "uploaded=${report.uploaded} unchanged=${report.unchanged}",
    )
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
