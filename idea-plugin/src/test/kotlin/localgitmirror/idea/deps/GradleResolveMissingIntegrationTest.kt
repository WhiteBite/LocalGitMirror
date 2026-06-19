package localgitmirror.idea.deps

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HONEST integration test for [GradleResolver.resolveMissing].
 *
 * This actually forks a real `gradle` against a tiny generated project and
 * checks that the init-script's `LenientConfiguration.unresolvedModuleDependencies`
 * path captures a dependency whose repository is unreachable — i.e. the exact
 * "corporate dep the dome can't fetch" scenario the whole feature exists for.
 *
 * It is skipped (not failed) when `gradle` isn't on PATH or has no network to
 * fetch its own distribution, so it never produces false CI failures — but on
 * a developer machine with gradle installed it exercises the real Groovy.
 */
class GradleResolveMissingIntegrationTest {

  private val created = mutableListOf<File>()

  private fun mkTmp(prefix: String): File =
    Files.createTempDirectory("lgm-rm-$prefix-").toFile().also { created.add(it) }

  @AfterTest
  fun cleanup() { created.forEach { it.deleteRecursively() } }

  /** True if a system `gradle` is callable. We only run the heavy test then. */
  private fun gradleAvailable(): Boolean {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val cmd = if (isWindows) listOf("cmd", "/c", "gradle", "-v") else listOf("gradle", "-v")
    return try {
      val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
      p.inputStream.bufferedReader().use { it.readText() }
      p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) { false }
  }

  @Test
  fun `resolveMissing captures a dependency from an unreachable repo`() {
    if (!gradleAvailable()) {
      println("[skip] gradle not on PATH — skipping resolveMissing integration test")
      return
    }

    val project = mkTmp("proj")
    // An empty LOCAL maven repo: resolution fails fast (artifact simply absent)
    // with no network wait, deterministically reproducing "dome can't fetch it".
    val emptyRepo = mkTmp("empty-repo")
    val repoUrl = emptyRepo.toURI().toString()
    File(project, "settings.gradle").writeText("rootProject.name = 'lgm-missing-probe'\n")
    File(project, "build.gradle").writeText(
      """
      plugins { id 'java' }
      repositories {
        // Only an empty local repo — the requested artifact is absent, so it
        // can never resolve. Same effect as a corporate dep on the dome.
        maven { url '$repoUrl' }
      }
      dependencies {
        implementation 'com.corp.internal:secret-lib:9.9.9'
      }
      """.trimIndent()
    )

    val r = GradleResolver.resolveMissing(project, timeoutSec = 240)

    // The unreachable dependency MUST be reported as missing.
    val labels = r.artifacts.map { "${it.g}:${it.n}:${it.v}" }
    assertTrue(
      labels.any { it == "com.corp.internal:secret-lib:9.9.9" },
      "Expected the unresolved corporate dep to be captured. ok=${r.ok} " +
        "artifacts=$labels log=${r.log.takeLast(800)}"
    )
  }

  @Test
  fun `resolveMissing on a fully-resolvable project reports nothing missing`() {
    if (!gradleAvailable()) {
      println("[skip] gradle not on PATH — skipping resolveMissing integration test")
      return
    }

    val project = mkTmp("proj-ok")
    // A project with NO external dependencies resolves cleanly → nothing missing.
    File(project, "settings.gradle").writeText("rootProject.name = 'lgm-clean-probe'\n")
    File(project, "build.gradle").writeText(
      """
      plugins { id 'java' }
      repositories { mavenCentral() }
      // no dependencies at all
      """.trimIndent()
    )

    val r = GradleResolver.resolveMissing(project, timeoutSec = 240)

    assertTrue(
      r.artifacts.none { it.n == "secret-lib" },
      "A clean project must not report phantom missing deps: ${r.artifacts.map { it.n }}"
    )
  }
}
