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

    // ── Step 1: fetch refs on a background thread, then ask user to pick a branch ──
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Getting Mirror branches…", true) {
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
        val result = refsResult ?: return

        if (result.code !in 200..299 || result.refs == null) {
          notify(project, "Не удалось получить ветки: HTTP ${result.code}: ${result.message}", NotificationType.ERROR)
          return
        }

        val remoteRefs = result.refs
        if (remoteRefs.isEmpty()) {
          notify(project, "Mirror репозиторий пустой.", NotificationType.INFORMATION)
          return
        }

        // ── Step 2: show branch picker on EDT ──
        val remoteBranches = remoteRefs.keys.sorted()
        val currentBranch = GitLocal.currentBranch(project, dir)

        // Pre-select: current branch if it exists on Mirror, else first branch
        val preselect = if (currentBranch != null && remoteBranches.contains(currentBranch))
          currentBranch else remoteBranches.first()

        val chosen = Messages.showEditableChooseDialog(
          "Выберите ветку для подтягивания с Mirror:",
          "LocalGitMirror: Pull from Mirror",
          null,
          remoteBranches.toTypedArray(),
          preselect,
          null
        ) ?: return // user cancelled

        if (!remoteRefs.containsKey(chosen)) {
          notify(project, "Ветка «$chosen» не найдена на Mirror.", NotificationType.WARNING)
          return
        }

        // ── Step 3: run the actual pull ──
        doPull(project, dir, settings, repoName, chosen, remoteRefs)
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

        try {
          val targetHash = remoteRefs[targetBranch] ?: run {
            notify(project, "[trace=$traceId] Ветка не найдена в refs", NotificationType.ERROR)
            return
          }

          // ── Check if we already have the objects ──
          indicator.text = "Проверяем локальные объекты…"
          val hasObjects = git(dir, "cat-file", "-e", targetHash).exitCode == 0

          val gitDirRes = git(dir, "rev-parse", "--git-dir")
          val rawGd = gitDirRes.stdout.trim()
          val gitDir = if (File(rawGd).isAbsolute) File(rawGd) else File(dir, rawGd)

          if (!hasObjects) {
            // ── Download bundle ──
            indicator.text = "Скачивание объектов… (это может занять несколько минут)"
            indicator.isIndeterminate = true

            val tmpDir = File(gitDir, ".cache")
            if (!tmpDir.exists()) tmpDir.mkdirs()
            val dumpOut = File(tmpDir, ".tmp_${UUID.randomUUID().toString().take(8)}")

            // Find a good since-hash: most recent local commit that exists on mirror
            val sinceHash = findSinceHash(dir, remoteRefs)

            val dl = MirrorApi.exportDump(
              baseUrl = settings.baseUrl,
              apiKey = SecretsStore.mirrorApiKey,
              repo = repoName,
              since = sinceHash,
              insecureTls = settings.mirrorInsecureTls,
              outFile = dumpOut,
              onProgress = { read, total ->
                if (total > 0) {
                  indicator.isIndeterminate = false
                  // base64 overhead ~33%; raw bundle is ~0.75× the JSON body
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
                // Server says nothing new — objects might already be present
              }
              dl.code !in 200..299 || dl.file == null -> {
                notify(project, "[trace=$traceId] Ошибка скачивания HTTP ${dl.code}: ${dl.message}", NotificationType.ERROR)
                historyService.add("Pull from Mirror", false, "trace=$traceId branch=$targetBranch HTTP ${dl.code}")
                return
              }
              else -> {
                indicator.text = "Распаковываем объекты…"
                indicator.isIndeterminate = true
                try {
                  val decryptedBytes = BundleCrypto.decryptDumpBytes(dl.file.readBytes(), SecretsStore.syncPassword)
                  fetchFromBundle(dir, decryptedBytes) { errMsg ->
                    notify(project, "[trace=$traceId] $errMsg", NotificationType.ERROR)
                    historyService.add("Pull from Mirror", false, "trace=$traceId $errMsg")
                  }
                } finally {
                  try { dl.file.delete() } catch (_: Exception) {}
                }
              }
            }
          }

          // ── Update only the requested branch ──
          indicator.text = "Обновляем ветку «$targetBranch»…"
          indicator.isIndeterminate = false
          indicator.fraction = 0.9

          val localBranches = listLocalBranches(dir)
          val result = applyBranch(dir, targetBranch, targetHash, localBranches)

          // ── Clean up temp fetch refs ──
          git(dir, "for-each-ref", "--format=%(refname)", "refs/fetched/").stdout
            .lines().filter { it.isNotBlank() }
            .forEach { git(dir, "update-ref", "-d", it.trim()) }

          // ── Reset working tree if we're on the updated branch ──
          val currentBranch = GitLocal.currentBranch(project, dir)
          if (currentBranch == targetBranch) {
            git(dir, "reset", "--hard", "HEAD")
          }

          indicator.fraction = 1.0

          val msg = "[trace=$traceId] Pull завершён: $result"
          notify(project, msg, NotificationType.INFORMATION)
          historyService.add("Pull from Mirror", true, "trace=$traceId branch=$targetBranch $result")

          if (!remoteRefs["HEAD"].isNullOrBlank()) {
            SyncStateStore.writeLastPulledHead(dir, remoteRefs["HEAD"]!!)
          }

        } catch (t: Throwable) {
          val msg = "[trace=$traceId] Pull не удался: ${t.message}"
          notify(project, msg, NotificationType.ERROR)
        }
      }
    })
  }

  /** Returns a since-hash to minimise bundle size: most recent local commit that mirror also has. */
  private fun findSinceHash(dir: File, remoteRefs: Map<String, String>): String? =
    PullLogic.findSinceHash(dir, remoteRefs)

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

  /** Pipe decrypted bundle bytes into `git fetch -` */
  private fun fetchFromBundle(
    dir: File,
    decryptedBytes: ByteArray,
    onError: (String) -> Unit
  ) {
    val pb = ProcessBuilder(listOf("git", "fetch", "-", "+refs/heads/*:refs/fetched/mirror/*"))
      .directory(dir)
      .redirectErrorStream(false)
    val proc = pb.start()
    proc.outputStream.use { it.write(decryptedBytes); it.flush() }
    val stdoutSb = StringBuilder()
    val stderrSb = StringBuilder()
    val t1 = Thread { stdoutSb.append(proc.inputStream.bufferedReader().readText()) }
    val t2 = Thread { stderrSb.append(proc.errorStream.bufferedReader().readText()) }
    t1.start(); t2.start()
    val exitCode = proc.waitFor(300, TimeUnit.SECONDS).let { if (it) proc.exitValue() else { proc.destroy(); -1 } }
    t1.join(); t2.join()
    if (exitCode != 0) {
      onError("Ошибка распаковки бандла: $stderrSb")
    }
  }

  // ── Git helpers ──

  private data class CmdResult(val exitCode: Int, val stdout: String, val stderr: String)

  private fun git(workDir: File, vararg args: String): CmdResult {
    localgitmirror.idea.sync.SyncLogger.log(workDir, "Exec: git ${args.joinToString(" ")}")
    val p = ProcessBuilder(listOf("git", *args))
      .directory(workDir)
      .redirectErrorStream(false)
      .start()
    val stdoutSb = StringBuilder()
    val stderrSb = StringBuilder()
    val t1 = Thread { stdoutSb.append(p.inputStream.bufferedReader().readText()) }
    val t2 = Thread { stderrSb.append(p.errorStream.bufferedReader().readText()) }
    t1.start(); t2.start()
    p.waitFor(300, TimeUnit.SECONDS)
    t1.join(); t2.join()
    val res = CmdResult(p.exitValue(), stdoutSb.toString().trim(), stderrSb.toString().trim())
    if (res.exitCode != 0) {
      localgitmirror.idea.sync.SyncLogger.log(workDir, "Git Failed (${res.exitCode}): ${res.stderr}")
    }
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
