package localgitmirror.idea.sync

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

object SyncLogger {
    private val ideaLog = Logger.getInstance(SyncLogger::class.java)

    fun log(dir: File, message: String) {
        ideaLog.info("[LocalGitMirror] $message")
        try {
            val gitDirRes = ProcessBuilder("git", "rev-parse", "--git-dir")
                .directory(dir)
                .redirectErrorStream(false)
                .start()
            val raw = gitDirRes.inputStream.bufferedReader().readText().trim()
            gitDirRes.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (gitDirRes.exitValue() != 0) return
            
            val gitDir = if (File(raw).isAbsolute) File(raw) else File(dir, raw)
            val logFile = File(gitDir, "lgm/sync.log")
            logFile.parentFile.mkdirs()
            
            // Log rotation: 2MB
            if (logFile.exists() && logFile.length() > 2 * 1024 * 1024) {
                logFile.delete()
            }
            
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
            logFile.appendText("[$ts] $message\n")
        } catch (e: Exception) {
            ideaLog.warn("Failed to write to repo sync.log: ${e.message}")
        }
    }
}
