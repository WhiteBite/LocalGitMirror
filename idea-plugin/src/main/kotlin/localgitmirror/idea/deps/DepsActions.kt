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

private fun humanBytes(b: Long): String {
  if (b < 1024) return "$b B"
  val units = arrayOf("KB", "MB", "GB", "TB")
  var v = b.toDouble() / 1024
  var i = 0
  while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
  return DecimalFormat("0.#").format(v) + " " + units[i]
}

private fun resolveRepoName(project: Project, settings: MirrorSettingsService.State): String =
  settings.repo.ifBlank { project.name }

private fun notify(project: Project, msg: String, type: NotificationType) {
  NotificationGroupManager.getInstance()
    .getNotificationGroup("LocalGitMirror")
    .createNotification(msg, type)
    .notify(project)
}

private fun parseInternalRepos(settings: MirrorSettingsService.State): List<String> =
  settings.internalRepos
    .split(',', '\n')
    .map { it.trim() }
    .filter { it.isNotBlank() }

/**
 * Resolve the effective list of "internal" repo substrings to filter the
 * artifact diff with.
 *
 *   1. If user filled `Internal repos` in Settings, that wins (manual override).
 *   2. Otherwise scan the project's gradle files and auto-detect.
 *   3. If nothing was found either way, return empty list (= no filter).
 *
 * Returns Pair(substrings, source) so the UI can tell the user where the
 * list came from ("manual" / "auto" / "none").
 */
private fun resolveInternalRepos(
  settings: MirrorSettingsService.State,
  projectDir: File
): Pair<List<String>, String> {
  val manual = parseInternalRepos(settings)
  if (manual.isNotEmpty()) return manual to "manual"
  val detected = RepoDetector.detect(projectDir)
  if (detected.internalSubstrings.isNotEmpty()) {
    return detected.internalSubstrings to "auto(${detected.sources.size} gradle files)"
  }
  return emptyList<String>() to "none"
}


// ─────────────────────────────────────────────────────────────────────────────
// 1. RequestDepsAction (dome side): scan local cache, send manifest
// ─────────────────────────────────────────────────────────────────────────────

class RequestDepsAction : AnAction() {
  override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }

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

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Запрос gradle-зависимостей", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.text = LocalGitMirrorBundle.message("deps.notify.scanStart")
        indicator.isIndeterminate = true

        val artifacts = DepsScanner.scan()
        val totalSize = artifacts.sumOf { it.size }
        notify(
          project,
          LocalGitMirrorBundle.message("deps.notify.scanResult", artifacts.size.toString(), humanBytes(totalSize)),
          NotificationType.INFORMATION
        )

        val manifest = DepsManifest.fromArtifacts(
          requester = System.getProperty("user.name") ?: "dome",
          project = repo,
          artifacts = artifacts
        )
        val plain = DepsManifest.toJsonBytes(manifest)
        val encrypted = BundleCrypto.encryptBundleBytes(plain, syncPwd)

        indicator.text = "Отправляем манифест (${humanBytes(encrypted.size.toLong())})…"
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
        notify(project, LocalGitMirrorBundle.message("deps.notify.requestSent"), NotificationType.INFORMATION)
        history.add("Deps request", true, "repo='$repo' id=${res.id} artifacts=${artifacts.size} size=${humanBytes(totalSize)}")
      }
    })
  }
}


// ─────────────────────────────────────────────────────────────────────────────
// 2. RespondDepsAction (work side): resolve diff, send archive
// ─────────────────────────────────────────────────────────────────────────────

