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
        history.add("Deps request", true, "id=${res.id} artifacts=${artifacts.size} size=${humanBytes(totalSize)}")
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
        indicator.text = "Проверяем pending-запросы…"
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
          notify(project, LocalGitMirrorBundle.message("deps.notify.noRequests"), NotificationType.INFORMATION)
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
        val internal = parseInternalRepos(settings)
        val toShip = DepsDiff.compute(workArtifacts, manifest, internal)
        val diffSize = toShip.sumOf { it.size }
        val filterText = if (internal.isEmpty()) "—" else internal.joinToString(",")
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
        history.add("Deps respond", true, "request=${req.id} artifacts=${toShip.size} size=${humanBytes(diffSize)}")
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
        notify(project, ok, NotificationType.INFORMATION)
        history.add("Deps apply", true,
          "installed=${unpackResult.installed} skipped=${unpackResult.skipped} invalid=${unpackResult.invalid} size=${humanBytes(unpackResult.totalBytes)}")
      }
    })
  }
}
