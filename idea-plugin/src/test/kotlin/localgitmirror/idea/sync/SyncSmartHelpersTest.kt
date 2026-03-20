package localgitmirror.idea.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import localgitmirror.idea.sync.v2.SyncEngine

class SyncSmartHelpersTest {
  private val engine = SyncEngine()

  @Test
  fun `parseKnownCommitHashes extracts hashes from json`() {
    val body = """{"repo":"x","known":["abc1234","DEF5678900abcd"]}"""
    val known = engine.parseKnownCommitHashes(body)
    assertEquals(setOf("abc1234", "def5678900abcd"), known)
  }

  @Test
  fun `pickBestKnownBase returns first known non-head candidate`() {
    val head = "9999999"
    val candidates = listOf("9999999", "aaaaaaa", "bbbbbbb")
    val known = setOf("bbbbbbb", "aaaaaaa")
    val picked = engine.pickBestKnownBase(head, candidates, known)
    assertEquals("aaaaaaa", picked)
  }

  @Test
  fun `pickBestKnownBase returns null when no known candidates`() {
    val picked = engine.pickBestKnownBase(
      head = "9999999",
      candidates = listOf("9999999", "aaaaaaa"),
      known = emptySet()
    )
    assertNull(picked)
  }
}
