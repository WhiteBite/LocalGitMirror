package localgitmirror.idea.deps

import java.io.File
import java.security.MessageDigest

/**
 * Scan a local maven repository (`~/.m2/repository/`) and yield artifacts in the
 * same shape [DepsScanner.Artifact] uses, so [GradleEcosystem] can subtract
 * already-present files from a deps request regardless of which layout (gradle
 * internal cache vs maven local) holds them.
 *
 * Why this exists: artifacts placed in `caches/modules-2/files-2.1/` by hand
 * (e.g. unpacked from a deps response) are NOT visible to gradle in offline
 * mode — gradle requires accompanying internal metadata it only writes when it
 * downloaded the artifact itself. Maven local has no such requirement: any
 * jar/pom that follows the `<group-as-path>/<name>/<version>/<filename>` layout
 * is resolvable, provided `mavenLocal()` is on the relevant repositories list.
 *
 * SHA-1 is computed from file bytes so the result lines up with the sha-dir
 * names gradle uses in its own cache (gradle stores files at
 * `<g>/<n>/<v>/<sha1>/<file>` where the dir name is the SHA-1 of the file
 * content). This lets the work side compare presence by exact content address.
 *
 * Heuristic for "this is a version dir":
 *   any file whose name starts with `<parent-dir>-<this-dir>` (e.g.
 *   `guava-31.0.1.jar` inside `.../guava/31.0.1/`). False positives in odd
 *   layouts are harmless — they just get reported as artifacts the work side
 *   wouldn't ship anyway.
 */
object MavenLocalScanner {

  fun cacheRoot(): File =
    File(System.getProperty("user.home") ?: ".", ".m2" + File.separator + "repository")

  fun scan(root: File = cacheRoot()): List<DepsScanner.Artifact> {
    if (!root.isDirectory) return emptyList()
    val out = mutableListOf<DepsScanner.Artifact>()
    val stack = ArrayDeque<File>()
    stack.add(root)
    while (stack.isNotEmpty()) {
      val dir = stack.removeFirst()
      val files = dir.listFiles { f ->
        f.isFile && !f.name.startsWith("_") && !f.name.endsWith(".lock")
      } ?: continue

      val parentDir = dir.parentFile
      val parentName = parentDir?.name.orEmpty()
      val expectedPrefix = if (parentName.isEmpty()) "" else "$parentName-${dir.name}"
      val isVersionDir = expectedPrefix.isNotEmpty() &&
        files.any { it.nameWithoutExtension.startsWith(expectedPrefix) }

      if (isVersionDir && parentDir != null) {
        val groupDir = parentDir.parentFile ?: continue
        if (!groupDir.canonicalPath.startsWith(root.canonicalPath)) continue
        val groupRel = groupDir.relativeTo(root).path
          .replace('\\', '/').trim('/')
        if (groupRel.isEmpty()) continue
        val group = groupRel.replace('/', '.')
        val name = parentName
        val version = dir.name
        for (f in files) {
          out.add(
            DepsScanner.Artifact(
              group = group,
              name = name,
              version = version,
              sha1 = sha1Of(f),
              fileName = f.name,
              absolutePath = f.absolutePath,
              size = f.length()
            )
          )
        }
      } else {
        // descend further
        dir.listFiles { f -> f.isDirectory }?.forEach { stack.add(it) }
      }
    }
    return out
  }

  /**
   * SHA-1 of file bytes as 40 lowercase hex chars. Buffered, no full-file load,
   * so safe even on a multi-megabyte jar. Returns "" on any I/O failure.
   */
  internal fun sha1Of(file: File): String = try {
    val md = MessageDigest.getInstance("SHA-1")
    file.inputStream().buffered().use { ins ->
      val buf = ByteArray(8192)
      while (true) {
        val n = ins.read(buf)
        if (n <= 0) break
        md.update(buf, 0, n)
      }
    }
    md.digest().joinToString("") { "%02x".format(it) }
  } catch (_: Throwable) {
    ""
  }

  /**
   * Convert "g.n.v" coords + a filename to the maven-local relative path:
   *   "com.google.guava" + "guava" + "31.0.1" + "guava-31.0.1.jar"
   *   → "com/google/guava/guava/31.0.1/guava-31.0.1.jar"
   */
  fun mavenLocalRelativePath(group: String, name: String, version: String, fileName: String): String {
    val groupPath = group.replace('.', '/')
    return "$groupPath/$name/$version/$fileName"
  }
}
