package localgitmirror.idea.deps

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class GradleTargetedResolverTest {

  @TempDir
  lateinit var tmp: File

  private fun fakeArtifact(root: File, group: String, name: String, version: String, sha: String, fileName: String): File {
    val dir = File(root, "$group/$name/$version/$sha").apply { mkdirs() }
    return File(dir, fileName).apply { writeText("bytes-of-$fileName") }
  }

  @Test
  fun `resolve returns exact sha1 and file pairs for a present coordinate`() {
    val root = File(tmp, "cache").apply { mkdirs() }
    fakeArtifact(root, "org.spring", "spring-core", "6.1.5", "abc111", "spring-core-6.1.5.jar")

    val found = GradleTargetedResolver.resolve(listOf(root), "org.spring", "spring-core", "6.1.5")
    assertEquals(1, found.size)
    assertEquals("abc111", found[0].sha1)
    assertEquals("spring-core-6.1.5.jar", found[0].file.name)
    assertEquals(
      File(root, "org.spring/spring-core/6.1.5/abc111/spring-core-6.1.5.jar").absolutePath,
      found[0].file.absolutePath
    )
  }

  @Test
  fun `resolve unions roots and dedupes by sha1 and fileName`() {
    val rootA = File(tmp, "a").apply { mkdirs() }
    val rootB = File(tmp, "b").apply { mkdirs() }
    fakeArtifact(rootA, "g", "n", "1.0", "shaAA", "n-1.0.jar")
    fakeArtifact(rootB, "g", "n", "1.0", "shaAA", "n-1.0.jar")
    fakeArtifact(rootB, "g", "n", "1.0", "shaBB", "n-1.0.pom")

    val found = GradleTargetedResolver.resolve(listOf(rootA, rootB), "g", "n", "1.0")
    assertEquals(2, found.size)
    assertEquals("shaAA", found[0].sha1)
    assertEquals(File(rootA, "g/n/1.0/shaAA/n-1.0.jar").absolutePath, found[0].file.absolutePath)
    assertEquals(setOf("shaAA", "shaBB"), found.map { it.sha1 }.toSet())
  }

  @Test
  fun `resolve skips sidecar and lock files like DepsScanner`() {
    val root = File(tmp, "cache").apply { mkdirs() }
    val dir = File(root, "g/n/1.0/sha1").apply { mkdirs() }
    File(dir, "n-1.0.jar").writeText("jar")
    File(dir, "_remote.repositories").writeText("n-1.0.jar>x=https://y")
    File(dir, ".lock").writeText("")

    val found = GradleTargetedResolver.resolve(listOf(root), "g", "n", "1.0")
    assertEquals(1, found.size)
    assertEquals("n-1.0.jar", found[0].file.name)
  }

  @Test
  fun `resolve returns empty when version dir is missing`() {
    val root = File(tmp, "cache").apply { mkdirs() }
    fakeArtifact(root, "g", "n", "2.0", "shaAA", "n-2.0.jar")
    assertEquals(emptyList(), GradleTargetedResolver.resolve(listOf(root), "g", "n", "9.9"))
  }

  @Test
  fun `resolve returns empty without exception when group dir is missing`() {
    val root = File(tmp, "cache").apply { mkdirs() }
    fakeArtifact(root, "g", "n", "1.0", "shaAA", "n-1.0.jar")
    assertEquals(emptyList(), GradleTargetedResolver.resolve(listOf(root), "no-such-group", "n", "1.0"))
  }

  @Test
  fun `resolve collects every file inside a sha dir`() {
    val root = File(tmp, "cache").apply { mkdirs() }
    val dir = File(root, "g/n/1.0/shaAA").apply { mkdirs() }
    File(dir, "n-1.0.jar").writeText("jar")
    File(dir, "n-1.0.module").writeText("module")
    File(dir, "n-1.0.pom").writeText("pom")

    val found = GradleTargetedResolver.resolve(listOf(root), "g", "n", "1.0")
    assertEquals(setOf("n-1.0.jar", "n-1.0.module", "n-1.0.pom"), found.map { it.file.name }.toSet())
    assertTrue(found.all { it.sha1 == "shaAA" })
  }

  @Test
  fun `resolve never visits artifacts under other coordinates`() {
    val root = File(tmp, "cache").apply { mkdirs() }
    fakeArtifact(root, "g", "n", "1.0", "shaAA", "n-1.0.jar")
    val decoyDir = File(root, "evil/decoy/0.0.1/shaZZ").apply { mkdirs() }
    val decoyFile = File(decoyDir, "decoy-0.0.1.jar").apply { writeText("x".repeat(10_000)) }
    decoyFile.setLastModified(System.currentTimeMillis() + 60_000)

    val found = GradleTargetedResolver.resolve(listOf(root), "g", "n", "1.0")
    assertEquals(1, found.size)
    assertEquals("n-1.0.jar", found[0].file.name)
    assertTrue(found.none { it.file.name.contains("decoy") })
    val coordDir = File(root, "g/n/1.0").canonicalPath
    assertTrue(found.all { it.file.canonicalPath.startsWith(coordDir) })
  }
}
