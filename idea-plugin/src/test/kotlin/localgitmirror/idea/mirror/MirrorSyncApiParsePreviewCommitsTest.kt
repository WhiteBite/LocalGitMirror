package localgitmirror.idea.mirror

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MirrorSyncApiParsePreviewCommitsTest {

  private fun parse(body: String): List<MirrorSyncApi.CommitInfo> =
    MirrorSyncApi.parsePreviewCommits(Json.parseToJsonElement(body).jsonObject)

  @Test
  fun `parses hash and message pairs`() {
    val commits = parse("""{"commits": [
      {"hash": "abc1234", "message": "fix thing"},
      {"hash": "def5678", "message": ""}
    ]}""")
    assertEquals(2, commits.size)
    assertEquals(MirrorSyncApi.CommitInfo("abc1234", "fix thing"), commits[0])
    assertEquals(MirrorSyncApi.CommitInfo("def5678", ""), commits[1])
  }

  @Test
  fun `message with json-hostile characters survives intact`() {
    val commits = parse("""{"commits": [{"hash": "abc", "message": "quoted \"x\" and \\ path"}]}""")
    assertEquals(1, commits.size)
    assertEquals("""quoted "x" and \ path""", commits[0].message)
  }

  @Test
  fun `missing commits field or non-array yields empty`() {
    assertTrue(parse("""{"diffstat": "1 file changed"}""").isEmpty())
    assertTrue(parse("""{"commits": "nope"}""").isEmpty())
    assertTrue(parse("""{}""").isEmpty())
  }

  @Test
  fun `entries without a hash are skipped`() {
    val commits = parse("""{"commits": [
      {"message": "no hash"},
      {"hash": "ok1", "message": "fine"}
    ]}""")
    assertEquals(1, commits.size)
    assertEquals("ok1", commits[0].hash)
  }
}
