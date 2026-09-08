package localgitmirror.idea.deps

import com.intellij.openapi.progress.ProgressIndicator
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WORK-side gap-filler: when [DepsResponder.respond] finds coordinates
 * missing from all local caches, ask the project's OWN package managers
 * (gradle, npm) to download them, then re-collect from cache.
 *
 * This replaces URL-guessing ([NexusFetcher]) as the primary gap-filler.
 * The existing Nexus fallback remains as a last resort.
 *
 * Stealth: these gradle/npm runs happen ONLY inside user-triggered respond
 * (same as today's collect), never automatically. npm/gradle process spawns
 * are normal dev-tooling child processes.
 */
object PkgManagerFetch {

  private const val CAP = 50

  /**
   * Ask gradle to download [coords] into its cache by creating a detached
   * configuration on the root project, adding every coordinate as a
   * dependency, mirroring the project's repositories, and resolving.
   *
   * @return count of coords whose files appear in cache AFTER the run.
   * Never throws: on any failure returns 0 and emits a diagnostics event.
   */
  fun fetchGradle(
    projectDir: File,
    coords: List<DepCoordinate>,
    indicator: ProgressIndicator? = null
  ): Int {
    if (coords.isEmpty() || !projectDir.isDirectory) return 0
    val capped = coords.take(CAP)
    indicator?.text = "Докачиваем ${capped.size} gradle-координат через gradle…"

    val initScript = buildGradleInitScript(capped)
    return runCatching {
      val run = GradleResolver.runGradleWithInitScript(
        projectDir = projectDir,
        initScriptContent = initScript,
        timeoutSec = 300,
        extraArgs = listOf("-q", "--console=plain")
      )
      if (run.timedOut) {
        DepsDiagnostics.event("pkg-fetch: gradle timed out after 300s")
        return@runCatching 0
      }
      val recovered = countRecoveredGradle(projectDir, capped)
      DepsDiagnostics.event("pkg-fetch: gradle recovered=$recovered/${capped.size} exitCode=${run.exitCode}")
      recovered
    }.getOrElse { t ->
      DepsDiagnostics.event("pkg-fetch: gradle failed: ${t.message?.take(200)}")
      0
    }
  }

  /**
   * Ask npm to download [coords] into its cache via `npm cache add
   * <name>@<version>`, run with cwd=projectDir so the project's .npmrc
   * registry applies.
   *
   * @return count of coords successfully added (exit 0). Never throws.
   */
  fun fetchNpm(
    projectDir: File,
    coords: List<DepCoordinate>,
    indicator: ProgressIndicator? = null
  ): Int {
    if (coords.isEmpty() || !projectDir.isDirectory) return 0
    val capped = coords.take(CAP)
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val npm = if (isWindows) listOf("cmd", "/c", "npm") else listOf("npm")

    indicator?.text = "Докачиваем ${capped.size} npm-пакетов через npm cache add…"
    var ok = 0
    for (coord in capped) {
      val full = if (coord.group.isNotEmpty()) "${coord.group}/${coord.name}" else coord.name
      val spec = "$full@${coord.version}"
      try {
        val proc = ProcessBuilder(npm + listOf("cache", "add", spec))
          .directory(projectDir)
          .redirectErrorStream(true)
          .start()
        proc.inputStream.bufferedReader().use { it.readText() }
        if (proc.waitFor(60, TimeUnit.SECONDS) && proc.exitValue() == 0) {
          ok++
        } else {
          proc.destroyForcibly()
        }
      } catch (_: Throwable) { }
    }
    DepsDiagnostics.event("pkg-fetch: npm recovered=$ok/${capped.size}")
    return ok
  }

  private fun countRecoveredGradle(projectDir: File, coords: List<DepCoordinate>): Int {
    val extraRoots = GradleResolver.discoverGradleUserHome(projectDir)?.let {
      listOf(File(it, "caches/modules-2/files-2.1"))
    } ?: emptyList()
    val allCached = DepsScanner.scanAllCandidates(extraRoots = extraRoots) + MavenLocalScanner.scan()
    val cachedKeys = allCached.map { "${it.group}:${it.name}:${it.version}" }.toSet()
    return coords.count { "${it.group}:${it.name}:${it.version}" in cachedKeys }
  }

  private fun buildGradleInitScript(coords: List<DepCoordinate>): String {
    val depsGroovyList = coords.joinToString(",\n  ") { c ->
      val notation = if (c.classifier.isNotEmpty())
        "${c.group}:${c.name}:${c.version}:${c.classifier}"
      else
        "${c.group}:${c.name}:${c.version}"
      "'${notation.replace("'", "\\'")}'"
    }
    return """
def pluginRepoUrls = []
def hasGradlePluginPortal = false

settingsEvaluated { settings ->
  try {
    settings.pluginManagement.repositories.each { repo ->
      try {
        def u = repo.url?.toString()
        if (u && (u.startsWith('http://') || u.startsWith('https://'))) {
          pluginRepoUrls << u
          if (u.contains('plugins.gradle.org')) hasGradlePluginPortal = true
        }
      } catch (Throwable ignored) { }
    }
  } catch (Throwable ignored) { }
}

gradle.projectsEvaluated {
  try {
    def root = gradle.rootProject

    // Detached-style configuration: transitive=false so only the explicitly
    // listed artifacts are fetched, not their entire dependency trees.
    def cfg = root.configurations.create('doccacheResolve')
    cfg.visible = false
    cfg.transitive = false
    cfg.canBeResolved = true
    cfg.canBeConsumed = false

    pluginRepoUrls.each { u ->
      try { root.repositories.maven { it.url = u } } catch (Throwable ignored) { }
    }
    try { root.repositories.mavenLocal() } catch (Throwable ignored) { }
    if (hasGradlePluginPortal) {
      try { root.repositories.gradlePluginPortal() } catch (Throwable ignored) { }
    }
    try {
      root.buildscript.repositories.each { repo ->
        try {
          def u = repo.url?.toString()
          if (u && (u.startsWith('http://') || u.startsWith('https://'))) {
            root.repositories.maven { it.url = u }
          }
        } catch (Throwable ignored) { }
      }
    } catch (Throwable ignored) { }

    [
  $depsGroovyList
    ].each { c ->
      try { cfg.dependencies.add(root.dependencies.create(c)) } catch (Throwable ignored) { }
    }

    try { cfg.files() } catch (Throwable ignored) { }
  } catch (Throwable ignored) { }
}
""".trimIndent()
  }
}
