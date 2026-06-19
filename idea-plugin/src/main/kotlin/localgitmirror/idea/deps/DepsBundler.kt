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

  /** Encode artifacts into a ZIP byte array. */
  fun pack(artifacts: List<DepsScanner.Artifact>): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      for (art in artifacts) {
        val source = File(art.absolutePath)
        if (!source.exists()) continue
        val entryName = "${art.group}/${art.name}/${art.version}/${art.sha1}/${art.fileName}"
        zip.putNextEntry(ZipEntry(entryName))
        source.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
      }
    }
    return baos.toByteArray()
  }

  data class UnpackResult(
    val installed: Int,
    val skipped: Int,        // already-present, byte-for-byte
    val invalid: Int,        // bad path / outside the cache root
    val totalBytes: Long
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

          if (target.exists() && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) {
            skipped++
            continue
          }
          target.writeBytes(bytes)
          installed++
          totalBytes += bytes.size
        } finally {
          zin.closeEntry()
        }
      }
    }
    return UnpackResult(installed, skipped, invalid, totalBytes)
  }
}
