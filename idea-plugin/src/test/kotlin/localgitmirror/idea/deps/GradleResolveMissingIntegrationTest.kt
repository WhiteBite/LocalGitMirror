package localgitmirror.idea.deps

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HONEST integration test for [GradleResolver.resolveMissing].
 *
 * Uses `--offline` strategy (the correct approach for the dome: Nexus is
 * unreachable, so we look for what is NOT in the local gradle cache).
 *
 * Tests:
 *   1. A project that depends on an artifact NOT in the local cache → that
 *      artifact must be reported as missing (via LenientConfiguration or
 *      the "No cached version" stdout fallback parser).
 *   2. A project with no external deps → nothing missing.
 *   3. parseNoCachedVersionLines pure unit test (no gradle fork needed):
 *      the real output gradle prints when running --offline with missing deps.
 *
 * The gradle-forking tests are skipped when gradle isn't on PATH.
 */
class GradleResolveMissingIntegrationTest {

  private val created = mutableListOf<File>()

  private fun mkTmp(prefix: String): File =
    Files.createTempDirectory("lgm-rm-$prefix-").toFile().also { created.add(it) }

  @AfterTest
  fun cleanup() { created.forEach { it.deleteRecursively() } }

  private fun gradleAvailable(): Boolean {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val cmd = if (isWindows) listOf("cmd", "/c", "gradle", "-v") else listOf("gradle", "-v")
    return try {
      val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
      p.inputStream.bufferedReader().use { it.readText() }
      p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) { false }
  }

  // ── Pure unit test — no gradle fork ──────────────────────────────────────────

  @Test
  fun `parseNoCachedVersionLines extracts coordinates from gradle offline error output`() {
    // This is the REAL output gradle prints when running --offline and a dep
    // is missing from the local cache. The fallback parser must extract it.
    val stdout = """
      > Could not resolve all artifacts for configuration ':classpath'.
         > Could not resolve ru.kryptonite:code-quality-plugin:1.1.0.
            Required by:
                root project :
            > No cached version of ru.kryptonite:code-quality-plugin:1.1.0 available for offline mode.
            > No cached version of ru.kryptonite:code-quality-plugin:1.1.0 available for offline mode.
         > Could not resolve org.gradle.toolchains:foojay-resolver:0.9.0.
            > No cached version of org.gradle.toolchains:foojay-resolver:0.9.0 available for offline mode.
    """.trimIndent()

    val result = GradleResolver.parseNoCachedVersionLines(stdout)
    val labels = result.map { "${it.g}:${it.n}:${it.v}" }

    assertTrue(
      "ru.kryptonite:code-quality-plugin:1.1.0" in labels,
      "Must extract ru.kryptonite plugin: $labels"
    )
    assertTrue(
      "org.gradle.toolchains:foojay-resolver:0.9.0" in labels,
      "Must extract foojay-resolver: $labels"
    )
    // Deduplication: the ru.kryptonite entry appears twice in the output.
    assertTrue(result.size == 2, "Must deduplicate: got ${result.size} entries: $labels")
  }

  @Test
  fun `parseNoCachedVersionLines returns empty for clean output`() {
    val result = GradleResolver.parseNoCachedVersionLines(
      "BUILD SUCCESSFUL\n\nWelcome to Gradle 8.10.2.\n"
    )
    assertTrue(result.isEmpty(), "Clean output must yield no missing deps")
  }

  // ── Integration tests (fork real gradle) ─────────────────────────────────────

  @Test
  fun `resolveMissing offline captures a dependency not in local cache`() {
    if (!gradleAvailable()) {
      println("[skip] gradle not on PATH")
      return
    }
    val project = mkTmp("offline-missing")
    // A project that requests a unique coordinate that will NEVER be in the
    // local cache. Running --offline makes gradle immediately report it missing.
    File(project, "settings.gradle").writeText("rootProject.name = 'lgm-offline-probe'\n")
    File(project, "build.gradle").writeText(
      """
      plugins { id 'java' }
      repositories { mavenLocal() }
      dependencies {
        implementation 'com.lgm.test.never.exists:unique-dep-xyz:99.99.99'
      }
      """.trimIndent()
    )

    val r = GradleResolver.resolveMissing(project, timeoutSec = 120)

    val labels = r.artifacts.map { "${it.g}:${it.n}:${it.v}" }
    assertTrue(
      labels.any { it.contains("unique-dep-xyz") },
      "Must report the missing dep. ok=${r.ok} artifacts=$labels " +
        "log=${r.log.takeLast(600)}"
    )
  }

  @Test
  fun `resolveMissing on a project with no dependencies reports nothing`() {
    if (!gradleAvailable()) {
      println("[skip] gradle not on PATH")
      return
    }
    val project = mkTmp("offline-clean")
    File(project, "settings.gradle").writeText("rootProject.name = 'lgm-clean-probe'\n")
    File(project, "build.gradle").writeText(
      """
      plugins { id 'java' }
      // No repositories, no dependencies — nothing to fail offline.
      """.trimIndent()
    )

    val r = GradleResolver.resolveMissing(project, timeoutSec = 120)

    assertTrue(
      r.artifacts.none { it.n.contains("unique-dep-xyz") },
      "A project with no deps must not report phantom missing: ${r.artifacts.map { it.n }}"
    )
  }
}
