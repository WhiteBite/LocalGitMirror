package localgitmirror.idea.sync

import java.io.File

/**
 * Lightweight state store to pick best incremental bases across branches.
 * Stored per-project under .git/.cache/state (hidden inside git directory).
 */
object SyncStateStore {
  private const val STATE_SUBDIR = ".cache/state"
  private const val LAST_SENT_FILE = "last_sent.txt"
  private const val LAST_BY_BRANCH_FILE = "last_by_branch.txt"
  private const val LEGACY_LAST_SYNC = ".last_sync"
  private const val LAST_PULLED_HEAD_FILE = "last_pulled_head.txt"
  private const val LEGACY_STATE_DIR = ".localgitmirror/state"
  private const val LEGACY_TMP_DIR = ".localgitmirror/tmp"

  /** Resolve .git dir via git rev-parse (handles worktrees). */
  private fun gitDir(projectDir: File): File {
    val proc = ProcessBuilder(listOf("git", "rev-parse", "--git-dir"))
      .directory(projectDir).redirectErrorStream(false).start()
    val raw = proc.inputStream.bufferedReader().readText().trim()
    proc.waitFor()
    val f = File(raw)
    return if (f.isAbsolute) f else File(projectDir, raw)
  }

  private fun stateDir(projectDir: File): File = File(gitDir(projectDir), STATE_SUBDIR)

  /** Migrate state files from legacy .localgitmirror/state/ to .git/.cache/state/ */
  private fun migrateLegacyStateDir(projectDir: File) {
    val legacyDir = File(projectDir, LEGACY_STATE_DIR)
    if (!legacyDir.exists()) return
    val newDir = stateDir(projectDir)
    if (!newDir.exists()) newDir.mkdirs()
    for (f in legacyDir.listFiles() ?: emptyArray()) {
      if (!f.isFile) continue
      val target = File(newDir, f.name)
      if (!target.exists()) {
        try { f.copyTo(target) } catch (_: Exception) {}
      }
      try { f.delete() } catch (_: Exception) {}
    }
    try { legacyDir.delete() } catch (_: Exception) {}
    // Also clean parent .localgitmirror/ if empty
    try {
      val parent = legacyDir.parentFile
      if (parent != null && parent.name == ".localgitmirror" && (parent.listFiles()?.isEmpty() != false)) {
        parent.delete()
      }
    } catch (_: Exception) {}
  }

  fun readLastSent(projectDir: File): String? {
    migrateLegacyStateDir(projectDir)
    val f = File(stateDir(projectDir), LAST_SENT_FILE)
    if (!f.exists()) return null
    return f.readText().trim().ifBlank { null }
  }

  fun writeLastSent(projectDir: File, head: String) {
    val dir = stateDir(projectDir)
    if (!dir.exists()) dir.mkdirs()
    File(dir, LAST_SENT_FILE).writeText(head.trim() + "\n")
  }

  fun readLastByBranch(projectDir: File): Map<String, String> {
    val f = File(stateDir(projectDir), LAST_BY_BRANCH_FILE)
    if (!f.exists()) return emptyMap()
    val map = mutableMapOf<String, String>()
    for (line in f.readLines()) {
      val trimmed = line.trim()
      if (trimmed.isBlank()) continue
      val idx = trimmed.indexOf('=')
      if (idx <= 0) continue
      val k = trimmed.substring(0, idx).trim()
      val v = trimmed.substring(idx + 1).trim()
      if (k.isNotBlank() && v.isNotBlank()) map[k] = v
    }
    return map
  }

  fun writeLastByBranch(projectDir: File, map: Map<String, String>) {
    val dir = stateDir(projectDir)
    if (!dir.exists()) dir.mkdirs()
    val content = map.entries
      .sortedBy { it.key }
      .joinToString("\n") { "${it.key}=${it.value}" }
    File(dir, LAST_BY_BRANCH_FILE).writeText(content + "\n")
  }

  fun updateAfterSend(projectDir: File, branch: String, head: String) {
    writeLastSent(projectDir, head)
    val m = readLastByBranch(projectDir).toMutableMap()
    if (branch.isNotBlank() && head.isNotBlank()) {
      m[branch] = head
      writeLastByBranch(projectDir, m)
    }
  }

  fun readLastPulledHead(projectDir: File): String? {
    val f = File(stateDir(projectDir), LAST_PULLED_HEAD_FILE)
    if (!f.exists()) return null
    return f.readText().trim().ifBlank { null }
  }

  fun writeLastPulledHead(projectDir: File, head: String) {
    val dir = stateDir(projectDir)
    if (!dir.exists()) dir.mkdirs()
    File(dir, LAST_PULLED_HEAD_FILE).writeText(head.trim() + "\n")
  }

  fun migrateLegacyIfPresent(projectDir: File) {
    migrateLegacyStateDir(projectDir)
    val legacy = File(projectDir, LEGACY_LAST_SYNC)
    if (!legacy.exists()) return
    val hash = legacy.readText().trim().ifBlank { null } ?: return
    // Best-effort migrate to new store.
    writeLastSent(projectDir, hash)
    try {
      legacy.delete()
    } catch (_: Exception) {
      // ignore
    }
  }

  fun cleanupOldSyncFiles(projectDir: File, keepPerRepo: Int = 5) {
    val gitDir = gitDir(projectDir)
    val newDir = File(gitDir, ".cache")

    // Also clean legacy location
    val legacyDir = File(projectDir, LEGACY_TMP_DIR)

    for (dir in listOf(newDir, legacyDir)) {
      if (!dir.exists()) continue
      val files = dir.listFiles { f ->
        f.isFile && (
          f.name.startsWith(".tmp_") ||
          (f.name.startsWith("cache_") && f.name.endsWith(".bin")) ||
          (f.name.startsWith("dump_") && f.name.endsWith(".dmp"))
        )
      }?.toList() ?: continue

      val sorted = files.sortedByDescending { it.lastModified() }
      for (f in sorted.drop(keepPerRepo)) {
        try { f.delete() } catch (_: Exception) {}
      }
    }
  }

  /** @deprecated Use [cleanupOldSyncFiles] instead. */
  fun cleanupOldDumps(projectDir: File, keepPerRepo: Int = 5) = cleanupOldSyncFiles(projectDir, keepPerRepo)
}
