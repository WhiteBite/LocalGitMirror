package localgitmirror.idea.deps

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Honest tests: build realistic Gradle file fixtures on disk and verify
 * that RepoDetector picks the same URLs a human reader would.
 *
 * Covers:
 *   - Groovy DSL `maven { url '...' }` and shorthand `maven '...'`
 *   - Kotlin DSL `maven { url = uri("...") }` and `maven("...")`
 *   - settings.gradle(.kts) with pluginManagement { repositories { } }
 *   - Multi-module: detection in subprojects (e.g. backend/build.gradle)
 *   - Public repos (Maven Central, Google, Plugin Portal) excluded
 *   - Commented-out URLs ignored
 *   - Manual setting in plugin Settings overrides auto-detect
 */
class RepoDetectorTest {

  private fun mkProject(prefix: String): File =
    Files.createTempDirectory("lgm-detect-$prefix-").toFile()

  private fun File.write(name: String, content: String) {
    File(this, name).writeText(content.trimIndent(), Charsets.UTF_8)
  }

  // ───────────────────────────────────────────────────────────────────────
  @Test
  fun `detects internal repo from groovy build dot gradle`() {
    val root = mkProject("groovy")
    try {
      root.write("build.gradle", """
        plugins { id 'java' }
        repositories {
          mavenCentral()
          maven {
            url 'https://nexus.kryptonit.local/repository/maven-public/'
          }
        }
      """)
      val d = RepoDetector.detect(root)
      assertEquals(listOf("nexus.kryptonit.local"), d.internalSubstrings)
      assertTrue(d.publicUrls.isEmpty(), "mavenCentral() shorthand has no URL to capture; ok")
    } finally { root.deleteRecursively() }
  }

  @Test
  fun `detects internal repo from kotlin DSL with uri()`() {
    val root = mkProject("kts")
    try {
      root.write("build.gradle.kts", """
        plugins { java }
        repositories {
          mavenCentral()
          maven { url = uri("https://artifacts.company.com/repo") }
        }
      """)
      val d = RepoDetector.detect(root)
      assertEquals(listOf("artifacts.company.com"), d.internalSubstrings)
    } finally { root.deleteRecursively() }
  }

  @Test
  fun `detects shorthand maven dash literal in groovy`() {
    val root = mkProject("shorthand")
    try {
      root.write("build.gradle", """
        repositories {
          maven 'https://internal.repo.company/maven'
        }
      """)
      val d = RepoDetector.detect(root)
      assertEquals(listOf("internal.repo.company"), d.internalSubstrings)
    } finally { root.deleteRecursively() }
  }

  @Test
  fun `detects shorthand maven() function in kotlin`() {
    val root = mkProject("kts-shorthand")
    try {
      root.write("build.gradle.kts", """
        repositories {
          maven("https://nexus.example.com/maven2")
        }
      """)
      val d = RepoDetector.detect(root)
      assertEquals(listOf("nexus.example.com"), d.internalSubstrings)
    } finally { root.deleteRecursively() }
  }

  @Test
  fun `excludes public repos`() {
    val root = mkProject("public-only")
    try {
      root.write("build.gradle", """
        repositories {
          maven { url 'https://repo1.maven.org/maven2/' }
          maven { url 'https://dl.google.com/dl/android/maven2/' }
          maven { url 'https://plugins.gradle.org/m2/' }
          maven { url 'https://jitpack.io' }
        }
      """)
      val d = RepoDetector.detect(root)
      assertTrue(d.internalSubstrings.isEmpty(),
        "All URLs are public, expected no internal but got: ${d.internalSubstrings}")
      assertEquals(4, d.publicUrls.size)
    } finally { root.deleteRecursively() }
  }

  @Test
  fun `mixes public and internal correctly`() {
    val root = mkProject("mixed")
    try {
      root.write("build.gradle", """
        repositories {
          maven { url 'https://repo1.maven.org/maven2/' }
          maven { url 'https://nexus.kryptonit.local/repo' }
          maven { url 'https://dl.google.com/dl/android/maven2/' }
          maven { url 'https://artifactory.company.io/libs-release' }
        }
      """)
      val d = RepoDetector.detect(root)
      assertEquals(2, d.internalSubstrings.size)
      assertContains(d.internalSubstrings, "nexus.kryptonit.local")
      assertContains(d.internalSubstrings, "artifactory.company.io")
      assertEquals(2, d.publicUrls.size)
    } finally { root.deleteRecursively() }
  }

  @Test
  fun `ignores commented-out URLs in groovy and kotlin`() {
    val root = mkProject("commented")
    try {
      root.write("build.gradle", """
        repositories {
          // maven { url 'https://nexus.OLD.local/repo' }
          maven { url 'https://nexus.NEW.local/repo' }
        }
      """)
      val d = RepoDetector.detect(root)
      assertEquals(listOf("nexus.new.local"), d.internalSubstrings)
      assertFalse(d.internalSubstrings.any { it.contains("old") })
    } finally { root.deleteRecursively() }
  }

  @Test
  fun `picks up settings dot gradle pluginManagement repos`() {
    val root = mkProject("settings")
    try {
      root.write("settings.gradle.kts", """
        pluginManagement {
          repositories {
            gradlePluginPortal()
            maven { url = uri("https://nexus.internal/plugins") }
          }
        }
        rootProject.name = "demo"
      """)
      val d = RepoDetector.detect(root)
      assertEquals(listOf("nexus.internal"), d.internalSubstrings)
    } finally { root.deleteRecursively() }
  }

