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
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import java.io.File
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
    val configured = settings.baseUrl.isNotBlank() && SecretsStore.syncPassword.isNotBlank()
    val dir = project.basePath?.let { java.io.File(it) } ?: java.io.File(".")
    val hasEcosystem = configured && DepsEcosystems.detect(dir).isNotEmpty()
    e.presentation.isEnabled = hasEcosystem
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val settings = service<MirrorSettingsService>().state
    val syncPwd = SecretsStore.syncPassword
    if (settings.baseUrl.isBlank() || syncPwd.isBlank()) {
      notify(project, LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val repo = resolveRepoName(project)
    val history = service<OperationsHistoryService>()
    service<DepsAutomationService>().recordLocalEvent()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Запрос недостающих зависимостей", true) {
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
    val configured = settings.baseUrl.isNotBlank() && SecretsStore.syncPassword.isNotBlank()
    e.presentation.isEnabled = computeRespondEnabled(configured, lastKnownPendingCount.get())
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val settings = service<MirrorSettingsService>().state
    val syncPwd = SecretsStore.syncPassword
    if (settings.baseUrl.isBlank() || syncPwd.isBlank()) {
      notify(project, LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val repo = resolveRepoName(project)
    val history = service<OperationsHistoryService>()
    service<DepsAutomationService>().recordLocalEvent()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Выдать запрошенные зависимости", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        indicator.text = "Проверяем запросы для repo='$repo'…"
        val pending = MirrorApi.depsPending(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repo,
          insecureTls = settings.mirrorInsecureTls
        )
        if (pending.code !in 200..299) {
          notify(project, "Не удалось получить список запросов: ${pending.message}", NotificationType.ERROR)
          return
        }
        lastKnownPendingCount.set(pending.items.size)
        if (pending.items.isEmpty()) {
          notify(project,
            "Нет запросов для repo='$repo'.\n" +
              "Проверь, что имя репозитория в настройках совпадает на обеих машинах.",
            NotificationType.WARNING)
          history.add("Deps respond", false, "no pending for repo='$repo'")
          return
        }

        val req = pending.items.first()
        indicator.text = "Скачиваем запрос ${req.id.take(8)}…"
        val tmpManifest = File.createTempFile("tmp-", ".bin").apply { deleteOnExit() }
        try {
          val dl = MirrorApi.depsDownload(
            baseUrl = settings.baseUrl,
            apiKey = SecretsStore.mirrorApiKey,
            repo = repo,
            insecureTls = settings.mirrorInsecureTls,
            id = req.id,
            kind = MirrorApi.DepsKind.MANIFEST,
            outFile = tmpManifest
          )
          if (dl.code !in 200..299 || dl.file == null) {
            notify(project, "Не удалось скачать запрос: ${dl.message}", NotificationType.ERROR)
            return
          }

          val manifestBlob = tmpManifest.readBytes()

          val result = DepsResponder.respond(project, settings, syncPwd, repo, req.id, manifestBlob, indicator)

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
                "0 из ${result.notFoundCount} найдено. GRADLE_USER_HOME=$gradleEnv | gradle сообщил=$gradleReported")
              history.add("Deps: кеши", false,
                "Просканировано ${scanned.size} кеш-путей (см. ниже)")
              scanned.forEach { root ->
                val exists = root.isDirectory
                val groups = if (exists) (root.listFiles { f -> f.isDirectory }?.size ?: 0) else 0
                val mark = if (exists) "OK, групп=$groups" else "НЕТ такой папки"
                history.add("Deps: кеш-путь", exists, "$mark — ${root.absolutePath}")
              }

              // Re-scan for the notification message (same as original code)
              val scanReport = scanned.joinToString("\n") { root ->
                val exists = root.isDirectory
                val groups = if (exists) (root.listFiles { f -> f.isDirectory }?.size ?: 0) else 0
                "  • ${root.absolutePath} — ${if (exists) "есть ($groups групп)" else "НЕТ"}"
              }
              val msg = buildString {
                appendLine("Ни одна из ${result.notFoundCount} зависимостей не найдена в кеше.")
                appendLine("Подробности записаны в Историю (панель плагина).")
                appendLine()
                appendLine("Искал в:")
                appendLine(scanReport)
                appendLine()
                append("GRADLE_USER_HOME = $gradleEnv")
              }
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
    val configured = settings.baseUrl.isNotBlank() && SecretsStore.syncPassword.isNotBlank()
    e.presentation.isEnabled = computeApplyEnabled(configured, lastKnownResponseCount.get())
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val settings = service<MirrorSettingsService>().state
    val syncPwd = SecretsStore.syncPassword
    if (settings.baseUrl.isBlank() || syncPwd.isBlank()) {
      notify(project, LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val repo = resolveRepoName(project)
    val history = service<OperationsHistoryService>()
    service<DepsAutomationService>().recordLocalEvent()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Применить полученные deps", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.text = "Проверяем готовые ответы…"
        val list = MirrorApi.depsResponses(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repo,
          insecureTls = settings.mirrorInsecureTls
        )
        if (list.code !in 200..299) {
          notify(project, "Не удалось получить список: ${list.message}", NotificationType.ERROR)
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
            "Готов ответ ${resp.id.take(8)} (${humanBytes(resp.size)}). Применить?",
            "DocCache: Применить deps",
            "Применить", "Отмена", null
          )
        }
        if (confirmed != Messages.YES) return

        val tmpResp = File.createTempFile("tmp-", ".bin").apply { deleteOnExit() }
        try {
          indicator.text = "Скачивание (${humanBytes(resp.size)})…"
          indicator.isIndeterminate = false
          val dl = MirrorApi.depsDownload(
            baseUrl = settings.baseUrl,
            apiKey = SecretsStore.mirrorApiKey,
            repo = repo,
            insecureTls = settings.mirrorInsecureTls,
            id = resp.id,
            kind = MirrorApi.DepsKind.RESPONSE,
            outFile = tmpResp,
            onProgress = { read, total ->
              if (total > 0) {
                indicator.fraction = (read.toDouble() / total).coerceIn(0.0, 0.99)
                indicator.text = "Скачивание ${humanBytes(read)} / ${humanBytes(total)}"
              }
            }
          )
          if (dl.code !in 200..299 || dl.file == null) {
            notify(project, "Не скачалось: ${dl.message}", NotificationType.ERROR)
            return
          }

          val responseBlob = tmpResp.readBytes()

          val result = DepsApplier.apply(project, settings, syncPwd, responseBlob, indicator)

          if (!result.success) {
            notify(project, result.message, NotificationType.ERROR)
            history.add("Deps apply", false, "decrypt/unpack failed: ${result.message}")
            return
          }

          // Ack the response on the server (one-shot: server deletes it).
          MirrorApi.depsAck(
            baseUrl = settings.baseUrl,
            apiKey = SecretsStore.mirrorApiKey,
            repo = repo,
            insecureTls = settings.mirrorInsecureTls,
            id = resp.id
          )

          // Offer to run npm/yarn install if the core suggests it (action only;
          // the automation service skips this dialog).
          var finalMsg = result.message
          if (result.suggestYarnInstall) {
            val runIt = com.intellij.util.ui.UIUtil.invokeAndWaitIfNeeded<Int> {
              Messages.showYesNoDialog(
                project,
                "${result.lockMsg}\nЗапустить yarn install --offline сейчас? (публичное из кеша yarn, корпоративное из mirror)",
                "DocCache: yarn install", "Запустить", "Позже", null
              )
            }
            if (runIt == Messages.YES) {
              indicator.text = "yarn install --offline…"
              val code = runCatching {
                val isWin = System.getProperty("os.name").lowercase().contains("win")
                val cmd = (if (isWin) listOf("cmd", "/c", "yarn") else listOf("yarn")) +
                  listOf("install", "--offline", "--pure-lockfile", "--non-interactive")
                val applyProj = project.basePath?.let { File(it) }
                val proc = ProcessBuilder(cmd).directory(applyProj).redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().forEachLine { /* drain */ }
                proc.waitFor()
              }.getOrElse { -1 }
              finalMsg += if (code == 0) " yarn install: OK."
                          else " yarn install: код $code (если не хватает публичного — один онлайн yarn install, дальше офлайн)."
            } else {
              finalMsg += " Запусти: yarn install --offline --pure-lockfile"
            }
          } else if (result.suggestNpmInstall) {
            val runIt = com.intellij.util.ui.UIUtil.invokeAndWaitIfNeeded<Int> {
              Messages.showYesNoDialog(
                project,
                "${result.lockMsg}\nЗапустить npm install сейчас? (публичное с npmjs, корпоративное из кеша)",
                "DocCache: npm install", "Запустить", "Позже", null
              )
            }
            if (runIt == Messages.YES) {
              indicator.text = "npm install…"
              val code = runCatching {
                val isWin = System.getProperty("os.name").lowercase().contains("win")
                val cmd = (if (isWin) listOf("cmd", "/c", "npm") else listOf("npm")) +
                  listOf("install", "--prefer-offline", "--registry",
                         "https://registry.npmjs.org", "--no-audit", "--no-fund")
                val applyProj = project.basePath?.let { File(it) }
                val proc = ProcessBuilder(cmd).directory(applyProj).redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().forEachLine { /* drain */ }
                proc.waitFor()
              }.getOrElse { -1 }
              finalMsg += if (code == 0) " npm install: OK." else " npm install: код $code (повтори вручную)."
            } else {
              finalMsg += " Запусти: npm install --prefer-offline --registry https://registry.npmjs.org"
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
