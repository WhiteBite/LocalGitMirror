package localgitmirror.idea.deps

import java.io.File

/**
 * Gradle implementation of [DepsEcosystem].
 *
 *  - resolveMissing: ask gradle (with --refresh-dependencies) what fails to
 *    resolve here → those g:n:v are corporate-only.
 *  - collect: walk the local modules-2 cache and pick files whose g:n:v match
 *    a requested coordinate. We ship ALL files under a matched sha-dir (jar +
 *    pom + module metadata) so the dome cache is complete and gradle is happy
 *    offline.
 *  - cacheRoot: ~/.gradle/caches/modules-2/files-2.1 (honours GRADLE_USER_HOME).
 */
object GradleEcosystem : DepsEcosystem {
  override val id: String = "gradle"

  private val MARKER_FILES = listOf(
    "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
  )

  override fun detect(projectDir: File): Boolean {
    if (MARKER_FILES.any { File(projectDir, it).isFile }) return true
    // also check direct subprojects (depth 1)
    val subs = projectDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return false
    return subs.any { sub -> MARKER_FILES.any { File(sub, it).isFile } }
  }

  override fun resolveMissing(projectDir: File, javaHome: String?): ResolveMissingResult {
    val r = GradleResolver.resolveMissing(projectDir, javaHome = javaHome)
    val coords = r.artifacts
      .map { DepCoordinate(id, it.g, it.n, it.v) }
      .distinctBy { it.key }
    return ResolveMissingResult(r.ok, coords, r.log, r.durationMs)
  }

  override fun collect(
    coordinates: List<DepCoordinate>,
    onMissingLocally: (DepCoordinate) -> Unit
  ): List<DepFileEntry> {
    val root = cacheRoot()
    val want = coordinates.filter { it.ecosystem == id }
      .associateBy { "${it.group}:${it.name}:${it.version}" }
    if (want.isEmpty()) return emptyList()

    // Scan the whole cache once, then group by g:n:v.
    val byGnv = DepsScanner.scan(root).groupBy { "${it.group}:${it.name}:${it.version}" }

    val out = mutableListOf<DepFileEntry>()
    for ((gnv, coord) in want) {
      val arts = byGnv[gnv]
      if (arts.isNullOrEmpty()) {
        onMissingLocally(coord)
        continue
      }
      for (art in arts) {
        val rel = "${art.group}/${art.name}/${art.version}/${art.sha1}/${art.fileName}"
        out.add(DepFileEntry(coord, art.absolutePath, rel, art.size))
      }
    }
    return out
  }

  override fun cacheRoot(): File = DepsScanner.cacheRoot()
}
