plugins {
  id("java")
  id("org.jetbrains.intellij") version "1.17.4"
  kotlin("jvm") version "1.9.24"
  id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

group = "localgitmirror"

// Version resolution (in priority order):
//   1. -PbuildNumber=<N>  gradle property (passed by CI: github.run_number)
//   2. BUILD_NUMBER env var
//   3. local .build_number file (dev machines, auto-incremented on buildPlugin)
// This avoids the "always 0.1.0" bug when .build_number is gitignored and
// therefore absent in CI checkouts.
val buildNumberFile = file(".build_number")
val ciBuildNumber: Int? =
  (project.findProperty("buildNumber") as String?)?.toIntOrNull()
    ?: System.getenv("BUILD_NUMBER")?.toIntOrNull()
val localBuildNumber: Int = if (buildNumberFile.exists()) {
  buildNumberFile.readText().trim().toIntOrNull() ?: 0
} else 0
// CI: version = 0.<run_number>.0 (deterministic, unique per run)
// Local: version = 0.<localBuildNumber + 1>.0
version = if (ciBuildNumber != null) {
  "0.${ciBuildNumber}.0"
} else {
  "0.${localBuildNumber + 1}.0"
}

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
      // Only bump the local counter for dev builds (not CI, which uses run_number)
      if (ciBuildNumber == null) {
        val f = file(".build_number")
        val cur = if (f.exists()) f.readText().trim().toIntOrNull() ?: 0 else 0
        f.writeText((cur + 1).toString())
        println("==> Built plugin version: $version  (next dev build will be 0.${cur + 2}.0)")
      } else {
        println("==> Built plugin version: $version  (CI build #$ciBuildNumber)")
      }
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
