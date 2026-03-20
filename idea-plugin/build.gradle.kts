import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
  id("java")
  id("org.jetbrains.intellij") version "1.17.4"
  kotlin("jvm") version "1.9.24"
}

group = "localgitmirror"
val baseVersion = (findProperty("pluginVersion") as String?) ?: "0.1.0"
val buildSuffix = (findProperty("buildSuffix") as String?)
  ?: LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
version = "$baseVersion-$buildSuffix"

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
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
