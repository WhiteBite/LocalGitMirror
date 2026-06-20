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
    val durationMs: Long,
    /** Gradle's real `gradleUserHomeDir` as reported by the init-script (may be null). */
    val gradleUserHome: String? = null
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
   * DOME side. Determine which artifacts the project needs but are NOT in the
   * local gradle cache — i.e. the corporate deps that must be requested from work.
   *
   * Strategy (two-pass):
   *
   *   1. Run gradle with `--offline` + the missing-init-script.
   *      `--offline` forces gradle to use only the local cache. Any dependency
   *      not cached locally causes gradle to report it via
   *      `LenientConfiguration.unresolvedModuleDependencies` (or fail hard).
   *      This is exact: a dep that IS cached is not reported as missing.
   *      This is correct: a dep missing from cache IS what we need to ship.
   *
   *      The previous `--refresh-dependencies` (online) approach failed because
   *      when the corporate Nexus is unreachable (expected on the dome), gradle
   *      crashes with a network exception BEFORE the init-script can record
   *      anything, so we got empty results.
   *
   *   2. If the init-script captured nothing but gradle exited non-zero,
   *      fall back to parsing stdout for "No cached version" lines —
   *      gradle's own error messages name the missing coordinates exactly.
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
        // --offline: use only local cache. Deps not in cache → unresolved.
        // This is the correct signal: "what the dome doesn't have".
        extraArgs = listOf("--offline")
      )
      if (run.timedOut) {
        return Result(false, emptyList(),
          "gradle resolveMissing timed out after ${timeoutSec}s\n${run.stdout.takeLast(2000)}",
          System.currentTimeMillis() - started)
      }
      val parsed = parseJsonLines(outputFile)
      // Extract the gradle-user-home marker emitted by the init-script.
      val guh = parsed.firstOrNull { it.g == "__GUH__" }?.f?.takeIf { it.isNotBlank() }
      val missing = parsed.filter { it.g != "__GUH__" }
      if (missing.isNotEmpty()) {
        return Result(
          ok = true,
          artifacts = missing,
          log = run.stdout.takeLast(4000) + run.javaHomeNote,
          durationMs = System.currentTimeMillis() - started,
          gradleUserHome = guh
        )
      }
      // Init-script got nothing but gradle still failed → parse stdout for
      // "No cached version of <g>:<n>:<v>" lines (gradle's own error output).
      val fallback = parseNoCachedVersionLines(run.stdout)
      val ok = run.exitCode == 0 || fallback.isNotEmpty()
      return Result(
        ok = ok,
        artifacts = fallback,
        log = run.stdout.takeLast(4000) + run.javaHomeNote +
          if (fallback.isNotEmpty()) "\n[fallback: parsed ${fallback.size} from stdout]" else "",
        durationMs = System.currentTimeMillis() - started,
        gradleUserHome = guh
      )
    } catch (t: Throwable) {
      return Result(false, emptyList(), "gradle resolveMissing failed: ${t.message}", System.currentTimeMillis() - started)
    } finally {
      try { outputFile.delete() } catch (_: Exception) {}
    }
  }

  /**
   * Fallback parser for gradle's own error output when LenientConfiguration
   * doesn't fire. Gradle prints lines like:
   *   > No cached version of ru.kryptonite:code-quality-plugin:1.1.0 available for offline mode.
   * We extract the coordinates from these, skipping plugin-marker artifacts
   * (those ending in `.gradle.plugin`) since they have no jar — the real
   * implementation artifact is captured via other means.
   */
  internal fun parseNoCachedVersionLines(stdout: String): List<ResolvedArtifact> {
    // Matches: "No cached version of <g>:<n>:<v> available for offline mode"
    val re = Regex("""No cached version of ([^:\s]+):([^:\s]+):([^\s]+)\s+available""")
    val seen = LinkedHashSet<String>()
    val out = mutableListOf<ResolvedArtifact>()
    for (m in re.findAll(stdout)) {
      val g = m.groupValues[1].trim()
      val n = m.groupValues[2].trim()
      val v = m.groupValues[3].trim()
      // Skip plugin-marker artifacts — they have no jar
      if (n.endsWith(".gradle.plugin")) continue
      val key = "$g:$n:$v"
      if (seen.add(key)) out.add(ResolvedArtifact(g, n, v, f = ""))
    }
    return out
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

  /**
   * Ask gradle itself for its real `gradleUserHomeDir` by running an init-script
   * that prints it. This is the authoritative cache location — it matches what
   * the IDE used to build, regardless of env vars or IDE settings. Returns null
   * on any failure (gradle missing, timeout). Cheap: `help` task, ~a few sec.
   */
  fun discoverGradleUserHome(projectDir: File, timeoutSec: Long = 120, javaHome: String? = null): String? {
    if (!projectDir.isDirectory) return null
    val out = File.createTempFile("lgm-guh-", ".txt")
    return try {
      val script = """
        gradle.projectsEvaluated {
          new File('${out.absolutePath.replace('\\', '/')}').text =
            gradle.gradleUserHomeDir.absolutePath
        }
      """.trimIndent()
      val run = runGradleWithInitScript(
        projectDir = projectDir,
        initScriptContent = script,
        timeoutSec = timeoutSec,
        javaHome = javaHome,
        extraArgs = listOf("--offline")
      )
      // Even if the build fails (offline), projectsEvaluated may have fired.
      val guh = if (out.exists()) out.readText().trim() else ""
      guh.ifBlank { null }
    } catch (_: Throwable) {
      null
    } finally {
      try { out.delete() } catch (_: Exception) {}
    }
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
   * Init-script for [resolveMissing]. Detects artifacts that are NOT in the
   * local Gradle cache (offline) so the dome can request them from work.
   *
   * Two passes per configuration:
   *
   *   Pass 1 — "resolved but file missing": use resolutionResult (never throws,
   *     works offline) to enumerate every component gradle KNOWS about in the
   *     dependency graph, then check whether the file actually exists on disk.
   *     This catches the common plugin case: gradle resolved the marker
   *     (ru.kryptonite.code-quality.gradle.plugin) and recorded its real
   *     implementation coordinates (ru.kryptonite.build:kryptonite-gradle-plugin)
   *     in the resolution result — but the jar isn't cached locally. We emit the
   *     REAL coordinates (from resolutionResult), not the marker coordinates.
   *
   *   Pass 2 — "unresolved": lenientConfiguration.unresolvedModuleDependencies
   *     catches anything gradle couldn't even start resolving (e.g. completely
   *     unknown version). We fall back to the selector coordinates for these.
   *
   * Emits one JSON line per missing coordinate with an EMPTY `f`.
   */
  private fun buildMissingInitScript(outputFile: File): String {
    val outPath = outputFile.absolutePath.replace('\\', '/')
    return """
// LocalGitMirror init-script — records every artifact missing from the local
// Gradle cache. Two-pass: resolutionResult (real coords, file-exists check)
// + lenientConfiguration (truly unresolved deps).
def lgmWriteMissing(out, group, name, version) {
  if (!group || !name || !version || version == 'null' || version == '') return
  // Skip pure plugin-marker artifacts — they have no jar, the real impl is
  // captured separately via resolutionResult.
  if (name.endsWith('.gradle.plugin')) return
  out.write('{"g":"' + group + '","n":"' + name + '","v":"' + version + '","f":""}\n')
}

// Pass 1: walk resolutionResult component graph, check files exist.
// resolutionResult works offline and gives REAL (non-marker) coordinates.
def lgmScanResolved(out, conf) {
  if (!conf.canBeResolved) return
  try {
    conf.incoming.resolutionResult.allComponents.each { component ->
      def id = component.moduleVersion
      if (!id) return
      // Skip the root project itself and Gradle's own virtual components
      if (id.group == 'unspecified' || id.version == 'unspecified' || id.version == '') return
      if (id.name.endsWith('.gradle.plugin')) return
      // Check whether any cached file for this g:n:v exists in the
      // gradle user home. If none → it's missing → request it.
      try {
        def artifacts = conf.incoming.artifactView { config ->
          config.componentFilter { c -> c.moduleVersion?.module == id.module }
          config.lenient(true)
        }.artifacts
        artifacts.each { ra ->
          if (!ra.file.exists()) {
            lgmWriteMissing(out, id.group, id.name, id.version)
          }
        }
        // If artifactView returned nothing for this component it means
        // the file was never downloaded; still request it.
        if (artifacts.isEmpty()) {
          lgmWriteMissing(out, id.group, id.name, id.version)
        }
      } catch (Throwable ignored) {
        // Fallback: if we can't check files, emit the coordinate anyway
        // so it at least appears in the request.
        lgmWriteMissing(out, id.group, id.name, id.version)
      }
    }
  } catch (Throwable ignored) { }
}

// Pass 2: truly unresolved (unknown version, repo unreachable etc.)
def lgmScanUnresolved(out, conf) {
  if (!conf.canBeResolved) return
  try {
    conf.resolvedConfiguration.lenientConfiguration.unresolvedModuleDependencies.each { dep ->
      def sel = dep.selector
      lgmWriteMissing(out, sel.group, sel.name, sel.version)
    }
  } catch (Throwable ignored) { }
}

def lgmScanConf(out, conf) {
  lgmScanResolved(out, conf)
  lgmScanUnresolved(out, conf)
}

settingsEvaluated { settings ->
  def out = new java.io.FileWriter('$outPath', true)
  try {
    // Record gradle's REAL user home so the plugin scans the exact cache.
    try { out.write('{"g":"__GUH__","n":"","v":"","f":"' + settings.gradle.gradleUserHomeDir.absolutePath.replace('\\', '/') + '"}\n') } catch (Throwable ignored) { }
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
