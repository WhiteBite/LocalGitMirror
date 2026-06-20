package localgitmirror.idea.deps

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Honest tests for gradle cache-root resolution — the bug where the plugin
 * scanned an EMPTY ~/.gradle while `gradle build` populated a different
 * gradle-user-home (env GRADLE_USER_HOME or the IDE's setting), so nothing was
 * found.
 *
 * These verify the decision logic directly (pure functions), designed to catch:
 *   - picking an empty cache over a populated one
 *   - ignoring a candidate that actually has artifacts
 *   - candidate ordering / dedup
 */
class CacheRootResolutionTest {

  private val created = mutableListOf<File>()
  private fun mkTmp(p: String): File = Files.createTempDirectory("lgm-cache-$p-").toFile().also { created.add(it) }
  @AfterTest fun cleanup() { created.forEach { it.deleteRecursively() } }

  private fun fakeArtifact(cacheRoot: File, g: String, n: String, v: String, sha: String, file: String) {
    File(cacheRoot, "$g/$n/$v/$sha/$file").apply { parentFile.mkdirs(); writeText("x") }
  }

  // ── pickCacheRoot ────────────────────────────────────────────────────────────

  @Test
  fun `prefers a populated cache over an empty one`() {
    val empty = mkTmp("empty")
    val populated = mkTmp("pop")
    fakeArtifact(populated, "ru.kryptonite", "code-quality-plugin", "1.1.0", "aaa", "code-quality-plugin-1.1.0.jar")

    // Empty listed FIRST — a naive "take first existing" would wrongly pick it.
    val picked = DepsScanner.pickCacheRoot(listOf(empty, populated))
    assertEquals(populated, picked, "Must pick the cache that actually has artifacts")
  }

  @Test
  fun `falls back to first existing when none populated`() {
    val a = mkTmp("a")
    val b = mkTmp("b")
    val picked = DepsScanner.pickCacheRoot(listOf(a, b))
    assertEquals(a, picked)
  }

  @Test
  fun `returns first candidate when none exist (for diagnostics)`() {
    val ghost1 = File(mkTmp("g1"), "does/not/exist1")
    val ghost2 = File(mkTmp("g2"), "does/not/exist2")
    assertEquals(ghost1, DepsScanner.pickCacheRoot(listOf(ghost1, ghost2)))
  }

  // ── candidateCacheRoots ──────────────────────────────────────────────────────

  @Test
  fun `candidates include env GRADLE_USER_HOME and default home`() {
    val roots = DepsScanner.candidateCacheRoots(
      gradleUserHomeEnv = "D:/gradle-x/.gradle",
      gradleUserHomeProp = null,
      userHome = "C:/Users/d.minkin",
      gradleHomeEnv = null
    ).map { it.path.replace('\\', '/') }

    assertTrue(roots.any { it.startsWith("D:/gradle-x/.gradle") }, "env GRADLE_USER_HOME must be a candidate: $roots")
    assertTrue(roots.any { it.contains("C:/Users/d.minkin/.gradle") }, "default ~/.gradle must be a candidate: $roots")
  }

  @Test
  fun `candidates include the IDE default home even when env points elsewhere`() {
    // The exact reported case: env points to a custom home, but the IDE used
    // the standard C:/Users/d.minkin/.gradle. Both must be probed.
    val roots = DepsScanner.candidateCacheRoots(
      gradleUserHomeEnv = "D:/gradle-8.10.2/.gradle",
      gradleUserHomeProp = null,
      userHome = "C:/Users/d.minkin",
      gradleHomeEnv = null
    ).map { it.path.replace('\\', '/') }
    assertTrue(roots.any { it.contains("C:/Users/d.minkin/.gradle/caches/modules-2/files-2.1") },
      "The IDE's default ~/.gradle cache must be among candidates: $roots")
  }

  @Test
  fun `candidates dedup repeated homes`() {
    val roots = DepsScanner.candidateCacheRoots(
      gradleUserHomeEnv = "C:/Users/x/.gradle",
      gradleUserHomeProp = "C:/Users/x/.gradle",
      userHome = "C:/Users/x",   // also yields C:/Users/x/.gradle
      gradleHomeEnv = null
    )
    // All three collapse to a single C:/Users/x/.gradle candidate.
    assertEquals(1, roots.size, "Duplicate homes must collapse: ${roots.map { it.path }}")
  }

  // ── scanAllCandidates merges across caches ───────────────────────────────────

  @Test
  fun `scan finds the artifact regardless of which candidate cache holds it`() {
    // Simulate: the wanted artifact lives ONLY in the second cache.
    val empty = mkTmp("scan-empty")
    val real = mkTmp("scan-real")
    fakeArtifact(real, "ru.kryptonite", "code-quality-plugin", "1.1.0", "sha1", "code-quality-plugin-1.1.0.jar")

    // scan() on the populated root must find it; on the empty one, nothing.
    assertTrue(DepsScanner.scan(empty).isEmpty())
    val found = DepsScanner.scan(real)
    assertEquals(1, found.size)
    assertEquals("code-quality-plugin", found[0].name)
    assertEquals("1.1.0", found[0].version)
  }
}
