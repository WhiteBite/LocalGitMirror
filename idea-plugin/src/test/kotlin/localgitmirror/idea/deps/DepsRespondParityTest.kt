package localgitmirror.idea.deps

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DepsRespondParityTest {

  @TempDir
  lateinit var tmp: File

  private fun gradleFile(
    root: File, g: String, n: String, v: String, sha: String,
    fileName: String, text: String = "bytes-of-$fileName"
  ): File {
    val dir = File(root, "$g/$n/$v/$sha").apply { mkdirs() }
    return File(dir, fileName).apply { writeText(text) }
  }

  private fun gradlePom(
    root: File, g: String, n: String, v: String, sha: String,
    parent: Triple<String, String, String>? = null
  ): File {
    val block = parent?.let { (pg, pn, pv) ->
      "<parent><groupId>$pg</groupId><artifactId>$pn</artifactId><version>$pv</version></parent>"
    } ?: ""
    return gradleFile(root, g, n, v, sha, "$n-$v.pom", "<project>$block</project>")
  }

  private fun collect(
    roots: List<File>,
    vararg coords: DepCoordinate,
    presentIndex: Map<String, Set<String>> = emptyMap(),
    missing: MutableList<DepCoordinate> = mutableListOf()
  ): List<DepFileEntry> =
    GradleEcosystem.collectFromRoots(coords.toList(), roots, presentIndex) { missing.add(it) }

  private fun shippedPairs(entries: List<DepFileEntry>): List<Pair<String, String>> =
    entries.map { File(it.absolutePath).parentFile.name to File(it.absolutePath).name }

  private fun assertShipped(expected: List<Pair<String, String>>, entries: List<DepFileEntry>) {
    val ord = compareBy<Pair<String, String>>({ it.first }, { it.second })
    assertEquals(expected.sortedWith(ord), shippedPairs(entries).sortedWith(ord))
  }

  @Test
  fun `multi sha dirs same coordinate and file name - freshest mtime ships`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    val oldJar = gradleFile(cache, "com.corp", "lib", "1.0", "bbbb", "lib-1.0.jar")
    val newJar = gradleFile(cache, "com.corp", "lib", "1.0", "aaaa", "lib-1.0.jar")
    val t = System.currentTimeMillis() - 100_000
    oldJar.setLastModified(t)
    newJar.setLastModified(t + 50_000)

    val entries = collect(listOf(cache), DepCoordinate("gradle", "com.corp", "lib", "1.0"))
    assertShipped(listOf("aaaa" to "lib-1.0.jar"), entries)
  }

  @Test
  fun `mtime tie between sha dirs - lexicographically higher absolutePath ships`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    val a = gradleFile(cache, "com.corp", "lib", "1.0", "aaaa", "lib-1.0.jar")
    val b = gradleFile(cache, "com.corp", "lib", "1.0", "bbbb", "lib-1.0.jar")
    val t = System.currentTimeMillis() - 100_000
    a.setLastModified(t)
    b.setLastModified(t)

    val entries = collect(listOf(cache), DepCoordinate("gradle", "com.corp", "lib", "1.0"))
    assertShipped(listOf("bbbb" to "lib-1.0.jar"), entries)
  }

  @Test
  fun `jar pom and module ship together - sources and javadoc are dropped`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    gradleFile(cache, "com.corp", "lib", "2.0", "shaJar", "lib-2.0.jar")
    gradlePom(cache, "com.corp", "lib", "2.0", "shaPom")
    gradleFile(cache, "com.corp", "lib", "2.0", "shaMod", "lib-2.0.module")
    gradleFile(cache, "com.corp", "lib", "2.0", "shaSrc", "lib-2.0-sources.jar")
    gradleFile(cache, "com.corp", "lib", "2.0", "shaDoc", "lib-2.0-javadoc.jar")

    val entries = collect(listOf(cache), DepCoordinate("gradle", "com.corp", "lib", "2.0"))
    assertShipped(
      listOf("shaJar" to "lib-2.0.jar", "shaMod" to "lib-2.0.module", "shaPom" to "lib-2.0.pom"),
      entries
    )
  }

  @Test
  fun `two classifier jars of one coordinate - only the freshest jar ships`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    val linux = gradleFile(cache, "com.corp", "lib", "3.0", "shaLin", "lib-3.0-linux.jar")
    val win = gradleFile(cache, "com.corp", "lib", "3.0", "shaWin", "lib-3.0-win.jar")
    val t = System.currentTimeMillis() - 100_000
    linux.setLastModified(t)
    win.setLastModified(t + 50_000)

    val entries = collect(listOf(cache), DepCoordinate("gradle", "com.corp", "lib", "3.0"))
    assertShipped(listOf("shaWin" to "lib-3.0-win.jar"), entries)
  }

  @Test
  fun `parent chain child to parent to grandparent ships every pom`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    gradleFile(cache, "com.corp", "child", "1.0", "shaCJ", "child-1.0.jar")
    gradlePom(cache, "com.corp", "child", "1.0", "shaCP", Triple("com.corp", "parent", "2.0"))
    gradlePom(cache, "com.corp", "parent", "2.0", "shaPP", Triple("com.corp", "grand", "3.0"))
    gradlePom(cache, "com.corp", "grand", "3.0", "shaGP")

    val entries = collect(listOf(cache), DepCoordinate("gradle", "com.corp", "child", "1.0"))
    assertShipped(
      listOf(
        "shaCJ" to "child-1.0.jar",
        "shaCP" to "child-1.0.pom",
        "shaPP" to "parent-2.0.pom",
        "shaGP" to "grand-3.0.pom"
      ),
      entries
    )
  }

  @Test
  fun `parent cycle A to B to A terminates with each pom shipped once`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    gradleFile(cache, "com.corp", "aa", "1.0", "shaAJ", "aa-1.0.jar")
    gradlePom(cache, "com.corp", "aa", "1.0", "shaAP", Triple("com.corp", "bb", "1.0"))
    gradleFile(cache, "com.corp", "bb", "1.0", "shaBJ", "bb-1.0.jar")
    gradlePom(cache, "com.corp", "bb", "1.0", "shaBP", Triple("com.corp", "aa", "1.0"))

    val entries = collect(listOf(cache), DepCoordinate("gradle", "com.corp", "aa", "1.0"))
    assertShipped(
      listOf("shaAJ" to "aa-1.0.jar", "shaAP" to "aa-1.0.pom", "shaBP" to "bb-1.0.pom"),
      entries
    )
  }

  @Test
  fun `parent present only in maven local layout is not shipped`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    gradleFile(cache, "com.corp", "child", "1.0", "shaCJ", "child-1.0.jar")
    gradlePom(cache, "com.corp", "child", "1.0", "shaCP", Triple("com.corp", "parent", "2.0"))
    val m2Dir = File(tmp, MavenLocalScanner.mavenLocalRelativePath("com.corp", "parent", "2.0", ""))
      .apply { mkdirs() }
    File(m2Dir, "parent-2.0.pom").writeText("<project/>")

    val entries = collect(listOf(cache), DepCoordinate("gradle", "com.corp", "child", "1.0"))
    assertShipped(listOf("shaCJ" to "child-1.0.jar", "shaCP" to "child-1.0.pom"), entries)
  }

  @Test
  fun `coordinate present only in maven local is reported missing`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    val m2Dir = File(tmp, MavenLocalScanner.mavenLocalRelativePath("com.corp", "m2only", "1.0", ""))
      .apply { mkdirs() }
    File(m2Dir, "m2only-1.0.jar").writeText("jar")
    File(m2Dir, "m2only-1.0.pom").writeText("<project/>")

    val missing = mutableListOf<DepCoordinate>()
    val coord = DepCoordinate("gradle", "com.corp", "m2only", "1.0")
    val entries = collect(listOf(cache), coord, missing = missing)
    assertTrue(entries.isEmpty())
    assertEquals(listOf(coord), missing)
  }

  @Test
  fun `files already at dome are subtracted - remaining files still ship`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    gradleFile(cache, "com.corp", "lib", "4.0", "shaJar", "lib-4.0.jar")
    gradlePom(cache, "com.corp", "lib", "4.0", "shaPom")

    val present = mapOf("com.corp:lib:4.0" to setOf("shaJar/lib-4.0.jar"))
    val entries = collect(
      listOf(cache), DepCoordinate("gradle", "com.corp", "lib", "4.0"), presentIndex = present
    )
    assertShipped(listOf("shaPom" to "lib-4.0.pom"), entries)
  }

  @Test
  fun `parent poms ship even when the dome already reports them`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    gradleFile(cache, "com.corp", "child", "1.0", "shaCJ", "child-1.0.jar")
    gradlePom(cache, "com.corp", "child", "1.0", "shaCP", Triple("com.corp", "parent", "2.0"))
    gradlePom(cache, "com.corp", "parent", "2.0", "shaPP")

    val present = mapOf("com.corp:parent:2.0" to setOf("shaPP/parent-2.0.pom"))
    val entries = collect(
      listOf(cache), DepCoordinate("gradle", "com.corp", "child", "1.0"), presentIndex = present
    )
    assertShipped(
      listOf("shaCJ" to "child-1.0.jar", "shaCP" to "child-1.0.pom", "shaPP" to "parent-2.0.pom"),
      entries
    )
  }

  @Test
  fun `same sha and file name in two roots ships once from the first root`() {
    val rootA = File(tmp, "a").apply { mkdirs() }
    val rootB = File(tmp, "b").apply { mkdirs() }
    gradleFile(rootA, "com.corp", "lib", "5.0", "shaAA", "lib-5.0.jar")
    gradleFile(rootB, "com.corp", "lib", "5.0", "shaAA", "lib-5.0.jar")

    val entries = collect(listOf(rootA, rootB), DepCoordinate("gradle", "com.corp", "lib", "5.0"))
    assertShipped(listOf("shaAA" to "lib-5.0.jar"), entries)
    assertTrue(entries[0].absolutePath.startsWith(rootA.absolutePath))
  }

  @Test
  fun `second pass finds a coordinate that appeared in cache after the first pass`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    val coord = DepCoordinate("gradle", "com.corp", "lib", "6.0")
    val missing = mutableListOf<DepCoordinate>()

    val first = collect(listOf(cache), coord, missing = missing)
    assertTrue(first.isEmpty())
    assertEquals(listOf(coord), missing)

    gradleFile(cache, "com.corp", "lib", "6.0", "shaJ", "lib-6.0.jar")
    gradlePom(cache, "com.corp", "lib", "6.0", "shaP")

    val missing2 = mutableListOf<DepCoordinate>()
    val second = collect(listOf(cache), coord, missing = missing2)
    assertTrue(missing2.isEmpty())
    assertShipped(listOf("shaJ" to "lib-6.0.jar", "shaP" to "lib-6.0.pom"), second)
  }

  @Test
  fun `yarn mirror hit resolves a scoped package by direct stat`() {
    val mirror = File(tmp, "mirror").apply { mkdirs() }
    val tgz = File(mirror, "@scope-name-1.2.3.tgz").apply { writeText("tgz") }
    val out = mutableListOf<DepFileEntry>()
    val processed = mutableSetOf<String>()
    val coord = DepCoordinate("npm", "@scope", "name", "1.2.3")

    YarnMirror.recoverFromMirrorDir(mirror, listOf(coord), emptyMap(), out, processed, 100)

    assertEquals(1, out.size)
    assertEquals(tgz.absolutePath, out[0].absolutePath)
    assertEquals("scope/name/name-1.2.3.tgz", out[0].relativePath)
    assertTrue(coord.key in processed)
  }

  @Test
  fun `yarn mirror miss leaves the coordinate for the v6 fallback`() {
    val mirror = File(tmp, "mirror").apply { mkdirs() }
    File(mirror, "other-9.9.tgz").writeText("tgz")
    val out = mutableListOf<DepFileEntry>()
    val processed = mutableSetOf<String>()
    val coord = DepCoordinate("npm", "@scope", "name", "1.2.3")

    YarnMirror.recoverFromMirrorDir(mirror, listOf(coord), emptyMap(), out, processed, 100)

    assertTrue(out.isEmpty())
    assertTrue(processed.isEmpty())
  }

  @Test
  fun `yarn mirror lookup is top level only - nested tgz is not picked up`() {
    val mirror = File(tmp, "mirror").apply { mkdirs() }
    val nested = File(mirror, "nested").apply { mkdirs() }
    File(nested, "@scope-name-1.2.3.tgz").writeText("tgz")
    val out = mutableListOf<DepFileEntry>()
    val processed = mutableSetOf<String>()
    val coord = DepCoordinate("npm", "@scope", "name", "1.2.3")

    YarnMirror.recoverFromMirrorDir(mirror, listOf(coord), emptyMap(), out, processed, 100)

    assertTrue(out.isEmpty())
    assertTrue(processed.isEmpty())
  }

  @Test
  fun `npm tarballs still resolve via cacache integrity`() {
    val content = File(tmp, "content-v2").apply { mkdirs() }
    val raw = byteArrayOf(1, 2, 3, 4, 5, 6)
    val hex = raw.joinToString("") { "%02x".format(it) }
    val integrity = "sha512-" + Base64.getEncoder().encodeToString(raw)
    val shard = File(content, "sha512/${hex.substring(0, 2)}/${hex.substring(2, 4)}/${hex.substring(4)}")
    shard.parentFile.mkdirs()
    shard.writeText("tarball")

    val found = NpmCache.locateByIntegrity(content, integrity)
    assertEquals(shard.absolutePath, found?.absolutePath)
  }
}
