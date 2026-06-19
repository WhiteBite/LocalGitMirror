package localgitmirror.idea.deps

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure logic tests — no network, no IntelliJ runtime.
 * Builds a fake gradle cache layout under a tmp dir and checks:
 *   - scanner finds all artifacts and reports correct sha1 dirname
 *   - bundler ZIPs and unpacks back into an identical tree
 *   - bundler refuses path-traversal entries
 *
 * (Ecosystem-level diff/collect logic lives in EcosystemDepsTest.)
 */
class DepsLogicTest {

  private fun fakeArtifact(
    root: File, group: String, name: String, version: String, sha: String,
    fileName: String, contents: ByteArray, originUrl: String? = null
  ): File {
    val dir = File(root, "$group/$name/$version/$sha").apply { mkdirs() }
    val artifact = File(dir, fileName).apply { writeBytes(contents) }
    if (originUrl != null) {
      File(dir, "_remote.repositories").writeText("$fileName>internal=$originUrl")
    }
    return artifact
  }

  private fun mkTmp(prefix: String): File =
    Files.createTempDirectory("lgm-deps-$prefix-").toFile()

  @Test
  fun `scanner finds all artifacts in fake cache`() {
    val cache = mkTmp("scan")
    try {
      fakeArtifact(cache, "org.spring", "spring-core", "6.1.5", "abc111", "spring-core-6.1.5.jar", "spring-bytes".toByteArray())
      fakeArtifact(cache, "com.kryptonit", "shared", "2.3.0", "def222", "shared-2.3.0.jar", "shared-bytes".toByteArray())
      fakeArtifact(cache, "junit", "junit", "5.10.1", "ghi333", "junit-5.10.1.jar", "junit-bytes".toByteArray())

      val scanned = DepsScanner.scan(cache)
      assertEquals(3, scanned.size)
      val keys = scanned.map { it.key }.toSet()
      assertContains(keys, "org.spring:spring-core:6.1.5:abc111:spring-core-6.1.5.jar")
      assertContains(keys, "com.kryptonit:shared:2.3.0:def222:shared-2.3.0.jar")
    } finally {
      cache.deleteRecursively()
    }
  }

  @Test
  fun `scanner skips sidecar and lock files`() {
    val cache = mkTmp("scan-sidecar")
    try {
      val dir = File(cache, "g/n/1.0/sha").apply { mkdirs() }
      File(dir, "n-1.0.jar").writeText("jar")
      File(dir, "_remote.repositories").writeText("n-1.0.jar>x=https://y")
      File(dir, ".lock").writeText("")
      val scanned = DepsScanner.scan(cache)
      assertEquals(1, scanned.size, "Only the jar is an artifact")
      assertEquals("n-1.0.jar", scanned[0].fileName)
    } finally { cache.deleteRecursively() }
  }

  @Test
  fun `bundler packs and unpacks identical artifact tree`() {
    val cache = mkTmp("bundle-src")
    val target = mkTmp("bundle-dst")
    try {
      fakeArtifact(cache, "g1", "n1", "1.0", "h1", "n1-1.0.jar", "data1".repeat(100).toByteArray())
      fakeArtifact(cache, "g2", "n2", "2.0", "h2", "n2-2.0.jar", "data2".repeat(50).toByteArray())
      val arts = DepsScanner.scan(cache)

      val zipBytes = DepsBundler.pack(arts)
      val result = DepsBundler.unpackInto(zipBytes, target)
      assertEquals(2, result.installed)
      assertEquals(0, result.skipped)
      assertEquals(0, result.invalid)

      val ext1 = File(target, "g1/n1/1.0/h1/n1-1.0.jar")
      val ext2 = File(target, "g2/n2/2.0/h2/n2-2.0.jar")
      assertTrue(ext1.exists() && ext2.exists())
      assertEquals(File(arts[0].absolutePath).readBytes().toList(), ext1.readBytes().toList())
    } finally {
      cache.deleteRecursively(); target.deleteRecursively()
    }
  }

  @Test
  fun `bundler unpack is idempotent - second run skips identical files`() {
    val cache = mkTmp("idem-src")
    val target = mkTmp("idem-dst")
    try {
      fakeArtifact(cache, "g", "n", "1", "h", "n.jar", "abc".toByteArray())
      val arts = DepsScanner.scan(cache)
      val zip = DepsBundler.pack(arts)

      val r1 = DepsBundler.unpackInto(zip, target)
      assertEquals(1, r1.installed)
      assertEquals(0, r1.skipped)

      val r2 = DepsBundler.unpackInto(zip, target)
      assertEquals(0, r2.installed)
      assertEquals(1, r2.skipped, "Same bytes already present must be reported as skipped")
    } finally {
      cache.deleteRecursively(); target.deleteRecursively()
    }
  }

  @Test
  fun `bundler refuses path traversal entries`() {
    val target = mkTmp("traversal")
    try {
      val baos = java.io.ByteArrayOutputStream()
      java.util.zip.ZipOutputStream(baos).use { zos ->
        zos.putNextEntry(java.util.zip.ZipEntry("../../escaped.txt"))
        zos.write("nope".toByteArray())
        zos.closeEntry()
        zos.putNextEntry(java.util.zip.ZipEntry("/abs/path.txt"))
        zos.write("nope".toByteArray())
        zos.closeEntry()
      }
      val res = DepsBundler.unpackInto(baos.toByteArray(), target)
      assertEquals(0, res.installed)
      assertEquals(2, res.invalid, "Both malicious entries must be rejected")
    } finally { target.deleteRecursively() }
  }
}