class RespondDepsAction : AnAction() {
  override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }

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

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Ответ на запрос deps", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.text = "Проверяем pending-запросы для repo='$repo'…"
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
        if (pending.items.isEmpty()) {
          // Helpful hint: the repo name on this machine probably doesn't match
          // the one used by the dome side when posting the request.
          val msg = "Нет ожидающих запросов для repo='$repo'.\n" +
            "Проверь Settings → LocalGitMirror → Mirror репозиторий: " +
            "имя должно совпадать с тем, под которым домашняя машина " +
            "отправила запрос (обычно имя проекта)."
          notify(project, msg, NotificationType.WARNING)
          history.add("Deps respond", false, "no pending for repo='$repo'")
          return
        }

        // Take the freshest request (the list is already newest-first).
        val req = pending.items.first()
        indicator.text = "Скачиваем манифест ${req.id.take(8)}…"
        val tmpManifest = File.createTempFile("lgm-manifest-", ".bin").apply { deleteOnExit() }
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
          notify(project, "Не удалось скачать манифест: ${dl.message}", NotificationType.ERROR)
          return
        }

        val manifest = try {
          val decoded = BundleCrypto.decryptDumpBytes(tmpManifest.readBytes(), syncPwd)
          DepsManifest.fromJsonBytes(decoded)
        } catch (t: Throwable) {
          notify(project, "Ошибка расшифровки манифеста: ${t.message ?: t::class.simpleName}", NotificationType.ERROR)
          return
        } finally {
          try { tmpManifest.delete() } catch (_: Exception) {}
        }

        indicator.text = "Сканируем локальный gradle-кеш…"
        val workArtifacts = DepsScanner.scan()
        val projectDir = project.basePath?.let { File(it) } ?: File(".")

        // ── Strategy A: ask Gradle directly which artifacts the project needs.
        // This catches plugin classpath (com.diffplug.spotless, etc.) and any
        // artifact whose origin sidecar is missing — a heuristic filter can't.
        indicator.text = "Спрашиваем у Gradle что нужно проекту…"
        val projectJdkHome = try {
          val sdk = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).projectSdk
          sdk?.homePath
        } catch (_: Throwable) { null }
        val gradleResult = GradleResolver.resolve(projectDir, javaHome = projectJdkHome)
        val toShip: List<DepsScanner.Artifact>
        val filterText: String

        if (gradleResult.ok && gradleResult.artifacts.isNotEmpty()) {
          // Build a key set from gradle's resolved list (group:name:version) — these
          // are what the project actually needs to build/run.
          val neededKeys = gradleResult.artifacts.map { "${it.g}:${it.n}:${it.v}" }.toSet()

          // Also remember the absolute paths gradle reported, so we can ALSO ship
          // the exact files (matched by path against our scan of the cache).
          val neededPaths = gradleResult.artifacts.map { it.f.replace('\\', '/').lowercase() }.toSet()

          // Dome's manifest tells us what it already has — by g:n:v:sha:filename.
          // We DON'T compare paths between machines because absolute paths differ
          // (C:\Users\dome\... vs C:\Users\work\...). We compare on identity.
          // For "does dome have it" we strip filename/sha because the same artifact
          // might have been downloaded with a different sha if released twice.
          val domeHaveGNV = manifest.artifacts.map { "${it.g}:${it.n}:${it.v}" }.toSet()

          toShip = workArtifacts.filter { art ->
            val gnv = "${art.group}:${art.name}:${art.version}"
            val pathLow = art.absolutePath.replace('\\', '/').lowercase()
            // Project needs it (by gnv OR exact path match) AND dome doesn't have it (by gnv)
            (gnv in neededKeys || pathLow in neededPaths) && gnv !in domeHaveGNV
          }
          filterText = "gradle: ${gradleResult.artifacts.size} resolved, ${toShip.size} новых для дома (за ${gradleResult.durationMs} мс)"

          // Diagnostic: write a debug file so we can see WHY toShip is whatever
          // it is, especially when it's 0 but the dome side is still missing things.
          try {
            val debug = buildString {
              appendLine("# LocalGitMirror — deps respond diagnostic")
              appendLine("# date: ${java.time.LocalDateTime.now()}")
              appendLine("# gradle resolved: ${gradleResult.artifacts.size}")
              appendLine("# work cache size: ${workArtifacts.size}")
              appendLine("# dome manifest size: ${manifest.artifacts.size}")
              appendLine("# to ship: ${toShip.size}")
              appendLine()
              appendLine("## Gradle resolved (first 30):")
              gradleResult.artifacts.take(30).forEach { appendLine("  ${it.g}:${it.n}:${it.v}  ->  ${it.f}") }
              appendLine()
              val neededOnly = neededKeys.toList()
              val domeMissing = neededOnly.filter { it !in domeHaveGNV }
              appendLine("## Needed but NOT in dome's manifest (${domeMissing.size}):")
              domeMissing.take(50).forEach { appendLine("  $it") }
              appendLine()
              val workHas = workArtifacts.map { "${it.group}:${it.name}:${it.version}" }.toSet()
              val needNotInWorkCache = neededKeys.filter { it !in workHas }
              appendLine("## Needed but NOT in work cache (${needNotInWorkCache.size}):")
              needNotInWorkCache.take(50).forEach { appendLine("  $it") }
            }
            File(projectDir, ".lgm-deps-debug.txt").writeText(debug, Charsets.UTF_8)
          } catch (_: Exception) {}
        } else {
          // Fallback to origin/substring filter
          val (internal, internalSource) = resolveInternalRepos(settings, projectDir)
          toShip = DepsDiff.compute(workArtifacts, manifest, internal)
          filterText = if (internal.isEmpty())
            "fallback: всё что у дома отсутствует"
          else
            "fallback: ${internal.joinToString(",")} ($internalSource)"
          if (!gradleResult.ok) {
            history.add("Deps respond", false, "gradle resolve failed: ${gradleResult.log.takeLast(300)}")
          }
        }
        val diffSize = toShip.sumOf { it.size }
        notify(
          project,
          LocalGitMirrorBundle.message("deps.notify.diffComputed", toShip.size.toString(), humanBytes(diffSize), filterText),
          NotificationType.INFORMATION
        )
        if (toShip.isEmpty()) {
          history.add("Deps respond", true, "nothing to send (filter=$filterText)")
          notify(project, "У дома уже есть всё нужное (фильтр: $filterText).", NotificationType.INFORMATION)
          return
        }

        indicator.text = "Упаковываем ${toShip.size} артефактов (${humanBytes(diffSize)})…"
        val zipBytes = DepsBundler.pack(toShip)
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
        notify(
          project,
          LocalGitMirrorBundle.message("deps.notify.responseSent", humanBytes(diffSize)),
          NotificationType.INFORMATION
        )

        // Persist the list of shipped artifacts so the user can audit later.
        val sentList = buildString {
          appendLine("# LocalGitMirror — gradle deps sent to dome")
          appendLine("# date: ${java.time.LocalDateTime.now()}")
          appendLine("# request_id: ${req.id}")
          appendLine("# total_bytes: $diffSize")
          appendLine("# strategy: $filterText")
          appendLine()
          appendLine("## Shipped (${toShip.size})")
          toShip.forEach { appendLine("  ${it.group}:${it.name}:${it.version}") }
        }
        val sentFile = File(projectDir, ".lgm-last-sent-deps.txt")
        try { sentFile.writeText(sentList, Charsets.UTF_8) } catch (_: Exception) {}

        history.add("Deps respond", true,
          "request=${req.id} artifacts=${toShip.size} size=${humanBytes(diffSize)} list=$sentFile")
      }
    })
  }
}


