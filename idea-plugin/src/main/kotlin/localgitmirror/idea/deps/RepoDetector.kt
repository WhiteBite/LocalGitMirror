package localgitmirror.idea.deps

import java.io.File

/**
 * Auto-detect "internal" Gradle repositories declared in a project.
 *
 * Strategy:
 * 1. Scan build.gradle, build.gradle.kts, settings.gradle, settings.gradle.kts
 *    in the project root and direct subprojects (depth 2).
 * 2. Extract every `url 'https://...'` / `url("https://...")` / `setUrl(...)`
 *    inside or outside `repositories { ... }` blocks (we don't try to be
 *    exact about the AST — substring matching on URLs is good enough).
 * 3. Drop URLs that match the well-known PUBLIC list (Maven Central, Google,
 *    Gradle Plugin Portal, JCenter, JBoss, Apache Snapshots, Sonatype OSS).
 * 4. What remains is treated as internal — those URLs (or hostname substrings)
 *    are used to filter the work cache when responding to a deps request.
 *
 * Pure file IO + regex; no Gradle Tooling API, so safe to run in tests
 * without bringing up an IntelliJ project.
 */
object RepoDetector {

  /** Hosts/path-patterns we know are public — auto-detected URLs matching any
   *  of these are excluded from the "internal" list. */
  private val PUBLIC_HOST_PATTERNS = listOf(
    "repo.maven.apache.org",
    "repo1.maven.org",
    "repo.maven.org",
    "maven.central",
    "central.sonatype.com",
    "central.maven.org",
    "dl.google.com",
    "maven.google.com",
    "plugins.gradle.org",
    "jcenter.bintray.com",
    "repository.jboss.org",
    "repo.spring.io",
    "oss.sonatype.org",
    "s01.oss.sonatype.org",
    "jitpack.io",
    "repository.apache.org",
  )

  /** URL regex that captures everything inside quotes after `url`/`setUrl`/`uri`. */
  private val URL_REGEX = Regex(
    """(?:url|setUrl|uri)\s*[(=]?\s*["']?(https?://[^"'\s)]+)""",
    RegexOption.IGNORE_CASE
  )

  /** Capture `maven { ... }` shorthand `maven 'https://...'`. */
  private val MAVEN_LITERAL_REGEX = Regex(
    """\bmaven\s*\(?\s*["'](https?://[^"']+)["']""",
    RegexOption.IGNORE_CASE
  )

  /** Files we look at, in priority order. */
  private val GRADLE_FILES = listOf(
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
  )

  data class Detection(
    val internalSubstrings: List<String>,
    val publicUrls: List<String>,
    val sources: List<File>
  )

  /**
   * Walk [projectRoot] and direct subprojects (depth 1) looking for gradle
   * files. Returns lists deduplicated, ordered by first appearance.
   */
  fun detect(projectRoot: File): Detection {
    if (!projectRoot.exists() || !projectRoot.isDirectory) {
      return Detection(emptyList(), emptyList(), emptyList())
    }

    val gradleFiles = collectGradleFiles(projectRoot)
    if (gradleFiles.isEmpty()) {
      return Detection(emptyList(), emptyList(), emptyList())
    }

    val urls = LinkedHashSet<String>()
    for (f in gradleFiles) {
      val text = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { continue }
      // Strip line comments without breaking URLs (which contain '//' in scheme).
      // We only consider '//' as a comment if it doesn't follow a colon (URL scheme).
      val stripped = text.lineSequence()
        .map { line -> stripLineComment(line) }
        .joinToString("\n")
      URL_REGEX.findAll(stripped).forEach { urls.add(normaliseUrl(it.groupValues[1])) }
      MAVEN_LITERAL_REGEX.findAll(stripped).forEach { urls.add(normaliseUrl(it.groupValues[1])) }
    }

    val publicUrls = mutableListOf<String>()
    val internalUrls = mutableListOf<String>()
    for (u in urls) {
      if (isPublic(u)) publicUrls.add(u) else internalUrls.add(u)
    }

    // Convert internal full URLs into hostname substrings — that's what
    // the rest of the pipeline (DepsScanner.matchesInternalRepo) uses to match
    // against the `_remote.repositories` sidecar entries.
    val substrings = internalUrls.map { hostOf(it) }.distinct().filter { it.isNotBlank() }

    return Detection(substrings, publicUrls, gradleFiles)
  }

  private fun collectGradleFiles(root: File): List<File> {
    val out = mutableListOf<File>()
    // Root-level files
    GRADLE_FILES.forEach { name ->
      val f = File(root, name)
      if (f.isFile) out.add(f)
    }
    // Direct subdirectories — typical multi-module layout
    val subDirs = root.listFiles { f ->
      f.isDirectory && !f.name.startsWith(".") && f.name != "build" &&
        f.name != "node_modules" && f.name != "out"
    } ?: emptyArray()
    for (sub in subDirs.sortedBy { it.name }) {
      GRADLE_FILES.forEach { name ->
        val f = File(sub, name)
        if (f.isFile) out.add(f)
      }
    }
    return out
  }

  private fun normaliseUrl(u: String): String =
    u.trim().trimEnd(',', ';', ')').trimEnd('/')

  /**
   * Drop a line-comment if it isn't part of a URL scheme.
   * Looks for `//` not preceded by `:` (`https:`, `http:`).
   * Also handles `#` comments in `gradle.properties`-style? Not needed for build files.
   */
  private fun stripLineComment(line: String): String {
    var i = 0
    while (i < line.length - 1) {
      if (line[i] == '/' && line[i + 1] == '/') {
        // Make sure it's not the `://` part of a URL
        if (i == 0 || line[i - 1] != ':') {
          return line.substring(0, i)
        }
      }
      i++
    }
    return line
  }

  private fun isPublic(url: String): Boolean {
    val low = url.lowercase()
    return PUBLIC_HOST_PATTERNS.any { low.contains(it) }
  }

  /** Extract host (with optional path prefix) from URL: `https://nexus.x.y/repo/...` -> `nexus.x.y`. */
  internal fun hostOf(url: String): String {
    val low = url.lowercase()
    val withoutScheme = low.removePrefix("https://").removePrefix("http://")
    val firstSlash = withoutScheme.indexOf('/')
    return if (firstSlash < 0) withoutScheme else withoutScheme.substring(0, firstSlash)
  }
}
