package localgitmirror.idea.deps

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorCrypto
import localgitmirror.idea.mirror.MirrorDepsApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import java.io.File
import java.util.concurrent.TimeUnit
private fun notify(project: Project, msg: String, type: NotificationType) {
  NotificationGroupManager.getInstance()
    .getNotificationGroup("DocCache")
    .createNotification(msg, type)
    .notify(project)
}

// ─────────────────────────────────────────────────────────────────────────────
// Visibility helpers (pure functions — no network, testable without IntelliJ)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pure function for [RespondDepsAction] / [ApplyDepsAction] enabled state.
 *
 * @param configured  true when baseUrl and syncPassword are both non-blank
 * @param lastKnownPending  cached count of pending items (may be -1 = unknown)
 * @return true when the action should be enabled in the UI
 */
fun computeRespondEnabled(configured: Boolean, lastKnownPending: Int): Boolean {
  if (!configured) return false
  // If cache is empty/unknown (-1) we err on the side of "show" (safe default)
  return lastKnownPending != 0
}

fun computeApplyEnabled(configured: Boolean, lastKnownPending: Int): Boolean =
  computeRespondEnabled(configured, lastKnownPending)

/**
 * Pure gate for deps request/respond/apply: a blank sync password is
 * acceptable when a v3 server key is pinned (relay crypto replaces it).
 */
fun depsTransferAllowed(baseUrl: String, syncPwd: String, v3Pinned: Boolean): Boolean =
  baseUrl.isNotBlank() && (syncPwd.isNotBlank() || v3Pinned)

// ─────────────────────────────────────────────────────────────────────────────
// 1. RequestDepsAction (DOME): figure out what we can't resolve locally, send it
// ─────────────────────────────────────────────────────────────────────────────

class RequestDepsAction : AnAction() {
  override fun update(e: AnActionEvent) {
    LocalGitMirrorBundle.localizePresentation(e, "LocalGitMirror.DepsRequest")
    val project = e.project
    if (project == null) {
      e.presentation.isEnabled = false
      return
    }
    val settings = service<MirrorSettingsService>().state
    val configured = depsTransferAllowed(settings.baseUrl, SecretsStore.cached.syncPassword, MirrorCrypto.isV3Pinned())
    val dir = project.basePath?.let { java.io.File(it) } ?: java.io.File(".")
    val hasEcosystem = configured && DepsEcosystems.detect(dir).isNotEmpty()
    e.presentation.isEnabled = hasEcosystem
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val settings = service<MirrorSettingsService>().state
    val syncPwd = SecretsStore.syncPassword
    if (!depsTransferAllowed(settings.baseUrl, syncPwd, MirrorCrypto.isV3Pinned())) {
      notify(project, LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val repo = resolveRepoName(project)
    val history = service<OperationsHistoryService>()
    runCatching { service<DepsAutomationService>().recordLocalEvent() }

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("deps.task.request"), true) {
      override fun run(indicator: ProgressIndicator) {
        val result = DepsRequester.request(project, settings, syncPwd, repo, indicator)
        if (result.success) {
          notify(project, result.message, NotificationType.INFORMATION)
          history.add("Deps request", true,
            "repo='$repo' id=${result.requestId ?: "-"} missing=${result.missingCount} eco=${result.ecosystem}")
        } else {
          notify(project, result.message, NotificationType.ERROR)
          history.add("Deps request", false, result.message)
        }
      }
    })
  }
}


// ─────────────────────────────────────────────────────────────────────────────
// 2. RespondDepsAction (WORK): collect the requested coords from cache, ship them
// ─────────────────────────────────────────────────────────────────────────────

class RespondDepsAction : AnAction() {

  companion object {
    /**
     * Cached count of pending requests from the last successful network call.
     * -1 = never fetched (unknown) → show unconditionally when configured.
     *  0 = known empty → hide.
     * >0 = known non-empty → show.
     */
    val lastKnownPendingCount = java.util.concurrent.atomic.AtomicInteger(-1)
  }

