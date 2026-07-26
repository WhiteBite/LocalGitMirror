package localgitmirror.idea.sync

import localgitmirror.idea.sync.v2.SyncConstants
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for sync negotiation tuning parameters.
 * 
 * These tests verify that SyncConstants values are reasonable and
 * that negotiation logic uses them correctly.
 */
class SyncNegotiationTest {

  @Test
  fun `constants are positive and reasonable`() {
    assertTrue("RECENT_COMMITS_DEPTH should be > 0", SyncConstants.RECENT_COMMITS_DEPTH > 0)
    assertTrue("ADDITIONAL_BRANCH_DEPTH should be > 0", SyncConstants.ADDITIONAL_BRANCH_DEPTH > 0)
    assertTrue("PULL_HAVES_DEPTH should be > 0", SyncConstants.PULL_HAVES_DEPTH > 0)
    assertTrue("PULL_HAVES_LIMIT should be > 0", SyncConstants.PULL_HAVES_LIMIT > 0)
    assertTrue("HAS_COMMITS_CANDIDATE_LIMIT should be > 0", SyncConstants.HAS_COMMITS_CANDIDATE_LIMIT > 0)
  }

  @Test
  fun `pull haves limit is greater than depth`() {
    // PULL_HAVES_LIMIT caps the total haves (tips + recent commits)
    // so it should be >= PULL_HAVES_DEPTH to avoid truncating useful data
    assertTrue(
      "PULL_HAVES_LIMIT (${SyncConstants.PULL_HAVES_LIMIT}) should be >= PULL_HAVES_DEPTH (${SyncConstants.PULL_HAVES_DEPTH})",
      SyncConstants.PULL_HAVES_LIMIT >= SyncConstants.PULL_HAVES_DEPTH
    )
  }

  @Test
  fun `has commits candidate limit prevents payload explosion`() {
    // 300 candidates * 40 chars/hash = 12KB payload, reasonable for HTTP
    assertTrue(
      "HAS_COMMITS_CANDIDATE_LIMIT should be <= 500 to prevent payload explosion",
      SyncConstants.HAS_COMMITS_CANDIDATE_LIMIT <= 500
    )
  }

  @Test
  fun `recent commits depth covers typical development cycle`() {
    // 200 commits ≈ 2 weeks of active development (10-15 commits/day)
    assertTrue(
      "RECENT_COMMITS_DEPTH (${SyncConstants.RECENT_COMMITS_DEPTH}) should be >= 100 to cover typical dev cycle",
      SyncConstants.RECENT_COMMITS_DEPTH >= 100
    )
    assertTrue(
      "RECENT_COMMITS_DEPTH (${SyncConstants.RECENT_COMMITS_DEPTH}) should be <= 500 to avoid memory issues",
      SyncConstants.RECENT_COMMITS_DEPTH <= 500
    )
  }

  @Test
  fun `additional branch depth matches recent commits depth`() {
    // Both should use same depth for consistency
    assertEquals(
      "ADDITIONAL_BRANCH_DEPTH should match RECENT_COMMITS_DEPTH for consistency",
      SyncConstants.RECENT_COMMITS_DEPTH,
      SyncConstants.ADDITIONAL_BRANCH_DEPTH
    )
  }
}
