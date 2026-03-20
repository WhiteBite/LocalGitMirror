package localgitmirror.idea.sync

import java.io.File

/**
 * Lightweight state store to pick best incremental bases across branches.
 * Stored per-project under .localgitmirror/state.
 */
object SyncStateStore {
  private const val STATE_DIR = ".localgitmirror/state"
  private const val LAST_SENT_FILE = "last_sent.txt"
  private const val LAST_BY_BRANCH_FILE = "last_by_branch.txt"
  private const val LEGACY_LAST_SYNC = ".last_sync"
  private const val LAST_PULLED_HEAD_FILE = "last_pulled_head.txt"
  private const val TMP_DIR = ".localgitmirror/tmp"

  private fun stateDir(projectDir: File): File = File(projectDir, STATE_DIR)

  fun readLastSent(projectDir: File): String? {
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

  fun cleanupOldDumps(projectDir: File, keepPerRepo: Int = 5) {
    val dir = File(projectDir, TMP_DIR)
    if (!dir.exists()) return

    val dumps = dir.listFiles { f ->
      f.isFile && f.name.startsWith("dump_") && f.name.endsWith(".dmp")
    }?.toList() ?: return

    val grouped = dumps.groupBy { file ->
      // dump_<repo>_YYYYMMDD_HHMM.dmp -> repo part between first and last two '_' parts.
      val name = file.name.removePrefix("dump_").removeSuffix(".dmp")
      val parts = name.split("_")
      if (parts.size < 3) "__unknown__" else parts.dropLast(2).joinToString("_")
    }

    for ((_, files) in grouped) {
      val sorted = files.sortedByDescending { it.lastModified() }
      val toDelete = sorted.drop(keepPerRepo)
      for (f in toDelete) {
        try {
          f.delete()
        } catch (_: Exception) {
          // best effort
        }
      }
    }
  }
}
