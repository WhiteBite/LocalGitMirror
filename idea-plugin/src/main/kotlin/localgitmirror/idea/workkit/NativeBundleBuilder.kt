package localgitmirror.idea.workkit

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import localgitmirror.idea.sync.SyncLogger

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

  private fun syncStateFile(workDir: File): File = File(gitDir(workDir), ".fetch-state")

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
    val outDir = File(gitDir(workDir), ".cache")
    if (!outDir.exists()) outDir.mkdirs()
    // Random-looking filename that blends in with temp files
    val id = UUID.randomUUID().toString().take(8)
    return File(outDir, ".tmp_$id")
  }

  private fun ensureGitRepo(workDir: File) {
    val res = git(workDir, "rev-parse", "--git-dir")
    if (res.exitCode != 0) {
      throw RuntimeException("Not a valid git repository. Run from your project directory.")
    }
  }

  private fun ensureCommitReachable(workDir: File, commit: String) {
    val res = git(workDir, "merge-base", "--is-ancestor", commit, "HEAD")
    if (res.exitCode != 0) {
      throw RuntimeException("Invalid sync state/base commit: $commit is not an ancestor of HEAD")
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

  /**
   * Runs git with a hard timeout and concurrent stderr drain. Reading stdout
   * to completion BEFORE stderr (the old pattern) deadlocks as soon as git
   * writes more than the OS pipe buffer to stderr: git blocks on the stderr
   * write, stdout never reaches EOF, and the caller blocks forever with no
   * timeout and no log line — exactly the "send hangs for 30 minutes with
   * zero logs" failure mode.
   */
  private fun git(workDir: File, vararg args: String): CmdResult {
    SyncLogger.log(workDir, "Exec: git ${args.joinToString(" ")}")
    val p = ProcessBuilder(listOf("git", *args))
      .directory(workDir)
      .redirectErrorStream(false)
      .start()
    val errSb = StringBuilder()
    val errT = Thread { errSb.append(p.errorStream.bufferedReader().readText()) }.apply { isDaemon = true }
    errT.start()
    val stdout = p.inputStream.bufferedReader().readText().trim()
    if (!p.waitFor(60, TimeUnit.SECONDS)) {
      p.destroyForcibly()
      SyncLogger.log(workDir, "Git TIMEOUT (60s): git ${args.joinToString(" ")}")
      return CmdResult(124, stdout, "timeout")
    }
    errT.join(2000)
    val stderr = errSb.toString().trim()
    if (p.exitValue() != 0) {
      SyncLogger.log(workDir, "Git failed (${p.exitValue()}): git ${args.joinToString(" ")}: $stderr")
    }
    return CmdResult(p.exitValue(), stdout, stderr)
  }

  /**
   * Runs git with stdout captured as raw bytes (for binary bundle output).
   * Returns null if the command fails or times out. Stdout and stderr are
   * drained concurrently on background threads; a hard timeout kills git so
   * a wedged child process can never freeze the send flow again.
   */
  private fun gitToStdout(workDir: File, args: List<String>, timeoutSeconds: Long = 600): ByteArray? {
    SyncLogger.log(workDir, "Exec: git ${args.joinToString(" ")}")
    val started = System.currentTimeMillis()
    val p = ProcessBuilder(listOf("git") + args)
      .directory(workDir)
      .redirectErrorStream(false)
      .start()
    val errSb = StringBuilder()
    val errT = Thread { errSb.append(p.errorStream.bufferedReader().readText()) }.apply { isDaemon = true }
    errT.start()
    val baos = ByteArrayOutputStream()
    val outT = Thread { p.inputStream.use { it.copyTo(baos) } }.apply { isDaemon = true }
    outT.start()
    if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      p.destroyForcibly()
      SyncLogger.log(workDir, "Git TIMEOUT (${timeoutSeconds}s): git ${args.joinToString(" ")}")
      return null
    }
    outT.join(5000)
    errT.join(2000)
    val exit = p.exitValue()
    val ms = System.currentTimeMillis() - started
    SyncLogger.log(workDir, "Git done in ${ms} ms (exit=$exit, ${baos.size()} bytes): git ${args.take(3).joinToString(" ")}")
    if (exit != 0 || baos.size() == 0) return null
    return baos.toByteArray()
  }
}
