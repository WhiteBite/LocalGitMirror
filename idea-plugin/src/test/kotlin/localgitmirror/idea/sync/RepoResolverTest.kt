package localgitmirror.idea.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import localgitmirror.idea.sync.v2.RepoResolver
import localgitmirror.idea.sync.v2.RepoSource

class RepoResolverTest {

  @Test
  fun `uses configured repo when valid`() {
    val r = RepoResolver.resolveByNames(
      projectName = "Onyx Platform",
      directoryName = "onyx-platform",
      configuredRepo = "my_CUSTOM repo"
    )

    assertEquals(RepoSource.SETTINGS, r.source)
    assertEquals("my_custom-repo", r.sanitized)
    assertNull(r.error)
  }

  @Test
  fun `fails when configured repo sanitizes to blank`() {
    val r = RepoResolver.resolveByNames(
      projectName = "Onyx Platform",
      directoryName = "onyx-platform",
      configuredRepo = "%%%%"
    )

    assertEquals(RepoSource.SETTINGS, r.source)
    assertEquals("", r.sanitized)
    assertNotNull(r.error)
  }

  @Test
  fun `falls back to project then directory then default`() {
    val fromProject = RepoResolver.resolveByNames(
      projectName = "Onyx Platform",
      directoryName = "workspace",
      configuredRepo = ""
    )
    assertEquals(RepoSource.PROJECT_NAME, fromProject.source)
    assertEquals("onyx-platform", fromProject.sanitized)

    val fromDir = RepoResolver.resolveByNames(
      projectName = "%%%%",
      directoryName = "ws_name",
      configuredRepo = ""
    )
    assertEquals(RepoSource.DIRECTORY_NAME, fromDir.source)
    assertEquals("ws_name", fromDir.sanitized)

    val fallback = RepoResolver.resolveByNames(
      projectName = "%%%%",
      directoryName = "%%%%",
      configuredRepo = ""
    )
    assertEquals(RepoSource.DEFAULT, fallback.source)
    assertEquals("default", fallback.sanitized)
  }
}
