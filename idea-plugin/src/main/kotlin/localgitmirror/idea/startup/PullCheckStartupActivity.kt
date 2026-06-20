package localgitmirror.idea.startup

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import localgitmirror.idea.deps.ApplyDepsAction
import localgitmirror.idea.deps.RespondDepsAction
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.SyncLogger
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.SwingUtilities

/**
 * Pure function: determines whether a "pending deps" notification should fire.
 *
 * @param count         number of pending items (>0 means there is something to show)
 * @param lastNotified  epoch-ms of the last notification (0 = never)
 * @param nowMillis     current epoch-ms
 * @param cooldownMs    minimum ms between consecutive notifications
 */
fun shouldNotifyPending(count: Int, lastNotified: Long, nowMillis: Long, cooldownMs: Long): Boolean {
  if (count <= 0) return false
  return (nowMillis - lastNotified) >= cooldownMs
}

/** Daemon-threaded runner so background network work never blocks IDE shutdown. */
private fun runDaemon(name: String, block: () -> Unit) {
  Thread(block, name).apply { isDaemon = true }.start()
}

class PullCheckStartupActivity : ProjectActivity {

  companion object {
    // Minimum seconds between checks (avoid spam on rapid alt-tabs)
    private const val COOLDOWN_SEC = 120L
  }

  /**
   * Called once per project open. Must return QUICKLY — IntelliJ holds a
   * ChildScope open for the duration of this suspend function, so any slow or
   * never-completing work inside it will block project/IDE shutdown (the scope
   * stays Active and the IDE waits for it to complete).
   *
   * Strategy: do all real work on daemon threads (won't block shutdown) and
   * register the window-focus listener synchronously before returning.
   */
  override suspend fun execute(project: Project) {
    val settings = service<MirrorSettingsService>().state
    if (!settings.autoCheckPullOnStartup) return
    if (settings.baseUrl.isBlank()) return

    val baseDir = project.basePath ?: return
    val dir = File(baseDir)
    if (!dir.exists()) return

    val repoName = localgitmirror.idea.sync.v2.RepoResolver
      .resolve(project, dir, settings.repo).sanitized.ifBlank { project.name }

    // Startup check on a DAEMON thread — never block the startup activity / EDT.
    runDaemon("lgm-startup-check") {
      try { checkForUpdates(project, dir, settings, repoName) } catch (_: Exception) {}
      try { checkForDeps(project, dir, settings, repoName) } catch (_: Exception) {}
    }

    // Register window focus listener. We use invokeLater but we do NOT await it —
    // execute() returns immediately so the ChildScope is released right away.
    // The listener is cleaned up via Disposer when the project closes.
    SwingUtilities.invokeLater {
      if (project.isDisposed) return@invokeLater
      val frame = WindowManager.getInstance().getFrame(project) ?: return@invokeLater

      val listener = object : WindowAdapter() {
        @Volatile private var lastCheckEpoch = System.currentTimeMillis() / 1000
        @Volatile private var lastDepsNotifyPendingMs = 0L
        @Volatile private var lastDepsNotifyResponsesMs = 0L

        override fun windowGainedFocus(e: WindowEvent?) {
          if (project.isDisposed) return
          val now = System.currentTimeMillis() / 1000
          if (now - lastCheckEpoch < COOLDOWN_SEC) return
          lastCheckEpoch = now

          val s = service<MirrorSettingsService>().state
          if (!s.autoCheckPullOnStartup) return
          if (s.baseUrl.isBlank()) return

          val repo = localgitmirror.idea.sync.v2.RepoResolver
            .resolve(project, dir, s.repo).sanitized.ifBlank { project.name }

          runDaemon("lgm-focus-check") {
            if (project.isDisposed) return@runDaemon
            try { checkForUpdates(project, dir, s, repo) } catch (_: Exception) {}
            try {
              val nowMs = System.currentTimeMillis()
              checkForDepsWithCooldown(
                project, dir, s, repo,
                lastPendingMs = lastDepsNotifyPendingMs,
                lastResponsesMs = lastDepsNotifyResponsesMs,
                nowMs = nowMs,
                cooldownMs = COOLDOWN_SEC * 1000
              ).let { (newPendingMs, newResponsesMs) ->
                if (newPendingMs > 0) lastDepsNotifyPendingMs = newPendingMs
                if (newResponsesMs > 0) lastDepsNotifyResponsesMs = newResponsesMs
              }
            } catch (_: Exception) {}
          }
        }
      }

      frame.addWindowFocusListener(listener)

      // Remove listener when project closes — prevents the frame reference from
      // keeping the project alive and blocking IDE shutdown.
      Disposer.register(project) {
        try { frame.removeWindowFocusListener(listener) } catch (_: Throwable) {}
      }
    }
    // execute() returns here immediately. The ChildScope is released.
    // All work continues on daemon threads or in the window listener.
  }

