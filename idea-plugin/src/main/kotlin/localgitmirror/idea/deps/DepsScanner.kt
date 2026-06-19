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
   * Returns the system Gradle cache directory: typically ~/.gradle/caches/modules-2/files-2.1.
   * Honours GRADLE_USER_HOME like Gradle itself does.
   */
  fun cacheRoot(): File {
    val gradleHome = System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() }
      ?: (System.getProperty("user.home") + File.separator + ".gradle")
    return File(gradleHome, "caches" + File.separator + "modules-2" + File.separator + "files-2.1")
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
   * Read the `_remote.repositories` sidecar that Gradle puts next to each artifact.
   * Format: `<filename>>repo-id=<url>` per line.
   * Returns the list of origin URLs/IDs (may be empty if Gradle didn't record them).
   */
  fun originsFor(artifact: Artifact): List<String> {
    val sidecar = File(File(artifact.absolutePath).parentFile, "_remote.repositories")
    if (!sidecar.exists()) return emptyList()
    return sidecar.readLines()
      .mapNotNull {
        val eq = it.indexOf('=')
        if (eq < 0) null else it.substring(eq + 1).trim().trimEnd(':')
      }
      .filter { it.isNotBlank() }
      .distinct()
  }

  /** True if the artifact came from any of the configured "internal" repositories. */
  fun matchesInternalRepo(artifact: Artifact, internalSubstrings: List<String>): Boolean {
    if (internalSubstrings.isEmpty()) return true   // no filter = anything counts
    val origins = originsFor(artifact)
    if (origins.isEmpty()) return false             // unknown origin -> NOT internal by default
    return origins.any { origin ->
      internalSubstrings.any { sub -> origin.contains(sub, ignoreCase = true) }
    }
  }
}
