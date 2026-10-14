package localgitmirror.idea.deps

import java.io.File

/**
 * Resolve ONE maven coordinate against the local repository without walking it:
 * the deps respond path holds group/name/version per manifest entry, and
 * mass-reading + mass-hashing all of `~/.m2` trips EDR exfil heuristics.
 * Only the coordinate dir is listed; sidecar `.sha1` files are preferred so
 * even the payload bytes are not read when maven already recorded the hash.
 */
object MavenLocalTargeted {

  data class M2Artifact(val sha1: String, val file: File, val fileName: String)

  internal data class ParsedFileName(val classifier: String, val ext: String)

  private val hex40 = Regex("[0-9a-f]{40}")

  fun resolve(
    m2Root: File = MavenLocalScanner.cacheRoot(),
    group: String,
    name: String,
    version: String
  ): List<M2Artifact> {
    val dir = File(m2Root, MavenLocalScanner.mavenLocalRelativePath(group, name, version, ""))
    if (!dir.isDirectory) return emptyList()
    val files = dir.listFiles { f ->
      f.isFile && !isSkippable(f.name) && parseCandidateFileName(name, version, f.name) != null
    } ?: return emptyList()
    return files.map { M2Artifact(sha1For(dir, it), it, it.name) }
  }

  /** Split `<name>-<version>[-classifier].ext`; null when the file doesn't belong to the coordinate. */
  internal fun parseCandidateFileName(name: String, version: String, fileName: String): ParsedFileName? {
    val prefix = "$name-$version"
    if (!fileName.startsWith(prefix)) return null
    val rest = fileName.substring(prefix.length)
    val dot = rest.lastIndexOf('.')
    val ext = if (dot >= 0) rest.substring(dot + 1) else ""
    val classifier = (if (dot >= 0) rest.substring(0, dot) else rest).removePrefix("-")
    return ParsedFileName(classifier, ext)
  }

  // Maven resolver bookkeeping files are never artifacts.
  private fun isSkippable(fileName: String): Boolean =
    fileName.startsWith("_") ||
      fileName.startsWith("maven-metadata") ||
      fileName.endsWith(".sha1") ||
      fileName.endsWith(".md5") ||
      fileName.endsWith(".lastUpdated") ||
      fileName.contains("resolver-status")

  private fun sha1For(dir: File, file: File): String {
    val sidecar = File(dir, file.name + ".sha1")
    if (sidecar.isFile) {
      val value = try { sidecar.readText().trim().lowercase() } catch (_: Throwable) { "" }
      if (hex40.matches(value)) return value
    }
    return MavenLocalScanner.sha1Of(file)
  }
}
