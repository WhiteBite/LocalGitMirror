package localgitmirror.idea.deps

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class MavenLocalTargetedTest {

  @TempDir
  lateinit var tmp: File

  private fun coordDir(group: String, name: String, version: String): File =
    File(tmp, MavenLocalScanner.mavenLocalRelativePath(group, name, version, "")).apply { mkdirs() }

  private fun independentSha1(file: File): String {
    val md = MessageDigest.getInstance("SHA-1")
    md.update(file.readBytes())
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  @Test
  fun `resolve returns jar pom and sources with sidecar or computed sha1`() {
    val dir = coordDir("com.example", "demo", "1.0")
    val jar = File(dir, "demo-1.0.jar").apply { writeText("jar-bytes") }
    val pom = File(dir, "demo-1.0.pom").apply { writeText("pom-bytes") }
    val sources = File(dir, "demo-1.0-sources.jar").apply { writeText("sources-bytes") }
    val sidecarSha = "a".repeat(40)
    File(dir, "demo-1.0.jar.sha1").writeText(sidecarSha.uppercase() + "\n")

    val found = MavenLocalTargeted.resolve(tmp, "com.example", "demo", "1.0")
    assertEquals(3, found.size)
    val byName = found.associateBy { it.fileName }
    assertEquals(sidecarSha, byName.getValue("demo-1.0.jar").sha1)
    assertEquals(independentSha1(pom), byName.getValue("demo-1.0.pom").sha1)
    assertEquals(independentSha1(sources), byName.getValue("demo-1.0-sources.jar").sha1)
    assertEquals(jar.absolutePath, byName.getValue("demo-1.0.jar").file.absolutePath)
  }

  @Test
  fun `invalid sidecar falls back to computed sha1`() {
    val dir = coordDir("com.example", "demo", "1.0")
    val jar = File(dir, "demo-1.0.jar").apply { writeText("jar-bytes") }
    File(dir, "demo-1.0.jar.sha1").writeText("z".repeat(39))
    val pom = File(dir, "demo-1.0.pom").apply { writeText("pom-bytes") }
    File(dir, "demo-1.0.pom.sha1").writeText("g".repeat(40))

    val found = MavenLocalTargeted.resolve(tmp, "com.example", "demo", "1.0")
    val byName = found.associateBy { it.fileName }
    assertEquals(independentSha1(jar), byName.getValue("demo-1.0.jar").sha1)
    assertEquals(independentSha1(pom), byName.getValue("demo-1.0.pom").sha1)
  }

  @Test
  fun `resolver bookkeeping files and directories are skipped`() {
    val dir = coordDir("com.example", "demo", "1.0")
    File(dir, "demo-1.0.jar").writeText("jar")
    File(dir, "demo-1.0.jar.lastUpdated").writeText("https://x/central")
    File(dir, "demo-1.0.pom.md5").writeText("0".repeat(32))
    File(dir, "maven-metadata-local.xml").writeText("<metadata/>")
    File(dir, "_remote.repositories").writeText("demo-1.0.jar>central=")
    File(dir, "demo-1.0-nested").mkdirs()

    val found = MavenLocalTargeted.resolve(tmp, "com.example", "demo", "1.0")
    assertEquals(listOf("demo-1.0.jar"), found.map { it.fileName })
  }

  @Test
  fun `files of another version in the same dir are excluded`() {
    val dir = coordDir("com.example", "demo", "1.0")
    File(dir, "demo-1.0.jar").writeText("jar")
    File(dir, "demo-2.0.jar").writeText("other")

    val found = MavenLocalTargeted.resolve(tmp, "com.example", "demo", "1.0")
    assertEquals(listOf("demo-1.0.jar"), found.map { it.fileName })
  }

  @Test
  fun `missing coordinate dir returns empty without exception`() {
    assertEquals(emptyList(), MavenLocalTargeted.resolve(tmp, "com.example", "nope", "9.9"))
    assertEquals(emptyList(), MavenLocalTargeted.resolve(File(tmp, "no-such-root"), "com.example", "demo", "1.0"))
  }

  @Test
  fun `classifier and extension are parsed from the remainder`() {
    val sources = MavenLocalTargeted.parseCandidateFileName("demo", "1.0", "demo-1.0-sources.jar")
    assertEquals("sources", sources?.classifier)
    assertEquals("jar", sources?.ext)
    val plain = MavenLocalTargeted.parseCandidateFileName("demo", "1.0", "demo-1.0.jar")
    assertEquals("", plain?.classifier)
    assertEquals("jar", plain?.ext)
    assertNull(MavenLocalTargeted.parseCandidateFileName("demo", "1.0", "other-1.0.jar"))
  }

  @Test
  fun `artifacts under other groups are never returned`() {
    val dir = coordDir("com.example", "demo", "1.0")
    File(dir, "demo-1.0.jar").writeText("jar")
    val decoyDir = coordDir("org.evil", "demo", "1.0")
    File(decoyDir, "demo-1.0.jar").writeText("decoy".repeat(10_000))

    val found = MavenLocalTargeted.resolve(tmp, "com.example", "demo", "1.0")
    assertEquals(1, found.size)
    val coordPath = dir.canonicalPath
    assertTrue(found.all { it.file.canonicalPath.startsWith(coordPath) })
    assertTrue(found.none { it.file.canonicalPath.replace('\\', '/').contains("org/evil") })
  }
}
