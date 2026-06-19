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
import localgitmirror.idea.workkit.BundleCrypto
import java.io.File
import java.text.DecimalFormat

internal fun humanBytes(b: Long): String {
  if (b < 1024) return "$b B"
  val units = arrayOf("KB", "MB", "GB", "TB")
  var v = b.toDouble() / 1024
  var i = 0
  while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
  return DecimalFormat("0.#").format(v) + " " + units[i]
}

private fun resolveRepoName(project: Project, settings: MirrorSettingsService.State): String {
  val dir = project.basePath?.let { File(it) } ?: File(".")
  return localgitmirror.idea.sync.v2.RepoResolver
    .resolve(project, dir, settings.repo)
    .sanitized
    .ifBlank { project.name }
}

private fun notify(project: Project, msg: String, type: NotificationType) {
  NotificationGroupManager.getInstance()
    .getNotificationGroup("LocalGitMirror")
    .createNotification(msg, type)
    .notify(project)
}

private fun projectJdkHome(project: Project): String? = try {
  com.intellij.openapi.roots.ProjectRootManager.getInstance(project).projectSdk?.homePath
} catch (_: Throwable) { null }

/**
 * STEALTH cleanup: earlier versions wrote `.lgm-deps-debug.txt`,
 * `.lgm-last-sent-deps.txt` and `.lgm-last-deps.txt` into the project root.
 * Those leak corporate package names into the git working tree. Remove any
 * that linger. Best-effort; never throws.
 */
