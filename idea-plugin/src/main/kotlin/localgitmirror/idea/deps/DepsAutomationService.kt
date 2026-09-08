package localgitmirror.idea.deps

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-level service that automates corporate-dependency transfer.
 *
 *  - HOME (hosts the Mirror server): auto-detects missing corporate deps on
 *    project open and on Gradle sync failure, sends a deps request, polls for
 *    responses, and auto-applies them.
 *  - WORK (corporate machine): polls for pending deps requests and auto-
 *    responds by shipping artifacts from the local cache.
 *
 * All network/processing runs on daemon threads — never on the EDT. The
 * poller is cancelled when the project is disposed. Failures are logged via
 * [DepsDiagnostics] and only surfaced as notifications for request/respond/apply
 * outcomes (never spammed; deduped by id).
 */
class DepsAutomationService(private val project: Project) : Disposable {

  private val log = Logger.getInstance(DepsAutomationService::class.java)

  private val settings get() = service<MirrorSettingsService>().state
  private val syncPwd get() = SecretsStore.syncPassword

  /** Re-resolved on every access: a machineRole settings change applies without restart. */
  private val role: MachineRole
    get() = RoleDetector.detect(settings)

  /** Repo name resolved once per [start]. */
  private var repoName: String = ""

  /** IDs of responses already applied (HOME) — prevents re-applying. */
  private val appliedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** IDs of responses already notified about when autoApply is off (HOME). */
  private val notifiedResponseIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** IDs currently being processed — prevents concurrent work on the same item. */
  private val inFlightIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** IDs of requests already sent (HOME) — prevents duplicate requests. */
  private val requestedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

  @Volatile private var started = false
  @Volatile private var disposed = false
  @Volatile private var lastMissingCheckMs = 0L

  private var pollerThread: Thread? = null
  private var initialCheckThread: Thread? = null
  private var vaultSyncInitThread: Thread? = null

  private val scheduler = DepsPollScheduler(
    baseIdleSec = { settings.depsPollSec }
  )

  /**
   * Called from UI actions (Request/Respond/Apply) and internal events
   * (auto-request sent, Gradle sync failure) to switch the poller into the
   * 15-minute fast window (30–60 s jittered sleeps).
   */
  fun recordLocalEvent() {
    scheduler.recordLocalEvent()
  }

  /**
   * Called from [DepsAutomationStartupActivity]. Idempotent: safe to call
   * multiple times. Resolves the role and repo name, then starts the poller
   * and (for HOME) the debounced initial missing-check.
   */
  fun start() {
    if (started) return
    started = true

    val s = settings
    if (s.baseUrl.isBlank() || syncPwd.isBlank()) return

    val baseDir = project.basePath ?: return
    val dir = File(baseDir)
    if (!dir.exists()) return

    repoName = localgitmirror.idea.sync.v2.RepoResolver
      .resolve(project, dir, "")
      .sanitized
      .ifBlank { project.name }
    if (repoName.isBlank()) return

    DepsDiagnostics.event("automation: started")

    // Register Gradle sync failure hook (HOME only, for auto-request)
    if (role == MachineRole.HOME && s.autoRequestDeps) {
      registerGradleSyncHook()
    }

    // WORK with auto-respond off: no background network activity.
    // Manual actions (Respond/Apply/Request) don't depend on the poller.
    if (role == MachineRole.WORK && !s.autoRespondDeps) return

    // Start the poller
    startPoller()

    // HOME: debounced initial missing-check (20s after start)
    if (role == MachineRole.HOME && s.autoRequestDeps) {
      scheduleInitialMissingCheck(20_000L)
    }

    // HOME: vault cache self-healing (40s after start, after the missing-check)
    if (role == MachineRole.HOME) {
      scheduleVaultCacheSync(40_000L)
    }
  }

  // ── Poller ──

  private fun startPoller() {
    pollerThread = Thread({
      while (!disposed && !Thread.currentThread().isInterrupted) {
        try {
          Thread.sleep(scheduler.nextSleepMs())
          if (disposed) break
          when (role) {
            MachineRole.HOME -> pollHome()
            MachineRole.WORK -> pollWork()
          }
        } catch (_: InterruptedException) {
          break
        } catch (t: Throwable) {
          DepsDiagnostics.event("automation: poller error")
          log.warn("Deps automation poller error", t)
        }
      }
    }, "doccache-poller").apply { isDaemon = true }
    pollerThread?.start()
  }

  // ── HOME behavior ──

