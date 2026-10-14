package localgitmirror.idea.deps

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

class TargetedParentClosureTest {

  @TempDir
  lateinit var tmp: File

  private fun gradlePom(root: File, g: String, n: String, v: String, sha: String, parent: Triple<String, String, String>?): File {
    val dir = File(root, "$g/$n/$v/$sha").apply { mkdirs() }
    val block = parent?.let { (pg, pn, pv) ->
      "<parent><groupId>$pg</groupId><artifactId>$pn</artifactId><version>$pv</version></parent>"
    } ?: ""
    return File(dir, "$n-$v.pom").apply { writeText("<project>$block</project>") }
  }

  private fun gradleFile(root: File, g: String, n: String, v: String, sha: String, fileName: String): File {
    val dir = File(root, "$g/$n/$v/$sha").apply { mkdirs() }
    return File(dir, fileName).apply { writeText("bytes-of-$fileName") }
  }

  private fun entryOf(pom: File, g: String, n: String, v: String): DepFileEntry =
    DepFileEntry(
      DepCoordinate(GradleEcosystem.id, g, n, v),
      pom.absolutePath,
      MavenLocalScanner.mavenLocalRelativePath(g, n, v, pom.name),
      pom.length()
    )

  @Test
  fun `parent pom ships while its jar stays behind`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    val childPom = gradlePom(cache, "com.corp", "child", "1.0", "shaC", Triple("com.corp", "parent", "2.0"))
    val parentPom = gradlePom(cache, "com.corp", "parent", "2.0", "shaP", null)
    gradleFile(cache, "com.corp", "parent", "2.0", "shaJ", "parent-2.0.jar")

    val out = mutableListOf(entryOf(childPom, "com.corp", "child", "1.0"))
    val added = TargetedParentClosure.expand(out, listOf(cache))

    assertEquals(1, added)
    assertEquals(2, out.size)
    val rels = out.map { it.relativePath }
    assertTrue("com/corp/parent/2.0/parent-2.0.pom" in rels)
    assertTrue(rels.none { it.endsWith(".jar") })
    val parentEntry = out.last()
    assertEquals(DepCoordinate("gradle", "com.corp", "parent", "2.0"), parentEntry.coordinate)
    assertEquals(parentPom.absolutePath, parentEntry.absolutePath)
    assertEquals(parentPom.length(), parentEntry.size)
  }

  @Test
  fun `grandparent chain resolves to fixpoint`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    val childPom = gradlePom(cache, "com.corp", "child", "1.0", "shaC", Triple("com.corp", "parent", "2.0"))
    gradlePom(cache, "com.corp", "parent", "2.0", "shaP", Triple("com.corp", "grand", "3.0"))
    gradlePom(cache, "com.corp", "grand", "3.0", "shaG", null)

    val out = mutableListOf(entryOf(childPom, "com.corp", "child", "1.0"))
    val added = TargetedParentClosure.expand(out, listOf(cache))

    assertEquals(2, added)
    val rels = out.map { it.relativePath }.toSet()
    assertTrue("com/corp/parent/2.0/parent-2.0.pom" in rels)
    assertTrue("com/corp/grand/3.0/grand-3.0.pom" in rels)
  }

  @Test
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  fun `cycle A to B to A terminates with each pom shipped once`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    val pomA = gradlePom(cache, "com.corp", "aa", "1.0", "shaA", Triple("com.corp", "bb", "1.0"))
    gradlePom(cache, "com.corp", "bb", "1.0", "shaB", Triple("com.corp", "aa", "1.0"))

    val out = mutableListOf(entryOf(pomA, "com.corp", "aa", "1.0"))
    val added = TargetedParentClosure.expand(out, listOf(cache))

    assertEquals(1, added)
    assertEquals(2, out.size)
    assertEquals(1, out.count { it.relativePath == "com/corp/aa/1.0/aa-1.0.pom" })
    assertEquals(1, out.count { it.relativePath == "com/corp/bb/1.0/bb-1.0.pom" })
  }

  @Test
  fun `parent missing from every gradle root is skipped silently`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    val childPom = gradlePom(cache, "com.corp", "child", "1.0", "shaC", Triple("com.corp", "ghost", "9.9"))

    val out = mutableListOf(entryOf(childPom, "com.corp", "child", "1.0"))
    val added = TargetedParentClosure.expand(out, listOf(cache, File(tmp, "no-such-root")))

    assertEquals(0, added)
    assertEquals(1, out.size)
  }

  @Test
  fun `parent present only in maven local layout is not shipped`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    val childPom = gradlePom(cache, "com.corp", "child", "1.0", "shaC", Triple("com.corp", "parent", "2.0"))
    val m2 = File(tmp, "m2").apply { mkdirs() }
    val m2Dir = File(m2, MavenLocalScanner.mavenLocalRelativePath("com.corp", "parent", "2.0", "")).apply { mkdirs() }
    File(m2Dir, "parent-2.0.pom").writeText("<project/>")

    val out = mutableListOf(entryOf(childPom, "com.corp", "child", "1.0"))
    val added = TargetedParentClosure.expand(out, listOf(cache))

    assertEquals(0, added)
    assertEquals(1, out.size)
  }

  @Test
  fun `already shipped parent pom is not duplicated when reached as a parent`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    val childPom = gradlePom(cache, "com.corp", "child", "1.0", "shaC", Triple("com.corp", "parent", "2.0"))
    val parentPom = gradlePom(cache, "com.corp", "parent", "2.0", "shaP", null)

    val out = mutableListOf(
      entryOf(childPom, "com.corp", "child", "1.0"),
      entryOf(parentPom, "com.corp", "parent", "2.0")
    )
    val added = TargetedParentClosure.expand(out, listOf(cache))

    assertEquals(0, added)
    assertEquals(2, out.size)
    assertEquals(1, out.count { it.relativePath == "com/corp/parent/2.0/parent-2.0.pom" })
  }

  @Test
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  fun `mutual parent cycle over shipped poms adds nothing and terminates`() {
    val cache = File(tmp, "cache").apply { mkdirs() }
    val pomA = gradlePom(cache, "com.corp", "aa", "1.0", "shaA", Triple("com.corp", "bb", "1.0"))
    val pomB = gradlePom(cache, "com.corp", "bb", "1.0", "shaB", Triple("com.corp", "aa", "1.0"))

    val out = mutableListOf(
      entryOf(pomA, "com.corp", "aa", "1.0"),
      entryOf(pomB, "com.corp", "bb", "1.0")
    )
    val added = TargetedParentClosure.expand(out, listOf(cache))

    assertEquals(0, added)
    assertEquals(2, out.size)
  }
}
