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
  // resolve() input validation — guards against accidental misuse
  // ─────────────────────────────────────────────────────────────────────────

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