  @Suppress("HttpCallOnEdt")
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
    val remoteHash = remoteRefs[currentBranch]?.sha ?: return
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

  /**
   * Startup (one-shot) deps check — no cooldown state needed on first run.
   */
  @Suppress("HttpCallOnEdt")
  private fun checkForDeps(
    project: Project,
    dir: File,
    settings: MirrorSettingsService.State,
    repoName: String
  ) {
    if (SecretsStore.syncPassword.isBlank()) return
    checkForDepsWithCooldown(project, dir, settings, repoName,
      lastPendingMs = 0L, lastResponsesMs = 0L,
      nowMs = System.currentTimeMillis(), cooldownMs = 0L)
  }

  /**
   * Deps check with cooldown. Returns a pair of (newPendingNotifyMs, newResponsesNotifyMs):
   * each is the current time if a notification was fired, or 0 otherwise.
   */
  @Suppress("HttpCallOnEdt")
  private fun checkForDepsWithCooldown(
    project: Project,
    @Suppress("UNUSED_PARAMETER") dir: File,
    settings: MirrorSettingsService.State,
    repoName: String,
    lastPendingMs: Long,
    lastResponsesMs: Long,
    nowMs: Long,
    cooldownMs: Long
  ): Pair<Long, Long> {
    if (SecretsStore.syncPassword.isBlank()) return Pair(0L, 0L)

    var notifiedPendingMs = 0L
    var notifiedResponsesMs = 0L

    // Check pending requests (work laptop role)
    val pending = try {
      MirrorApi.depsPending(
        baseUrl = settings.baseUrl,
        apiKey = SecretsStore.mirrorApiKey,
        repo = repoName,
        insecureTls = settings.mirrorInsecureTls
      )
    } catch (_: Exception) { null }

    if (pending != null && pending.code in 200..299) {
      val count = pending.items.size
      // Update visibility cache
      RespondDepsAction.lastKnownPendingCount.set(count)
      if (shouldNotifyPending(count, lastPendingMs, nowMs, cooldownMs)) {
        val notification = NotificationGroupManager.getInstance()
          .getNotificationGroup("LocalGitMirror")
          .createNotification(
            "На Mirror ждёт $count запрос(ов) зависимостей. Нажми «Выдать».",
            NotificationType.INFORMATION
          )
          .addAction(NotificationAction.createSimpleExpiring("Выдать") {
            RespondDepsAction().actionPerformed(
              com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                "DepsStartupCheck", null,
                com.intellij.openapi.actionSystem.DataContext { dataId ->
                  if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(dataId)) project else null
                }
              )
            )
          })
        notification.notify(project)
        notifiedPendingMs = nowMs
      }
    }

    // Check available responses (home PC role)
    val responses = try {
      MirrorApi.depsResponses(
        baseUrl = settings.baseUrl,
        apiKey = SecretsStore.mirrorApiKey,
        repo = repoName,
        insecureTls = settings.mirrorInsecureTls
      )
    } catch (_: Exception) { null }

    if (responses != null && responses.code in 200..299) {
      val count = responses.items.size
      // Update visibility cache
      ApplyDepsAction.lastKnownResponseCount.set(count)
      if (shouldNotifyPending(count, lastResponsesMs, nowMs, cooldownMs)) {
        val notification = NotificationGroupManager.getInstance()
          .getNotificationGroup("LocalGitMirror")
          .createNotification(
            "Готов ответ с зависимостями ($count). Нажми «Применить».",
            NotificationType.INFORMATION
          )
          .addAction(NotificationAction.createSimpleExpiring("Применить") {
            ApplyDepsAction().actionPerformed(
              com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                "DepsStartupCheck", null,
                com.intellij.openapi.actionSystem.DataContext { dataId ->
                  if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(dataId)) project else null
                }
              )
            )
          })
        notification.notify(project)
        notifiedResponsesMs = nowMs
      }
    }

    return Pair(notifiedPendingMs, notifiedResponsesMs)
  }
}
