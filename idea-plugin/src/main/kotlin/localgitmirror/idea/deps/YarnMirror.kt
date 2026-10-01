package localgitmirror.idea.deps

import java.io.File

internal object YarnMirror {
  // ── yarn (classic v1) offline-mirror support ────────────────────────────────

  fun yarnOfflineMirror(): File = File(System.getProperty("user.home"), ".lgm-yarn-offline")

  /** yarn v1 offline-mirror filename: scope '/' -> '-', then '-<version>.tgz'. */
  fun yarnTarballName(name: String, version: String): String =
    name.replace("/", "-") + "-" + version + ".tgz"

  private fun readFully(ins: java.io.InputStream, buf: ByteArray): Boolean {
    var off = 0
    while (off < buf.size) {
      val r = ins.read(buf, off, buf.size - off)
      if (r < 0) return false
      off += r
    }
    return true
  }

  /**
   * Read (name, version) from package/package.json inside an npm .tgz, using a
   * minimal gzip+tar reader (no external dependency). Returns null on any issue.
   */
  fun readTgzNameVersion(tgz: File): Pair<String, String>? {
    return try {
      java.util.zip.GZIPInputStream(java.io.BufferedInputStream(tgz.inputStream())).use { gz ->
        val header = ByteArray(512)
        while (true) {
          if (!readFully(gz, header)) break
          if (header.all { it.toInt() == 0 }) break
          val name = String(header, 0, 100, Charsets.US_ASCII).substringBefore('\u0000').trim()
          if (name.isEmpty()) break
          val sizeField = String(header, 124, 12, Charsets.US_ASCII).trim().trim('\u0000', ' ')
          val size = sizeField.takeWhile { it in '0'..'7' }.ifEmpty { "0" }.toLong(8)
          if (name == "package/package.json") {
            val data = ByteArray(size.toInt())
            if (!readFully(gz, data)) return null
            val text = String(data, Charsets.UTF_8)
            val nm = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            val ver = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            return if (nm != null && ver != null) nm to ver else null
          }
          // skip this entry's content, padded to 512-byte blocks
          var toSkip = ((size + 511) / 512) * 512
          val skip = ByteArray(8192)
          while (toSkip > 0) {
            val r = gz.read(skip, 0, minOf(skip.size.toLong(), toSkip).toInt())
            if (r <= 0) break
            toSkip -= r
          }
        }
        null
      }
    } catch (_: Throwable) {
      null
    }
  }

  /** Copy corporate .tgz from the npm offline-mirror into a yarn v1
   *  offline-mirror, renamed to yarn's `<name '/'->'-'>-<version>.tgz`. */
  fun buildYarnMirror(tarballsRoot: File, mirror: File): Int {
    if (!tarballsRoot.isDirectory) return 0
    mirror.mkdirs()
    var n = 0
    tarballsRoot.walkTopDown().filter { it.isFile && it.extension == "tgz" }.forEach { tgz ->
      val nv = readTgzNameVersion(tgz) ?: return@forEach
      runCatching {
        tgz.copyTo(File(mirror, yarnTarballName(nv.first, nv.second)), overwrite = true)
        n++
      }
    }
    return n
  }

  fun writeYarnrc(project: File, mirror: File) {
    val yarnrc = File(project, ".yarnrc")
    val existing = if (yarnrc.isFile) yarnrc.readText() else ""
    val keep = existing.lines().filterNot {
      it.trim().startsWith("yarn-offline-mirror") || it.trim().startsWith("registry ")
    }.toMutableList()
    val mp = mirror.absolutePath.replace("\\", "/")
    keep.add("yarn-offline-mirror \"$mp\"")
    keep.add("yarn-offline-mirror-pruning false")
    keep.add("registry \"https://registry.npmjs.org\"")
    yarnrc.writeText(keep.joinToString("\n").trim('\n') + "\n")
  }

  /** Write the offline-mirror into the GLOBAL ~/.yarnrc (outside any repo) so it
   *  survives branch switches and never dirties a project. No registry is set
   *  (that could affect other projects); `yarn install --offline` needs none.
   *  Existing ~/.yarnrc lines are preserved. */
  fun setGlobalYarnMirror(mirror: File) {
    val yarnrc = File(System.getProperty("user.home"), ".yarnrc")
    val existing = if (yarnrc.isFile) yarnrc.readText() else ""
    val keep = existing.lines().filter {
      it.isNotBlank() && !it.trim().startsWith("yarn-offline-mirror")
    }.toMutableList()
    val mp = mirror.absolutePath.replace("\\", "/")
    keep.add("yarn-offline-mirror \"$mp\"")
    keep.add("yarn-offline-mirror-pruning false")
    yarnrc.writeText(keep.joinToString("\n") + "\n")
  }

  // ── WORK: yarn cache recovery for packages not in npm _cacache ──────────────

