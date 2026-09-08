package localgitmirror.idea.deps

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [DepsPollScheduler] with injectable clock and random.
 *
 * Verifies:
 *  - Idle interval = base with +/-30% jitter
 *  - Fast window: 30-60s for 15 minutes after recordLocalEvent()
 *  - Exponential backoff: double after K=4 empty polls, cap at 4x
 *  - Non-empty poll resets backoff to base
 */
class DepsPollSchedulerTest {

  private fun scheduler(
    baseIdleSec: Int = 300,
    clock: () -> Long = { 0L },
    random: Random = Random(42)
  ) = DepsPollScheduler(
    baseIdleSec = { baseIdleSec },
    clock = clock,
    random = random
  )

  // ── Idle jitter ──────────────────────────────────────────────────────────

  @Test
  fun `idle sleep is within plus-minus 30 percent of base`() {
    val s = scheduler(baseIdleSec = 300)
    repeat(1000) {
      val ms = s.nextSleepMs()
      val sec = ms / 1000
      // 300 * 0.7 = 210, 300 * 1.3 = 390
      assertTrue(sec in 210..390, "Idle sleep $sec outside [210, 390]")
    }
  }

  @Test
  fun `idle sleep respects min poll clamp of 15s`() {
    val s = scheduler(baseIdleSec = 5)
    repeat(100) {
      val sec = s.nextSleepMs() / 1000
      // 5 clamped to 15, jitter [10, 19]
      assertTrue(sec in 10..19, "Clamped idle $sec outside [10, 19]")
    }
  }

  @Test
  fun `idle sleep respects max poll clamp of 600s`() {
    val s = scheduler(baseIdleSec = 9999)
    repeat(100) {
      val sec = s.nextSleepMs() / 1000
      // 9999 clamped to 600, jitter [420, 780]
      assertTrue(sec in 420..780, "Clamped idle $sec outside [420, 780]")
    }
  }

  // ── Fast window ──────────────────────────────────────────────────────────

  @Test
  fun `fast window sleep is 30 to 60 seconds`() {
    val s = scheduler(baseIdleSec = 300, clock = { 0L })
    s.recordLocalEvent()
    repeat(1000) {
      val sec = s.nextSleepMs() / 1000
      assertTrue(sec in 30..60, "Fast window sleep $sec outside [30, 60]")
    }
  }

  @Test
  fun `fast window expires after 15 minutes`() {
    var now = 0L
    val s = scheduler(baseIdleSec = 300, clock = { now })
    s.recordLocalEvent()
    // Inside fast window
    now = 14 * 60 * 1000L
    val fastSec = s.nextSleepMs() / 1000
    assertTrue(fastSec in 30..60, "Should be in fast window: $fastSec")
    // After 15 minutes — back to idle
    now = 15 * 60 * 1000L + 1
    val idleSec = s.nextSleepMs() / 1000
    assertTrue(idleSec in 210..390, "Should be idle after fast window: $idleSec")
  }

  @Test
  fun `fast window boundary at exactly 15 min has expired`() {
    var now = 0L
    val s = scheduler(baseIdleSec = 300, clock = { now })
    s.recordLocalEvent()
    now = 15 * 60 * 1000L  // exactly at boundary — "at most 15 min" means expired
    val sec = s.nextSleepMs() / 1000
    assertTrue(sec in 210..390, "At exactly 15min boundary fast window should have expired: $sec")
  }

  // ── Exponential backoff ──────────────────────────────────────────────────

  @Test
  fun `backoff doubles after K=4 empty polls`() {
    val s = scheduler(baseIdleSec = 300)
    // 3 empty polls — still at base
    repeat(3) { s.recordPollResult(false) }
    var sec = s.nextSleepMs() / 1000
    assertTrue(sec in 210..390, "After 3 empty, still base: $sec")
    // 4th empty poll — triggers doubling
    s.recordPollResult(false)
    sec = s.nextSleepMs() / 1000
    // 300 * 2 = 600, jitter [420, 780]
    assertTrue(sec in 420..780, "After 4 empty, should be 2x: $sec")
  }

