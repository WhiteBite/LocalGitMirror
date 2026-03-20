package localgitmirror.idea.workkit

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object NativeStealthApply {
  private const val SYNC_STATE_PULL = ".last_sync_pull"

  data class ApplyResult(
    val ok: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int
  )

  data class BundleBranchInfo(
    val fetchRef: String,
    val branchName: String?
  )

  fun applyDump(
    workDir: File,
    password: String,
    dumpFile: File,
    mode: String,
    newBranchName: String? = null
  ): ApplyResult {
    return try {
      ensureGitRepo(workDir)
      if (!isCleanWorkTree(workDir)) {
        return ApplyResult(false, "", "Uncommitted changes detected. Commit or stash before applying.", 1)
      }

      val bundleBytes = NativeStealthDump.decryptDumpBytes(dumpFile.readBytes(), password)
      val tmpDir = File(workDir, ".localgitmirror/tmp")
      if (!tmpDir.exists()) tmpDir.mkdirs()
      val bundleFile = File(tmpDir, "apply_${System.currentTimeMillis()}.bundle")
      bundleFile.writeBytes(bundleBytes)

      try {
        val branchInfo = extractBundleBranchInfo(bundleFile, workDir)
        val fetch = git(workDir, "fetch", bundleFile.absolutePath, branchInfo.fetchRef)
        if (fetch.exitCode != 0) {
          return ApplyResult(false, "", fetch.stderr.ifBlank { fetch.stdout }.ifBlank { "Failed to fetch from bundle" }, fetch.exitCode)
        }

        val outcome = when (mode) {
          "auto" -> applyAuto(workDir, branchInfo)
          "ff-only" -> applyFfOnly(workDir)
          else -> applyNewBranch(workDir, newBranchName)
        }

        if (outcome.startsWith("ERROR:")) {
          return ApplyResult(false, "", outcome.removePrefix("ERROR:").trim(), 1)
        }

        val head = headHash(workDir)
        if (head.isNotBlank()) {
          File(workDir, SYNC_STATE_PULL).writeText(head + "\n", StandardCharsets.UTF_8)
        }

        val out = buildString {
          appendLine("[+] SUCCESS: Stealth dump applied")
          appendLine("Mode: $outcome")
          if (branchInfo.branchName != null) {
            appendLine("Source branch: ${branchInfo.branchName}")
          }
          appendLine("After:  ${if (head.isBlank()) "(empty)" else head}")
        }.trim()
        ApplyResult(true, out, "", 0)
      } finally {
        try {
          bundleFile.delete()
        } catch (_: Exception) {
        }
      }
    } catch (t: Throwable) {
      ApplyResult(false, "", t.message ?: "Stealth apply failed", 1)
    }
  }

  /**
   * AUTO mode: extract branch name from bundle. If local branch exists → ff-only merge.
   * If not → create new branch from FETCH_HEAD.
   */
  private fun applyAuto(workDir: File, branchInfo: BundleBranchInfo): String {
    val targetBranch = branchInfo.branchName
    if (targetBranch.isNullOrBlank()) {
      // Fallback: can't determine branch, use ff-only on current
      return applyFfOnly(workDir)
    }

    val localBranches = listLocalBranches(workDir)
    val branchExists = localBranches.contains(targetBranch)

    if (branchExists) {
      // Branch exists locally → checkout and ff-only merge
      val currentBranch = currentBranch(workDir)
      if (currentBranch != targetBranch) {
        val co = git(workDir, "checkout", targetBranch)
        if (co.exitCode != 0) {
          return "ERROR: Failed to checkout '$targetBranch': ${co.stderr.ifBlank { co.stdout }}"
        }
      }
      val merge = git(workDir, "merge", "--ff-only", "FETCH_HEAD")
      if (merge.exitCode != 0) {
        // ff-only failed — probably diverged. Create a suffixed branch.
        return createSuffixedBranch(workDir, targetBranch)
      }
      return "auto(ff) -> $targetBranch"
    } else {
      // Branch doesn't exist locally → create it from FETCH_HEAD
      val co = git(workDir, "checkout", "-b", targetBranch, "FETCH_HEAD")
      if (co.exitCode != 0) {
        return "ERROR: Failed to create branch '$targetBranch': ${co.stderr.ifBlank { co.stdout }}"
      }
      return "auto(new) -> $targetBranch"
    }
  }

  private fun applyFfOnly(workDir: File): String {
    val branch = currentBranch(workDir)
    if (branch.isBlank()) {
      return "ERROR: Cannot determine current branch"
    }
    val merge = git(workDir, "merge", "--ff-only", "FETCH_HEAD")
    if (merge.exitCode != 0) {
      return "ERROR: ${merge.stderr.ifBlank { merge.stdout }.ifBlank { "Fast-forward failed" }}"
    }
    return "ff-only -> $branch"
  }

  private fun applyNewBranch(workDir: File, newBranchName: String?): String {
    val baseName = (newBranchName ?: "stealth-pull-${System.currentTimeMillis()}").trim()
    var finalName = baseName
    var co = git(workDir, "checkout", "-b", finalName, "FETCH_HEAD")
    if (co.exitCode != 0 && co.stderr.contains("already exists", ignoreCase = true)) {
      for (i in 1..9) {
        finalName = "$baseName-$i"
        co = git(workDir, "checkout", "-b", finalName, "FETCH_HEAD")
        if (co.exitCode == 0) break
      }
    }
    if (co.exitCode != 0) {
      return "ERROR: Failed to create new branch '$finalName': ${co.stderr.ifBlank { co.stdout }}"
    }
    return "new-branch -> $finalName"
  }

  private fun createSuffixedBranch(workDir: File, baseName: String): String {
    for (i in 1..9) {
      val name = "$baseName-$i"
      val co = git(workDir, "checkout", "-b", name, "FETCH_HEAD")
      if (co.exitCode == 0) return "auto(diverged) -> $name"
    }
    return "ERROR: Cannot create branch based on '$baseName' (all suffixed names taken)"
  }

  /**
   * Extract branch info from a bundle file.
   * Returns the ref to fetch AND the clean branch name for auto mode.
   * Prefers non-main/non-master branches (the actual feature branch).
   */
  fun extractBundleBranchInfo(bundlePath: File, workDir: File): BundleBranchInfo {
    val r = git(workDir, "bundle", "list-heads", bundlePath.absolutePath)
    if (r.exitCode != 0) return BundleBranchInfo("HEAD", null)

    // Parse refs: each line is "<hash> <ref>"
    val refs = r.stdout.lines()
      .mapNotNull { line ->
        val parts = line.trim().split(" ", limit = 2)
        if (parts.size == 2 && parts[1].isNotBlank()) parts[1] else null
      }
      .filter { it.isNotBlank() }

    if (refs.isEmpty()) return BundleBranchInfo("HEAD", null)

    // Extract clean branch names from refs/heads/xxx
    val branchRefs = refs.filter { it.startsWith("refs/heads/") }
    val defaultRefs = setOf("refs/heads/main", "refs/heads/master")

    // Prefer feature branches (non-main, non-master) over defaults
    val featureBranch = branchRefs.firstOrNull { it !in defaultRefs }
    if (featureBranch != null) {
      val cleanName = featureBranch.removePrefix("refs/heads/")
      return BundleBranchInfo(featureBranch, cleanName)
    }

    // Fallback to any branch ref
    val anyBranch = branchRefs.firstOrNull()
    if (anyBranch != null) {
      val cleanName = anyBranch.removePrefix("refs/heads/")
      return BundleBranchInfo(anyBranch, cleanName)
    }

    // Last resort: first ref
    return BundleBranchInfo(refs.first(), null)
  }

  private fun ensureGitRepo(workDir: File) {
    val r = git(workDir, "rev-parse", "--git-dir")
    if (r.exitCode != 0) throw RuntimeException("Not a valid git repository. Run from your project directory.")
  }

  private fun currentBranch(workDir: File): String {
    val r = git(workDir, "rev-parse", "--abbrev-ref", "HEAD")
    val name = r.stdout.trim()
    if (r.exitCode != 0 || name.isBlank() || name == "HEAD") return ""
    return name
  }

  private fun headHash(workDir: File): String {
    val r = git(workDir, "rev-parse", "HEAD")
    if (r.exitCode != 0) return ""
    return r.stdout.trim()
  }

  private fun isCleanWorkTree(workDir: File): Boolean {
    val r = git(workDir, "status", "--porcelain")
    if (r.exitCode != 0) return false
    return r.stdout.trim().isEmpty()
  }

  private fun listLocalBranches(workDir: File): Set<String> {
    val r = git(workDir, "for-each-ref", "--format=%(refname:short)", "refs/heads")
    if (r.exitCode != 0) return emptySet()
    return r.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
  }

  private data class CmdResult(val exitCode: Int, val stdout: String, val stderr: String)

  private fun git(workDir: File, vararg args: String): CmdResult {
    val p = ProcessBuilder(listOf("git", *args))
      .directory(workDir)
      .redirectErrorStream(false)
      .start()
    p.waitFor(300, TimeUnit.SECONDS)
    val stdout = p.inputStream.bufferedReader().readText().trim()
    val stderr = p.errorStream.bufferedReader().readText().trim()
    return CmdResult(p.exitValue(), stdout, stderr)
  }
}