  /**
   * Corporate frontend projects using yarn v1 keep their packages in the yarn
   * global cache (NOT in npm _cacache), so the cacache scan in [NpmEcosystem.collect]
   * misses them. Two yarn-side sources are tried, in order:
   *   1. The plugin's yarn offline mirror (~/.lgm-yarn-offline) — tarballs
   *      already named in yarn's `<name '/'->'-'>-<version>.tgz` form.
   *   2. The yarn v6 global cache (<root>/v6/<hash>/node_modules/<pkg>/) —
   *      packages stored EXTRACTED with a package.json; we repack each into a
   *      proper .tgz via `npm pack` and drop it into the npm offline mirror.
   * Capped at 100 recoveries per collect call; never throws.
   */
  internal fun recoverFromYarn(
    missing: List<DepCoordinate>,
    presentIndex: Map<String, Set<String>>
  ): List<DepFileEntry> {
    if (missing.isEmpty()) return emptyList()
    val out = mutableListOf<DepFileEntry>()
    val cap = 100
    val processed = mutableSetOf<String>()

    // 1. Yarn offline mirror — scan by filename.
    val mirror = yarnOfflineMirror()
    if (mirror.isDirectory) {
      val byFilename = HashMap<String, File>()
      mirror.walkTopDown().filter { it.isFile && it.extension == "tgz" }.forEach {
        byFilename[it.name] = it
      }
      for (coord in missing) {
        if (out.size >= cap) break
        if (coord.key in processed) continue
        val fullName = if (coord.group.isNotEmpty()) "${coord.group}/${coord.name}" else coord.name
        val tgz = byFilename[yarnTarballName(fullName, coord.version)] ?: continue
        if (!isAlreadyAtDome(coord, tgz.name, presentIndex)) {
          out.add(DepFileEntry(coord, tgz.absolutePath, NpmCache.mirrorRelativePath(coord), tgz.length()))
        }
        processed.add(coord.key)
      }
    }

    // 2. Yarn global cache (extracted) — repack via npm pack.
    if (out.size < cap) {
      val stillMissing = missing.filter { it.key !in processed }
      if (stillMissing.isNotEmpty()) {
        val yarnRoot = yarnCacheDir()
        if (yarnRoot != null) {
          val v6 = File(yarnRoot, "v6")
          if (v6.isDirectory) recoverFromYarnV6(v6, stillMissing, presentIndex, out, cap)
        }
      }
    }

    DepsDiagnostics.event("npm collect: yarn recovered=${out.size}")
    return out
  }

  /** Walk the yarn v6 cache, match package.json name+version, repack matches. */
  private fun recoverFromYarnV6(
    v6Root: File,
    missing: List<DepCoordinate>,
    presentIndex: Map<String, Set<String>>,
    out: MutableList<DepFileEntry>,
    cap: Int
  ) {
    val wanted = HashMap<String, DepCoordinate>()
    for (c in missing) {
      val full = if (c.group.isNotEmpty()) "${c.group}/${c.name}" else c.name
      wanted["$full@${c.version}"] = c
    }

    val matches = mutableListOf<Pair<File, DepCoordinate>>()
    v6Root.walkTopDown().filter { it.isFile && it.name == "package.json" }.forEach { pj ->
      if (matches.size >= cap) return@forEach
      val nv = NpmRepack.readPackageNameVersion(pj) ?: return@forEach
      val coord = wanted[nv] ?: return@forEach
      matches.add(pj.parentFile to coord)
    }
    if (matches.isEmpty()) return

    val npm = NpmRepack.npmCommand() ?: return
    val tempDir = java.nio.file.Files.createTempDirectory("lgm-yarn-repack").toFile()
    try {
      for ((pkgDir, coord) in matches) {
        if (out.size >= cap) break
        val tgz = NpmRepack.repackWithNpm(npm, pkgDir, tempDir) ?: continue
        if (isAlreadyAtDome(coord, tgz.name, presentIndex)) {
          runCatching { tgz.delete() }
          continue
        }
        out.add(DepFileEntry(coord, tgz.absolutePath, NpmCache.mirrorRelativePath(coord), tgz.length()))
      }
    } finally {
      runCatching { tempDir.deleteRecursively() }
    }
  }

  /** True if the dome already has a file with this name under the same coord. */
  private fun isAlreadyAtDome(
    coord: DepCoordinate, fileName: String, presentIndex: Map<String, Set<String>>
  ): Boolean {
    val coordKey = "${coord.group}:${coord.name}:${coord.version}"
    val alreadyAtDome = presentIndex[coordKey].orEmpty()
    val fileKey = "${coord.classifier}/$fileName"
    return fileKey in alreadyAtDome
  }

  // ── yarn cache root discovery ───────────────────────────────────────────────

  /** Locate the yarn v1 global cache root: `yarn cache dir` or fallback paths. */
  private fun yarnCacheDir(): File? {
    val fromCmd = runYarnCacheDir()
    if (fromCmd != null && fromCmd.isDirectory) return fromCmd
    val localAppData = System.getenv("LOCALAPPDATA")
    if (localAppData != null) {
      val f = File(localAppData, "Yarn/Cache")
      if (f.isDirectory) return f
    }
    val home = System.getProperty("user.home")
    listOf(File(home, ".yarn/cache"), File(home, "Library/Caches/Yarn")).forEach {
      if (it.isDirectory) return it
    }
    return null
  }

  private fun runYarnCacheDir(): File? {
    val yarn = yarnCommand() ?: return null
    val cwd = GradleEcosystem.collectProjectDir ?: File(System.getProperty("user.home"))
    return try {
      val proc = ProcessBuilder(yarn + listOf("cache", "dir"))
        .directory(cwd)
        .redirectErrorStream(true).start()
      val out = proc.inputStream.bufferedReader().use { it.readText().trim() }
      if (!proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
        proc.destroyForcibly()
        return null
      }
      if (proc.exitValue() == 0 && out.isNotBlank()) File(out) else null
    } catch (_: Throwable) { null }
  }

  private fun yarnCommand(): List<String>? {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    return if (isWindows) listOf("cmd", "/c", "yarn") else listOf("yarn")
  }
}
