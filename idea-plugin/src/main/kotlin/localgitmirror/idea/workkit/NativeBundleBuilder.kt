package localgitmirror.idea.workkit

import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object NativeBundleBuilder {

  data class BundleBuildResult(
    val mode: String,
    val bundleBytes: ByteArray,
    val head: String
  )

  /** Resolve the actual .git directory (handles worktrees). */
  private fun gitDir(workDir: File): File {
    val res = git(workDir, "rev-parse", "--git-dir")
    if (res.exitCode != 0) throw RuntimeException("Not a valid git repository.")
    val raw = res.stdout.trim()
    val f = File(raw)
    return if (f.isAbsolute) f else File(workDir, raw)
  }

  private fun syncStateFile(workDir: File): File = File(gitDir(workDir), "lgm-sync-state")

  /**
   * Creates a git bundle entirely in memory — no plaintext touches disk.
   * Uses `git bundle create -` which writes to stdout.
   */
  fun createBundle(
    workDir: File,
    excludeBases: List<String> = emptyList(),
    additionalBranches: List<String> = emptyList(),
    negotiationUsed: Boolean = false
  ): BundleBuildResult {
    ensureGitRepo(workDir)

    val stateFile = syncStateFile(workDir)

    val validExcludes = mutableListOf<String>()
    excludeBases.filter { it.isNotBlank() }.forEach {
      ensureCommitReachable(workDir, it)
      validExcludes.add(it)
    }

    val branch = currentBranch(workDir).ifBlank { "HEAD" }
    val refsToPack = mutableListOf(branch)
    refsToPack.addAll(additionalBranches.filter { it.isNotBlank() && it != branch })

    val mode: String
    val bundleBytes: ByteArray

    when {
      validExcludes.isNotEmpty() -> {
        val exclusions = validExcludes.map { "^$it" }
        val allArgs = listOf("bundle", "create", "-") + refsToPack + exclusions
        bundleBytes = gitToStdout(workDir, allArgs)
          ?: throw RuntimeException("No new changes to sync")
        mode = "incremental(bases=${validExcludes.size})"
      }
      // Only use state file when negotiation was NOT used.
      // If negotiation ran and returned empty excludeBases, it means
      // the mirror has none of our commits → must send full bundle.
      !negotiationUsed && stateFile.exists() -> {
        val lastHash = stateFile.readText().trim()
        ensureCommitReachable(workDir, lastHash)
        val allArgs = listOf("bundle", "create", "-") + refsToPack + listOf("^$lastHash")
        bundleBytes = gitToStdout(workDir, allArgs)
          ?: throw RuntimeException("No new changes to sync")
        mode = "incremental"
      }
      else -> {
        val allArgs = listOf("bundle", "create", "-") + refsToPack
        bundleBytes = gitToStdout(workDir, allArgs)
          ?: throw RuntimeException("Failed to create bundle")
        mode = "full"
      }
    }

    val head = currentHead(workDir)
    stateFile.writeText(head + "\n")

    return BundleBuildResult(mode = mode, bundleBytes = bundleBytes, head = head)
  }

  fun makeSyncFile(workDir: File, repoName: String): File {
    val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
    val outDir = File(gitDir(workDir), "lgm")
    if (!outDir.exists()) outDir.mkdirs()
    return File(outDir, "cache_${repoName}_$ts.bin")
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

  /**
   * Runs git with stdout captured as raw bytes (for binary bundle output).
   * Returns null if the command fails.
   */
  private fun gitToStdout(workDir: File, args: List<String>): ByteArray? {
    val pb = ProcessBuilder(listOf("git") + args)
      .directory(workDir)
      .redirectErrorStream(false)
    val p = pb.start()
    val baos = ByteArrayOutputStream()
    p.inputStream.use { it.copyTo(baos) }
    val stderr = p.errorStream.bufferedReader().readText().trim()
    val exit = p.waitFor()
    if (exit != 0 || baos.size() == 0) return null
    return baos.toByteArray()
  }
}
