package localgitmirror.idea.deps

import java.io.File

// Skip rules mirror DepsScanner.scan.
object GradleTargetedResolver {

  data class ResolvedArtifact(val sha1: String, val file: File)

  fun resolve(roots: List<File>, group: String, name: String, version: String): List<ResolvedArtifact> {
    val seen = LinkedHashSet<String>()
    val out = mutableListOf<ResolvedArtifact>()
    for (root in roots) {
      val versionDir = File(root, "$group/$name/$version")
      if (!versionDir.isDirectory) continue
      val shaDirs = versionDir.listFiles { f -> f.isDirectory } ?: continue
      for (shaDir in shaDirs) {
        val sha1 = shaDir.name
        val files = shaDir.listFiles { f -> f.isFile && !f.name.startsWith("_") && f.name != ".lock" } ?: continue
        for (file in files) {
          if (seen.add("$sha1:${file.name}")) out.add(ResolvedArtifact(sha1, file))
        }
      }
    }
    return out
  }
}
