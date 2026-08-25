package localgitmirror.idea.git

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import localgitmirror.idea.sync.SyncLogger

/**
 * Background object-store maintenance. Fragmented stores (dozens of packs,
 * thousands of loose objects) make every graph-walk git command
 * (merge-base, log, is-ancestor) seconds slow on Windows + AV — which is
 * what turned sync negotiation into multi-minute hangs. Instead of asking
 * the user to run `git gc` manually, the plugin consolidates the store
 * itself: fire-and-forget, throttled to once per day per repo, only when
 * fragmentation crosses a threshold, never on the sync path.
 */
object RepoMaintenance {
  private val ideaLog = Logger.getInstance(RepoMaintenance::class.java)

  private const val PACKS_THRESHOLD = 20
  private const val LOOSE_THRESHOLD = 1000
  private const val MIN_INTERVAL_MS = 24L * 60 * 60 * 1000 // once per day
  private const val GC_TIMEOUT_SECONDS = 600L

  /** Fire-and-forget: run `git gc` in a daemon thread if the store is fragmented. */
  fun autoGcIfNeeded(project: Project, workDir: File) {
    val t = Thread({
      try {
        run(project, workDir)
      } catch (e: Exception) {
        ideaLog.warn("[auto-gc] failed: ${e.message}")
      }
    }, "LGM-auto-gc")
    t.isDaemon = true
    t.start()
  }

  private fun run(project: Project, workDir: File) {
    val gitDirRes = GitLocal.run(project, workDir, 10, "rev-parse", "--git-dir")
    if (!gitDirRes.ok()) return
    val raw = gitDirRes.stdout.trim()
    val gitDir = if (File(raw).isAbsolute) File(raw) else File(workDir, raw)
    val stamp = File(gitDir, ".cache/last-auto-gc")
    val now = System.currentTimeMillis()
    if (stamp.exists() && now - stamp.lastModified() < MIN_INTERVAL_MS) return

    val stats = GitLocal.objectStoreStats(project, workDir) ?: return
    if (stats.packs <= PACKS_THRESHOLD && stats.looseCount <= LOOSE_THRESHOLD) {
      writeStamp(stamp)
      return
    }

    SyncLogger.log(
      workDir,
      "[auto-gc] object store fragmented (packs=${stats.packs}, loose=${stats.looseCount}) — running git gc in background"
    )
    val res = GitLocal.run(project, workDir, GC_TIMEOUT_SECONDS, "gc", "--quiet")
    SyncLogger.log(workDir, "[auto-gc] finished exit=${res.exitCode} ${res.stderr.take(200)}")
    writeStamp(stamp)
  }

  private fun writeStamp(stamp: File) {
    try {
      stamp.parentFile?.mkdirs()
      stamp.writeText(System.currentTimeMillis().toString())
    } catch (e: Exception) {
      ideaLog.warn("[auto-gc] cannot write stamp: ${e.message}")
    }
  }
}
