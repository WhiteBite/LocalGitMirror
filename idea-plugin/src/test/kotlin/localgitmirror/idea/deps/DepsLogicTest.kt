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
 *   - internal-repo filter works against _remote.repositories sidecar
 *   - manifest round-trips through JSON
 *   - diff returns exactly what the work side should ship
 *   - bundler ZIPs and unpacks back into an identical tree
 *   - bundler refuses path-traversal entries
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
  fun `internal repo filter matches by substring of origin url`() {
    val cache = mkTmp("filter")
    try {
      fakeArtifact(cache, "org.spring", "spring-core", "6.1.5", "abc", "x.jar", "x".toByteArray(), originUrl = "https://repo1.maven.org/maven2/")
      fakeArtifact(cache, "com.kryptonit", "shared", "2.3.0", "def", "y.jar", "y".toByteArray(), originUrl = "https://nexus.kryptonit.local/repo/")

      val all = DepsScanner.scan(cache)
      val internalOnly = all.filter { DepsScanner.matchesInternalRepo(it, listOf("nexus.kryptonit")) }
      assertEquals(1, internalOnly.size)
      assertEquals("shared", internalOnly[0].name)
    } finally { cache.deleteRecursively() }
  }

  @Test
  fun `internal filter empty means accept everything`() {
    val cache = mkTmp("filter-empty")
    try {
      fakeArtifact(cache, "g", "a", "1", "s", "a.jar", "a".toByteArray(), originUrl = "https://x")
      val all = DepsScanner.scan(cache)
      assertTrue(DepsScanner.matchesInternalRepo(all[0], emptyList()))
    } finally { cache.deleteRecursively() }
  }

  @Test
  fun `internal filter rejects artifact without origin sidecar when filter active`() {
    val cache = mkTmp("filter-no-origin")
    try {
      // No _remote.repositories sidecar — origin unknown
      fakeArtifact(cache, "g", "a", "1", "s", "a.jar", "a".toByteArray(), originUrl = null)
      val all = DepsScanner.scan(cache)
      assertTrue(!DepsScanner.matchesInternalRepo(all[0], listOf("nexus.local")))
    } finally { cache.deleteRecursively() }
  }

  @Test
  fun `manifest round-trips through JSON`() {
    val cache = mkTmp("manifest")
    try {
      fakeArtifact(cache, "g1", "n1", "1.0", "sh1", "n1-1.0.jar", "x".toByteArray())
      fakeArtifact(cache, "g2", "n2", "2.0", "sh2", "n2-2.0.jar", "y".toByteArray())
      val arts = DepsScanner.scan(cache)

      val m = DepsManifest.fromArtifacts(requester = "DOM", project = "onyx", artifacts = arts)
      val bytes = DepsManifest.toJsonBytes(m)
      val back = DepsManifest.fromJsonBytes(bytes)
      assertEquals(2, back.artifacts.size)
      assertEquals(m.keys(), back.keys())
    } finally { cache.deleteRecursively() }
  }

  @Test
  fun `diff yields only what work has and dome lacks, filtered by internal`() {
    val cacheWork = mkTmp("work")
    val cacheDome = mkTmp("dome")
    try {
      // Work has 3 artifacts: 2 from nexus, 1 from maven central.
      fakeArtifact(cacheWork, "com.kryptonit", "shared", "2.3.0", "ww1", "shared-2.3.0.jar", "shared".toByteArray(), originUrl = "https://nexus.local/")
      fakeArtifact(cacheWork, "com.kryptonit", "internal", "1.0.0", "ww2", "internal-1.0.0.jar", "internal".toByteArray(), originUrl = "https://nexus.local/")
      fakeArtifact(cacheWork, "org.spring", "spring", "6.1.5", "ww3", "spring-6.1.5.jar", "spring".toByteArray(), originUrl = "https://repo1.maven.org/")

      // Dome has only the spring one (downloaded from public repo).
      fakeArtifact(cacheDome, "org.spring", "spring", "6.1.5", "ww3", "spring-6.1.5.jar", "spring".toByteArray())

      val workArts = DepsScanner.scan(cacheWork)
      val domeManifest = DepsManifest.fromArtifacts("DOM", "onyx", DepsScanner.scan(cacheDome))

      val diff = DepsDiff.compute(workArts, domeManifest, internalRepoSubstrings = listOf("nexus.local"))
      // Two nexus artifacts: shared + internal. Spring is excluded by internal-only filter
      // (and dome has it anyway).
      assertEquals(2, diff.size, "Expected exactly nexus-only missing artifacts in diff: ${diff.map { it.key }}")
      assertTrue(diff.all { it.absolutePath.startsWith(cacheWork.absolutePath) })
    } finally {
      cacheWork.deleteRecursively(); cacheDome.deleteRecursively()
    }
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

      // Verify the contents in the destination tree match
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
      // Build a malicious zip by hand
      val baos = java.io.ByteArrayOutputStream()
      java.util.zip.ZipOutputStream(baos).use { zos ->
        // Try to escape with ../../etc/passwd
        zos.putNextEntry(java.util.zip.ZipEntry("../../escaped.txt"))
        zos.write("nope".toByteArray())
        zos.closeEntry()
        // Absolute path
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
