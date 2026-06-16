plugins {
  kotlin("jvm") version "1.9.24"
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

dependencies {
  compileOnly("io.gitlab.arturbosch.detekt:detekt-api:1.23.7")
  testImplementation("io.gitlab.arturbosch.detekt:detekt-api:1.23.7")
  testImplementation("io.gitlab.arturbosch.detekt:detekt-test:1.23.7")
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
  useJUnitPlatform()
}
