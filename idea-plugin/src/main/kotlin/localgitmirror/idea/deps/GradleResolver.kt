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
    val outputFile = File.createTempFile("lgm-resolved-", ".jsonl")
    try {
      val run = runGradleWithInitScript(
        projectDir = projectDir,
        initScriptContent = buildInitScript(outputFile),
        timeoutSec = timeoutSec,
        javaHome = javaHome,
        extraArgs = extraArgs
      )
      if (run.timedOut) {
        return Result(false, emptyList(),
          "gradle resolve timed out after ${timeoutSec}s\n${run.stdout.takeLast(2000)}",
          System.currentTimeMillis() - started)
      }
      val artifacts = parseJsonLines(outputFile)
      return Result(
        ok = run.exitCode == 0 && artifacts.isNotEmpty(),
        artifacts = artifacts,
        log = run.stdout.takeLast(4000) + run.javaHomeNote,
        durationMs = System.currentTimeMillis() - started
      )
    } catch (t: Throwable) {
      return Result(false, emptyList(), "gradle resolve failed: ${t.message}", System.currentTimeMillis() - started)
    } finally {
      try { outputFile.delete() } catch (_: Exception) {}
    }
  }

  /** Raw outcome of running gradle with a temporary init-script. */
  data class RawRun(
    val timedOut: Boolean,
    val exitCode: Int,
    val stdout: String,
    val javaHomeNote: String
  )

  /**
   * Run the project's gradle with [initScriptContent] dropped into a temp file
   * and `help` as the task. Shared by [resolve] and [resolveMissing]. Handles
   * JAVA_HOME normalisation, stdout draining and the hard timeout.
   */
  fun runGradleWithInitScript(
    projectDir: File,
    initScriptContent: String,
    timeoutSec: Long = 300,
    javaHome: String? = null,
    extraArgs: List<String> = emptyList(),
    task: String = "help"
  ): RawRun {
    val initScript = File.createTempFile("lgm-init-", ".gradle")
    try {
      initScript.writeText(initScriptContent)
      val cmd = mutableListOf<String>().apply {
        addAll(pickGradleCommand(projectDir))
        add("--init-script"); add(initScript.absolutePath)
        add("-q")
        add("--no-daemon")
        addAll(extraArgs)
        add(task)
      }
      val pb = ProcessBuilder(cmd).directory(projectDir).redirectErrorStream(true)
      val effectiveJavaHome = resolveJavaHome(javaHome)
      if (effectiveJavaHome != null) {
        pb.environment()["JAVA_HOME"] = effectiveJavaHome
        pb.environment()["JDK_HOME"] = effectiveJavaHome
      }
      val proc = pb.start()
      val stdoutCollector = StringBuilder()
      val readerThread = Thread {
        proc.inputStream.bufferedReader().useLines { it.forEach { line -> stdoutCollector.appendLine(line) } }
      }
      readerThread.start()
      val finished = proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
      val note = if (effectiveJavaHome != null) " (JAVA_HOME=$effectiveJavaHome)" else ""
      if (!finished) {
        proc.destroyForcibly()
        readerThread.join(2_000)
        return RawRun(timedOut = true, exitCode = -1, stdout = stdoutCollector.toString(), javaHomeNote = note)
      }
      readerThread.join(5_000)
      return RawRun(timedOut = false, exitCode = proc.exitValue(), stdout = stdoutCollector.toString(), javaHomeNote = note)
    } finally {
      try { initScript.delete() } catch (_: Exception) {}
    }
  }

  /**
   * DOME side. Run gradle with `--refresh-dependencies` and an init-script that
   * records every *unresolved* module dependency (via LenientConfiguration).
   * On the dome, corporate-only artifacts fail to resolve and show up here —
   * giving us the exact, machine-independent set to request from work.
   *
   * Returns coordinates as [GradleResolver.ResolvedArtifact] with an empty `f`
   * (no local file — that's the whole point), so callers can reuse parsing.
   */
  fun resolveMissing(
    projectDir: File,
    timeoutSec: Long = 300,
    javaHome: String? = null
  ): Result {
    val started = System.currentTimeMillis()
    if (!projectDir.exists() || !projectDir.isDirectory) {
      return Result(false, emptyList(), "projectDir not found: ${projectDir.absolutePath}", 0)
    }
    val outputFile = File.createTempFile("lgm-missing-", ".jsonl")
    try {
      val run = runGradleWithInitScript(
        projectDir = projectDir,
        initScriptContent = buildMissingInitScript(outputFile),
        timeoutSec = timeoutSec,
        javaHome = javaHome,
        // --refresh-dependencies forces a real resolve attempt against the
        // repos available HERE, so corporate deps actually fail (not served
        // stale from cache).
        extraArgs = listOf("--refresh-dependencies")
      )
      if (run.timedOut) {
        return Result(false, emptyList(),
          "gradle resolveMissing timed out after ${timeoutSec}s\n${run.stdout.takeLast(2000)}",
          System.currentTimeMillis() - started)
      }
      val missing = parseJsonLines(outputFile)
      // Note: exit code may be non-zero when a config fails hard; we still trust
      // whatever the init-script managed to record. "ok" means we got a usable
      // signal (either clean run, or at least some unresolved entries captured).
      val ok = run.exitCode == 0 || missing.isNotEmpty()
      return Result(
        ok = ok,
        artifacts = missing,
        log = run.stdout.takeLast(4000) + run.javaHomeNote,
        durationMs = System.currentTimeMillis() - started
      )
    } catch (t: Throwable) {
      return Result(false, emptyList(), "gradle resolveMissing failed: ${t.message}", System.currentTimeMillis() - started)
    } finally {
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

  /**
   * Init-script for [resolveMissing]. Uses each resolvable configuration's
   * LenientConfiguration to enumerate module dependencies that FAILED to
   * resolve against the repositories available on this (dome) machine, plus
   * the settings + buildscript classpaths (so unresolved PLUGINS are captured
   * too — that's the foojay/spotless case).
   *
   * Emits one JSON line per unresolved coordinate with an EMPTY `f` (no local
   * file — that's the point). [parseJsonLines] reuses the same shape.
   */
  private fun buildMissingInitScript(outputFile: File): String {
    val outPath = outputFile.absolutePath.replace('\\', '/')
    return """
// LocalGitMirror init-script — records every module dependency that could NOT
// be resolved on this machine (= corporate/internal artifacts to request).
def lgmWriteMissing(out, group, name, version) {
  if (version == null || version == 'null' || version == '') return
  out.write('{"g":"' + group + '","n":"' + name + '","v":"' + version + '","f":""}\n')
}

def lgmScanConf(out, conf) {
  if (!conf.canBeResolved) return
  try {
    def lenient = conf.resolvedConfiguration.lenientConfiguration
    lenient.unresolvedModuleDependencies.each { dep ->
      def sel = dep.selector
      lgmWriteMissing(out, sel.group, sel.name, sel.version)
    }
  } catch (Throwable ignored) { }
}

settingsEvaluated { settings ->
  def out = new java.io.FileWriter('$outPath', true)
  try {
    try { lgmScanConf(out, settings.buildscript.configurations.classpath) } catch (Throwable ignored) { }
  } finally { out.close() }
}

allprojects { p ->
  p.afterEvaluate {
    def out = new java.io.FileWriter('$outPath', true)
    try {
      p.configurations.each { conf -> lgmScanConf(out, conf) }
      try { lgmScanConf(out, p.buildscript.configurations.classpath) } catch (Throwable ignored) { }
    } finally { out.close() }
  }
}
""".trimIndent()
  }
}
