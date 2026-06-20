plugins {
  id("java")
  id("org.jetbrains.intellij") version "1.17.4"
  kotlin("jvm") version "1.9.24"
  id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

group = "localgitmirror"

// ── Version: derived from git commit count — single source of truth ──────────
// version = 0.<number-of-commits>.0
//
// Why this is the right approach:
//   * AUTO-INCREMENT: every commit bumps the count, so every push gets a new
//     version with zero manual work and no counter file to maintain.
//   * IDENTICAL EVERYWHERE: commit count is a property of the git history
//     itself — the SAME commit yields the SAME version on the dome laptop, the
//     work laptop, and in CI. No more "local 0.50 vs release 0.31" drift.
//   * Falls back to a VERSION file, then 0.0.0, when git history isn't available
//     (e.g. building from a source zip without .git, or a shallow CI checkout).
//
// CI note: the checkout must use fetch-depth: 0 so the full history is present;
// otherwise `git rev-list --count` only sees the shallow clone.
fun gitCommitCount(): Int? = try {
  val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
    .directory(rootDir)
    .redirectErrorStream(true)
    .start()
  val out = proc.inputStream.bufferedReader().readText().trim()
  if (proc.waitFor() == 0) out.toIntOrNull() else null
} catch (_: Exception) { null }

val resolvedVersion: String = run {
  val count = gitCommitCount()
  when {
    count != null -> "0.$count.0"
    file("VERSION").exists() -> file("VERSION").readText().trim()
    else -> "0.0.0"
  }
}
version = resolvedVersion

repositories {
  mavenCentral()
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

kotlin {
  jvmToolchain(17)
}

intellij {
  version.set("2024.1")
  type.set("IC")
  plugins.set(listOf())
}

tasks {
  runIde {
    jvmArgs("-Didea.is.internal=true")
  }

  // Searchable options index is nice-to-have but is very heavy in CI/low-memory
  // environments and can OOM. It is not required for installing plugin from ZIP.
  named("buildSearchableOptions") {
    enabled = false
  }
  named("jarSearchableOptions") {
    enabled = false
  }

  named("buildPlugin") {
    doLast {
      println("==> Built plugin version: $version  (derived from git commit count)")
    }
  }

  patchPluginXml {
    version.set(project.version.toString())
    sinceBuild.set("241")
    untilBuild.set("263.*")
  }

  signPlugin {
    // empty: local dev only
  }

  publishPlugin {
    // empty: local dev only
  }

  test {
    useJUnitPlatform()
  }
}

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
  implementation("org.jmdns:jmdns:3.5.9")
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
  detekt(project(":detekt-rules"))
  detekt("io.gitlab.arturbosch.detekt:detekt-cli:1.23.7")
}

detekt {
  config.setFrom(file("detekt.yml"))
  buildUponDefaultConfig = true
  allRules = false
}
