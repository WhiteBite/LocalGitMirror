package localgitmirror.idea.git

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object GitDirCache {
  private val cache = ConcurrentHashMap<String, File>()
  private val spawns = AtomicInteger(0)

  fun gitDir(projectDir: File): File =
    cache.computeIfAbsent(projectDir.canonicalPath) { discover(projectDir) }

  fun invalidate(projectDir: File) {
    cache.remove(projectDir.canonicalPath)
  }

  internal fun spawnCount(): Int = spawns.get()

  internal fun clearAll() {
    cache.clear()
    spawns.set(0)
  }

  private fun discover(projectDir: File): File {
    spawns.incrementAndGet()
    val pb = ProcessBuilder("git", "rev-parse", "--git-dir")
      .directory(projectDir)
      .redirectErrorStream(false)
    val env = pb.environment()
    env["GIT_TERMINAL_PROMPT"] = "0"
    env["GCM_INTERACTIVE"] = "never"
    val proc = pb.start()
    val outSb = StringBuilder()
    val errSb = StringBuilder()
    // drain both streams before waitFor: a full pipe buffer otherwise deadlocks the wait
    val outT = Thread { outSb.append(proc.inputStream.bufferedReader().readText()) }.apply { isDaemon = true }
    val errT = Thread { errSb.append(proc.errorStream.bufferedReader().readText()) }.apply { isDaemon = true }
    outT.start()
    errT.start()
    if (!proc.waitFor(15, TimeUnit.SECONDS)) {
      proc.destroyForcibly()
      outT.join(2000)
      errT.join(2000)
      throw IOException("timeout running git rev-parse --git-dir in ${projectDir.absolutePath}")
    }
    outT.join(2000)
    errT.join(2000)
    if (proc.exitValue() != 0) {
      throw IOException("git rev-parse --git-dir failed (exit=${proc.exitValue()}) in ${projectDir.absolutePath}: ${errSb.toString().trim()}")
    }
    val raw = outSb.toString().trim()
    if (raw.isEmpty()) {
      throw IOException("git rev-parse --git-dir returned empty output in ${projectDir.absolutePath}")
    }
    val f = File(raw)
    return (if (f.isAbsolute) f else File(projectDir, raw)).canonicalFile
  }
}
