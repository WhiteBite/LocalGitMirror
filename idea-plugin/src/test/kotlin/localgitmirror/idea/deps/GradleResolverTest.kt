package localgitmirror.idea.deps

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the wrapper-detection and JSON-line parsing logic.
 *
 * We intentionally don't shell out to a real `gradle` here — that's both
 * slow (~30s per test, downloads gradle) and flaky in CI. The end-to-end
 * "resolve a real project" path is verified manually because it depends on
 * having gradle installed; a CI integration job is the right place for that.
 */
class GradleResolverTest {

  private fun mkTmp(prefix: String): File =
    Files.createTempDirectory("lgm-resolver-$prefix-").toFile()

  // ─────────────────────────────────────────────────────────────────────────
  // Wrapper auto-detection
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  fun `picks gradlew bat on windows when present`() {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    if (!isWindows) return  // skip on linux/mac
    val root = mkTmp("wrapper-bat")
    try {
      File(root, "gradlew.bat").writeText("@echo off")
      val cmd = GradleResolver.pickGradleCommand(root)
      assertEquals(1, cmd.size)
      assertTrue(cmd[0].endsWith("gradlew.bat"), "Expected gradlew.bat, got $cmd")
    } finally { root.deleteRecursively() }
  }

  @Test
  fun `falls back to system gradle when no wrapper present`() {
    val root = mkTmp("no-wrapper")
    try {
      val cmd = GradleResolver.pickGradleCommand(root)
      assertEquals(1, cmd.size)
      assertTrue(cmd[0] == "gradle" || cmd[0] == "gradle.bat",
        "Expected system gradle, got $cmd")
    } finally { root.deleteRecursively() }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // JAVA_HOME resolution — guards the IDE from a broken user env
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  fun `unwrapBinSuffix strips trailing bin from windows-style path`() {
    assertEquals("C:\\Users\\d.minkin\\jdk-25",
      GradleResolver.unwrapBinSuffix("C:\\Users\\d.minkin\\jdk-25\\bin"))
  }

  @Test
  fun `unwrapBinSuffix strips trailing bin from unix-style path`() {
    assertEquals("/opt/jdk-25", GradleResolver.unwrapBinSuffix("/opt/jdk-25/bin"))
  }

  @Test
  fun `unwrapBinSuffix leaves correct paths alone`() {
    assertEquals("C:\\Users\\jdk-25", GradleResolver.unwrapBinSuffix("C:\\Users\\jdk-25"))
    assertEquals("/opt/jdk-25", GradleResolver.unwrapBinSuffix("/opt/jdk-25"))
  }

  @Test
  fun `unwrapBinSuffix is case insensitive`() {
    assertEquals("C:\\jdk-25", GradleResolver.unwrapBinSuffix("C:\\jdk-25\\BIN"))
  }

  @Test
  fun `unwrapBinSuffix handles trailing slash`() {
    assertEquals("/opt/jdk-25", GradleResolver.unwrapBinSuffix("/opt/jdk-25/bin/"))
  }

  @Test
  fun `resolveJavaHome falls back to running JDK when explicit is null`() {
    val home = GradleResolver.resolveJavaHome(null)
    // The current process is running on SOME JDK, so it must be discoverable
    assertTrue(home != null && File(home).exists(),
      "Expected a working JAVA_HOME, got: $home")
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val javaBin = File(home, "bin/" + if (isWindows) "java.exe" else "java")
    assertTrue(javaBin.exists(), "java binary must exist at $javaBin")
  }

  @Test
  fun `resolveJavaHome strips broken bin suffix from explicit input`() {
    // Give it OUR running JDK with an evil "/bin" tail.
    val running = System.getProperty("java.home")
    val broken = "$running${File.separator}bin"   // simulating the user's env

    val home = GradleResolver.resolveJavaHome(broken)
    assertEquals(running, home,
      "Should have stripped the spurious /bin and returned the real JDK home")
  }

  @Test
  fun `resolveJavaHome returns null when path is not a real JDK`() {
    val home = GradleResolver.resolveJavaHome("/this/is/not/a/jdk/at/all")
    assertEquals(null, home)
  }

  @Test
  fun `resolveJavaHome ignores blank input and falls back to running JDK`() {
    val home = GradleResolver.resolveJavaHome("   ")
    assertTrue(home != null && File(home).exists())
  }

  // ─────────────────────────────────────────────────────────────────────────
  // parseNoCachedVersionLines — stdout fallback parser
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  fun `parseNoCachedVersionLines extracts real coordinates`() {
    val stdout = """
      > No cached version of ru.kryptonite.build:kryptonite-gradle-plugin:1.1.0 available for offline mode.
      > No cached version of org.springframework:spring-core:6.1.5 available for offline mode.
    """.trimIndent()
    val result = GradleResolver.parseNoCachedVersionLines(stdout)
    assertEquals(2, result.size)
    assertEquals("ru.kryptonite.build", result[0].g)
    assertEquals("kryptonite-gradle-plugin", result[0].n)
    assertEquals("1.1.0", result[0].v)
  }

  @Test
  fun `parseNoCachedVersionLines skips plugin marker artifacts`() {
    // Plugin markers (group.id.gradle.plugin) are POM-only redirects with no
    // jar. They must be filtered out — requesting them from work would find
    // nothing in files-2.1 because only the real implementation has a jar.
    // This test catches the bug where we requested
    //   ru.kryptonite:code-quality-plugin:1.1.0   (marker, no jar)
    // instead of
    //   ru.kryptonite.build:kryptonite-gradle-plugin:1.1.0  (real jar)
    val stdout = """
      > No cached version of ru.kryptonite.code-quality:ru.kryptonite.code-quality.gradle.plugin:1.1.0 available for offline mode.
      > No cached version of ru.kryptonite.build:kryptonite-gradle-plugin:1.1.0 available for offline mode.
    """.trimIndent()
    val result = GradleResolver.parseNoCachedVersionLines(stdout)
    // Marker must be skipped, real implementation must be kept
    assertEquals(1, result.size, "Marker artifact must be filtered out, got: $result")
    assertEquals("kryptonite-gradle-plugin", result[0].n)
  }

  @Test
  fun `parseNoCachedVersionLines deduplicates repeated entries`() {
    val stdout = """
      > No cached version of com.example:lib:1.0 available for offline mode.
      > No cached version of com.example:lib:1.0 available for offline mode.
    """.trimIndent()
    val result = GradleResolver.parseNoCachedVersionLines(stdout)
    assertEquals(1, result.size)
  }

  @Test
  fun `parseNoCachedVersionLines returns empty for unrelated output`() {
    val stdout = "BUILD FAILED\nCould not resolve something else entirely"
    val result = GradleResolver.parseNoCachedVersionLines(stdout)
    assertTrue(result.isEmpty())
  }

  @Test
  fun `resolve returns error when projectDir does not exist`() {
    val nope = File("/very/much/not/exist/lgm-test-resolver")
    val r = GradleResolver.resolve(nope)
    assertFalse(r.ok)
    assertTrue(r.artifacts.isEmpty())
    assertTrue(r.log.contains("not found"), "Log should explain why: ${r.log}")
  }

  @Test
  fun `resolve handles a non-gradle directory cleanly`() {
    val empty = mkTmp("empty-dir")
    try {
      // No build.gradle*, no wrapper. Should fail without throwing.
      val r = GradleResolver.resolve(empty, timeoutSec = 10)
      assertFalse(r.ok, "Empty dir cannot resolve: ${r.log.takeLast(500)}")
      assertTrue(r.artifacts.isEmpty())
    } finally { empty.deleteRecursively() }
  }
}
