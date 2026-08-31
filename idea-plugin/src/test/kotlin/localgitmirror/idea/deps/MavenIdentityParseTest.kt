package localgitmirror.idea.deps

import kotlin.test.Test
import kotlin.test.assertEquals

class MavenIdentityParseTest {

  @Test
  fun `simple version without classifier`() {
    val id = parseMavenIdentity("foo-1.0.jar")
    assertEquals("foo", id.artifact)
    assertEquals("1.0", id.version)
    assertEquals("", id.classifier)
    assertEquals("jar", id.extension)
  }

  @Test
  fun `version with classifier`() {
    val id = parseMavenIdentity("foo-1.0-linux.jar")
    assertEquals("foo", id.artifact)
    assertEquals("1.0", id.version)
    assertEquals("linux", id.classifier)
    assertEquals("jar", id.extension)
  }

  @Test
  fun `multi-segment version with beta qualifier`() {
    val id = parseMavenIdentity("foo-1.0.0-beta-linux.jar")
    assertEquals("foo", id.artifact)
    assertEquals("1.0.0-beta", id.version)
    assertEquals("linux", id.classifier)
    assertEquals("jar", id.extension)
  }

  @Test
  fun `SNAPSHOT version without classifier`() {
    val id = parseMavenIdentity("foo-2.0-SNAPSHOT.pom")
    assertEquals("foo", id.artifact)
    assertEquals("2.0-SNAPSHOT", id.version)
    assertEquals("", id.classifier)
    assertEquals("pom", id.extension)
  }

  @Test
  fun `rc1 qualifier with classifier`() {
    val id = parseMavenIdentity("my-artifact-3.1.4-rc1-linux.jar")
    assertEquals("my-artifact", id.artifact)
    assertEquals("3.1.4-rc1", id.version)
    assertEquals("linux", id.classifier)
    assertEquals("jar", id.extension)
  }

  @Test
  fun `simple version in pom`() {
    val id = parseMavenIdentity("foo-1.0.pom")
    assertEquals("foo", id.artifact)
    assertEquals("1.0", id.version)
    assertEquals("", id.classifier)
    assertEquals("pom", id.extension)
  }

  @Test
  fun `no version returns empty`() {
    val id = parseMavenIdentity("foo.jar")
    assertEquals("", id.artifact)
    assertEquals("", id.version)
    assertEquals("", id.classifier)
    assertEquals("jar", id.extension)
  }

  @Test
  fun `no extension`() {
    val id = parseMavenIdentity("foo-1.0")
    assertEquals("foo", id.artifact)
    assertEquals("1.0", id.version)
    assertEquals("", id.classifier)
    assertEquals("", id.extension)
  }
}