  private fun pollHome() {
    val s = settings
    if (s.baseUrl.isBlank() || syncPwd.isBlank()) return

    // Check for available responses
    val responses = runCatching {
      MirrorApi.depsResponses(
        baseUrl = s.baseUrl,
        apiKey = SecretsStore.mirrorApiKey,
        repo = repoName,
        insecureTls = s.mirrorInsecureTls
      )
    }.getOrNull() ?: return

    if (responses.code !in 200..299) return

    // Update visibility cache for the manual action
    ApplyDepsAction.lastKnownResponseCount.set(responses.items.size)

    scheduler.recordPollResult(hadItems = responses.items.isNotEmpty())

    for (item in responses.items) {
      if (disposed) return
      if (item.id in appliedIds) continue
      if (item.id in notifiedResponseIds && !s.autoApplyDeps) continue
      if (!inFlightIds.add(item.id)) continue  // already being processed

      runCatching { processHomeResponse(item.id, s) }
        .onFailure { DepsDiagnostics.event("automation: response processing failed") }
      inFlightIds.remove(item.id)
    }
  }

  private fun processHomeResponse(id: String, s: MirrorSettingsService.State) {
    val tmpResp = File.createTempFile("tmp-", ".bin").apply { deleteOnExit() }
    try {
      val dl = MirrorApi.depsDownload(
        baseUrl = s.baseUrl,
        apiKey = SecretsStore.mirrorApiKey,
        repo = repoName,
        insecureTls = s.mirrorInsecureTls,
        id = id,
        kind = MirrorApi.DepsKind.RESPONSE,
        outFile = tmpResp
      )
      if (dl.code !in 200..299 || dl.file == null) {
        DepsDiagnostics.event("automation: response download failed")
        return
      }

      if (s.autoApplyDeps) {
        val result = DepsApplier.apply(project, s, syncPwd, tmpResp.readBytes(), null)
        val history = service<OperationsHistoryService>()
        if (result.success) {
          // Ack the response on the server (one-shot: server deletes it).
          runCatching {
            MirrorApi.depsAck(
              baseUrl = s.baseUrl,
              apiKey = SecretsStore.mirrorApiKey,
              repo = repoName,
              insecureTls = s.mirrorInsecureTls,
              id = id
            )
          }
          appliedIds.add(id)
          notify(LocalGitMirrorBundle.message("auto.notify.applied", result.installed), NotificationType.INFORMATION)
          DepsDiagnostics.event("automation: applied installed=${result.installed}")
          history.add(LocalGitMirrorBundle.message("history.op.autoDepsApply"), true,
            "installed=${result.installed} id=$id")
        } else {
          notify(LocalGitMirrorBundle.message("auto.notify.applyFailed", result.message), NotificationType.ERROR)
          DepsDiagnostics.event("automation: apply failed")
          history.add(LocalGitMirrorBundle.message("history.op.autoDepsApply"), false,
            "err=${result.message.take(300)} id=$id")
        }
      } else {
        // Don't auto-apply: notify once per id
        if (notifiedResponseIds.add(id)) {
          notify(LocalGitMirrorBundle.message("auto.notify.responseReceived"), NotificationType.INFORMATION)
          DepsDiagnostics.event("automation: response received (autoApply off)")
        }
      }
    } finally {
      runCatching { tmpResp.delete() }
    }
  }

  /**
   * HOME: detect missing corporate deps and send a request. Called on start
   * (debounced 20s) and on Gradle sync failure (debounced 30s).
   *
   * Skips if a request is already pending for this repo (server-side dedup).
   */
  fun scheduleMissingCheck(debounceMs: Long = 30_000L) {
    if (role != MachineRole.HOME) return
    if (!settings.autoRequestDeps) return

    val now = System.currentTimeMillis()
    if (now - lastMissingCheckMs < debounceMs) return
    lastMissingCheckMs = now

    Thread({
      if (disposed) return@Thread
      runCatching { doMissingCheck() }
        .onFailure { DepsDiagnostics.event("automation: missing-check failed") }
    }, "doccache-check").apply { isDaemon = true }.start()
  }

  private fun scheduleInitialMissingCheck(debounceMs: Long) {
    initialCheckThread = Thread({
      try {
        Thread.sleep(debounceMs)
      } catch (_: InterruptedException) {
        return@Thread
      }
      if (disposed) return@Thread
      lastMissingCheckMs = System.currentTimeMillis()
      runCatching { doMissingCheck() }
        .onFailure { DepsDiagnostics.event("automation: initial missing-check failed") }
    }, "doccache-init-check").apply { isDaemon = true }
    initialCheckThread?.start()
  }

  private fun scheduleVaultCacheSync(delayMs: Long) {
    vaultSyncInitThread = Thread({
      try {
        Thread.sleep(delayMs)
      } catch (_: InterruptedException) {
        return@Thread
      }
      if (disposed) return@Thread
      runCatching { VaultCacheSync.syncInBackground(project, "auto") }
        .onFailure { DepsDiagnostics.event("automation: vault-sync scheduling failed") }
    }, "doccache-vault-init").apply { isDaemon = true }
    vaultSyncInitThread?.start()
  }

