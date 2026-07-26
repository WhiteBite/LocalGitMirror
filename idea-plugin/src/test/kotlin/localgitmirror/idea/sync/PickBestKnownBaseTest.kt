package localgitmirror.idea.sync

import localgitmirror.idea.sync.v2.SyncEngine
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for pickBestKnownBase logic used in sync negotiation.
 * 
 * This function selects the best common ancestor from a list of candidates
 * that the server is known to have. The goal is to minimize bundle size
 * by excluding commits the server already has.
 */
class PickBestKnownBaseTest {

  private val engine = SyncEngine()

  @Test
  fun `returns null when candidates is empty`() {
    val result = engine.pickBestKnownBase(
      head = "abc123",
      candidates = emptyList(),
      known = setOf("abc123")
    )
    assertNull(result)
  }

  @Test
  fun `returns null when known is empty`() {
    val result = engine.pickBestKnownBase(
      head = "abc123",
      candidates = listOf("def456", "ghi789"),
      known = emptySet()
    )
    assertNull(result)
  }

  @Test
  fun `skips head itself`() {
    // If HEAD is in candidates, it should be skipped (we want ancestors, not HEAD)
    val result = engine.pickBestKnownBase(
      head = "abc123",
      candidates = listOf("abc123", "def456"),
      known = setOf("abc123", "def456")
    )
    assertEquals("def456", result)
  }

  @Test
  fun `returns first known candidate that is not head`() {
    val result = engine.pickBestKnownBase(
      head = "abc123",
      candidates = listOf("abc123", "def456", "ghi789"),
      known = setOf("def456", "ghi789")
    )
    assertEquals("def456", result)
  }

  @Test
  fun `case insensitive matching`() {
    val result = engine.pickBestKnownBase(
      head = "ABC123",
      candidates = listOf("abc123", "DEF456"),
      known = setOf("def456")
    )
    assertEquals("DEF456", result)
  }

  @Test
  fun `skips invalid hashes`() {
    val result = engine.pickBestKnownBase(
      head = "abc123",
      candidates = listOf("invalid", "def456", "too-short"),
      known = setOf("def456")
    )
    assertEquals("def456", result)
  }

  @Test
  fun `skips blank candidates`() {
    val result = engine.pickBestKnownBase(
      head = "abc123",
      candidates = listOf("", "  ", "def456"),
      known = setOf("def456")
    )
    assertEquals("def456", result)
  }

  @Test
  fun `returns null when no candidates are known`() {
    val result = engine.pickBestKnownBase(
      head = "abc123",
      candidates = listOf("def456", "ghi789"),
      known = setOf("xyz999")
    )
    assertNull(result)
  }

  @Test
  fun `accepts short hashes (7 chars)`() {
    val result = engine.pickBestKnownBase(
      head = "abc1234",
      candidates = listOf("def5678"),
      known = setOf("def5678")
    )
    assertEquals("def5678", result)
  }

  @Test
  fun `accepts full hashes (40 chars)`() {
    val fullHash = "a".repeat(40)
    val result = engine.pickBestKnownBase(
      head = "b".repeat(40),
      candidates = listOf(fullHash),
      known = setOf(fullHash)
    )
    assertEquals(fullHash, result)
  }
}
