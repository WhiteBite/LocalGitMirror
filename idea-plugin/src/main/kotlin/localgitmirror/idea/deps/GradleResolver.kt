package localgitmirror.idea.deps

import com.google.gson.Gson
import java.io.File

/**
 * Ask Gradle itself which artifacts the project needs.
 *
 * We drop a tiny init-script next to a temp file and run `gradle help` (the
 * cheapest task that triggers project configuration). The init-script walks
 * every configuration on every project AND the rootProject's buildscript
 * classpath (= Gradle plugins like `com.diffplug.spotless`) and writes one
 * JSON-line per resolved artifact:
 *
 *     {"g":"...","n":"...","v":"...","f":"<absolute path to file in cache>"}
 *
 * No origin URL is needed — if Gradle resolved it for THIS project, then
 * the project NEEDS it. That's stronger than any sidecar/group heuristic.
 *
 * Falls back gracefully: on resolution failure (offline, missing Nexus, …)
 * returns an empty list and lets the caller decide what to do.
 */
object GradleResolver {

  data class ResolvedArtifact(
    val g: String,
    val n: String,
    val v: String,
    val f: String   // absolute file path in the local Gradle cache
  )

  data class Result(
    val ok: Boolean,
    val artifacts: List<ResolvedArtifact>,
    val log: String,
    val durationMs: Long
  )

  /**
   * Run the project's gradle (`gradlew`/`gradlew.bat` if present, otherwise
   * system `gradle`) with a temporary init-script, returning every artifact
   * Gradle had to resolve.
   *
   * @param projectDir   project root containing build.gradle(.kts)
   * @param timeoutSec   hard timeout; we don't want a hung gradle daemon
   *                     to freeze the IDE
   * @param extraArgs    extra arguments — typically empty; a caller can add
   *                     "--offline" to verify the cache is self-sufficient
   */
  fun resolve(
    projectDir: File,
    timeoutSec: Long = 300,
    extraArgs: List<String> = emptyList()
  ): Result {
    val started = System.currentTimeMillis()
    if (!projectDir.exists() || !projectDir.isDirectory) {
      return Result(false, emptyList(), "projectDir not found: ${projectDir.absolutePath}", 0)
    }

    val initScript = File.createTempFile("lgm-resolve-", ".gradle")
    val outputFile = File.createTempFile("lgm-resolved-", ".jsonl")
    try {
      initScript.writeText(buildInitScript(outputFile))

      val gradleCmd = pickGradleCommand(projectDir)
      val cmd = mutableListOf<String>().apply {
        addAll(gradleCmd)
        add("--init-script"); add(initScript.absolutePath)
        add("-q")                              // quiet — we only care about errors
        add("--no-daemon")                     // don't leave a daemon behind on dev machines
        addAll(extraArgs)
        add("help")                            // cheapest task that still configures projects
      }

      val pb = ProcessBuilder(cmd).directory(projectDir).redirectErrorStream(true)
      val proc = pb.start()
      val stdoutCollector = StringBuilder()
      val readerThread = Thread {
        proc.inputStream.bufferedReader().useLines { it.forEach { line -> stdoutCollector.appendLine(line) } }
      }
      readerThread.start()

      val finished = proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
      if (!finished) {
        proc.destroyForcibly()
        readerThread.join(2_000)
        return Result(false, emptyList(),
          "gradle resolve timed out after ${timeoutSec}s\n${stdoutCollector.takeLast(2000)}",
          System.currentTimeMillis() - started)
      }
      readerThread.join(5_000)

      val artifacts = parseJsonLines(outputFile)
      val ok = proc.exitValue() == 0
      return Result(
        ok = ok && artifacts.isNotEmpty(),
        artifacts = artifacts,
        log = stdoutCollector.takeLast(4000).toString(),
        durationMs = System.currentTimeMillis() - started
      )
    } catch (t: Throwable) {
      return Result(false, emptyList(), "gradle resolve failed: ${t.message}", System.currentTimeMillis() - started)
    } finally {
      try { initScript.delete() } catch (_: Exception) {}
      try { outputFile.delete() } catch (_: Exception) {}
    }
  }

  /** Pick `./gradlew`/`gradlew.bat` if the project ships a wrapper; else system gradle. */
  internal fun pickGradleCommand(projectDir: File): List<String> {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val wrapperBat = File(projectDir, "gradlew.bat")
    val wrapperSh = File(projectDir, "gradlew")
    return when {
      isWindows && wrapperBat.exists() -> listOf(wrapperBat.absolutePath)
      !isWindows && wrapperSh.exists() -> listOf(wrapperSh.absolutePath)
      else -> listOf(if (isWindows) "gradle.bat" else "gradle")
    }
  }

  /**
   * The init-script that does the actual work. It runs INSIDE Gradle, so it
   * has full access to the project model — much more reliable than parsing
   * `build.gradle` ourselves.
   *
   * For each project: every configuration that `isCanBeResolved`, plus the
   * rootProject's buildscript classpath (= the plugins like com.diffplug.spotless).
   * For each artifact: `{ "g": ..., "n": ..., "v": ..., "f": "<abs path>" }`.
   *
   * Output is written to [outputFile] one JSON-object per line so we don't
   * depend on JSON pretty-printing.
   */
  private fun buildInitScript(outputFile: File): String {
    // Use a forward-slash path so the Groovy single-quoted literal works on Windows too
    val outPath = outputFile.absolutePath.replace('\\', '/')
    // Groovy script body — Kotlin string interpolation puts only outPath in.
    // Inside Groovy: backslashes are escaped \\ to produce a single backslash literal,
    // and \n / \\ in the Groovy single-quoted strings are written as \\n and \\\\.
    return """
allprojects { p ->
  p.afterEvaluate {
    def out = new java.io.FileWriter('$outPath', true)
    try {
      p.configurations.each { conf ->
        if (conf.canBeResolved) {
          try {
            conf.resolvedConfiguration.resolvedArtifacts.each { a ->
              def id = a.moduleVersion.id
              out.write('{"g":"' + id.group + '","n":"' + id.name + '","v":"' + id.version + '","f":"' + a.file.absolutePath.replace('\\', '/') + '"}\n')
            }
          } catch (Throwable ignored) { }
        }
      }
      if (p == p.rootProject) {
        try {
          p.buildscript.configurations.classpath.resolvedConfiguration.resolvedArtifacts.each { a ->
            def id = a.moduleVersion.id
            out.write('{"g":"' + id.group + '","n":"' + id.name + '","v":"' + id.version + '","f":"' + a.file.absolutePath.replace('\\', '/') + '"}\n')
          }
        } catch (Throwable ignored) { }
      }
    } finally { out.close() }
  }
}
""".trimIndent()
  }

  private fun parseJsonLines(file: File): List<ResolvedArtifact> {
    if (!file.exists()) return emptyList()
    val gson = Gson()
    val seen = LinkedHashSet<String>()
    val out = mutableListOf<ResolvedArtifact>()
    for (line in file.readLines()) {
      val trimmed = line.trim()
      if (trimmed.isEmpty()) continue
      try {
        val a = gson.fromJson(trimmed, ResolvedArtifact::class.java) ?: continue
        val key = "${a.g}:${a.n}:${a.v}:${a.f}"
        if (seen.add(key)) out.add(a)
      } catch (_: Exception) { /* skip malformed lines */ }
    }
    return out
  }
}