  private fun doMissingCheck() {
    val s = settings
    if (s.baseUrl.isBlank() || syncPwd.isBlank()) return
    if (repoName.isBlank()) return

    // Check if a request is already pending — skip if so
    val pending = runCatching {
      MirrorApi.depsPending(
        baseUrl = s.baseUrl,
        apiKey = SecretsStore.mirrorApiKey,
        repo = repoName,
        insecureTls = s.mirrorInsecureTls
      )
    }.getOrNull()

    if (pending != null && pending.code in 200..299 && pending.items.isNotEmpty()) {
      DepsDiagnostics.event("automation: skipping request — ${pending.items.size} already pending")
      return
    }

    // Detect missing and send request
    val result = DepsRequester.request(project, s, syncPwd, repoName, null)
    val history = service<OperationsHistoryService>()
    if (result.success && result.missingCount > 0 && result.requestId != null) {
      requestedIds.add(result.requestId)
      notify(LocalGitMirrorBundle.message("auto.notify.requestSent", result.missingCount), NotificationType.INFORMATION)
      DepsDiagnostics.event("automation: request sent missing=${result.missingCount}")
      history.add(LocalGitMirrorBundle.message("history.op.autoDepsRequest"), true,
        "missing=${result.missingCount} id=${result.requestId}")
      scheduler.recordLocalEvent()
    } else if (!result.success) {
      notify(LocalGitMirrorBundle.message("auto.notify.requestFailed", result.message), NotificationType.ERROR)
      DepsDiagnostics.event("automation: request failed")
      history.add(LocalGitMirrorBundle.message("history.op.autoDepsRequest"), false,
        "err=${result.message.take(300)}")
    }
  }

  // ── WORK behavior ──

  private fun pollWork() {
    val s = settings
    if (s.baseUrl.isBlank() || syncPwd.isBlank()) return
    if (!s.autoRespondDeps) return

    val pending = runCatching {
      MirrorApi.depsPending(
        baseUrl = s.baseUrl,
        apiKey = SecretsStore.mirrorApiKey,
        repo = repoName,
        insecureTls = s.mirrorInsecureTls
      )
    }.getOrNull() ?: return

    if (pending.code !in 200..299) return

    // Update visibility cache for the manual action
    RespondDepsAction.lastKnownPendingCount.set(pending.items.size)

    scheduler.recordPollResult(hadItems = pending.items.isNotEmpty())

    for (item in pending.items) {
      if (disposed) return
      if (!inFlightIds.add(item.id)) continue  // already being processed

      runCatching { processWorkRequest(item.id, s) }
        .onFailure { DepsDiagnostics.event("automation: request processing failed") }
      inFlightIds.remove(item.id)
    }
  }

  private fun processWorkRequest(id: String, s: MirrorSettingsService.State) {
    val tmpManifest = File.createTempFile("tmp-", ".bin").apply { deleteOnExit() }
    try {
      val dl = MirrorApi.depsDownload(
        baseUrl = s.baseUrl,
        apiKey = SecretsStore.mirrorApiKey,
        repo = repoName,
        insecureTls = s.mirrorInsecureTls,
        id = id,
        kind = MirrorApi.DepsKind.MANIFEST,
        outFile = tmpManifest
      )
      if (dl.code !in 200..299 || dl.file == null) {
        DepsDiagnostics.event("automation: manifest download failed")
        return
      }

      val manifestBlob = tmpManifest.readBytes()
      val result = DepsResponder.respond(project, s, syncPwd, repoName, id, manifestBlob, null)
      val history = service<OperationsHistoryService>()

      if (result.success) {
        notify(LocalGitMirrorBundle.message("auto.notify.sent", result.sentCount), NotificationType.INFORMATION)
        DepsDiagnostics.event("automation: responded shipped=${result.sentCount} bytes=${result.bytes}")
        history.add(LocalGitMirrorBundle.message("history.op.autoDepsRespond"), true,
          "sent=${result.sentCount} id=$id")
      } else {
        // Don't spam error balloons for "0 found" — that's expected on WORK
        // when the cache doesn't have the requested artifacts. Log only.
        if (result.sentCount == 0 && result.notFoundCount > 0) {
          DepsDiagnostics.event("automation: respond 0 found (notFound=${result.notFoundCount})")
          history.add(LocalGitMirrorBundle.message("history.op.autoDepsRespond"), false,
            "sent=0 notFound=${result.notFoundCount} id=$id")
        } else {
          notify(LocalGitMirrorBundle.message("auto.notify.respondFailed", result.message), NotificationType.ERROR)
          DepsDiagnostics.event("automation: respond failed")
          history.add(LocalGitMirrorBundle.message("history.op.autoDepsRespond"), false,
            "err=${result.message.take(300)} id=$id")
        }
      }
    } finally {
      runCatching { tmpManifest.delete() }
    }
  }

