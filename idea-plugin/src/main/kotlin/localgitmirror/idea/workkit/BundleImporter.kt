package localgitmirror.idea.workkit

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object BundleImporter {
  private const val SYNC_STATE_PULL = ".pull-state"

  data class ApplyResult(
    val ok: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int
  )

  data class BundleBranchInfo(
    val fetchRef: String,
    val cleanName: String,
    val hash: String
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

      val bundleBytes = BundleCrypto.decryptDumpBytes(dumpFile.readBytes(), password)
      val gitDirRes = ProcessBuilder(listOf("git", "rev-parse", "--git-dir"))
        .directory(workDir).redirectErrorStream(false).start()
      val rawGitDir = gitDirRes.inputStream.bufferedReader().readText().trim()
      gitDirRes.waitFor()
      val gitDir = if (java.io.File(rawGitDir).isAbsolute) java.io.File(rawGitDir) else File(workDir, rawGitDir)
      val tmpDir = File(gitDir, ".cache")
      if (!tmpDir.exists()) tmpDir.mkdirs()
      val bundleFile = File(tmpDir, ".tmp_${java.util.UUID.randomUUID().toString().take(8)}")
      bundleFile.writeBytes(bundleBytes)

      try {
        val branchInfos = extractAllBundleBranches(bundleFile, workDir)
        if (branchInfos.isEmpty()) {
           return ApplyResult(false, "", "No branches found in bundle", 1)
        }

        // Fetch ALL refs from the bundle to local repo directly
        // Syntax: git fetch bundle.bundle +refs/heads/*:refs/remotes/bundle/* (or just direct target hashes)
        // A simpler way: we just fetch the hashes directly so they are in the object database:
        val fetchArgs = branchInfos.map { it.hash }.toTypedArray()
        val fetch = git(workDir, "fetch", bundleFile.absolutePath, *fetchArgs)
        if (fetch.exitCode != 0) {
          return ApplyResult(false, "", fetch.stderr.ifBlank { fetch.stdout }.ifBlank { "Failed to fetch from bundle" }, fetch.exitCode)
        }

        val outcome = when (mode) {
           // We map auto to the new multi-branch logic
          "auto" -> applyMultiAuto(workDir, branchInfos)
          "ff-only" -> applyFfOnly(workDir, branchInfos.firstOrNull()?.hash ?: "FETCH_HEAD")
          else -> applyNewBranch(workDir, newBranchName, branchInfos.firstOrNull()?.hash ?: "FETCH_HEAD")
        }

        if (outcome.startsWith("ERROR:")) {
          return ApplyResult(false, "", outcome.removePrefix("ERROR:").trim(), 1)
        }

        val head = headHash(workDir)
        if (head.isNotBlank()) {
          File(gitDir, SYNC_STATE_PULL).writeText(head + "\n", StandardCharsets.UTF_8)
        }

        val out = buildString {
          appendLine("[+] Sync import applied")
          appendLine("Mode: $mode")
          appendLine("Result:\n$outcome")
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
      ApplyResult(false, "", t.message ?: "Sync import failed", 1)
    }
  }

  /**
   * Applies all branches found in the bundle securely.
   */
  private fun applyMultiAuto(workDir: File, branchInfos: List<BundleBranchInfo>): String {
    val localBranches = listLocalBranches(workDir)
    val preBranch = currentBranch(workDir)

    val results = mutableListOf<String>()
    
    // Sort branches so we apply defaults last (makes UI nicer, preferred feature branch usually first)
    val sortedBranches = branchInfos.sortedBy { 
       if (it.cleanName in listOf("main", "master")) 1 else 0 
    }

    for (info in sortedBranches) {
       val targetBranch = info.cleanName
       val hash = info.hash
       
       if (localBranches.contains(targetBranch)) {
          val isDescendant = git(workDir, "merge-base", "--is-ancestor", targetBranch, hash).exitCode == 0
          if (isDescendant) {
             // Safe to fast-forward
             val update = git(workDir, "update-ref", "refs/heads/$targetBranch", hash)
             if (update.exitCode == 0) {
                results.add("  - auto(ff) -> $targetBranch")
             } else {
                results.add("  - ERROR: Failed to update $targetBranch: ${update.stderr.ifBlank { update.stdout }}")
             }
          } else {
             // Diverged: do not destructively update, create a suffixed branch
             val suffixed = createSuffixedBranch(workDir, targetBranch, hash)
             results.add("  - auto(diverged) -> $suffixed")
          }
       } else {
          // Doesn't exist locally: create it
          val create = git(workDir, "branch", targetBranch, hash)
          if (create.exitCode == 0) {
             results.add("  - auto(new) -> $targetBranch")
          } else {
             results.add("  - ERROR: Failed to create $targetBranch: ${create.stderr.ifBlank { create.stdout }}")
          }
       }
    }

    // Now decide which branch to checkout and stay on
    // We prefer the feature branch (first non-main/master), then main/master, then pre-branch
    val preferredBranch = sortedBranches.firstOrNull()?.cleanName ?: preBranch
    if (preferredBranch != preBranch && preferredBranch.isNotBlank()) {
        val checkout = git(workDir, "checkout", preferredBranch)
        if (checkout.exitCode != 0) {
            results.add("  - WARN: Failed to auto-checkout $preferredBranch")
        } else {
            // Because we did update-ref, if we checked out the branch we need to reset hard to the working tree
            // Actually, if we're currently ON preferredBranch and we updated its tip via update-ref, 
            // the working directory doesn't reflect the new tip!
            // Wait, if preBranch == preferredBranch, we did NOT checkout.
            // If we did NOT checkout, we need to ensure the working tree matches the updated ref!
        }
    }

    // CRITICAL: if the current branch was updated via update-ref, the working tree is out of sync.
    // Since we verified the tree is clean at the start, we can safely reset --hard HEAD to sync the working tree.
    val currentNow = currentBranch(workDir)
    git(workDir, "reset", "--hard", "HEAD")
    git(workDir, "clean", "-fd")

    results.add("\nChecked out: $currentNow")
    return results.joinToString("\n")
  }

  private fun applyFfOnly(workDir: File, hash: String): String {
    val branch = currentBranch(workDir)
    if (branch.isBlank()) {
      return "ERROR: Cannot determine current branch"
    }
    val merge = git(workDir, "merge", "--ff-only", hash)
    if (merge.exitCode != 0) {
      return "ERROR: ${merge.stderr.ifBlank { merge.stdout }.ifBlank { "Fast-forward failed" }}"
    }
    return "ff-only -> $branch"
  }

  private fun applyNewBranch(workDir: File, newBranchName: String?, hash: String): String {
    val baseName = (newBranchName ?: "sync-import-${System.currentTimeMillis()}").trim()
    var finalName = baseName
    var co = git(workDir, "checkout", "-b", finalName, hash)
    if (co.exitCode != 0 && co.stderr.contains("already exists", ignoreCase = true)) {
      for (i in 1..9) {
        finalName = "$baseName-$i"
        co = git(workDir, "checkout", "-b", finalName, hash)
        if (co.exitCode == 0) break
      }
    }
    if (co.exitCode != 0) {
      return "ERROR: Failed to create new branch '$finalName': ${co.stderr.ifBlank { co.stdout }}"
    }
    return "new-branch -> $finalName"
  }

  private fun createSuffixedBranch(workDir: File, baseName: String, hash: String): String {
    for (i in 1..99) {
      val name = "$baseName-$i"
      val co = git(workDir, "branch", name, hash)
      if (co.exitCode == 0) return name
    }
    return "ERROR: suffix taken"
  }

  /**
   * Extract ALL branch info from a bundle file.
   */
  fun extractAllBundleBranches(bundlePath: File, workDir: File): List<BundleBranchInfo> {
    val r = git(workDir, "bundle", "list-heads", bundlePath.absolutePath)
    if (r.exitCode != 0) return emptyList()

    // Parse refs: each line is "<hash> <ref>"
    val list = mutableListOf<BundleBranchInfo>()
    for (line in r.stdout.lines()) {
        val parts = line.trim().split(" ", limit = 2)
        if (parts.size == 2 && parts[1].isNotBlank()) {
            val hash = parts[0]
            val ref = parts[1]
            if (ref.startsWith("refs/heads/")) {
                list.add(BundleBranchInfo(ref, ref.removePrefix("refs/heads/"), hash))
            }
        }
    }
    return list
  }

  // Legacy method kept for backwards compatibility if needed elsewhere
  fun extractBundleBranchInfo(bundlePath: File, workDir: File): BundleBranchInfo {
    val all = extractAllBundleBranches(bundlePath, workDir)
    return all.firstOrNull { it.cleanName !in setOf("main", "master") } 
        ?: all.firstOrNull() 
        ?: BundleBranchInfo("HEAD", "unknown", "HEAD")
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