  @Test
  fun `backoff doubles again after another K=4 empty polls`() {
    val s = scheduler(baseIdleSec = 300)
    // First doubling
    repeat(4) { s.recordPollResult(false) }
    // Second doubling
    repeat(4) { s.recordPollResult(false) }
    val sec = s.nextSleepMs() / 1000
    // 300 * 4 = 1200, jitter [840, 1560]
    assertTrue(sec in 840..1560, "After 8 empty, should be 4x: $sec")
  }

  @Test
  fun `backoff caps at 4x base`() {
    val s = scheduler(baseIdleSec = 300)
    // Trigger many doublings
    repeat(20) { s.recordPollResult(false) }
    val sec = s.nextSleepMs() / 1000
    // 300 * 4 = 1200, jitter [840, 1560]
    assertTrue(sec in 840..1560, "Backoff should cap at 4x: $sec")
  }

  @Test
  fun `non-empty poll resets backoff to base`() {
    val s = scheduler(baseIdleSec = 300)
    // Build up backoff
    repeat(8) { s.recordPollResult(false) }
    var sec = s.nextSleepMs() / 1000
    assertTrue(sec in 840..1560, "Should be at 4x: $sec")
    // Non-empty resets
    s.recordPollResult(true)
    sec = s.nextSleepMs() / 1000
    assertTrue(sec in 210..390, "After non-empty, should reset to base: $sec")
  }

  @Test
  fun `non-empty poll resets consecutive empty counter`() {
    val s = scheduler(baseIdleSec = 300)
    // 3 empty + 1 non-empty + 3 empty — should NOT trigger backoff
    repeat(3) { s.recordPollResult(false) }
    s.recordPollResult(true)
    repeat(3) { s.recordPollResult(false) }
    val sec = s.nextSleepMs() / 1000
    assertTrue(sec in 210..390, "Counter should reset after non-empty: $sec")
  }

  // ── Fast window + backoff interaction ────────────────────────────────────

  @Test
  fun `fast window takes precedence over backoff`() {
    var now = 0L
    val s = scheduler(baseIdleSec = 300, clock = { now })
    // Build up backoff
    repeat(8) { s.recordPollResult(false) }
    // Trigger fast window
    s.recordLocalEvent()
    val sec = s.nextSleepMs() / 1000
    assertTrue(sec in 30..60, "Fast window should override backoff: $sec")
  }

  @Test
  fun `backoff persists after fast window expires`() {
    var now = 0L
    val s = scheduler(baseIdleSec = 300, clock = { now })
    // Build up backoff
    repeat(8) { s.recordPollResult(false) }
    // Trigger fast window
    s.recordLocalEvent()
    // Move past fast window
    now = 16 * 60 * 1000L
    val sec = s.nextSleepMs() / 1000
    // Backoff should still be at 4x
    assertTrue(sec in 840..1560, "Backoff should persist after fast window: $sec")
  }

  @Test
  fun `recordLocalEvent extends fast window`() {
    var now = 0L
    val s = scheduler(baseIdleSec = 300, clock = { now })
    s.recordLocalEvent()
    // 10 min later, another event
    now = 10 * 60 * 1000L
    s.recordLocalEvent()
    // 14 min after second event = 24 min after first — should still be fast
    now = 24 * 60 * 1000L
    val sec = s.nextSleepMs() / 1000
    assertTrue(sec in 30..60, "Second event should extend fast window: $sec")
    // 16 min after second event = 26 min after first — should be idle
    now = 26 * 60 * 1000L
    val idleSec = s.nextSleepMs() / 1000
    assertTrue(idleSec in 210..390, "Should be idle after extended fast window: $idleSec")
  }
}
