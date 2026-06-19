package localgitmirror.idea.deps

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Diagnostics sink for the deps-sync feature.
 *
 * STEALTH: we never write anything into the project directory. Listing
 * corporate package names inside a git working tree would (a) show up in
 * `git status`, (b) risk being committed, and (c) leak the internal supply
 * chain. Instead all diagnostics go to:
 *
 *   1. the IDE log (Help > Show Log), and
 *   2. a single rotating file under the IDE's own log dir, well outside any
 *      project tree: <idea-log>/localgitmirror/deps-diag.log
 *
 * The file is opt-in via [enabled]; when false only the IDE log is touched
 * (and even that omits raw coordinate names unless [verbose]).
 */
object DepsDiagnostics {
  private val ideaLog = Logger.getInstance(DepsDiagnostics::class.java)
  private val ts get() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
  private const val MAX_BYTES = 1_024 * 1024  // 1 MB, then truncated

  /** Master switch for the on-disk diagnostic file. Off by default = stealth. */
  @Volatile var enabled: Boolean = false

  /** When true, the on-disk file may include coordinate labels (names/versions). */
  @Volatile var verbose: Boolean = false

  private fun diagFile(): File {
    val dir = File(PathManager.getLogPath(), "localgitmirror")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "deps-diag.log")
  }

  /** Short, name-free status line — always safe to emit to the IDE log. */
  fun event(message: String) {
    ideaLog.info("[deps] $message")
    if (enabled) append("[$ts] $message")
  }

  /**
   * Detailed block (may contain coordinate labels). Only persisted when both
   * [enabled] and [verbose] are on; otherwise reduced to a counts-only line.
   */
  fun detail(title: String, lines: () -> List<String>) {
    if (!enabled) { ideaLog.debug("[deps] $title (${lines().size} lines, file disabled)"); return }
    if (!verbose) { append("[$ts] $title — ${lines().size} entries (verbose off, names hidden)"); return }
    val body = buildString {
      appendLine("[$ts] $title")
      lines().forEach { appendLine("    $it") }
    }
    append(body)
  }

  private fun append(text: String) {
    try {
      val f = diagFile()
      if (f.exists() && f.length() > MAX_BYTES) f.writeText("")  // simple rotation
      f.appendText(text.trimEnd('\n') + "\n", Charsets.UTF_8)
    } catch (t: Throwable) {
      ideaLog.warn("deps diagnostics write failed: ${t.message}")
    }
  }

  /** Absolute path of the diagnostic file, for surfacing in notifications. */
  fun diagFilePath(): String = diagFile().absolutePath
}