// ─────────────────────────────────────────────────────────────────────────────
// 3. ApplyDepsAction (dome side): fetch latest response, unpack, ack
// ─────────────────────────────────────────────────────────────────────────────

class ApplyDepsAction : AnAction() {
  override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }

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
        if (list.items.isEmpty()) {
          notify(project, LocalGitMirrorBundle.message("deps.notify.noResponses"), NotificationType.INFORMATION)
          return
        }

        // Confirm before downloading multi-MB archive
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
          DepsBundler.unpackInto(decrypted)
        } catch (t: Throwable) {
          notify(project, "Ошибка применения: ${t.message ?: t::class.simpleName}", NotificationType.ERROR)
          history.add("Deps apply", false, "decrypt/unpack failed: ${t.message}")
          return
        } finally {
          try { tmpResp.delete() } catch (_: Exception) {}
        }

        // Tell the server it's safe to delete the response blob
        MirrorApi.depsAck(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repo,
          insecureTls = settings.mirrorInsecureTls,
          id = resp.id
        )

        val ok = LocalGitMirrorBundle.message(
          "deps.notify.appliedOk",
          unpackResult.installed.toString(),
          humanBytes(unpackResult.totalBytes),
          unpackResult.skipped.toString()
        )

        // Save the install list so the user can see exactly what landed
        val installManifest = buildString {
          appendLine("# LocalGitMirror — installed gradle deps")
          appendLine("# date: ${java.time.LocalDateTime.now()}")
          appendLine("# response_id: ${resp.id}")
          appendLine("# total_bytes: ${unpackResult.totalBytes}")
          appendLine()
          appendLine("## Installed (${unpackResult.installed})")
          unpackResult.installedEntries.forEach { appendLine("  $it") }
          if (unpackResult.skippedEntries.isNotEmpty()) {
            appendLine()
            appendLine("## Already up-to-date (${unpackResult.skipped})")
            unpackResult.skippedEntries.forEach { appendLine("  $it") }
          }
        }
        val manifestFile = File(project.basePath ?: ".", ".lgm-last-deps.txt")
        try { manifestFile.writeText(installManifest, Charsets.UTF_8) } catch (_: Exception) {}

        // Notification with a "View list" action
        val previewLines = unpackResult.installedEntries.take(10).joinToString("\n  • ", prefix = "  • ")
        val moreCount = (unpackResult.installedEntries.size - 10).coerceAtLeast(0)
        val notifMsg = buildString {
          append(ok)
          appendLine()
          appendLine()
          if (unpackResult.installedEntries.isNotEmpty()) {
            appendLine("Установлены:")
            append(previewLines)
            if (moreCount > 0) append("\n  … и ещё $moreCount")
          }
          appendLine()
          appendLine()
          append("Полный список: ${manifestFile.absolutePath}")
        }
        notify(project, notifMsg, NotificationType.INFORMATION)
        history.add("Deps apply", true,
          "installed=${unpackResult.installed} skipped=${unpackResult.skipped} invalid=${unpackResult.invalid} size=${humanBytes(unpackResult.totalBytes)} list=$manifestFile")
      }
    })
  }
}
