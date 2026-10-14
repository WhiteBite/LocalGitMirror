package localgitmirror.idea.deps

import java.io.File

// expandParentPomClosure twin for the targeted respond path: gradle cache roots only, never m2.
object TargetedParentClosure {

  fun expand(out: MutableList<DepFileEntry>, cacheRoots: List<File>): Int {
    val shippedRel = out.mapTo(HashSet()) { it.relativePath }
    val queue = ArrayDeque(out.filter { it.relativePath.endsWith(".pom", true) }.map { it.absolutePath })
    val visited = HashSet(queue)
    var added = 0
    while (queue.isNotEmpty()) {
      val pomText = runCatching { File(queue.removeFirst()).readText() }.getOrNull() ?: continue
      val (g, n, v) = GradleEcosystem.parentCoordOfPom(pomText) ?: continue
      val arts = GradleTargetedResolver.resolve(cacheRoots, g, n, v).map {
        DepsScanner.Artifact(g, n, v, it.sha1, it.file.name, it.file.absolutePath, it.file.length())
      }
      for (a in GradleEcosystem.pickShipableArtifacts(arts)) {
        if (!a.fileName.endsWith(".pom", true)) continue
        val rel = MavenLocalScanner.mavenLocalRelativePath(a.group, a.name, a.version, a.fileName)
        if (shippedRel.add(rel)) {
          out.add(DepFileEntry(DepCoordinate(GradleEcosystem.id, a.group, a.name, a.version), a.absolutePath, rel, a.size))
          added++
        }
        if (visited.add(a.absolutePath)) queue.addLast(a.absolutePath)
      }
    }
    return added
  }
}