  // ── Gradle sync failure hook ──

  /**
   * Subscribe to the Gradle sync failure topic on the application message bus.
   *
   * The [ExternalSystemTaskNotificationListener] class is part of the
   * `external-system-impl` module, which is NOT in the base IC platform SDK
   * classpath (it requires the Gradle plugin to be present). We use reflection
   * to subscribe at runtime so the code compiles without the dependency.
   *
   * If the class is not available at runtime (Gradle plugin not installed),
   * the hook is silently skipped — the initial missing-check (20s after start)
   * and the poller still work.
   *
   * Filters events where the external system id is Gradle and the working
   * directory matches this project's basePath. On failure, schedules a
   * debounced missing-check.
   */
  private fun registerGradleSyncHook() {
    val basePath = project.basePath ?: return
    val canonicalBase = runCatching { File(basePath).canonicalPath }.getOrDefault(basePath)

    runCatching {
      // Resolve the topic class and TOPIC field via reflection.
      val listenerClass = Class.forName("com.intellij.openapi.externalSystem.ExternalSystemTaskNotificationListener")
      val topicField = listenerClass.getDeclaredField("TOPIC")
      topicField.isAccessible = true
      @Suppress("UNCHECKED_CAST")
      val topic = topicField.get(null) as com.intellij.util.messages.Topic<*>

      // Resolve ExternalSystemTaskId class for the listener proxy.
      val taskIdClass = Class.forName("com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId")
      val getProjectSystemId = taskIdClass.getMethod("getProjectSystemId")
      val projectSystemIdClass = Class.forName("com.intellij.openapi.externalSystem.model.ProjectSystemId")
      val getName = projectSystemIdClass.getMethod("getName")

      // Create a dynamic proxy implementing the listener interface.
      val proxy = java.lang.reflect.Proxy.newProxyInstance(
        listenerClass.classLoader,
        arrayOf(listenerClass)
      ) { _, method, args ->
        if (method.name == "onFailure" && args != null && args.size >= 4) {
          val id = args[0]
          val workingDirectory = args[2] as? String
          if (id != null && workingDirectory != null) {
            // Check if it's Gradle
            val sysId = runCatching { getProjectSystemId.invoke(id) }.getOrNull()
            val sysName = runCatching { getName.invoke(sysId) as? String }.getOrNull()
            if (sysName != null && sysName.equals("GRADLE", ignoreCase = true)) {
              // Check if the working directory matches this project's basePath
              val canonicalWd = runCatching { File(workingDirectory).canonicalPath }
                .getOrDefault(workingDirectory)
              if (canonicalWd.equals(canonicalBase, ignoreCase = true)) {
                // Schedule the missing-check (debounced 30s)
                scheduleMissingCheck(30_000L)
                scheduler.recordLocalEvent()
              }
            }
          }
        }
        null  // void method
      }

      val connection = ApplicationManager.getApplication().messageBus.connect(project)
      @Suppress("UNCHECKED_CAST")
      val subscribeMethod = connection.javaClass.getMethod("subscribe", com.intellij.util.messages.Topic::class.java, Any::class.java)
      subscribeMethod.invoke(connection, topic, proxy)

      DepsDiagnostics.event("automation: Gradle sync failure hook registered (via reflection)")
    }.onFailure {
      // Fallback: if the ExternalSystemTaskNotificationListener API is not
      // available (Gradle plugin not installed), the sync-failure hook is
      // not active. The initial missing-check (20s after start) and the
      // poller still work.
      DepsDiagnostics.event("automation: Gradle sync hook unavailable")
      log.warn("ExternalSystemTaskNotificationListener not available; sync-failure hook disabled", it)
    }
  }

  // ── Notification helper (EDT-safe) ──

  private fun notify(message: String, type: NotificationType) {
    if (project.isDisposed) return
    ApplicationManager.getApplication().invokeLater {
      if (project.isDisposed) return@invokeLater
      NotificationGroupManager.getInstance()
        .getNotificationGroup("DocCache")
        .createNotification(message, type)
        .notify(project)
    }
  }

  // ── Disposable ──

  override fun dispose() {
    disposed = true
    pollerThread?.interrupt()
    initialCheckThread?.interrupt()
    vaultSyncInitThread?.interrupt()
    DepsDiagnostics.event("automation: stopped (disposed)")
  }
}
