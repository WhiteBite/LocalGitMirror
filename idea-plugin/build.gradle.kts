plugins {
  id("java")
  id("org.jetbrains.intellij") version "1.17.4"
  kotlin("jvm") version "1.9.24"
}

group = "localgitmirror"

// Auto-incrementing version: reads .build_number, bumps only on buildPlugin
val buildNumberFile = file(".build_number")
val buildNumber: Int = if (buildNumberFile.exists()) {
  buildNumberFile.readText().trim().toIntOrNull() ?: 0
} else 0
version = "0.${buildNumber + 1}.0"

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
      val f = file(".build_number")
      val cur = if (f.exists()) f.readText().trim().toIntOrNull() ?: 0 else 0
      f.writeText((cur + 1).toString())
      println("==> Built plugin version: $version  (next build will be 0.${cur + 2}.0)")
    }
  }

  patchPluginXml {
    sinceBuild.set("241")
    untilBuild.set("253.*")
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
}
