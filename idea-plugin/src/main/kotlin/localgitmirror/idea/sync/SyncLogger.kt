package localgitmirror.idea.sync

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object SyncLogger {
    private val ideaLog = Logger.getInstance(SyncLogger::class.java)

    // One writer at a time: sync tasks, startup focus-checks and pull actions
    // can log concurrently; unsynchronized appendText interleaves lines on
    // Windows. The git-dir is resolved once per project dir instead of
    // forking `git rev-parse` on every single log line.
    private val lock = Any()
    private val gitDirCache = ConcurrentHashMap<String, File>()

    fun log(dir: File, message: String) {
        ideaLog.info("[Sync] $message")
        try {
            val gitDir = gitDirCache[dir.absolutePath]
                ?: resolveGitDir(dir)?.also { gitDirCache[dir.absolutePath] = it }
                ?: return
            val logFile = File(gitDir, ".cache/sync.log")
            synchronized(lock) {
                logFile.parentFile.mkdirs()

                // Log rotation: 2MB
                if (logFile.exists() && logFile.length() > 2 * 1024 * 1024) {
                    logFile.delete()
                }

                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
                logFile.appendText("[$ts] $message\n")
            }
        } catch (e: Exception) {
            ideaLog.warn("Failed to write to repo sync.log: ${e.message}")
        }
    }

    private fun resolveGitDir(dir: File): File? {
        val p = ProcessBuilder("git", "rev-parse", "--git-dir")
            .directory(dir)
            .redirectErrorStream(false)
            .start()
        val raw = p.inputStream.bufferedReader().readText().trim()
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return null
        }
        if (p.exitValue() != 0) return null
        return if (File(raw).isAbsolute) File(raw) else File(dir, raw)
    }
}
