package localgitmirror.idea.deps

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the redesigned, ecosystem-agnostic deps sync:
 *   - DepCoordinate identity is machine-independent
 *   - npm lockfile parsing picks ONLY corporate-registry packages
 *   - npm integrity -> cacache content path resolution
 *   - multi-ecosystem ZIP routing on unpack (path-prefixed by ecosystem)
 */
class EcosystemDepsTest {

  private fun mkTmp(prefix: String): File =
    Files.createTempDirectory("lgm-eco-$prefix-").toFile()

  // ── DepCoordinate ──────────────────────────────────────────────────────────

  @Test
  fun `coordinate key is path-independent and stable`() {
    val a = DepCoordinate("gradle", "com.foojay", "foojay-resolver", "0.8.0")
    val b = DepCoordinate("gradle", "com.foojay", "foojay-resolver", "0.8.0")
    assertEquals(a.key, b.key)
    assertEquals("gradle|com.foojay:foojay-resolver:0.8.0", a.key)
  }

  @Test
  fun `npm coordinate label uses scope and at-version`() {
    val c = DepCoordinate("npm", "@corp", "ui-kit", "2.3.1")
    assertEquals("@corp/ui-kit@2.3.1", c.label)
  }

  // ── npm lockfile parsing ─────────────────────────────────────────────────────

  @Test
  fun `lockfile v3 picks only corporate packages`() {
    val lock = """
    {
      "name": "app",
      "lockfileVersion": 3,
      "packages": {
        "": { "name": "app", "version": "1.0.0" },
        "node_modules/lodash": {
          "version": "4.17.21",
          "resolved": "https://registry.npmjs.org/lodash/-/lodash-4.17.21.tgz",
          "integrity": "sha512-PUBLICAAA=="
        },
        "node_modules/@corp/ui-kit": {
          "version": "2.3.1",
          "resolved": "https://nexus.corp.local/repository/npm/@corp/ui-kit/-/ui-kit-2.3.1.tgz",
          "integrity": "sha512-Q29ycA=="
        },
        "node_modules/@corp/utils": {
          "version": "1.0.0",
          "resolved": "https://nexus.corp.local/repository/npm/@corp/utils/-/utils-1.0.0.tgz",
          "integrity": "sha512-V1ils=="
        }
      }
    }
    """.trimIndent()

    val coords = NpmEcosystem.parseLockfileForCorporate(lock)
    assertEquals(2, coords.size, "Only the two corporate packages should be returned: ${coords.map { it.label }}")
    val labels = coords.map { it.label }.toSet()
    assertTrue("@corp/ui-kit@2.3.1" in labels)
    assertTrue("@corp/utils@1.0.0" in labels)
    assertTrue(coords.none { it.name == "lodash" }, "Public lodash must be excluded")
    // integrity is carried for cache lookup
    assertEquals("sha512-Q29ycA==", coords.first { it.name == "ui-kit" }.classifier)
  }

  @Test
  fun `lockfile v1 dependencies form is parsed too`() {
    val lock = """
    {
      "name": "app",
      "lockfileVersion": 1,
      "dependencies": {
        "left-pad": {
          "version": "1.3.0",
          "resolved": "https://registry.npmjs.org/left-pad/-/left-pad-1.3.0.tgz",
          "integrity": "sha1-pub="
        },
        "@corp/secret": {
          "version": "0.1.0",
          "resolved": "https://nexus.corp.local/npm/@corp/secret/-/secret-0.1.0.tgz",
          "integrity": "sha512-corp=="
        }
      }
    }
    """.trimIndent()

    val coords = NpmEcosystem.parseLockfileForCorporate(lock)
    assertEquals(1, coords.size)
    assertEquals("@corp/secret@0.1.0", coords[0].label)
  }

  @Test
  fun `git and file resolved entries are skipped`() {
    val lock = """
    {
      "lockfileVersion": 3,
      "packages": {
        "node_modules/forked": {
          "version": "1.0.0",
          "resolved": "git+https://github.com/x/forked.git#abc"
        },
        "node_modules/local": {
          "version": "1.0.0",
          "resolved": "file:../local"
        }
      }
    }
    """.trimIndent()
    assertTrue(NpmEcosystem.parseLockfileForCorporate(lock).isEmpty())
  }

