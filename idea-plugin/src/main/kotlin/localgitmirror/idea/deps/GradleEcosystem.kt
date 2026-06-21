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

  /**
   * Optional project-dir hint set by the caller right before [collect], so the
   * gradle-reported `gradleUserHomeDir` can be probed as an extra cache root.
   * This catches the case where the IDE/work machine uses a gradle home that
   * isn't any of the env/default candidates.
   */
  @Volatile var collectProjectDir: File? = null

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

    // Plugin's init-script has two well-known false-positive sources:
    //   1. BOM-only artifacts (Spring Boot BOM, Testcontainers BOM, …) — `artifactView.artifacts`
    //      returns empty for them, and the script falls into the `if (artifacts.isEmpty())`
    //      branch and reports them missing.
    //   2. In offline mode `ra.file.exists()` often returns false even when the artifact
    //      *is* on disk (gradle's expected sha1-path differs from the actual one in cache).
    // Both produce coords that ARE already cached locally — sending them wastes bandwidth.
    // Filter them out here by scanning the real cache directories on disk.
    val extraCacheRoots = r.gradleUserHome
      ?.let { listOf(File(it, "caches/modules-2/files-2.1")) }
      ?: emptyList()
    val allCached = DepsScanner.scanAllCandidates(extraRoots = extraCacheRoots) + MavenLocalScanner.scan()

    // A coordinate is "satisfied" (don't re-request) only if we hold the real
    // artifact gradle needs — NOT just any file. Having only the .pom of a
    // jar-packaged dependency does NOT satisfy it: gradle still fails with
    // "Could not find ...jar". BOMs/parents (packaging=pom) ARE satisfied by
    // the pom alone.
    val coordKinds = HashMap<String, MutableSet<ArtifactKind>>()
    val coordPom = HashMap<String, File>()
    for (a in allCached) {
      val gnv = "${a.group}:${a.name}:${a.version}"
      val kind = classifyArtifact(a.fileName) ?: ArtifactKind.OTHER
      coordKinds.getOrPut(gnv) { HashSet() }.add(kind)
      if (a.fileName.endsWith(".pom", true)) coordPom[gnv] = File(a.absolutePath)
    }
    fun coordSatisfied(gnv: String): Boolean {
      val kinds = coordKinds[gnv] ?: return false
      if (kinds.any { it == ArtifactKind.JAR || it == ArtifactKind.AAR || it == ArtifactKind.KLIB }) return true
      val pom = coordPom[gnv] ?: return false
      return pomPackaging(pom) in setOf("pom", "bom")
    }
    val cachedCoords: Set<String> = coordKinds.keys.filterTo(HashSet()) { coordSatisfied(it) }

    // Project's own subprojects never live in any artifact cache (they're built locally,
    // not downloaded). gradle still reports them as "needing resolution" — strip them.
    val rootName = readRootProjectName(projectDir).orEmpty()
    fun isOwnSubproject(group: String): Boolean =
      rootName.isNotBlank() && (group == rootName || group.startsWith("$rootName."))

    val coords = r.artifacts
      .map { DepCoordinate(id, it.g, it.n, it.v) }
      .distinctBy { it.key }
      .filterNot { c ->
        val gnv = "${c.group}:${c.name}:${c.version}"
        gnv in cachedCoords || isOwnSubproject(c.group)
      }
    return ResolveMissingResult(r.ok, coords, r.log, r.durationMs)
  }

  /** Parse `rootProject.name = "..."` from settings.gradle(.kts). Null if not found. */
  private fun readRootProjectName(projectDir: File): String? {
    val settings = listOf("settings.gradle.kts", "settings.gradle")
      .map { File(projectDir, it) }.firstOrNull { it.isFile } ?: return null
    val re = Regex("""rootProject\.name\s*=\s*["']([^"']+)["']""")
    return re.find(settings.readText())?.groupValues?.get(1)
  }

  override fun collect(
    coordinates: List<DepCoordinate>,
    presentIndex: Map<String, Set<String>>,
    onMissingLocally: (DepCoordinate) -> Unit
  ): List<DepFileEntry> {
    val want = coordinates.filter { it.ecosystem == id }
      .associateBy { "${it.group}:${it.name}:${it.version}" }
    if (want.isEmpty()) return emptyList()

    // Scan ALL candidate gradle caches (env GRADLE_USER_HOME, IDE setting,
    // default ~/.gradle, plus a gradle-reported home if a project hint was set).
    // The IDE may have built into a different gradle-home than our env inherited,
    // so we must look in every one — not just cacheRoot().
    val extraRoots = collectProjectDir?.let { dir ->
      GradleResolver.discoverGradleUserHome(dir)?.let {
        listOf(File(it, "caches/modules-2/files-2.1"))
      }
    } ?: emptyList()

    val allArtifacts = DepsScanner.scanAllCandidates(extraRoots = extraRoots)
    val byGnv = allArtifacts.groupBy { "${it.group}:${it.name}:${it.version}" }

    val scannedRoots = DepsScanner.candidateCacheRoots()
      .filter { it.isDirectory }.joinToString(", ") { it.absolutePath }
    DepsDiagnostics.event(
      "gradle collect: scanned cache(s)=[$scannedRoots] totalArtifacts=${allArtifacts.size} " +
        "wanted=${want.size} presentCoords=${presentIndex.size}"
    )

    val out = mutableListOf<DepFileEntry>()
    for ((gnv, coord) in want) {
      val arts = byGnv[gnv]
      if (arts.isNullOrEmpty()) {
        onMissingLocally(coord)
        continue
      }
      // 1. Trim docs/sources/tests + dedup multiple sha-dirs per kind.
      // 2. Subtract files the dome already has at the exact same content
      //    address (sha1 + fileName) — gradle's sha1 dir name IS the sha1 of
      //    the file's bytes, so identical addresses ↔ identical bytes.
      val shipable = pickShipableArtifacts(arts)
      val alreadyAtDome = presentIndex[gnv].orEmpty()
      val toShip = if (alreadyAtDome.isEmpty()) shipable else shipable.filter {
        ("${it.sha1}/${it.fileName}") !in alreadyAtDome
      }
      if (toShip.isEmpty()) {
        // Two reasons we end up here:
        //   a) nothing matched at all → genuinely missing locally (rare; should
        //      have been caught by `arts.isNullOrEmpty()` above)
        //   b) the dome already has every file we'd otherwise ship → this is
        //      the desired outcome, not an error. Don't warn.
        if (shipable.isEmpty()) onMissingLocally(coord)
        continue
      }
      for (art in toShip) {
        // Bundle entries use the maven-local layout so the dome can drop the
        // file straight into ~/.m2/repository/ and have it resolved by
        // mavenLocal() — independent of any gradle-internal metadata.
        val rel = MavenLocalScanner.mavenLocalRelativePath(art.group, art.name, art.version, art.fileName)
        out.add(DepFileEntry(coord, art.absolutePath, rel, art.size))
      }
    }

    // Parent-pom closure: every shipped .pom may declare a <parent> whose own
    // pom must also reach the dome, or mavenLocal() resolution fails with
    // "Could not find <parent>". The work machine's cache holds the full chain
    // (gradle downloaded it during its successful build), including PRIVATE
    // parents that Maven Central doesn't have. Walk it so the dome receives a
    // self-contained set.
    val addedParents = expandParentPomClosure(out, byGnv)
    if (addedParents > 0) {
      DepsDiagnostics.event("gradle collect: added $addedParents parent pom(s) to complete the chain")
    }
    return out
  }

  /** Parse a pom file's `<parent>` coordinate. Null if absent/malformed. */
  internal fun parentCoordOfPom(pomText: String): Triple<String, String, String>? {
    val block = Regex("""<parent>(.*?)</parent>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
      .find(pomText)?.groupValues?.get(1) ?: return null
    fun tag(t: String): String? =
      Regex("""<$t>\s*([^<]+?)\s*</$t>""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.trim()
    val g = tag("groupId") ?: return null
    val n = tag("artifactId") ?: return null
    val v = tag("version") ?: return null
    return if (g.isNotBlank() && n.isNotBlank() && v.isNotBlank()) Triple(g, n, v) else null
  }

  /**
   * Walk every shipped .pom for `<parent>` coords and append the parent's pom
   * (recursively) from the work cache. Parent artifacts are pom-only, so we
   * ship just the .pom. Parents absent from the cache are skipped — they're
   * public and the dome resolves them from Maven Central. Mutates [out];
   * returns the count of parent poms added.
   */
  private fun expandParentPomClosure(
    out: MutableList<DepFileEntry>,
    byGnv: Map<String, List<DepsScanner.Artifact>>
  ): Int {
    val shippedRel = out.mapTo(HashSet()) { it.relativePath }
    val queue = ArrayDeque(out.filter { it.relativePath.endsWith(".pom", true) }.map { it.absolutePath })
    val visited = HashSet(queue)
    var added = 0
    while (queue.isNotEmpty()) {
      val pomText = runCatching { File(queue.removeFirst()).readText() }.getOrNull() ?: continue
      val (g, n, v) = parentCoordOfPom(pomText) ?: continue
      val arts = byGnv["$g:$n:$v"] ?: continue
      for (a in pickShipableArtifacts(arts)) {
        if (!a.fileName.endsWith(".pom", true)) continue
        val rel = MavenLocalScanner.mavenLocalRelativePath(a.group, a.name, a.version, a.fileName)
        if (shippedRel.add(rel)) {
          out.add(DepFileEntry(DepCoordinate(id, a.group, a.name, a.version), a.absolutePath, rel, a.size))
          added++
        }
        if (visited.add(a.absolutePath)) queue.addLast(a.absolutePath)
      }
    }
    return added
  }

  /**
   * For one g:n:v, decide which cache files to ship. Pure & visible-for-tests.
   *
   *   - File classification — see [classifyArtifact].
   *   - "Doc" kinds (sources/javadoc/tests) are dropped entirely.
   *   - For every remaining kind we keep the freshest file (by mtime descending,
   *     then by absolutePath for determinism on equal mtimes), so we ship at most
   *     one jar + one pom + one module + one aar etc. — the exact set gradle
   *     needs to resolve the dep offline.
   */
  internal fun pickShipableArtifacts(arts: List<DepsScanner.Artifact>): List<DepsScanner.Artifact> {
    if (arts.isEmpty()) return emptyList()
    val byKind = LinkedHashMap<ArtifactKind, MutableList<DepsScanner.Artifact>>()
    for (a in arts) {
      val kind = classifyArtifact(a.fileName) ?: continue   // null = drop
      byKind.getOrPut(kind) { mutableListOf() }.add(a)
    }
    val out = mutableListOf<DepsScanner.Artifact>()
    for ((_, group) in byKind) {
      // mtime is more reliable than `version` (which is the same g:n:v string here).
      // Fall back to absolutePath for stable ordering when mtimes tie.
      val freshest = group.maxWithOrNull(
        compareBy<DepsScanner.Artifact> { File(it.absolutePath).lastModified() }
          .thenBy { it.absolutePath }
      ) ?: continue
      out.add(freshest)
    }
    return out
  }

  /** Read a pom's <packaging> (Maven default 'jar'), lowercased. */
  internal fun pomPackaging(pom: File): String {
    val text = runCatching { pom.readText() }.getOrNull() ?: return "jar"
    val m = Regex("""<packaging>\s*([^<]+?)\s*</packaging>""", RegexOption.IGNORE_CASE).find(text)
    return m?.groupValues?.get(1)?.trim()?.lowercase() ?: "jar"
  }

  /** Categorise a gradle cache file. Null = exclude from the bundle. */
  internal enum class ArtifactKind { JAR, POM, MODULE, AAR, KLIB, OTHER }

  internal fun classifyArtifact(fileName: String): ArtifactKind? {
    val lower = fileName.lowercase()
    return when {
      // Documentation / debug bundles — gradle does not need these to build.
      // Per gradle convention these names are deterministic: <name>-<v>-<classifier>.jar.
      lower.endsWith("-sources.jar") -> null
      lower.endsWith("-javadoc.jar") -> null
      lower.endsWith("-tests.jar") -> null
      lower.endsWith("-test.jar") -> null
      lower.endsWith(".jar") -> ArtifactKind.JAR
      lower.endsWith(".pom") -> ArtifactKind.POM
      lower.endsWith(".module") -> ArtifactKind.MODULE
      lower.endsWith(".aar") -> ArtifactKind.AAR
      lower.endsWith(".klib") -> ArtifactKind.KLIB
      // Unknown extension (e.g. .zip distributions) — keep, gradle may need it.
      else -> ArtifactKind.OTHER
    }
  }

  /**
   * DOME side. Enumerate every cache file gradle has on this machine, in the
   * exact (g, n, v, sha1, fileName) shape the work side needs to subtract from
   * its `collect()` output.
   *
   * Reads from BOTH locations so the work side can suppress identical content
   * regardless of where the dome holds it:
   *   - Gradle internal cache (caches/modules-2/files-2.1) — what gradle
   *     downloaded itself; sha1 comes from the directory layout (which IS the
   *     sha-1 of the file's bytes per gradle's content-address scheme).
   *   - Maven local (~/.m2/repository) — where Mirror unpacks received files;
   *     sha1 is computed from file bytes since the maven layout doesn't carry
   *     it explicitly.
   *
   * Both scans return the same Artifact shape; we dedup by content address
   * (sha1+filename) so an artifact present in both locations counts once.
   *
   * Reuses [pickShipableArtifacts] policy on each (g:n:v) group so the keys
   * line up with what the work side would actually package.
   */
  override fun enumeratePresent(): List<PresentArtifact> {
    val extraRoots = collectProjectDir?.let { dir ->
      GradleResolver.discoverGradleUserHome(dir)?.let {
        listOf(File(it, "caches/modules-2/files-2.1"))
      }
    } ?: emptyList()
    val gradleCacheArtifacts = DepsScanner.scanAllCandidates(extraRoots = extraRoots)
    val mavenLocalArtifacts = MavenLocalScanner.scan()
    val all = gradleCacheArtifacts + mavenLocalArtifacts
    if (all.isEmpty()) return emptyList()

    val out = ArrayList<PresentArtifact>()
    val seen = HashSet<String>()  // dedup key: g:n:v:sha1:fileName
    for ((_, group) in all.groupBy { "${it.group}:${it.name}:${it.version}" }) {
      for (art in pickShipableArtifacts(group)) {
        val key = "${art.group}:${art.name}:${art.version}:${art.sha1}:${art.fileName}"
        if (!seen.add(key)) continue
        out.add(PresentArtifact(art.group, art.name, art.version, art.sha1, art.fileName))
      }
    }
    return out
  }

  /**
   * Gradle's offline mode requires either a fully populated `metadata-2.X`
   * cache (which we cannot reproduce by copying files) or a real repository
   * with the artifact. We pick the latter: maven-local. As long as the project
   * has `mavenLocal()` on its repositories list, gradle resolves a manually-
   * placed file under `~/.m2/repository/<g>/<n>/<v>/<file>` even with --offline.
   *
   * The companion init-script `lgm-mavenlocal-fallback.gradle` (installed by
   * Mirror under `~/.gradle/init.d/`) ensures every project — including
   * `pluginManagement.repositories` and root `buildscript.repositories` — has
   * mavenLocal() declared, so this works without touching the project files.
   */
  override fun cacheRoot(): File = MavenLocalScanner.cacheRoot()

  /**
   * Lazily install the init-script that adds mavenLocal() to every gradle
   * build's repository lists. Idempotent: if the file already has the same
   * marker line we leave it alone (so a user-customised script survives).
   *
   * Lives at `~/.gradle/init.d/lgm-mavenlocal-fallback.gradle`. Gradle picks
   * up every `*.gradle` / `*.gradle.kts` in `init.d` automatically.
   *
   * Returns true if the file was written (created or updated), false if it
   * was already current.
   */
  fun ensureMavenLocalInitScript(): Boolean {
    val gradleHome = File(System.getProperty("user.home") ?: ".", ".gradle")
    val initDir = File(gradleHome, "init.d")
    if (!initDir.exists()) initDir.mkdirs()
    val target = File(initDir, "lgm-mavenlocal-fallback.gradle")
    val expected = MAVENLOCAL_INIT_SCRIPT
    if (target.isFile && target.readText() == expected) return false
    target.writeText(expected)
    return true
  }

  private val MAVENLOCAL_INIT_SCRIPT: String = """
    // LocalGitMirror v3 — auto-generated. Do not edit by hand: Mirror's apply
    // step rewrites this file when its content drifts from the plugin's copy.
    //
    // Makes artifacts unpacked into ~/.m2/repository (via 'Apply received deps')
    // resolvable from every gradle build without editing project files.
    //
    // We deliberately DO NOT touch settings.pluginManagement.repositories here.
    // Declaring any pluginManagement repository in an init script suppresses
    // gradle's implicit default (the plugin portal), which breaks projects that
    // rely on it (e.g. they declare no pluginManagement block of their own).
    // Projects that need mavenLocal() for plugin/marker resolution already
    // declare it in their own settings.gradle, so the init script only has to
    // cover project + buildscript dependency repositories.
    //
    // `beforeProject` runs before each project's build.gradle is evaluated, so a
    // buildscript {} classpath declared there sees mavenLocal() in its repo list.

    gradle.beforeProject { project ->
        project.buildscript.repositories {
            mavenLocal()
        }
        project.repositories {
            mavenLocal()
        }
    }
  """.trimIndent() + "\n"
}
