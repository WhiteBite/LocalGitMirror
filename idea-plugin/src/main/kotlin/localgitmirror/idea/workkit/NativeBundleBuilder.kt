package localgitmirror.idea.workkit

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object NativeBundleBuilder {
  private const val SYNC_STATE = ".last_sync"

  data class BundleBuildResult(
    val mode: String,
    val bundleFile: File,
    val head: String
  )

  fun createBundle(workDir: File, baseCommit: String? = null): BundleBuildResult {
    ensureGitRepo(workDir)

    val bundleFile = File(workDir, "debug_info.tmp")
    val syncStateFile = File(workDir, SYNC_STATE)
    val forcedBase = baseCommit?.trim().orEmpty()

    val branch = currentBranch(workDir).ifBlank { "HEAD" }
    val mode = when {
      forcedBase.isNotBlank() -> {
        ensureCommitReachable(workDir, forcedBase)
        val res = git(workDir, "bundle", "create", bundleFile.absolutePath, branch, "^$forcedBase")
        if (res.exitCode != 0) throw RuntimeException("No new changes to sync")
        "incremental(base)"
      }
      syncStateFile.exists() -> {
        val lastHash = syncStateFile.readText().trim()
        ensureCommitReachable(workDir, lastHash)
        val res = git(workDir, "bundle", "create", bundleFile.absolutePath, branch, "^$lastHash")
        if (res.exitCode != 0) throw RuntimeException("No new changes to sync")
        "incremental"
      }
      else -> {
        val res = git(workDir, "bundle", "create", bundleFile.absolutePath, "--all")
        if (res.exitCode != 0) throw RuntimeException("Failed to create bundle: ${res.stderr.ifBlank { res.stdout }}")
        "full"
      }
    }

    val head = currentHead(workDir)
    syncStateFile.writeText(head + "\n")

    return BundleBuildResult(mode = mode, bundleFile = bundleFile, head = head)
  }

  fun makeDumpFile(workDir: File, repoName: String): File {
    val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
    val outDir = File(workDir, ".localgitmirror/tmp")
    if (!outDir.exists()) outDir.mkdirs()
    return File(outDir, "dump_${repoName}_$ts.dmp")
  }

  private fun ensureGitRepo(workDir: File) {
    val res = git(workDir, "rev-parse", "--git-dir")
    if (res.exitCode != 0) {
      throw RuntimeException("Not a valid git repository. Run from your project directory.")
    }
  }

  private fun ensureCommitReachable(workDir: File, commit: String) {
    val res = git(workDir, "rev-list", "HEAD...$commit", "--count")
    if (res.exitCode != 0) {
      throw RuntimeException("Invalid sync state/base commit")
    }
  }

  private fun currentHead(workDir: File): String {
    val res = git(workDir, "rev-parse", "HEAD")
    if (res.exitCode != 0) {
      throw RuntimeException(res.stderr.ifBlank { "Failed to resolve HEAD" })
    }
    return res.stdout.trim()
  }

  private fun currentBranch(workDir: File): String {
    val res = git(workDir, "rev-parse", "--abbrev-ref", "HEAD")
    if (res.exitCode != 0 || res.stdout.trim() == "HEAD") return ""
    return res.stdout.trim()
  }

  private data class CmdResult(val exitCode: Int, val stdout: String, val stderr: String)

  private fun git(workDir: File, vararg args: String): CmdResult {
    val pb = ProcessBuilder(listOf("git", *args))
      .directory(workDir)
      .redirectErrorStream(false)
    val p = pb.start()
    val stdout = p.inputStream.bufferedReader().readText().trim()
    val stderr = p.errorStream.bufferedReader().readText().trim()
    return CmdResult(p.waitFor(), stdout, stderr)
  }
}
