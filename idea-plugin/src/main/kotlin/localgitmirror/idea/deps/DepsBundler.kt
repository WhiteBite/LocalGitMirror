package localgitmirror.idea.deps

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pack/unpack a set of artifacts as a ZIP whose internal layout mirrors
 * Gradle's cache layout: `<group>/<name>/<version>/<sha1>/<filename>`.
 * On the receiving side we drop the entries straight back into
 * `<gradle-cache>/modules-2/files-2.1/` and Gradle picks them up offline.
 *
 * No native zstd dep — JDK's deflate is good enough for jars (already deflated
 * inside, so further gain is marginal anyway).
 */
object DepsBundler {

  /** Encode artifacts into a ZIP byte array (gradle convenience overload). */
  fun pack(artifacts: List<DepsScanner.Artifact>): ByteArray =
    packEntries(artifacts.map {
      DepFileEntry(
        coordinate = DepCoordinate("gradle", it.group, it.name, it.version),
        absolutePath = it.absolutePath,
        relativePath = "${it.group}/${it.name}/${it.version}/${it.sha1}/${it.fileName}",
        size = it.size
      )
    })

  /** Encode ecosystem-agnostic file entries into a ZIP, keyed by relativePath. */
  fun packEntries(entries: List<DepFileEntry>): ByteArray {
    val baos = ByteArrayOutputStream()
    val seen = HashSet<String>()
    ZipOutputStream(baos).use { zip ->
      for (entry in entries) {
        val source = File(entry.absolutePath)
        if (!source.exists()) continue
        val name = entry.relativePath.replace('\\', '/').trimStart('/')
        if (name.isEmpty() || name.contains("..") || !seen.add(name)) continue
        zip.putNextEntry(ZipEntry(name))
        source.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
      }
    }
    return baos.toByteArray()
  }

  /**
   * Multi-ecosystem unpack. ZIP entry names are expected to be prefixed with
   * the ecosystem id, e.g. `gradle/<group>/.../file.jar` or `npm/<scope>/...tgz`.
   * Each entry is routed to the cache root returned by [rootFor] for that
   * ecosystem (null = skip the entry as invalid). Path-traversal is rejected
   * after canonicalisation, same as [unpackInto].
   */
  fun unpackRouted(
    zipBytes: ByteArray,
    rootFor: (ecosystem: String) -> File?
  ): UnpackResult {
    var installed = 0
    var skipped = 0
    var invalid = 0
    var totalBytes = 0L
    val installedNames = mutableListOf<String>()
    val skippedNames = mutableListOf<String>()
    val metaFiles = HashMap<String, ByteArray>()  // __meta__/* (e.g. package-lock.json)
    // Cache rootFor() results per ecosystem — rootFor() may be expensive
    // (e.g. GradleEcosystem.cacheRoot() spawns a gradle process to discover
    // GRADLE_USER_HOME). Without this cache it would be called once per ZIP
    // entry, causing a ~2-minute hang per artifact on the dome side.
    val rootCache = HashMap<String, File?>()
    val canonicalRoots = HashMap<String, File>()

    ZipInputStream(zipBytes.inputStream()).use { zin ->
      while (true) {
        val entry: ZipEntry = zin.nextEntry ?: break
        try {
          if (entry.isDirectory) continue
          val full = entry.name.replace('\\', '/').trimStart('/')
          val slash = full.indexOf('/')
          if (slash <= 0) { invalid++; continue }
          val eco = full.substring(0, slash)
          val rel = full.substring(slash + 1)
          if (rel.isEmpty() || rel.contains("..")) { invalid++; continue }

          // Meta entries (e.g. the project's package-lock.json) are not cache
          // artifacts — collect them for the caller, don't route to a cache.
          if (eco == "__meta__") { metaFiles[rel] = zin.readBytes(); continue }

          val root = rootCache.getOrPut(eco) { rootFor(eco) } ?: run { invalid++; null } ?: continue
          if (!root.exists()) root.mkdirs()
          val rootCanonical = canonicalRoots.getOrPut(eco) { root.canonicalFile }

          val target = File(root, rel).canonicalFile
          if (!target.path.startsWith(rootCanonical.path + File.separator) &&
              target.path != rootCanonical.path
          ) { invalid++; continue }
          target.parentFile?.mkdirs()

          val bytes = zin.readBytes()
          val displayName = displayNameFor(eco, rel)

          // Skip writes when the file at `target` is byte-identical to what we
          // received. We compare in three stages, cheap-to-expensive:
          //   1. size (one stat call) — most mismatches die here
          //   2. first/last 4 KiB sample (one short read)
          //   3. full byte compare only if both samples match (rare)
          // For gradle's content-addressed cache the directory name IS the
          // sha1 of the file's bytes, so size-match alone is already a near-
          // certain identity proof — but we still verify a sample, since we
          // can't statically know every ecosystem layout follows that rule.
          if (target.exists() &&
              target.length() == bytes.size.toLong() &&
              isSameContent(target, bytes)
          ) {
            skipped++; skippedNames.add(displayName); continue
          }
          target.writeBytes(bytes)
          installed++
          totalBytes += bytes.size
          installedNames.add(displayName)
        } finally {
          zin.closeEntry()
        }
      }
    }
    return UnpackResult(installed, skipped, invalid, totalBytes, installedNames, skippedNames, metaFiles)
  }

