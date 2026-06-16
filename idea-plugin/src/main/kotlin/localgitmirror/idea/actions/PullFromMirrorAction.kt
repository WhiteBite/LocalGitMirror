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
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.SyncStateStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import localgitmirror.idea.workkit.WorkKit
import java.io.File
import java.util.UUID

class PullFromMirrorAction : AnAction() {

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabled = project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val dirPath = e.project?.basePath ?: return
    val dir = File(dirPath)

    val settings = service<MirrorSettingsService>().state
    val repoName = settings.repo.ifBlank { project.name }

    if (!GitLocal.isCleanWorkTree(project, dir)) {
      notify(project, "Working tree has uncommitted changes. Commit/stash before syncing.", NotificationType.WARNING, dir)
      return
    }

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Sync Pull", false) {
      override fun run(indicator: ProgressIndicator) {
        val traceId = UUID.randomUUID().toString().take(8)
        
        notify(project, "[trace=$traceId] Starting sync pull: repo=$repoName", NotificationType.INFORMATION, dir)

        indicator.text = "Fetching remote refs from Mirror"
        val refsResult = MirrorApi.getRefs(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repoName,
          insecureTls = settings.mirrorInsecureTls
        )

        if (refsResult.code !in 200..299 || refsResult.refs == null) {
          notify(project, "[trace=$traceId] Failed to fetch refs HTTP ${refsResult.code}: ${refsResult.message}", NotificationType.ERROR, dir)
          return
        }

        val remoteRefs = refsResult.refs
        if (remoteRefs.isEmpty()) {
           notify(project, "[trace=$traceId] Mirror repository is empty.", NotificationType.INFORMATION, dir)
           return
        }

        indicator.text = "Checking local objects"
        var needsBundle = false
        val missingCommits = mutableListOf<String>()
        for ((_, hash) in remoteRefs) {
            val check = git(dir, "cat-file", "-e", hash)
            if (check.exitCode != 0) {
                needsBundle = true
                missingCommits.add(hash)
            }
        }

        val gitDirRes = git(dir, "rev-parse", "--git-dir")
        val rawGd = gitDirRes.stdout.trim()
        val gitDir = if (java.io.File(rawGd).isAbsolute) java.io.File(rawGd) else File(dir, rawGd)

        val localBranches = listLocalBranches(dir)

        var applyOutput = ""

        if (needsBundle) {
            indicator.text = "Downloading objects from Mirror"
            val tmpDir = File(gitDir, ".cache")
            if (!tmpDir.exists()) tmpDir.mkdirs()
            val dumpOut = File(tmpDir, ".tmp_${UUID.randomUUID().toString().take(8)}")

            val dl = MirrorApi.exportDump(
              baseUrl = settings.baseUrl,
              apiKey = SecretsStore.mirrorApiKey,
              repo = repoName,
              since = null, // Download all missing parts (since is not strictly needed for export-dump now)
              insecureTls = settings.mirrorInsecureTls,
              outFile = dumpOut
            )

            if (dl.code == 204) {
              // 204 means no bundle was created (all required objects are already present on server but somehow cat-file failed?)
            } else if (dl.code !in 200..299 || dl.file == null) {
              notify(project, "[trace=$traceId] Failed to download bundle HTTP ${dl.code}: ${dl.message}", NotificationType.ERROR, dir)
              return
            } else {
              indicator.text = "Unpacking and fetching objects"
              try {
                  // Decrypt in memory — no plaintext bundle touches disk
                  val decryptedBytes = localgitmirror.idea.workkit.BundleCrypto.decryptDumpBytes(dl.file.readBytes(), SecretsStore.syncPassword)

                  // Pipe decrypted bundle directly to git fetch via stdin
                  val pb = ProcessBuilder(listOf("git", "fetch", "-", "+refs/heads/*:refs/fetched/mirror/*"))
                    .directory(dir)
                    .redirectErrorStream(false)
                  val proc = pb.start()
                  proc.outputStream.use { it.write(decryptedBytes); it.flush() }
                  val fetchStdout = proc.inputStream.bufferedReader().readText()
                  val fetchStderr = proc.errorStream.bufferedReader().readText()
                  val exitCode = proc.waitFor()
                  if (exitCode != 0) {
                      notify(project, "[trace=$traceId] Failed to fetch from bundle: $fetchStderr", NotificationType.ERROR, dir)
                      return
                  }
              } finally {
                  try { dl.file.delete() } catch (_: Exception) {}
              }
            }
        }

        indicator.text = "Updating local branches"
        val results = mutableListOf<String>()
        
        for ((bName, hash) in remoteRefs) {
            if (localBranches.contains(bName)) {
                val isDescendant = git(dir, "merge-base", "--is-ancestor", bName, hash).exitCode == 0
                val isExact = git(dir, "rev-parse", bName).stdout.trim() == hash
                
                if (isExact) {
                    continue // Already up to date
                }
                
                if (isDescendant) {
                    git(dir, "update-ref", "refs/heads/$bName", hash)
                    results.add("$bName -> updated to ${hash.take(7)}")
                } else {
                    val localHash = git(dir, "rev-parse", bName).stdout.trim()
                    val suffixed = createSuffixedBranch(dir, bName, localHash)
                    git(dir, "update-ref", "refs/heads/$bName", hash)
                    results.add("$bName -> diverged (local moved to $suffixed), updated to ${hash.take(7)}")
                }
            } else {
                git(dir, "branch", bName, hash)
                results.add("$bName -> newly created at ${hash.take(7)}")
            }
        }

        // Clean up temporary fetched refs
        git(dir, "for-each-ref", "--format=%(refname)", "refs/fetched/").stdout.lines().forEach {
           if (it.isNotBlank()) git(dir, "update-ref", "-d", it.trim())
        }

        if (results.isEmpty()) {
            notify(project, "[trace=$traceId] Sync Pull: Automatically checked out & everything is up to date.", NotificationType.INFORMATION)
            return
        }

        git(dir, "reset", "--hard", "HEAD")

        val summary = results.joinToString("\n")
        applyOutput += summary
        notify(project, "[trace=$traceId] Sync pull completed:\n$summary", NotificationType.INFORMATION)
        
        if (!refsResult.head.isNullOrBlank()) {
            SyncStateStore.writeLastPulledHead(dir, refsResult.head)
        }
      }
    })
  }

  private fun notify(project: Project, message: String, type: NotificationType, logDir: File? = null) {
    if (logDir != null) {
      localgitmirror.idea.sync.SyncLogger.log(logDir, "[${type.name}] $message")
    }
    NotificationGroupManager.getInstance()
      .getNotificationGroup("LocalGitMirror")
      .createNotification(message, type)
      .notify(project)
  }

  private data class CmdResult(val exitCode: Int, val stdout: String, val stderr: String)

  private fun git(workDir: File, vararg args: String): CmdResult {
    localgitmirror.idea.sync.SyncLogger.log(workDir, "Exec: git ${args.joinToString(" ")}")
    val p = ProcessBuilder(listOf("git", *args))
      .directory(workDir)
      .redirectErrorStream(false)
      .start()
    // Read streams concurrently to avoid pipe deadlock
    val stdoutStr = java.lang.StringBuilder()
    val stderrStr = java.lang.StringBuilder()
    val t1 = Thread { stdoutStr.append(p.inputStream.bufferedReader().readText()) }
    val t2 = Thread { stderrStr.append(p.errorStream.bufferedReader().readText()) }
    t1.start(); t2.start()
    p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)
    t1.join(); t2.join()
    
    val res = CmdResult(p.exitValue(), stdoutStr.toString().trim(), stderrStr.toString().trim())
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
      val name = "$baseName-$i"
      val co = git(workDir, "branch", name, hash)
      if (co.exitCode == 0) return name
    }
    return "$baseName-local"
  }
}
