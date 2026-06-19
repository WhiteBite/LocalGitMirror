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
   * @param javaHome     explicit JAVA_HOME to pass to the gradle process.
   *                     If null, falls back to the JDK running IntelliJ
   *                     (`java.home`). NEVER trusts the user's env var,
   *                     because in practice it's often broken (points at
   *                     `\bin` subdir, missing, set to deleted JDK, etc.).
   */
  fun resolve(
    projectDir: File,
    timeoutSec: Long = 300,
    extraArgs: List<String> = emptyList(),
    javaHome: String? = null
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

      // Override JAVA_HOME for the gradle subprocess so a broken user env
      // (the classic ".../jdk-25/bin" instead of ".../jdk-25") doesn't fail us.
      val effectiveJavaHome = resolveJavaHome(javaHome)
      if (effectiveJavaHome != null) {
        pb.environment()["JAVA_HOME"] = effectiveJavaHome
        // Some setups also read JDK_HOME; keep them in sync.
        pb.environment()["JDK_HOME"] = effectiveJavaHome
      }

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
      val effectiveJavaNote = if (effectiveJavaHome != null) " (JAVA_HOME=$effectiveJavaHome)" else ""
      return Result(
        ok = ok && artifacts.isNotEmpty(),
        artifacts = artifacts,
        log = (stdoutCollector.takeLast(4000).toString() + effectiveJavaNote),
        durationMs = System.currentTimeMillis() - started
      )
    } catch (t: Throwable) {
      return Result(false, emptyList(), "gradle resolve failed: ${t.message}", System.currentTimeMillis() - started)
    } finally {
      try { initScript.delete() } catch (_: Exception) {}
      try { outputFile.delete() } catch (_: Exception) {}
    }
  }

  /**
   * Pick a JAVA_HOME we know works:
   *   1. Caller-provided (e.g. ProjectSdk path from IntelliJ)
   *   2. The JDK currently running this code (always real, always at least
   *      whatever IntelliJ itself is using, which is JDK 17+)
   * NEVER falls through to the user's environment, because that env is
   * exactly what we're trying to work around.
   *
   * Also defensively unwraps the broken `\bin` suffix some users have:
   * `C:\Users\x\jdk-25\bin` -> `C:\Users\x\jdk-25`.
   */
  internal fun resolveJavaHome(explicit: String?): String? {
    val candidate = explicit?.takeIf { it.isNotBlank() }
      ?: System.getProperty("java.home")?.takeIf { it.isNotBlank() }
      ?: return null
    val cleaned = unwrapBinSuffix(candidate)
    val javaBin = File(cleaned, "bin/" + (if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"))
    return if (javaBin.exists()) cleaned else null
  }

  /** "C:/Users/x/jdk-25/bin" -> "C:/Users/x/jdk-25". Anything else is returned untouched. */
  internal fun unwrapBinSuffix(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    val sep = if (trimmed.contains('\\')) '\\' else '/'
    val tail = trimmed.substringAfterLast(sep)
    return if (tail.equals("bin", ignoreCase = true)) trimmed.substringBeforeLast(sep) else trimmed
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
    val outPath = outputFile.absolutePath.replace('\\', '/')
    return """
// LocalGitMirror init-script — reports every artifact gradle resolves for
// this project, including plugin classpaths and settings pluginManagement.
def lgmWrite(out, group, name, version, file) {
  out.write('{"g":"' + group + '","n":"' + name + '","v":"' + version + '","f":"' + file.absolutePath.replace('\\', '/') + '"}\n')
}

settingsEvaluated { settings ->
  // Settings-script plugins (e.g. foojay-resolver-convention) live here
  def out = new java.io.FileWriter('$outPath', true)
  try {
    try {
      settings.buildscript.configurations.classpath.resolvedConfiguration.resolvedArtifacts.each { a ->
        def id = a.moduleVersion.id
        lgmWrite(out, id.group, id.name, id.version, a.file)
      }
    } catch (Throwable ignored) { }
    try {
      settings.pluginManagement.plugins.each { /* declared plugins go through buildscript */ }
    } catch (Throwable ignored) { }
  } finally { out.close() }
}

allprojects { p ->
  p.afterEvaluate {
    def out = new java.io.FileWriter('$outPath', true)
    try {
      // Project configurations (compile/runtime/test/etc.)
      p.configurations.each { conf ->
        if (conf.canBeResolved) {
          try {
            conf.resolvedConfiguration.resolvedArtifacts.each { a ->
              def id = a.moduleVersion.id
              lgmWrite(out, id.group, id.name, id.version, a.file)
            }
          } catch (Throwable ignored) { }
        }
      }
      // Buildscript classpath of root project (build.gradle plugins)
      if (p == p.rootProject) {
        try {
          p.buildscript.configurations.classpath.resolvedConfiguration.resolvedArtifacts.each { a ->
            def id = a.moduleVersion.id
            lgmWrite(out, id.group, id.name, id.version, a.file)
          }
        } catch (Throwable ignored) { }
      }
      // Buildscript classpath of subprojects (separate plugin classpaths)
      try {
        p.buildscript.configurations.classpath.resolvedConfiguration.resolvedArtifacts.each { a ->
          def id = a.moduleVersion.id
          lgmWrite(out, id.group, id.name, id.version, a.file)
        }
      } catch (Throwable ignored) { }
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
