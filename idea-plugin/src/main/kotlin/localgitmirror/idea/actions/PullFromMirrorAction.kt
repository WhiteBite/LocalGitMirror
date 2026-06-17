package localgitmirror.idea.actions

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
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.SyncStateStore
import localgitmirror.idea.workkit.BundleCrypto
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class PullFromMirrorAction : AnAction() {

  companion object {
    // Process-wide guard: prevents concurrent pull/push operations from
    // racing on the same .git directory (e.g. startup auto-check + manual pull).
    private val operationInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val dirPath = project.basePath ?: return
    val dir = File(dirPath)

    val settings = service<MirrorSettingsService>().state
    val repoName = settings.repo.ifBlank { project.name }

    if (!GitLocal.isCleanWorkTree(project, dir)) {
      notify(project, LocalGitMirrorBundle.message("notify.worktree.dirty"), NotificationType.WARNING)
      return
    }

    if (!operationInProgress.compareAndSet(false, true)) {
      notify(project, "Операция синхронизации уже выполняется. Дождитесь её завершения.", NotificationType.WARNING)
      return
    }

    // ── Step 1: fetch refs in background ──
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Получаем ветки…", true) {
      private var refsResult: MirrorApi.RefsResult? = null

      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        indicator.text = "Получаем список веток с Mirror…"
        refsResult = MirrorApi.getRefs(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repoName,
          insecureTls = settings.mirrorInsecureTls
        )
      }

      override fun onSuccess() {
        val result = refsResult ?: run { operationInProgress.set(false); return }

        if (result.code !in 200..299 || result.refs == null) {
          notify(project, "Не удалось получить ветки: HTTP ${result.code}: ${result.message}", NotificationType.ERROR)
          operationInProgress.set(false)
          return
        }

        val remoteRefs = result.refs
        if (remoteRefs.isEmpty()) {
          notify(project, "Mirror репозиторий пустой.", NotificationType.INFORMATION)
          operationInProgress.set(false)
          return
        }

        // ── Step 2: branch picker on EDT — shows MIRROR branches, including ones missing locally ──
        val remoteBranches = remoteRefs.keys.sorted()
        val currentBranch = GitLocal.currentBranch(project, dir)
        val localBranches = GitLocal.listBranches(project, dir).toSet()

        // Mark branches that don't exist locally with a ★ so user knows it's a new branch
        val displayItems = remoteBranches.map { b ->
          if (localBranches.contains(b)) b else "★ $b  (новая)"
        }.toTypedArray()

        val preselect = if (currentBranch != null && remoteBranches.contains(currentBranch))
          displayItems[remoteBranches.indexOf(currentBranch)]
        else
          displayItems.first()

        val chosenDisplay = Messages.showEditableChooseDialog(
          "Выберите ветку для подтягивания с Mirror:\n(★ = ветки которых нет локально — будут созданы)",
          "LocalGitMirror: Pull from Mirror",
          null,
          displayItems,
          preselect,
          null
        )
        if (chosenDisplay == null) {
          operationInProgress.set(false)  // user cancelled
          return
        }

        // Strip display decoration back to plain branch name
        val chosenIdx = displayItems.indexOf(chosenDisplay)
        val chosen = if (chosenIdx >= 0) remoteBranches[chosenIdx]
                     else chosenDisplay.removePrefix("★ ").substringBefore("  (")

        if (!remoteRefs.containsKey(chosen)) {
          notify(project, "Ветка «$chosen» не найдена на Mirror.", NotificationType.WARNING)
          operationInProgress.set(false)
          return
        }

        // ── Step 3: fetch preview, then confirm, then pull ──
        previewAndPull(project, dir, settings, repoName, chosen, remoteRefs)
      }

      override fun onThrowable(error: Throwable) {
        notify(project, "Ошибка получения веток: ${error.message}", NotificationType.ERROR)
        operationInProgress.set(false)
      }
    })
  }

  /** Fetch a preview of incoming commits, show a confirmation dialog, then pull. */
  private fun previewAndPull(
    project: Project,
    dir: File,
    settings: MirrorSettingsService.State,
    repoName: String,
    targetBranch: String,
    remoteRefs: Map<String, String>
  ) {
    val targetHash = remoteRefs[targetBranch] ?: run { operationInProgress.set(false); return }
    // If we already have the target hash, nothing to pull
    val alreadyHave = git(dir, "cat-file", "-e", targetHash).exitCode == 0 &&
      git(dir, "rev-parse", targetBranch).stdout.trim() == targetHash
    if (alreadyHave) {
      notify(project, "Ветка «$targetBranch» уже актуальна (${targetHash.take(7)}).", NotificationType.INFORMATION)
      operationInProgress.set(false)
      return
    }

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Превью изменений…", true) {
      private var preview: MirrorApi.PreviewPullDetailsResult? = null

      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        indicator.text = "Запрашиваем список изменений…"
        // since = local tip of this branch if it exists, else null (full history)
        val localTip = git(dir, "rev-parse", "--verify", targetBranch).let {
          if (it.exitCode == 0) it.stdout.trim() else null
        }
        preview = MirrorApi.previewPullDetails(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repoName,
          since = localTip,
          insecureTls = settings.mirrorInsecureTls,
          branch = targetBranch
        )
      }

      override fun onSuccess() {
        val p = preview
        val summary = when {
          p == null || p.code !in 200..299 ->
            "Не удалось получить превью (продолжить вслепую?)."
          p.commits.isEmpty() ->
            "Новых коммитов нет, но ветка обновится до ${targetHash.take(7)}."
          else -> buildString {
            appendLine("Прилетит ${p.commits.size} коммит(ов) в «$targetBranch»:")
            appendLine()
            p.commits.take(10).forEach { appendLine("  • ${it.hash.take(7)} ${it.message}") }
            if (p.commits.size > 10) appendLine("  … и ещё ${p.commits.size - 10}")
            if (p.diffstat.isNotBlank()) {
              appendLine()
              appendLine(p.diffstat.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "")
            }
          }
        }

        val confirmed = Messages.showYesNoDialog(
          project, summary, "LocalGitMirror: Подтвердите Pull",
          "Подтянуть", "Отмена", null
        )
        if (confirmed == Messages.YES) {
          doPull(project, dir, settings, repoName, targetBranch, remoteRefs)
        } else {
          operationInProgress.set(false)  // user declined
        }
      }

      override fun onThrowable(error: Throwable) {
        notify(project, "Ошибка превью: ${error.message}", NotificationType.ERROR)
        operationInProgress.set(false)
      }
    })
  }

  private fun doPull(
    project: Project,
    dir: File,
    settings: MirrorSettingsService.State,
    repoName: String,
    targetBranch: String,
    remoteRefs: Map<String, String>
  ) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Pull «$targetBranch»", false) {
      override fun run(indicator: ProgressIndicator) {
        val traceId = UUID.randomUUID().toString().take(8)
        val historyService = service<OperationsHistoryService>()
        // Save the branch's current hash for rollback (null if branch is new)
        val branchBackupHash = git(dir, "rev-parse", "--verify", targetBranch)
          .let { if (it.exitCode == 0) it.stdout.trim() else null }

        try {
          val targetHash = remoteRefs[targetBranch] ?: run {
            notify(project, "[trace=$traceId] Ветка не найдена в refs", NotificationType.ERROR)
            return
          }

          // ── Check if objects exist locally ──
          indicator.text = "Проверяем локальные объекты…"
          val hasObjects = git(dir, "cat-file", "-e", targetHash).exitCode == 0

          val gitDirRes = git(dir, "rev-parse", "--git-dir")
          val rawGd = gitDirRes.stdout.trim()
          val gitDir = if (File(rawGd).isAbsolute) File(rawGd) else File(dir, rawGd)

          if (!hasObjects) {
            indicator.text = "Скачивание объектов…"
            indicator.isIndeterminate = true

            val tmpDir = File(gitDir, ".cache").also { if (!it.exists()) it.mkdirs() }
            val dumpOut = File(tmpDir, ".tmp_${UUID.randomUUID().toString().take(8)}")

            // Collect "haves": all local commit hashes the server can exclude.
            // This lets the server bundle only the delta for the chosen branch.
            val haves = collectHaves(dir)
            val sinceHash = findSinceHash(dir, remoteRefs)

            val dl = MirrorApi.exportDump(
              baseUrl = settings.baseUrl,
              apiKey = SecretsStore.mirrorApiKey,
              repo = repoName,
              since = sinceHash,
              insecureTls = settings.mirrorInsecureTls,
              outFile = dumpOut,
              branch = targetBranch,
              haves = haves,
              onProgress = { read, total ->
                if (total > 0) {
                  indicator.isIndeterminate = false
                  indicator.fraction = (read.toDouble() / total).coerceIn(0.0, 0.95)
                  val readMb = "%.1f".format(read / 1_048_576.0)
                  val totalMb = "%.1f".format(total / 1_048_576.0)
                  indicator.text = "Скачивание… $readMb / $totalMb МБ"
                } else {
                  indicator.isIndeterminate = true
                  indicator.text = "Скачивание объектов…"
                }
              }
            )

            when {
              dl.code == 204 -> {
                // Server has nothing newer than `since` — but we know target hash is missing
                // locally. This means sinceHash was wrong or server state is inconsistent.
                // Retry without `since` to get a full bundle.
                indicator.text = "Запрашиваем полный бандл…"
                indicator.isIndeterminate = true
                val dlFull = MirrorApi.exportDump(
                  baseUrl = settings.baseUrl,
                  apiKey = SecretsStore.mirrorApiKey,
                  repo = repoName,
                  since = null,
                  insecureTls = settings.mirrorInsecureTls,
                  outFile = dumpOut,
                  branch = targetBranch
                )
                if (dlFull.code == 204 || dlFull.code !in 200..299 || dlFull.file == null) {
                  notify(project, "[trace=$traceId] Сервер не может отдать нужные объекты (${dlFull.code})", NotificationType.ERROR)
                  historyService.add("Pull from Mirror", false, "trace=$traceId 204 on full export")
                  return
                }
                if (!doFetch(dir, dlFull.file, traceId, project, historyService, targetBranch)) return
              }
              dl.code !in 200..299 || dl.file == null -> {
                notify(project, "[trace=$traceId] Ошибка скачивания HTTP ${dl.code}: ${dl.message}", NotificationType.ERROR)
                historyService.add("Pull from Mirror", false, "trace=$traceId branch=$targetBranch HTTP ${dl.code}")
                return
              }
              else -> {
                indicator.text = "Распаковываем объекты…"
                indicator.isIndeterminate = true
                if (!doFetch(dir, dl.file, traceId, project, historyService, targetBranch)) return
              }
            }

            // Verify objects are now available after fetch
            val hasNow = git(dir, "cat-file", "-e", targetHash).exitCode == 0
            if (!hasNow) {
              notify(project, "[trace=$traceId] Объекты ветки «$targetBranch» недоступны после загрузки. Попробуйте ещё раз.", NotificationType.ERROR)
              historyService.add("Pull from Mirror", false, "trace=$traceId objects missing after fetch")
              return
            }
          }

          // ── Apply the branch ref ──
          indicator.text = "Обновляем ветку «$targetBranch»…"
          indicator.isIndeterminate = false
          indicator.fraction = 0.9

          val localBranches = listLocalBranches(dir)
          val isNewBranch = !localBranches.contains(targetBranch)
          val result = applyBranch(dir, targetBranch, targetHash, localBranches)

          // ── Cleanup temp refs ──
          git(dir, "for-each-ref", "--format=%(refname)", "refs/fetched/").stdout
            .lines().filter { it.isNotBlank() }
            .forEach { git(dir, "update-ref", "-d", it.trim()) }

          // ── Reset working tree if already on this branch ──
          val currentBranch = GitLocal.currentBranch(project, dir)
          if (currentBranch == targetBranch) {
            git(dir, "reset", "--hard", "HEAD")
          }

          indicator.fraction = 1.0

          // ── Notify user — hint to checkout if branch was new ──
          val checkoutHint = if (isNewBranch)
            "\nЧтобы переключиться: git checkout $targetBranch"
          else ""
          val msg = "[trace=$traceId] Pull завершён: $result$checkoutHint"
          notify(project, msg, NotificationType.INFORMATION)
          historyService.add("Pull from Mirror", true, "trace=$traceId branch=$targetBranch $result")

          if (!remoteRefs["HEAD"].isNullOrBlank()) {
            SyncStateStore.writeLastPulledHead(dir, remoteRefs["HEAD"]!!)
          }

        } catch (t: Throwable) {
          // ── Rollback: restore the branch to its pre-pull hash ──
          if (branchBackupHash != null) {
            git(dir, "update-ref", "refs/heads/$targetBranch", branchBackupHash)
          }
          val msg = "[trace=$traceId] Pull не удался: ${humanizeGitError(t.message)}"
          notify(project, msg, NotificationType.ERROR)
          historyService.add("Pull from Mirror", false, "trace=$traceId ${t.message}")
        } finally {
          operationInProgress.set(false)
        }
      }

      override fun onThrowable(error: Throwable) {
        operationInProgress.set(false)
      }
    })
  }

  /** Map common raw git/network errors to human-friendly hints. */
  private fun humanizeGitError(raw: String?): String {
    if (raw.isNullOrBlank()) return "неизвестная ошибка"
    val low = raw.lowercase()
    return when {
      "prerequisite" in low ->
        "бандл требует коммиты, которых нет локально. Попробуйте полный pull (ветка скачается целиком)."
      "could not read" in low || "bad object" in low ->
        "повреждённые или неполные объекты. Повторите pull."
      "timed out" in low || "timeout" in low ->
        "превышено время ожидания. Проверьте сеть/нагрузку сервера."
      "connection" in low || "connect" in low ->
        "не удалось подключиться к Mirror. Проверьте URL/порт."
      "would clobber" in low || "non-fast-forward" in low ->
        "локальная ветка расходится с Mirror. Сохраните изменения и повторите."
      else -> raw.take(300)
    }
  }

  /**
   * Decrypt dump file and pipe into `git fetch -`.
   * Returns true on success, false on failure (error already notified).
   */
  private fun doFetch(
    dir: File,
    dumpFile: File,
    traceId: String,
    project: Project,
    historyService: OperationsHistoryService,
    targetBranch: String
  ): Boolean {
    return try {
      val decryptedBytes = BundleCrypto.decryptDumpBytes(dumpFile.readBytes(), SecretsStore.syncPassword)
      var fetchError: String? = null
      fetchFromBundle(dir, decryptedBytes) { err -> fetchError = err }
      if (fetchError != null) {
        notify(project, "[trace=$traceId] $fetchError", NotificationType.ERROR)
        historyService.add("Pull from Mirror", false, "trace=$traceId $fetchError")
        false
      } else true
    } catch (e: Exception) {
      notify(project, "[trace=$traceId] Ошибка расшифровки: ${e.message}", NotificationType.ERROR)
      historyService.add("Pull from Mirror", false, "trace=$traceId decrypt error: ${e.message}")
      false
    } finally {
      try { dumpFile.delete() } catch (_: Exception) {}
    }
  }

  /** Returns a since-hash to minimise bundle size. */
  private fun findSinceHash(dir: File, remoteRefs: Map<String, String>): String? =
    PullLogic.findSinceHash(dir, remoteRefs)

  /**
   * Collect commit hashes the local repo already has, so the server can exclude
   * them and bundle only the delta. Includes:
   *  - all local branch tips
   *  - the last ~50 commits reachable from HEAD (covers the common case fast)
   * Capped to keep the request small.
   */
  private fun collectHaves(dir: File): List<String> {
    val result = LinkedHashSet<String>()

    // All local branch tips
    git(dir, "for-each-ref", "--format=%(objectname)", "refs/heads/").stdout
      .lines().map { it.trim() }.filter { it.isNotBlank() }
      .forEach { result.add(it) }

    // Recent commits from HEAD (depth 50)
    git(dir, "rev-list", "--max-count=50", "HEAD").stdout
      .lines().map { it.trim() }.filter { it.isNotBlank() }
      .forEach { result.add(it) }

    return result.take(100)
  }

  /** Apply a single branch ref. Returns human-readable result. */
  private fun applyBranch(
    dir: File,
    branchName: String,
    newHash: String,
    localBranches: Set<String>
  ): String = PullLogic.applyBranch(
    branchName = branchName,
    newHash = newHash,
    localBranches = localBranches,
    revParse = { ref -> git(dir, "rev-parse", ref).stdout.trim() },
    isAncestor = { a, d -> git(dir, "merge-base", "--is-ancestor", a, d).exitCode == 0 },
    updateRef = { ref, hash -> git(dir, "update-ref", "refs/heads/$ref", hash) },
    createBranch = { ref, hash -> git(dir, "branch", ref, hash) },
    createSuffixedBranch = { base, hash -> createSuffixedBranch(dir, base, hash) }
  )

  /** Pipe decrypted bundle bytes into `git fetch -`. */
  private fun fetchFromBundle(dir: File, decryptedBytes: ByteArray, onError: (String) -> Unit) {
    val pb = ProcessBuilder(listOf("git", "fetch", "-", "+refs/heads/*:refs/fetched/mirror/*"))
      .directory(dir).redirectErrorStream(false)
    val proc = pb.start()
    proc.outputStream.use { it.write(decryptedBytes); it.flush() }
    val stdoutSb = StringBuilder()
    val stderrSb = StringBuilder()
    val t1 = Thread { stdoutSb.append(proc.inputStream.bufferedReader().readText()) }
    val t2 = Thread { stderrSb.append(proc.errorStream.bufferedReader().readText()) }
    t1.start(); t2.start()
    val exitCode = proc.waitFor(300, TimeUnit.SECONDS).let { if (it) proc.exitValue() else { proc.destroy(); -1 } }
    t1.join(); t2.join()
    if (exitCode != 0) onError("Ошибка распаковки бандла: $stderrSb")
  }

  // ── Git helpers ──

  private data class CmdResult(val exitCode: Int, val stdout: String, val stderr: String)

  private fun git(workDir: File, vararg args: String): CmdResult {
    localgitmirror.idea.sync.SyncLogger.log(workDir, "Exec: git ${args.joinToString(" ")}")
    val p = ProcessBuilder(listOf("git", *args)).directory(workDir).redirectErrorStream(false).start()
    val stdoutSb = StringBuilder(); val stderrSb = StringBuilder()
    val t1 = Thread { stdoutSb.append(p.inputStream.bufferedReader().readText()) }
    val t2 = Thread { stderrSb.append(p.errorStream.bufferedReader().readText()) }
    t1.start(); t2.start()
    p.waitFor(300, TimeUnit.SECONDS)
    t1.join(); t2.join()
    val res = CmdResult(p.exitValue(), stdoutSb.toString().trim(), stderrSb.toString().trim())
    if (res.exitCode != 0) localgitmirror.idea.sync.SyncLogger.log(workDir, "Git Failed (${res.exitCode}): ${res.stderr}")
    return res
  }

  private fun listLocalBranches(workDir: File): Set<String> {
    val r = git(workDir, "for-each-ref", "--format=%(refname:short)", "refs/heads")
    if (r.exitCode != 0) return emptySet()
    return r.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
  }

  private fun createSuffixedBranch(workDir: File, baseName: String, hash: String): String {
    for (i in 1..99) {
      val name = "$baseName-local-$i"
      if (git(workDir, "branch", name, hash).exitCode == 0) return name
    }
    return "$baseName-local"
  }

  private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("LocalGitMirror")
      .createNotification(message, type)
      .notify(project)
  }
}
