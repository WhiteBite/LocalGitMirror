package localgitmirror.idea.gitlab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitLabApiParseProjectNameTest {

  @Test
  fun `extracts the project name from a project payload`() {
    assertEquals(
      "Onyx Platform",
      GitLabApi.parseProjectName("""{"id": 42, "name": "Onyx Platform", "path": "onyx-platform"}""")
    )
  }

  @Test
  fun `name with escaped characters is decoded`() {
    assertEquals(
      "a \"quoted\" name",
      GitLabApi.parseProjectName("""{"name": "a \"quoted\" name"}""")
    )
  }

  @Test
  fun `missing name or non-object body yields null`() {
    assertNull(GitLabApi.parseProjectName("""{"id": 42}"""))
    assertNull(GitLabApi.parseProjectName("[]"))
    assertNull(GitLabApi.parseProjectName("not json"))
  }
}