  override fun update(e: AnActionEvent) {
    LocalGitMirrorBundle.localizePresentation(e, "LocalGitMirror.DepsRespond")
    val project = e.project
    if (project == null) { e.presentation.isEnabled = false; return }
    val settings = service<MirrorSettingsService>().state
    val configured = depsTransferAllowed(settings.baseUrl, SecretsStore.cached.syncPassword, MirrorCrypto.isV3Pinned())
    e.presentation.isEnabled = computeRespondEnabled(configured, lastKnownPendingCount.get())
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val settings = service<MirrorSettingsService>().state
    val syncPwd = SecretsStore.syncPassword
    if (!depsTransferAllowed(settings.baseUrl, syncPwd, MirrorCrypto.isV3Pinned())) {
      notify(project, LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val repo = resolveRepoName(project)
    val history = service<OperationsHistoryService>()
    runCatching { service<DepsAutomationService>().recordLocalEvent() }

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("deps.task.respond"), true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        indicator.text = LocalGitMirrorBundle.message("deps.progress.respondCheck", repo)
        val pending = MirrorDepsApi.depsPending(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repo,
          insecureTls = settings.mirrorInsecureTls
        )
        if (pending.code !in 200..299) {
          notify(project, LocalGitMirrorBundle.message("deps.notify.respondListFailed", pending.message), NotificationType.ERROR)
          return
        }
        lastKnownPendingCount.set(pending.items.size)
        if (pending.items.isEmpty()) {
          notify(project, LocalGitMirrorBundle.message("deps.notify.noPending", repo), NotificationType.WARNING)
          history.add("Deps respond", false, "no pending for repo='$repo'")
          return
        }

        val req = pending.items.first()
        indicator.text = LocalGitMirrorBundle.message("deps.progress.downloadRequest", req.id.take(8))
        val tmpManifest = File.createTempFile("tmp-", ".bin").apply { deleteOnExit() }
        try {
          val dl = MirrorDepsApi.depsDownload(
            baseUrl = settings.baseUrl,
            apiKey = SecretsStore.mirrorApiKey,
            repo = repo,
            insecureTls = settings.mirrorInsecureTls,
            id = req.id,
            kind = MirrorDepsApi.DepsKind.MANIFEST,
            outFile = tmpManifest
          )
          if (dl.code !in 200..299 || dl.file == null) {
            notify(project, LocalGitMirrorBundle.message("deps.notify.requestDownloadFailed", dl.message), NotificationType.ERROR)
            return
          }

          val manifestBlob = tmpManifest.readBytes()

          val result = DepsResponder.respond(project, settings, syncPwd, repo, req.id, manifestBlob, indicator, dl.decrypted)

          if (result.success) {
            notify(project, result.message, NotificationType.INFORMATION)
            history.add("Deps respond", true,
              "request=${req.id} shipped=${result.sentCount} notFound=${result.notFoundCount} size=${humanBytes(result.bytes)}")
          } else {
            // The "0 found" case: do the detailed history logging that the core
            // deliberately leaves to the caller (UI concern).
            if (result.sentCount == 0 && result.notFoundCount > 0) {
              val scanned = DepsScanner.candidateCacheRoots()
              val gradleEnv = System.getenv("GRADLE_USER_HOME") ?: "(не задана)"
              val workProjectDir = project.basePath?.let { File(it) }
              val gradleReported = workProjectDir?.let {
                try { GradleResolver.discoverGradleUserHome(it) } catch (_: Throwable) { null }
              } ?: "(не определён)"

              history.add("Deps respond", false,
                LocalGitMirrorBundle.message("deps.history.zeroFound", result.notFoundCount, gradleEnv, gradleReported))
              history.add("Deps: кеши", false,
                LocalGitMirrorBundle.message("deps.history.scanned", scanned.size))
              scanned.forEach { root ->
                val exists = root.isDirectory
                val groups = if (exists) (root.listFiles { f -> f.isDirectory }?.size ?: 0) else 0
                val mark = if (exists) LocalGitMirrorBundle.message("deps.history.cacheOk", groups)
                           else LocalGitMirrorBundle.message("deps.history.cacheMissing")
                history.add("Deps: кеш-путь", exists, "$mark — ${root.absolutePath}")
              }

              // Re-scan for the notification message (same as original code)
              val scanReport = scanned.joinToString("\n") { root ->
                val exists = root.isDirectory
                val groups = if (exists) (root.listFiles { f -> f.isDirectory }?.size ?: 0) else 0
                "  • ${root.absolutePath} — " +
                  (if (exists) LocalGitMirrorBundle.message("deps.history.cacheReportOk", groups)
                   else LocalGitMirrorBundle.message("deps.history.cacheReportMissing"))
              }
              val msg = LocalGitMirrorBundle.message(
                "deps.notify.zeroFound",
                result.notFoundCount, scanReport, gradleEnv)
              notify(project, msg, NotificationType.WARNING)
            } else {
              notify(project, result.message, NotificationType.ERROR)
              history.add("Deps respond", false, result.message)
            }
          }
        } finally {
          runCatching { tmpManifest.delete() }
        }
      }
    })
  }
}


