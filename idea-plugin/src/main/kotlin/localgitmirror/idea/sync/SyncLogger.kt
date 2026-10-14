package localgitmirror.idea.sync

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import localgitmirror.idea.git.GitDirCache

object SyncLogger {
    private val ideaLog = Logger.getInstance(SyncLogger::class.java)

    // One writer at a time: sync tasks, startup focus-checks and pull actions
    // can log concurrently; unsynchronized appendText interleaves lines on
    // Windows.
    private val lock = Any()

    fun log(dir: File, message: String) {
        ideaLog.info("[DocCache] $message")
        try {
            val gitDir = try {
                GitDirCache.gitDir(dir)
            } catch (e: IOException) {
                return
            }
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
}
