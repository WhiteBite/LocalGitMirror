package localgitmirror.idea.deps

import java.io.File

/**
 * Scans the local Gradle artifact cache to build a manifest of what's already
 * present. The "dome" (consumer) side sends this to the "work" (producer) side
 * so the producer can compute a diff and ship only what's missing.
 *
 * Gradle layout:
 *   <gradle-user-home>/caches/modules-2/files-2.1/<group>/<name>/<version>/<sha1>/<file>
 * Each <sha1> directory contains exactly one `<file>` (jar/aar/pom/etc.).
 * The directory name itself IS the SHA1 of the file's bytes.
 */
object DepsScanner {

  data class Artifact(
    val group: String,
    val name: String,
    val version: String,
    val sha1: String,
    val fileName: String,
    val absolutePath: String,
    val size: Long
  ) {
    /** Stable key for diff comparisons. Includes sha1 to catch re-released versions. */
    val key: String get() = "$group:$name:$version:$sha1:$fileName"
  }

  /**
   * Returns the Gradle artifact cache directory, robustly.
   *
   * The naive "GRADLE_USER_HOME or ~/.gradle" is NOT enough: IntelliJ frequently
   * runs gradle with a different gradle-user-home than the env var the IDE
   * process inherited (e.g. configured in Settings → Build Tools → Gradle, or a
   * custom GRADLE_USER_HOME like D:\gradle-x\.gradle). The result: `gradle build`
   * populates one cache, but the plugin scans an empty ~/.gradle and reports
   * "nothing found".
   *
   * Strategy: collect all plausible gradle-home candidates and return the first
   * whose modules-2 cache actually EXISTS and is non-empty. Falls back to the
   * env/default path so behaviour is unchanged when only one cache exists.
   */
  fun cacheRoot(): File = pickCacheRoot(candidateCacheRoots())

  /** Ordered list of candidate `modules-2/files-2.1` dirs to probe. Internal for tests. */
  internal fun candidateCacheRoots(
    gradleUserHomeEnv: String? = System.getenv("GRADLE_USER_HOME"),
    gradleUserHomeProp: String? = System.getProperty("gradle.user.home"),
    userHome: String = System.getProperty("user.home") ?: "",
    gradleHomeEnv: String? = System.getenv("GRADLE_HOME")
  ): List<File> {
    val sub = "caches" + File.separator + "modules-2" + File.separator + "files-2.1"
    val homes = LinkedHashSet<String>()
    gradleUserHomeProp?.takeIf { it.isNotBlank() }?.let { homes.add(it) }
    gradleUserHomeEnv?.takeIf { it.isNotBlank() }?.let { homes.add(it) }
    if (userHome.isNotBlank()) homes.add(userHome + File.separator + ".gradle")
    // GRADLE_HOME points at the gradle *distribution*, but some setups keep a
    // sibling .gradle there too — cheap to probe.
    gradleHomeEnv?.takeIf { it.isNotBlank() }?.let { homes.add(it + File.separator + ".gradle") }
    // Dedup by normalised absolute path (handles "/" vs "\" and trailing slashes
    // so e.g. "C:/Users/x/.gradle" and "C:\Users\x\.gradle" collapse to one).
    val seen = LinkedHashSet<String>()
    val out = mutableListOf<File>()
    for (home in homes) {
      val f = File(home, sub)
      val norm = f.path.replace('\\', '/').trimEnd('/').lowercase()
      if (seen.add(norm)) out.add(f)
    }
    return out
  }

  /**
   * Pick the best cache root from [candidates]: prefer one that exists AND
   * contains at least one group dir (non-empty). If none is populated, return
   * the first existing one; if none exists, return the first candidate (so the
   * path is still reported in diagnostics). Internal for tests.
   */
  internal fun pickCacheRoot(candidates: List<File>): File {
    if (candidates.isEmpty()) {
      return File(System.getProperty("user.home") ?: ".",
        ".gradle/caches/modules-2/files-2.1")
    }
    candidates.firstOrNull { it.isDirectory && (it.listFiles { f -> f.isDirectory }?.isNotEmpty() == true) }
      ?.let { return it }
    candidates.firstOrNull { it.isDirectory }?.let { return it }
    return candidates.first()
  }

  /**
   * Walk the cache and yield every artifact found.
   * Bounded depth (group/name/version/sha1/file) — won't escape into surprise paths.
   *
   * If [onlyGroups] is non-empty, restricts to those group prefixes (substring match).
   * Useful when the dome wants to limit the manifest size — but defaults to "all"
   * because we want a complete picture for the diff.
   */
  fun scan(root: File = cacheRoot(), onlyGroupSubstrings: List<String> = emptyList()): List<Artifact> {
    if (!root.exists() || !root.isDirectory) return emptyList()
    val out = mutableListOf<Artifact>()

    val groupDirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
    for (groupDir in groupDirs) {
      val group = groupDir.name
      if (onlyGroupSubstrings.isNotEmpty() && onlyGroupSubstrings.none { group.contains(it, ignoreCase = true) }) {
        continue
      }
      val nameDirs = groupDir.listFiles { f -> f.isDirectory } ?: continue
      for (nameDir in nameDirs) {
        val name = nameDir.name
        val versionDirs = nameDir.listFiles { f -> f.isDirectory } ?: continue
        for (versionDir in versionDirs) {
          val version = versionDir.name
          val shaDirs = versionDir.listFiles { f -> f.isDirectory } ?: continue
          for (shaDir in shaDirs) {
            val sha1 = shaDir.name
            // Each sha-dir holds exactly one artifact file (jar/aar/pom/module).
            // Sidecar files like `_remote.repositories` describe origin but are
            // NOT artifacts themselves and must be skipped.
            val files = shaDir.listFiles { f ->
              f.isFile && !f.name.startsWith("_") && f.name != ".lock"
            } ?: continue
            for (file in files) {
              out.add(
                Artifact(
                  group = group,
                  name = name,
                  version = version,
                  sha1 = sha1,
                  fileName = file.name,
                  absolutePath = file.absolutePath,
                  size = file.length()
                )
              )
            }
          }
        }
      }
    }
    return out
  }

  /**
   * Scan EVERY candidate gradle cache and merge results, deduplicated by key.
   * This is what `collect`/`resolveMissing` should use: the artifact may live in
   * whichever gradle-user-home the IDE actually used (env var, IDE setting,
   * default ~/.gradle), and we don't reliably know which one — so we look in all.
   */
  fun scanAllCandidates(
    onlyGroupSubstrings: List<String> = emptyList(),
    extraRoots: List<File> = emptyList()
  ): List<Artifact> {
    val roots = (extraRoots + candidateCacheRoots())
      .filter { it.isDirectory }.distinctBy { it.canonicalPath }
    if (roots.isEmpty()) return emptyList()
    val seen = LinkedHashSet<String>()
    val out = mutableListOf<Artifact>()
    for (root in roots) {
      for (art in scan(root, onlyGroupSubstrings)) {
        if (seen.add(art.key)) out.add(art)
      }
    }
    return out
  }
}