// ─────────────────────────────────────────────────────────────────────────────
// 3. ApplyDepsAction (DOME): fetch the response, unpack into each cache root
// ─────────────────────────────────────────────────────────────────────────────

class ApplyDepsAction : AnAction() {

  companion object {
    /**
     * Cached count of available responses from the last successful network call.
     * -1 = unknown → show unconditionally when configured.
     *  0 = known empty → hide.
     * >0 = known non-empty → show.
     */
    val lastKnownResponseCount = java.util.concurrent.atomic.AtomicInteger(-1)
  }

  override fun update(e: AnActionEvent) {
    LocalGitMirrorBundle.localizePresentation(e, "LocalGitMirror.DepsApply")
    val project = e.project
    if (project == null) { e.presentation.isEnabled = false; return }
    val settings = service<MirrorSettingsService>().state
    val configured = depsTransferAllowed(settings.baseUrl, SecretsStore.cached.syncPassword, MirrorCrypto.isV3Pinned())
    e.presentation.isEnabled = computeApplyEnabled(configured, lastKnownResponseCount.get())
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val settings = service<MirrorSettingsService>().state
    val syncPwd = SecretsStore.syncPassword
    if (!depsTransferAllowed(settings.baseUrl, syncPwd, MirrorCrypto.isV3Pinned())) {
      notify(project, LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val repo = resolveRepoName(project)
    val history = service<OperationsHistoryService>()
    runCatching { service<DepsAutomationService>().recordLocalEvent() }

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("deps.task.apply"), true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.text = LocalGitMirrorBundle.message("deps.progress.applyCheck")
        val list = MirrorDepsApi.depsResponses(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repo,
          insecureTls = settings.mirrorInsecureTls
        )
        if (list.code !in 200..299) {
          notify(project, LocalGitMirrorBundle.message("deps.notify.responsesListFailed", list.message), NotificationType.ERROR)
          return
        }
        lastKnownResponseCount.set(list.items.size)
        if (list.items.isEmpty()) {
          notify(project, LocalGitMirrorBundle.message("deps.notify.noResponses"), NotificationType.INFORMATION)
          return
        }

        val resp = list.items.first()
        val confirmed = com.intellij.util.ui.UIUtil.invokeAndWaitIfNeeded<Int> {
          Messages.showYesNoDialog(
            project,
            LocalGitMirrorBundle.message("deps.dialog.applyConfirm", resp.id.take(8), humanBytes(resp.size)),
            LocalGitMirrorBundle.message("deps.dialog.applyTitle"),
            LocalGitMirrorBundle.message("deps.dialog.applyYes"), LocalGitMirrorBundle.message("deps.dialog.cancel"), null
          )
        }
        if (confirmed != Messages.YES) return

        val tmpResp = File.createTempFile("tmp-", ".bin").apply { deleteOnExit() }
        try {
          indicator.text = LocalGitMirrorBundle.message("deps.progress.downloading", humanBytes(resp.size))
          indicator.isIndeterminate = false
          val dl = MirrorDepsApi.depsDownload(
            baseUrl = settings.baseUrl,
            apiKey = SecretsStore.mirrorApiKey,
            repo = repo,
            insecureTls = settings.mirrorInsecureTls,
            id = resp.id,
            kind = MirrorDepsApi.DepsKind.RESPONSE,
            outFile = tmpResp,
            onProgress = { read, total ->
              if (total > 0) {
                indicator.fraction = (read.toDouble() / total).coerceIn(0.0, 0.99)
                indicator.text = LocalGitMirrorBundle.message("deps.progress.downloadProgress", humanBytes(read), humanBytes(total))
              }
            }
          )
          if (dl.code !in 200..299 || dl.file == null) {
            notify(project, LocalGitMirrorBundle.message("deps.notify.responseDownloadFailed", dl.message), NotificationType.ERROR)
            return
          }

          val responseBlob = tmpResp.readBytes()

          val result = DepsApplier.apply(project, settings, syncPwd, responseBlob, indicator, dl.decrypted)

          if (!result.success) {
            notify(project, result.message, NotificationType.ERROR)
            history.add("Deps apply", false, "decrypt/unpack failed: ${result.message}")
            return
          }

          // a failed ack leaves the item on the server but must not mask the successful local apply
          val ack = MirrorDepsApi.depsAck(
            baseUrl = settings.baseUrl,
            apiKey = SecretsStore.mirrorApiKey,
            repo = repo,
            insecureTls = settings.mirrorInsecureTls,
            id = resp.id
          )
          if (ack.code !in 200..299) {
            history.add("Deps apply", true,
              "ack failed (HTTP ${ack.code}): response ${resp.id} left on server")
            notify(project, LocalGitMirrorBundle.message("deps.notify.ackFailed", ack.code), NotificationType.WARNING)
          }

          // Offer to run npm/yarn install if the core suggests it (action only;
          // the automation service skips this dialog).
          var finalMsg = result.message
          if (result.suggestYarnInstall) {
            val runIt = com.intellij.util.ui.UIUtil.invokeAndWaitIfNeeded<Int> {
              Messages.showYesNoDialog(
                project,
                LocalGitMirrorBundle.message("deps.dialog.yarnMessage", result.lockMsg),
                LocalGitMirrorBundle.message("deps.dialog.yarnTitle"),
                LocalGitMirrorBundle.message("deps.dialog.run"), LocalGitMirrorBundle.message("deps.dialog.later"), null
              )
            }
            if (runIt == Messages.YES) {
              indicator.text = LocalGitMirrorBundle.message("deps.progress.yarnInstall")
              val code = runCatching {
                val isWin = System.getProperty("os.name").lowercase().contains("win")
                val cmd = (if (isWin) listOf("cmd", "/c", "yarn") else listOf("yarn")) +
                  listOf("install", "--offline", "--pure-lockfile", "--non-interactive")
                runPackageManager(cmd, project.basePath?.let { File(it) }, indicator)
              }.getOrElse { -1 }
              finalMsg += if (code == 0) " " + LocalGitMirrorBundle.message("deps.yarnInstall.ok")
                          else " " + LocalGitMirrorBundle.message("deps.yarnInstall.code", code)
            } else {
              finalMsg += " " + LocalGitMirrorBundle.message("deps.yarnInstall.hint")
            }
          } else if (result.suggestNpmInstall) {
            val runIt = com.intellij.util.ui.UIUtil.invokeAndWaitIfNeeded<Int> {
              Messages.showYesNoDialog(
                project,
                LocalGitMirrorBundle.message("deps.dialog.npmMessage", result.lockMsg),
                LocalGitMirrorBundle.message("deps.dialog.npmTitle"),
                LocalGitMirrorBundle.message("deps.dialog.run"), LocalGitMirrorBundle.message("deps.dialog.later"), null
              )
            }
            if (runIt == Messages.YES) {
              indicator.text = LocalGitMirrorBundle.message("deps.progress.npmInstall")
              val code = runCatching {
                val isWin = System.getProperty("os.name").lowercase().contains("win")
                val cmd = (if (isWin) listOf("cmd", "/c", "npm") else listOf("npm")) +
                  listOf("install", "--prefer-offline", "--registry",
                         "https://registry.npmjs.org", "--no-audit", "--no-fund")
                runPackageManager(cmd, project.basePath?.let { File(it) }, indicator)
              }.getOrElse { -1 }
              finalMsg += if (code == 0) " " + LocalGitMirrorBundle.message("deps.npmInstall.ok")
                          else " " + LocalGitMirrorBundle.message("deps.npmInstall.code", code)
            } else {
              finalMsg += " " + LocalGitMirrorBundle.message("deps.npmInstall.hint")
            }
          }

          notify(project, finalMsg, NotificationType.INFORMATION)
          history.add("Deps apply", true,
            "installed=${result.installed} skipped=${result.skipped} invalid=${result.invalid} size=${humanBytes(result.bytes)}")
        } finally {
          runCatching { tmpResp.delete() }
        }
      }
    })
  }
}

private const val PKG_MGR_TIMEOUT_MS = 10 * 60_000L

/** Run yarn/npm with output drained off-thread; kills the process on cancel or after the timeout. */
private fun runPackageManager(cmd: List<String>, workDir: File?, indicator: ProgressIndicator): Int {
  val proc = try {
    ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true).start()
  } catch (_: Throwable) {
    return -1
  }
  Thread { runCatching { proc.inputStream.bufferedReader().forEachLine { } } }
    .apply { isDaemon = true }.start()
  val deadline = System.currentTimeMillis() + PKG_MGR_TIMEOUT_MS
  while (!proc.waitFor(200, TimeUnit.MILLISECONDS)) {
    if (indicator.isCanceled) {
      proc.destroyForcibly()
      return 130
    }
    if (System.currentTimeMillis() > deadline) {
      proc.destroyForcibly()
      return 124
    }
  }
  return proc.exitValue()
}
