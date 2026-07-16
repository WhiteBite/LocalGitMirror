package localgitmirror.idea.actions

import java.io.File

/**
 * Pure pull-side logic extracted from PullFromMirrorAction for testability.
 * No IntelliJ/project dependencies — all side-effects injected as lambdas.
 */
internal object PullLogic {

  /**
   * A raw git bundle starts with "# v2 git bundle\n" or "# v3 git bundle\n".
   * Hybrid pull writes the server response as a raw bundle, while legacy pull
   * writes a password-encrypted dump that must be decrypted first.
   */
  fun looksLikeGitBundle(bytes: ByteArray): Boolean {
    if (bytes.size < 15) return false
    val header = String(bytes, 0, minOf(16, bytes.size), Charsets.US_ASCII)
    return header.startsWith("# v2 git bundle") || header.startsWith("# v3 git bundle")
  }

  /**
   * Returns the best "since" hash to minimise bundle size:
   * the first local ref hash that also appears in the remote refs map.
   *
   * @param dir project directory (used to run git for-each-ref)
   * @param remoteRefs map of branchName → hash from Mirror
   * @param forEachRef supplier of git for-each-ref output (injected for testing)
   */
  fun findSinceHash(
    dir: File,
    remoteRefs: Map<String, String>,
    forEachRef: (Array<out String>) -> String = { args ->
      val p = ProcessBuilder(listOf("git", *args)).directory(dir).start()
      p.inputStream.bufferedReader().readText().also { p.waitFor() }.trim()
    }
  ): String? {
    if (remoteRefs.isEmpty()) return null
    val remoteHashes = remoteRefs.values.toSet()
    val localHashes = forEachRef(arrayOf("for-each-ref", "--format=%(objectname)", "refs/heads/"))
      .lines().map { it.trim() }.filter { it.isNotBlank() }
    return localHashes.firstOrNull { it in remoteHashes }
  }

  /**
   * Apply a single branch ref locally. Returns a human-readable result string.
   * All git operations are injected so this is fully unit-testable.
   *
   * @param branchName  the branch name to update/create
   * @param newHash     the target commit hash from Mirror
   * @param localBranches set of locally existing branch names
   * @param revParse    resolves a ref to its current hash
   * @param isAncestor  returns true if [a] is an ancestor of [d]
   * @param updateRef   updates refs/heads/[ref] to [hash]
   * @param createBranch creates a new branch [ref] at [hash] (used for new branches)
   * @param createSuffixedBranch creates a backup branch and returns its name
   */
  fun applyBranch(
    branchName: String,
    newHash: String,
    localBranches: Set<String>,
    revParse: (String) -> String,
    isAncestor: (ancestor: String, descendant: String) -> Boolean,
    updateRef: (ref: String, hash: String) -> Unit,
    createBranch: (ref: String, hash: String) -> Unit,
    createSuffixedBranch: (base: String, hash: String) -> String
  ): String {
    return if (localBranches.contains(branchName)) {
      val localHash = revParse(branchName)
      when {
        localHash == newHash ->
          "уже актуальна (${newHash.take(7)})"

        isAncestor(branchName, newHash) -> {
          // Local is an ancestor of remote — clean fast-forward
          updateRef(branchName, newHash)
          "обновлена до ${newHash.take(7)}"
        }

        else -> {
          // Histories diverged — save the local branch under a backup name
          val backup = createSuffixedBranch(branchName, localHash)
          updateRef(branchName, newHash)
          "расходилась (локальная сохранена в «$backup»), обновлена до ${newHash.take(7)}"
        }
      }
    } else {
      createBranch(branchName, newHash)
      "создана новая ветка ${newHash.take(7)}"
    }
  }
}