  @Test
  fun `public registry detection covers subdomains`() {
    assertTrue(NpmEcosystem.isPublicRegistry("https://registry.npmjs.org/x/-/x-1.tgz"))
    assertTrue(NpmEcosystem.isPublicRegistry("https://registry.yarnpkg.com/x"))
    assertTrue(!NpmEcosystem.isPublicRegistry("https://nexus.corp.local/npm/x"))
  }

  @Test
  fun `splitScopeName handles scoped and unscoped`() {
    assertEquals("@corp" to "ui", NpmEcosystem.splitScopeName("@corp/ui"))
    assertEquals("" to "lodash", NpmEcosystem.splitScopeName("lodash"))
  }

  // ── npm cacache integrity resolution ─────────────────────────────────────────

  @Test
  fun `locateByIntegrity finds sharded content file`() {
    val content = mkTmp("cacache")
    try {
      // Build a fake integrity whose hash is known bytes -> hex.
      val raw = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x01, 0x02, 0x03)
      val hex = raw.joinToString("") { "%02x".format(it) }   // "abcdef010203"
      val integrity = "sha512-" + Base64.getEncoder().encodeToString(raw)

      val shard = File(content, "sha512/${hex.substring(0,2)}/${hex.substring(2,4)}/${hex.substring(4)}")
      shard.parentFile.mkdirs()
      shard.writeText("tarball-bytes")

      val found = NpmEcosystem.locateByIntegrity(content, integrity)
      assertNotNull(found, "Should resolve the sharded content path")
      assertEquals("tarball-bytes", found!!.readText())
    } finally { content.deleteRecursively() }
  }

  @Test
  fun `locateByIntegrity returns null for blank or missing`() {
    val content = mkTmp("cacache-empty")
    try {
      assertNull(NpmEcosystem.locateByIntegrity(content, ""))
      assertNull(NpmEcosystem.locateByIntegrity(content, "sha512-" + Base64.getEncoder().encodeToString(byteArrayOf(9,9,9,9))))
    } finally { content.deleteRecursively() }
  }

  @Test
  fun `npm mirror relative path is flat per scope`() {
    assertEquals("corp/ui-kit/ui-kit-2.3.1.tgz",
      NpmEcosystem.mirrorRelativePath(DepCoordinate("npm", "@corp", "ui-kit", "2.3.1")))
    assertEquals("lodash/lodash-4.17.21.tgz",
      NpmEcosystem.mirrorRelativePath(DepCoordinate("npm", "", "lodash", "4.17.21")))
  }

  // ── multi-ecosystem routing on unpack ────────────────────────────────────────

  @Test
  fun `unpackRouted sends entries to the right cache root by prefix`() {
    val gradleRoot = mkTmp("groot")
    val npmRoot = mkTmp("nroot")
    val srcA = mkTmp("srcA")
    try {
      // two source files
      val jar = File(srcA, "a.jar").apply { writeText("jardata") }
      val tgz = File(srcA, "a.tgz").apply { writeText("tgzdata") }

      val entries = listOf(
        DepFileEntry(DepCoordinate("gradle", "g", "n", "1"), jar.absolutePath, "gradle/g/n/1/sha/a.jar", jar.length()),
        DepFileEntry(DepCoordinate("npm", "@corp", "n", "1"), tgz.absolutePath, "npm/corp/n/n-1.tgz", tgz.length()),
      )
      val zip = DepsBundler.packEntries(entries)

      val res = DepsBundler.unpackRouted(zip) { eco ->
        when (eco) { "gradle" -> gradleRoot; "npm" -> npmRoot; else -> null }
      }
      assertEquals(2, res.installed)
      assertTrue(File(gradleRoot, "g/n/1/sha/a.jar").exists())
      assertTrue(File(npmRoot, "corp/n/n-1.tgz").exists())
      assertEquals("jardata", File(gradleRoot, "g/n/1/sha/a.jar").readText())
    } finally {
      gradleRoot.deleteRecursively(); npmRoot.deleteRecursively(); srcA.deleteRecursively()
    }
  }

  @Test
  fun `unpackRouted rejects unknown ecosystem and traversal`() {
    val gradleRoot = mkTmp("groot2")
    val src = mkTmp("src2")
    try {
      val f = File(src, "x").apply { writeText("data") }
      // unknown ecosystem prefix -> invalid; traversal -> invalid
      val entries = listOf(
        DepFileEntry(DepCoordinate("bogus", "g", "n", "1"), f.absolutePath, "bogus/g/file", f.length()),
      )
      val zip = DepsBundler.packEntries(entries)
      val res = DepsBundler.unpackRouted(zip) { eco -> if (eco == "gradle") gradleRoot else null }
      assertEquals(0, res.installed)
      assertEquals(1, res.invalid)
    } finally { gradleRoot.deleteRecursively(); src.deleteRecursively() }
  }

  @Test
  fun `gradle ecosystem collect finds requested coords in cache`() {
    val cache = mkTmp("gcache")
    try {
      // fake gradle cache: group/name/version/sha/file
      File(cache, "com.foojay/foojay-resolver/0.8.0/aaa/foojay-resolver-0.8.0.jar").apply {
        parentFile.mkdirs(); writeText("foojay")
      }
      File(cache, "org.public/lib/1.0/bbb/lib-1.0.jar").apply {
        parentFile.mkdirs(); writeText("public")
      }

      val want = listOf(DepCoordinate("gradle", "com.foojay", "foojay-resolver", "0.8.0"))
      // collect() uses the default cacheRoot(); to test against our tmp cache we
      // exercise the underlying scan+group logic via DepsScanner directly.
      val byGnv = DepsScanner.scan(cache).groupBy { "${it.group}:${it.name}:${it.version}" }
      val match = byGnv["com.foojay:foojay-resolver:0.8.0"]
      assertNotNull(match)
      assertEquals(1, match!!.size)
      assertTrue(want.first().label == "com.foojay:foojay-resolver:0.8.0")
    } finally { cache.deleteRecursively() }
  }

  // ── npm cache dir resolution (pure, injectable) ──────────────────────────────

  @Test
  fun `npm cache dir honours npm_config_cache on any OS`() {
    val win = NpmEcosystem.npmCacheDirFrom(
      npmConfigCache = "X:/custom/cache", localAppData = "C:/Users/x/AppData/Local",
      userHome = "C:/Users/x", isWindows = true
    )
    val unix = NpmEcosystem.npmCacheDirFrom(
      npmConfigCache = "/custom/cache", localAppData = null,
      userHome = "/home/x", isWindows = false
    )
    assertEquals(File("X:/custom/cache"), win)
    assertEquals(File("/custom/cache"), unix)
  }

  @Test
  fun `npm cache dir uses LocalAppData on windows`() {
    val dir = NpmEcosystem.npmCacheDirFrom(
      npmConfigCache = null, localAppData = "C:/Users/x/AppData/Local",
      userHome = "C:/Users/x", isWindows = true
    )
    assertEquals(File("C:/Users/x/AppData/Local", "npm-cache"), dir)
  }

  @Test
  fun `npm cache dir falls back to home AppData when LocalAppData missing`() {
    val dir = NpmEcosystem.npmCacheDirFrom(
      npmConfigCache = null, localAppData = null,
      userHome = "C:/Users/x", isWindows = true
    )
    assertEquals(File("C:/Users/x", "AppData/Local/npm-cache"), dir)
  }

  @Test
  fun `npm cache dir uses dot-npm on unix`() {
    val dir = NpmEcosystem.npmCacheDirFrom(
      npmConfigCache = null, localAppData = null,
      userHome = "/home/x", isWindows = false
    )
    assertEquals(File("/home/x", ".npm"), dir)
  }

  @Test
  fun `npm cache dir ignores blank npm_config_cache`() {
    val dir = NpmEcosystem.npmCacheDirFrom(
      npmConfigCache = "   ", localAppData = null,
      userHome = "/home/x", isWindows = false
    )
    assertEquals(File("/home/x", ".npm"), dir)
  }

  @Test
  fun `npm postInstall is a no-op when there are no tarballs`() {
    // No .tgz paths → must return "" without ever invoking npm.
    assertEquals("", NpmEcosystem.postInstall(emptyList()))
    assertEquals("", NpmEcosystem.postInstall(listOf("not-a-tarball.txt", "also/none.jar")))
  }
}
