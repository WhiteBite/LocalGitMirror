package localgitmirror.idea.deps

import localgitmirror.idea.startup.shouldNotifyPending
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests for action visibility logic and startup notification decisions.
 * No IntelliJ runtime required — all functions under test take plain parameters.
 *
 * Designed to catch real bugs:
 *   - inverted condition (enabled when it should be disabled, and vice versa)
 *   - off-by-one in cooldown check (< vs <=)
 *   - ignoring the configured flag
 *   - treating unknown cache (-1) incorrectly
 */
class DepsActionsVisibilityTest {

    // ── computeRespondEnabled ────────────────────────────────────────────────

    @Test
    fun `respond disabled when not configured regardless of pending count`() {
        assertFalse(computeRespondEnabled(configured = false, lastKnownPending = 0))
        assertFalse(computeRespondEnabled(configured = false, lastKnownPending = 1))
        assertFalse(computeRespondEnabled(configured = false, lastKnownPending = 5))
        assertFalse(computeRespondEnabled(configured = false, lastKnownPending = -1))
    }

    @Test
    fun `respond disabled when configured and known-empty (count = 0)`() {
        assertFalse(computeRespondEnabled(configured = true, lastKnownPending = 0))
    }

    @Test
    fun `respond enabled when configured and pending count is positive`() {
        assertTrue(computeRespondEnabled(configured = true, lastKnownPending = 1))
        assertTrue(computeRespondEnabled(configured = true, lastKnownPending = 99))
    }

    @Test
    fun `respond enabled when configured and cache is unknown (-1)`() {
        // Safe default: if we have never fetched, show the action so the user
        // can still trigger it manually. A bug here would be returning false.
        assertTrue(computeRespondEnabled(configured = true, lastKnownPending = -1))
    }

    @Test
    fun `respond count = 0 is strictly disabled, not just falsy`() {
        // Explicitly verify the boundary: 0 means "we know there's nothing"
        assertFalse(computeRespondEnabled(configured = true, lastKnownPending = 0))
    }

    // ── computeApplyEnabled (symmetric) ─────────────────────────────────────

    @Test
    fun `apply disabled when not configured`() {
        assertFalse(computeApplyEnabled(configured = false, lastKnownPending = 0))
        assertFalse(computeApplyEnabled(configured = false, lastKnownPending = 3))
        assertFalse(computeApplyEnabled(configured = false, lastKnownPending = -1))
    }

    @Test
    fun `apply disabled when configured and known-empty`() {
        assertFalse(computeApplyEnabled(configured = true, lastKnownPending = 0))
    }

    @Test
    fun `apply enabled when configured and responses available`() {
        assertTrue(computeApplyEnabled(configured = true, lastKnownPending = 1))
        assertTrue(computeApplyEnabled(configured = true, lastKnownPending = 42))
    }

    @Test
    fun `apply enabled when configured and cache unknown`() {
        assertTrue(computeApplyEnabled(configured = true, lastKnownPending = -1))
    }

    // ── shouldNotifyPending ──────────────────────────────────────────────────

    @Test
    fun `no notification when count is zero`() {
        // Even if cooldown has fully expired, count=0 must return false.
        assertFalse(shouldNotifyPending(count = 0, lastNotified = 0L, nowMillis = 999_999L, cooldownMs = 1000L))
        assertFalse(shouldNotifyPending(count = 0, lastNotified = 0L, nowMillis = 0L, cooldownMs = 0L))
    }

    @Test
    fun `no notification when count is negative`() {
        assertFalse(shouldNotifyPending(count = -1, lastNotified = 0L, nowMillis = 999_999L, cooldownMs = 1000L))
    }

    @Test
    fun `no notification when count positive but cooldown not yet expired`() {
        val lastNotified = 1_000_000L
        val cooldown = 60_000L                   // 60 s
        val now = lastNotified + cooldown - 1    // 1 ms before expiry
        assertFalse(shouldNotifyPending(count = 3, lastNotified = lastNotified, nowMillis = now, cooldownMs = cooldown))
    }

    @Test
    fun `notification fires when count positive and cooldown exactly expired`() {
        val lastNotified = 1_000_000L
        val cooldown = 60_000L
        val now = lastNotified + cooldown          // exactly at expiry boundary
        assertTrue(shouldNotifyPending(count = 1, lastNotified = lastNotified, nowMillis = now, cooldownMs = cooldown))
    }

    @Test
    fun `notification fires when count positive and cooldown long past`() {
        val lastNotified = 0L                      // never notified
        val cooldown = 120_000L
        val now = 999_999_999L
        assertTrue(shouldNotifyPending(count = 5, lastNotified = lastNotified, nowMillis = now, cooldownMs = cooldown))
    }

    @Test
    fun `notification fires on first run (lastNotified = 0, cooldownMs = 0)`() {
        // On startup the cooldown is 0 so we always notify if count > 0
        assertTrue(shouldNotifyPending(count = 1, lastNotified = 0L, nowMillis = 0L, cooldownMs = 0L))
    }

    @Test
    fun `cooldown boundary is inclusive at exactly expired`() {
        // If now - lastNotified == cooldownMs the notification SHOULD fire.
        // An off-by-one bug using strictly-greater instead of >= would break this.
        val lastNotified = 500L
        val cooldown = 100L
        val now = 600L   // exactly 100 ms elapsed
        assertTrue(shouldNotifyPending(count = 2, lastNotified = lastNotified, nowMillis = now, cooldownMs = cooldown))
    }

    @Test
    fun `cooldown boundary one ms before expiry must not fire`() {
        val lastNotified = 500L
        val cooldown = 100L
        val now = 599L   // 99 ms elapsed — not yet expired
        assertFalse(shouldNotifyPending(count = 2, lastNotified = lastNotified, nowMillis = now, cooldownMs = cooldown))
    }
}