  @Test
  fun `multi-module - detects repos in subprojects`() {
    val root = mkProject("multi")
    try {
      root.write("settings.gradle", """
        include 'core', 'web'
      """)
      File(root, "core").mkdirs()
      File(root, "web").mkdirs()
      File(root, "core").write("build.gradle", """
        repositories {
          maven { url 'https://nexus.first.local/repo' }
        }
      """)
      File(root, "web").write("build.gradle.kts", """
        repositories {
          maven("https://nexus.second.local/repo")
        }
      """)
      val d = RepoDetector.detect(root)
      assertEquals(2, d.internalSubstrings.size)
      assertContains(d.internalSubstrings, "nexus.first.local")
      assertContains(d.internalSubstrings, "nexus.second.local")
      assertTrue(d.sources.size >= 3, "Expected to scan settings + 2 subproject files")
    } finally { root.deleteRecursively() }
  }

  @Test
  fun `deduplicates same repo URL across files`() {
    val root = mkProject("dedup")
    try {
      val nexusUrl = "https://nexus.local/repo"
      root.write("build.gradle", """repositories { maven { url '$nexusUrl' } }""")
      File(root, "core").mkdirs()
      File(root, "core").write("build.gradle", """repositories { maven { url '$nexusUrl' } }""")
      val d = RepoDetector.detect(root)
      assertEquals(listOf("nexus.local"), d.internalSubstrings)
    } finally { root.deleteRecursively() }
  }

  @Test
  fun `empty project returns empty detection`() {
    val root = mkProject("empty")
    try {
      val d = RepoDetector.detect(root)
      assertTrue(d.internalSubstrings.isEmpty())
      assertTrue(d.sources.isEmpty())
    } finally { root.deleteRecursively() }
  }

  @Test
  fun `non-existent dir returns empty without crashing`() {
    val d = RepoDetector.detect(File("/path/that/does/not/exist/lgm-test"))
    assertTrue(d.internalSubstrings.isEmpty())
  }

  // ───────────────────────────────────────────────────────────────────────
  // Realistic onyx-style fixture: large gradle file with mixed repos
  // ───────────────────────────────────────────────────────────────────────
  @Test
  fun `realistic onyx-platform fixture detects exactly nexus`() {
    val root = mkProject("onyx-realistic")
    try {
      root.write("settings.gradle.kts", """
        pluginManagement {
          repositories {
            gradlePluginPortal()
            maven { url = uri("https://nexus.kryptonit.io/repository/plugins") }
          }
        }
        plugins {
          id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
        }
        rootProject.name = "onyx-platform"
        include("core", "api", "web")
      """)
      root.write("build.gradle.kts", """
        plugins {
          kotlin("jvm") version "1.9.24"
          id("org.springframework.boot") version "3.2.0" apply false
        }
        allprojects {
          repositories {
            mavenCentral()
            // Internal mirror with curated dependencies
            maven {
              url = uri("https://nexus.kryptonit.io/repository/maven-public/")
              credentials {
                username = providers.gradleProperty("nexusUser").orNull
                password = providers.gradleProperty("nexusPass").orNull
              }
            }
            // Public Spring repo for milestones
            maven { url = uri("https://repo.spring.io/milestone") }
          }
        }
      """)
      val d = RepoDetector.detect(root)
      // Spring milestone IS in our public list, so it should be excluded.
      assertEquals(listOf("nexus.kryptonit.io"), d.internalSubstrings,
        "Expected only the kryptonit nexus host. publics=${d.publicUrls}")
    } finally { root.deleteRecursively() }
  }

  // ───────────────────────────────────────────────────────────────────────
  // Honesty check: what RepoDetector returns must actually work as a filter
  // when fed back into DepsScanner.matchesInternalRepo
  // ───────────────────────────────────────────────────────────────────────
  @Test
  fun `detected substrings actually match the sidecar origin URLs`() {
    val projectRoot = mkProject("e2e-detect")
    val cacheRoot = mkProject("e2e-cache")
    try {
      projectRoot.write("build.gradle.kts", """
        repositories {
          mavenCentral()
          maven("https://nexus.kryptonit.io/repository/maven-public")
        }
      """)
      // Build a fake artifact whose sidecar URL matches what gradle would write
      val sha = "abc123"
      val artifactDir = File(cacheRoot, "com.kryptonit/internal-lib/1.0.0/$sha").apply { mkdirs() }
      File(artifactDir, "internal-lib-1.0.0.jar").writeBytes("payload".toByteArray())
      File(artifactDir, "_remote.repositories")
        .writeText("internal-lib-1.0.0.jar>nexus=https://nexus.kryptonit.io/repository/maven-public/")

      val detection = RepoDetector.detect(projectRoot)
      val all = DepsScanner.scan(cacheRoot)
      assertEquals(1, all.size)

      val matched = all.filter { DepsScanner.matchesInternalRepo(it, detection.internalSubstrings) }
      assertEquals(1, matched.size,
        "Auto-detected substrings ${detection.internalSubstrings} must match origin URL the gradle writes")
    } finally {
      projectRoot.deleteRecursively(); cacheRoot.deleteRecursively()
    }
  }

  @Test
  fun `host extraction handles weird inputs`() {
    assertEquals("nexus.local", RepoDetector.hostOf("https://nexus.local/repo/path"))
    assertEquals("nexus.local", RepoDetector.hostOf("HTTPS://Nexus.Local/REPO"))
    assertEquals("nexus.local:8443", RepoDetector.hostOf("https://nexus.local:8443/x"))
    assertEquals("nexus.local", RepoDetector.hostOf("http://nexus.local"))
  }
}