  private fun displayNameFor(eco: String, rel: String): String {
    val parts = rel.split('/')
    return when (eco) {
      "gradle" -> if (parts.size >= 4) "${parts[0]}:${parts[1]}:${parts[2]}" else rel
      "npm" -> parts.lastOrNull() ?: rel
      else -> rel
    }
  }

  /**
   * Compare a file on disk to a byte buffer cheaply.
   *
   * Caller MUST have already verified that sizes match — this function does
   * not re-check, it just decides "are the bytes equal?". Strategy:
   *
   *   - For files ≤ 8 KiB: read fully and compare. Tiny anyway.
   *   - Otherwise: read the first 4 KiB and last 4 KiB. If either differs,
   *     the files differ. If both match, fall back to a full byte-by-byte
   *     compare to catch the rare case where only the middle differs.
   *
   * The sample check alone catches almost every false positive in practice
   * because real file mismatches at identical sizes always involve different
   * content somewhere — and zips/jars/tar.gz all have headers and trailers
   * that sit exactly in the sampled regions.
   */
  internal fun isSameContent(target: File, bytes: ByteArray): Boolean {
    val len = bytes.size
    if (len <= 8 * 1024) {
      return target.readBytes().contentEquals(bytes)
    }
    val sample = 4 * 1024
    java.io.RandomAccessFile(target, "r").use { raf ->
      val head = ByteArray(sample)
      raf.seek(0)
      raf.readFully(head)
      for (i in 0 until sample) if (head[i] != bytes[i]) return false

      val tail = ByteArray(sample)
      raf.seek((len - sample).toLong())
      raf.readFully(tail)
      for (i in 0 until sample) if (tail[i] != bytes[len - sample + i]) return false
    }
    // Samples match — most likely the whole file is identical. Verify the
    // middle too. Could be omitted for speed, but byte-equal correctness is
    // cheap relative to a wasted re-write.
    return target.readBytes().contentEquals(bytes)
  }

  data class UnpackResult(
    val installed: Int,
    val skipped: Int,        // already-present, byte-for-byte
    val invalid: Int,        // bad path / outside the cache root
    val totalBytes: Long,
    val installedEntries: List<String> = emptyList(),  // group:name:version
    val skippedEntries: List<String> = emptyList(),
    val meta: Map<String, ByteArray> = emptyMap()       // __meta__/* (e.g. package-lock.json)
  )

  /**
   * Extract the ZIP into the local Gradle cache.
   * Refuses any entry whose normalised path would land outside [cacheRoot].
   * Returns counts so the UI can report what happened.
   */
  fun unpackInto(zipBytes: ByteArray, cacheRoot: File = DepsScanner.cacheRoot()): UnpackResult {
    if (!cacheRoot.exists()) cacheRoot.mkdirs()
    val rootCanonical = cacheRoot.canonicalFile

    var installed = 0
    var skipped = 0
    var invalid = 0
    var totalBytes = 0L
    val installedNames = mutableListOf<String>()
    val skippedNames = mutableListOf<String>()

    ZipInputStream(zipBytes.inputStream()).use { zin ->
      while (true) {
        val entry: ZipEntry = zin.nextEntry ?: break
        try {
          if (entry.isDirectory) continue
          val name = entry.name.replace('\\', '/')
          // Defensive: reject absolute-like or parent-traversal paths
          if (name.startsWith("/") || name.contains("..")) {
            invalid++
            continue
          }
          val target = File(cacheRoot, name).canonicalFile
          // Must still be inside cacheRoot after canonicalisation
          if (!target.path.startsWith(rootCanonical.path + File.separator) &&
              target.path != rootCanonical.path
          ) {
            invalid++
            continue
          }
          target.parentFile?.mkdirs()

          // Read entry bytes once; we may need them for a "skipped if identical" check
          val bytes = zin.readBytes()

          // Recover artifact identity from the zip path: <group>/<name>/<version>/<sha>/<file>
          val parts = name.split('/')
          val displayName = if (parts.size >= 4) "${parts[0]}:${parts[1]}:${parts[2]}" else name

          if (target.exists() && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) {
            skipped++
            skippedNames.add(displayName)
            continue
          }
          target.writeBytes(bytes)
          installed++
          totalBytes += bytes.size
          installedNames.add(displayName)
        } finally {
          zin.closeEntry()
        }
      }
    }
    return UnpackResult(installed, skipped, invalid, totalBytes, installedNames, skippedNames)
  }
}
