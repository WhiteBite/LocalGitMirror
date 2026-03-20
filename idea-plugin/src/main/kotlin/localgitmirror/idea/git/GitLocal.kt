package localgitmirror.idea.git

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

object GitLocal {
  data class CommitSummary(
    val hash: String,
    val subject: String
  ) {
    fun display(): String = "$hash $subject"
  }

  data class Result(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
  ) {
    fun ok(): Boolean = exitCode == 0
  }

  fun run(project: Project, workDir: File, timeoutSeconds: Long, vararg args: String): Result {
    val cmd = mutableListOf("git")
    cmd.addAll(args)

    val pb = ProcessBuilder(cmd)
      .directory(workDir)
      .redirectErrorStream(false)

    // Avoid interactive hangs.
    val env = pb.environment()
    env["GIT_TERMINAL_PROMPT"] = "0"
    env["GCM_INTERACTIVE"] = "never"

    val proc = pb.start()
    if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      proc.destroyForcibly()
      return Result(124, "", "Timeout running: git ${args.joinToString(" ")}")
    }

    val stdout = proc.inputStream.bufferedReader().readText()
    val stderr = proc.errorStream.bufferedReader().readText()
    return Result(proc.exitValue(), stdout.trim(), stderr.trim())
  }

  fun currentBranch(project: Project, workDir: File): String? {
    val r = run(project, workDir, 10, "rev-parse", "--abbrev-ref", "HEAD")
    if (!r.ok()) return null
    val name = r.stdout.trim()
    if (name.isBlank() || name == "HEAD") return null
    return name
  }

  fun isCleanWorkTree(project: Project, workDir: File): Boolean {
    val r = run(project, workDir, 10, "status", "--porcelain")
    if (!r.ok()) return false
    return r.stdout.isBlank()
  }

  fun localBranches(project: Project, workDir: File): List<String> {
    val r = run(project, workDir, 10, "for-each-ref", "--format=%(refname:short)", "refs/heads")
    if (!r.ok()) return emptyList()
    return r.stdout
      .lines()
      .map { it.trim() }
      .filter { it.isNotBlank() }
  }

  fun push(project: Project, workDir: File, remote: String, branch: String, setUpstream: Boolean): Result {
    return if (setUpstream) {
      run(project, workDir, 300, "push", "-u", remote, branch)
    } else {
      run(project, workDir, 300, "push", remote, branch)
    }
  }

  fun checkout(project: Project, workDir: File, branchOrRef: String): Result {
    return run(project, workDir, 60, "checkout", branchOrRef)
  }

  fun checkoutNew(project: Project, workDir: File, branch: String, fromRef: String): Result {
    return run(project, workDir, 60, "checkout", "-b", branch, fromRef)
  }

  fun deleteLocalBranch(project: Project, workDir: File, branch: String, force: Boolean): Result {
    return if (force) {
      run(project, workDir, 30, "branch", "-D", branch)
    } else {
      run(project, workDir, 30, "branch", "-d", branch)
    }
  }

  fun fetch(project: Project, workDir: File, remote: String, ref: String? = null): Result {
    return if (ref.isNullOrBlank()) {
      run(project, workDir, 120, "fetch", remote)
    } else {
      run(project, workDir, 120, "fetch", remote, ref)
    }
  }

  fun pullFfOnly(project: Project, workDir: File, remote: String, branch: String): Result {
    return run(project, workDir, 180, "pull", "--ff-only", remote, branch)
  }

  fun cherryPick(project: Project, workDir: File, commitHash: String): Result {
    return run(project, workDir, 120, "cherry-pick", commitHash)
  }

  fun cherryPickAbort(project: Project, workDir: File): Result {
    return run(project, workDir, 30, "cherry-pick", "--abort")
  }

  fun remoteBranches(project: Project, workDir: File, remote: String): List<String> {
    val r = run(project, workDir, 20, "for-each-ref", "--format=%(refname:short)", "refs/remotes/$remote")
    if (!r.ok()) return emptyList()
    return r.stdout
      .lines()
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .filterNot { it.endsWith("/HEAD") }
  }

  fun recentCommits(project: Project, workDir: File, limit: Int): List<CommitSummary> {
    val n = if (limit <= 0) 30 else limit
    val r = run(project, workDir, 20, "log", "--oneline", "-n", n.toString())
    if (!r.ok()) return emptyList()
    return r.stdout
      .lines()
      .mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isBlank()) return@mapNotNull null
        val idx = trimmed.indexOf(' ')
        if (idx <= 0) return@mapNotNull null
        val hash = trimmed.substring(0, idx)
        val subj = trimmed.substring(idx + 1).trim()
        if (hash.isBlank()) return@mapNotNull null
        CommitSummary(hash, subj)
      }
  }

  fun headHash(project: Project, workDir: File): String? {
    val r = run(project, workDir, 10, "rev-parse", "HEAD")
    if (!r.ok()) return null
    val v = r.stdout.trim()
    if (v.isBlank()) return null
    return v
  }

  fun isAncestor(project: Project, workDir: File, ancestor: String, descendant: String): Boolean {
    if (ancestor.isBlank() || descendant.isBlank()) return false
    val r = run(project, workDir, 10, "merge-base", "--is-ancestor", ancestor, descendant)
    return r.exitCode == 0
  }

  fun mergeBase(project: Project, workDir: File, a: String, b: String): String? {
    if (a.isBlank() || b.isBlank()) return null
    val r = run(project, workDir, 10, "merge-base", a, b)
    if (!r.ok()) return null
    val v = r.stdout.trim()
    if (v.isBlank()) return null
    return v
  }

  fun commitCount(project: Project, workDir: File, range: String? = null): Int? {
    val r = if (range.isNullOrBlank()) {
      run(project, workDir, 20, "rev-list", "--count", "HEAD")
    } else {
      run(project, workDir, 20, "rev-list", "--count", range)
    }
    if (!r.ok()) return null
    return r.stdout.trim().toIntOrNull()
  }
}