private fun sweepLegacyDepsFiles(projectDir: File) {
  listOf(".lgm-deps-debug.txt", ".lgm-last-sent-deps.txt", ".lgm-last-deps.txt").forEach {
    runCatching { File(projectDir, it).takeIf { f -> f.exists() }?.delete() }
  }
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
    val repo = resolveRepoName(project, settings)
    val history = service<OperationsHistoryService>()
    val projectDir = project.basePath?.let { File(it) } ?: File(".")
    val jdkHome = projectJdkHome(project)

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Запрос недостающих зависимостей", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        sweepLegacyDepsFiles(projectDir)
        val ecosystems = DepsEcosystems.detect(projectDir)
        if (ecosystems.isEmpty()) {
          notify(project, "Не найден ни gradle, ни npm проект в ${projectDir.name}.", NotificationType.WARNING)
          return
        }

        val allMissing = mutableListOf<DepCoordinate>()
        val logs = StringBuilder()
        for (eco in ecosystems) {
          indicator.text = "Определяем недостающие ${eco.id}-зависимости…"
          val r = eco.resolveMissing(projectDir, jdkHome)
          logs.appendLine("[${eco.id}] ok=${r.ok} missing=${r.missing.size} (${r.durationMs}ms)")
          if (!r.ok && r.missing.isEmpty()) {
            logs.appendLine("  ! ${r.log.takeLast(300)}")
          }
          allMissing.addAll(r.missing)
        }
        val missing = allMissing.distinctBy { it.key }

        // Sync the stealth toggle, then emit diagnostics to the IDE log / opt-in
        // file under the IDE log dir — NEVER into the project tree.
        DepsDiagnostics.enabled = settings.depsDiagnosticsEnabled
        DepsDiagnostics.verbose = settings.depsDiagnosticsVerbose
        DepsDiagnostics.event("request: ecosystems=${ecosystems.joinToString(",") { it.id }} missing=${missing.size}")
        for (line in logs.lineSequence()) if (line.isNotBlank()) DepsDiagnostics.event(line.trim())
        DepsDiagnostics.detail("Missing coordinates") { missing.map { "${it.ecosystem}  ${it.label}" } }

        if (missing.isEmpty()) {
          notify(project,
            "Всё резолвится локально — запрашивать нечего.",
            NotificationType.INFORMATION)
          history.add("Deps request", true, "nothing missing (${ecosystems.joinToString(",") { it.id }})")
          return
        }

        val manifest = DepsRequestManifest(
          version = 2,
          requester = System.getProperty("user.name") ?: "dome",
          project = repo,
          ecosystem = ecosystems.joinToString(",") { it.id },
          missing = missing
        )
        val encrypted = BundleCrypto.encryptBundleBytes(DepsRequestManifest.toJsonBytes(manifest), syncPwd)

        indicator.text = "Отправляем запрос (${missing.size} координат)…"
        val res = MirrorApi.depsRequest(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repo,
          insecureTls = settings.mirrorInsecureTls,
          encryptedManifest = encrypted
        )
        if (res.code !in 200..299 || res.id == null) {
          val msg = "Запрос не отправлен (${res.code}): ${res.message}"
          notify(project, msg, NotificationType.ERROR)
          history.add("Deps request", false, msg)
          return
        }
        notify(project,
          "Запрошено ${missing.size} недостающих зависимостей (${manifest.ecosystem}).\n" +
            "На рабочей машине нажми «Выдать запрошенные зависимости».",
          NotificationType.INFORMATION)
        history.add("Deps request", true, "repo='$repo' id=${res.id} missing=${missing.size} eco=${manifest.ecosystem}")
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
    val repo = resolveRepoName(project, settings)
    val history = service<OperationsHistoryService>()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Выдать запрошенные зависимости", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        project.basePath?.let { sweepLegacyDepsFiles(File(it)) }
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
        // Update visibility cache so update() knows the current count
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
        val tmpManifest = File.createTempFile("lgm-req-", ".bin").apply { deleteOnExit() }
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

        val manifest = try {
          DepsRequestManifest.fromJsonBytes(BundleCrypto.decryptDumpBytes(tmpManifest.readBytes(), syncPwd))
        } catch (t: Throwable) {
          notify(project, "Ошибка расшифровки запроса: ${t.message ?: t::class.simpleName}", NotificationType.ERROR)
          return
        } finally {
          runCatching { tmpManifest.delete() }
        }

        if (manifest.version < 2 || manifest.missing.isEmpty()) {
          notify(project,
            "Запрос пуст или в старом формате (v${manifest.version}). " +
              "Обнови плагин на домашней машине и повтори «Запросить».",
            NotificationType.WARNING)
          history.add("Deps respond", false, "empty/legacy manifest v${manifest.version}")
          return
        }

        // Collect requested coordinates from local caches, grouped by ecosystem.
        indicator.text = "Ищем ${manifest.missing.size} координат в локальном кеше…"
        val byEco = manifest.missing.groupBy { it.ecosystem }
        val entries = mutableListOf<DepFileEntry>()
        val notFound = mutableListOf<DepCoordinate>()
        for ((ecoId, coords) in byEco) {
          val eco = DepsEcosystems.byId(ecoId)
          if (eco == null) { notFound.addAll(coords); continue }
          entries.addAll(eco.collect(coords) { notFound.add(it) })
        }

        if (entries.isEmpty()) {
          notify(project,
            "Ни одна из ${manifest.missing.size} запрошенных зависимостей не найдена в локальном кеше.\n" +
              "Собери проект на рабочей машине (gradle build / npm install), чтобы они попали в кеш.",
            NotificationType.WARNING)
          history.add("Deps respond", false, "0 of ${manifest.missing.size} found in cache")
          return
        }

        // Pack with ecosystem-prefixed entry names so the dome can route them.
        val prefixed = entries.map {
          it.copy(relativePath = "${it.coordinate.ecosystem}/${it.relativePath}")
        }
        val diffSize = prefixed.sumOf { it.size }
        indicator.text = "Упаковываем ${prefixed.size} файлов (${humanBytes(diffSize)})…"
        val zipBytes = DepsBundler.packEntries(prefixed)
        val encrypted = BundleCrypto.encryptBundleBytes(zipBytes, syncPwd)

        indicator.text = "Отправляем (${humanBytes(encrypted.size.toLong())})…"
        val res = MirrorApi.depsRespond(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repo,
          insecureTls = settings.mirrorInsecureTls,
          requestId = req.id,
          encryptedArchive = encrypted
        )
        if (res.code !in 200..299 || res.id == null) {
          val msg = "Не отправлено (${res.code}): ${res.message}"
          notify(project, msg, NotificationType.ERROR)
          history.add("Deps respond", false, msg)
          return
        }

        val foundCoords = entries.map { it.coordinate.key }.toSet()
        val notFoundUnique = notFound.distinctBy { it.key }
        DepsDiagnostics.enabled = settings.depsDiagnosticsEnabled
        DepsDiagnostics.verbose = settings.depsDiagnosticsVerbose
        DepsDiagnostics.event("respond: shipped=${foundCoords.size} notFound=${notFoundUnique.size} bytes=$diffSize")
        DepsDiagnostics.detail("Shipped coordinates") {
          entries.map { it.coordinate.label }.distinct().sorted()
        }
        if (notFoundUnique.isNotEmpty()) {
          DepsDiagnostics.detail("Requested but NOT in local cache") {
            notFoundUnique.map { "${it.ecosystem}  ${it.label}" }
          }
        }

        val warn = if (notFoundUnique.isNotEmpty()) " (не найдено ${notFoundUnique.size})" else ""
        notify(project,
          "Отправлено ${humanBytes(diffSize)} — ${foundCoords.size} зависимостей$warn.",
          NotificationType.INFORMATION)
        history.add("Deps respond", true,
          "request=${req.id} shipped=${foundCoords.size} notFound=${notFoundUnique.size} size=${humanBytes(diffSize)}")
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
    val repo = resolveRepoName(project, settings)
    val history = service<OperationsHistoryService>()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Применить полученные deps", true) {
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
        // Update visibility cache so update() knows the current count
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
            "LocalGitMirror: Применить deps",
            "Применить", "Отмена", null
          )
        }
        if (confirmed != Messages.YES) return

        val tmpResp = File.createTempFile("lgm-deps-resp-", ".bin").apply { deleteOnExit() }
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

        indicator.isIndeterminate = true
        indicator.text = "Расшифровка и распаковка…"
        val unpackResult = try {
          val decrypted = BundleCrypto.decryptDumpBytes(tmpResp.readBytes(), syncPwd)
          DepsBundler.unpackRouted(decrypted) { ecoId -> DepsEcosystems.byId(ecoId)?.cacheRoot() }
        } catch (t: Throwable) {
          notify(project, "Ошибка применения: ${t.message ?: t::class.simpleName}", NotificationType.ERROR)
          history.add("Deps apply", false, "decrypt/unpack failed: ${t.message}")
          return
        } finally {
          runCatching { tmpResp.delete() }
        }

        MirrorApi.depsAck(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repo,
          insecureTls = settings.mirrorInsecureTls,
          id = resp.id
        )

        // Best-effort per-ecosystem post-install (e.g. npm cache add). The
        // unpack stored display names, but postInstall needs cache-relative
        // paths, so recompute them from the npm mirror tree.
        val postStatus = StringBuilder()
        runCatching {
          val npmMirrorRoot = NpmEcosystem.cacheRoot()
          if (npmMirrorRoot.isDirectory) {
            val tgzRel = npmMirrorRoot.walkTopDown()
              .filter { it.isFile && it.extension == "tgz" }
              .map { it.relativeTo(npmMirrorRoot).path.replace('\\', '/') }
              .toList()
            if (tgzRel.isNotEmpty()) {
              val s = NpmEcosystem.postInstall(tgzRel)
              if (s.isNotBlank()) postStatus.append(s)
            }
          }
        }

        val npmMirror = NpmEcosystem.cacheRoot()
        val npmInstalled = unpackResult.installedEntries.any { it.endsWith(".tgz") }

        DepsDiagnostics.enabled = settings.depsDiagnosticsEnabled
        DepsDiagnostics.verbose = settings.depsDiagnosticsVerbose
        DepsDiagnostics.event("apply: installed=${unpackResult.installed} skipped=${unpackResult.skipped} invalid=${unpackResult.invalid} bytes=${unpackResult.totalBytes}")
        DepsDiagnostics.detail("Installed") { unpackResult.installedEntries }
        if (unpackResult.skippedEntries.isNotEmpty()) {
          DepsDiagnostics.detail("Already present") { unpackResult.skippedEntries }
        }

        val msg = buildString {
          append("Применено: ${unpackResult.installed} установлено, ${unpackResult.skipped} уже было")
          if (unpackResult.invalid > 0) append(", ${unpackResult.invalid} отклонено")
          append(" (${humanBytes(unpackResult.totalBytes)}).")
          if (npmInstalled) {
            appendLine(); appendLine()
            if (postStatus.isNotEmpty()) {
              append("npm: $postStatus")
            } else {
              append("npm-тарболы: ${npmMirror.absolutePath}\nставь из этой папки (npm install --offline).")
            }
          }
        }
        notify(project, msg, NotificationType.INFORMATION)
        history.add("Deps apply", true,
          "installed=${unpackResult.installed} skipped=${unpackResult.skipped} invalid=${unpackResult.invalid} size=${humanBytes(unpackResult.totalBytes)}")
      }
    })
  }
}
