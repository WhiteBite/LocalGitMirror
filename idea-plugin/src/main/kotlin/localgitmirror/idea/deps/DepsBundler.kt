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

          val root = rootFor(eco) ?: run { invalid++; null } ?: continue
          if (!root.exists()) root.mkdirs()
          val rootCanonical = canonicalRoots.getOrPut(eco) { root.canonicalFile }

          val target = File(root, rel).canonicalFile
          if (!target.path.startsWith(rootCanonical.path + File.separator) &&
              target.path != rootCanonical.path
          ) { invalid++; continue }
          target.parentFile?.mkdirs()

          val bytes = zin.readBytes()
          val displayName = displayNameFor(eco, rel)

          if (target.exists() && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) {
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
    return UnpackResult(installed, skipped, invalid, totalBytes, installedNames, skippedNames)
  }

  private fun displayNameFor(eco: String, rel: String): String {
    val parts = rel.split('/')
    return when (eco) {
      "gradle" -> if (parts.size >= 4) "${parts[0]}:${parts[1]}:${parts[2]}" else rel
      "npm" -> parts.lastOrNull() ?: rel
      else -> rel
    }
  }

  data class UnpackResult(
    val installed: Int,
    val skipped: Int,        // already-present, byte-for-byte
    val invalid: Int,        // bad path / outside the cache root
    val totalBytes: Long,
    val installedEntries: List<String> = emptyList(),  // group:name:version
    val skippedEntries: List<String> = emptyList()
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
