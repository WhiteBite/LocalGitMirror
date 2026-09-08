package localgitmirror.idea.deps

import java.util.Random

/**
 * Adaptive poll scheduler for [DepsAutomationService].
 *
 * Three modes, layered:
 *
 *  1. **Fast window** — for [FAST_WINDOW_MS] (15 min) after any local event
 *     ([recordLocalEvent]), sleeps are uniform 30–60 s. This lets the poller
 *     react quickly right after the user did something (manual request,
 *     respond, apply, or a Gradle sync failure) instead of waiting up to 5 min.
 *
 *  2. **Idle with backoff** — when not in the fast window, the base interval is
 *     [baseIdleSec] (default 300 s). After [BACKOFF_K] (4) consecutive empty
 *     polls the interval doubles, up to [MAX_BACKOFF_MULTIPLIER]× (4×) the
 *     base. Any non-empty poll resets the multiplier to 1×.
 *
 *  3. **Jitter** — every idle/backoff sleep is jittered ±30 % to avoid
 *     lockstep with any external observer. The fast-window sleep is already
 *     a uniform random in [30, 60] so no extra jitter is applied.
 *
 * Clock and random are injectable for deterministic unit tests.
 */
class DepsPollScheduler(
  private val baseIdleSec: () -> Int,
  private val clock: () -> Long = System::currentTimeMillis,
  private val random: Random = Random()
) {
  @Volatile private var fastWindowEndMs: Long = 0L
  @Volatile private var consecutiveEmptyPolls: Int = 0
  @Volatile private var backoffMultiplier: Int = 1

  fun recordLocalEvent() {
    fastWindowEndMs = clock() + FAST_WINDOW_MS
  }

  fun recordPollResult(hadItems: Boolean) {
    if (hadItems) {
      consecutiveEmptyPolls = 0
      backoffMultiplier = 1
    } else {
      consecutiveEmptyPolls++
      if (consecutiveEmptyPolls >= BACKOFF_K) {
        consecutiveEmptyPolls = 0
        backoffMultiplier = (backoffMultiplier * 2).coerceAtMost(MAX_BACKOFF_MULTIPLIER)
      }
    }
  }

  fun nextSleepMs(): Long {
    val now = clock()
    val inFastWindow = now < fastWindowEndMs
    val baseSec = baseIdleSec().coerceIn(MIN_POLL_SEC, MAX_POLL_SEC)

    val sleepSec = if (inFastWindow) {
      FAST_MIN_SEC + random.nextInt(FAST_MAX_SEC - FAST_MIN_SEC + 1)
    } else {
      val effectiveBase = baseSec * backoffMultiplier
      applyJitter(effectiveBase)
    }
    return sleepSec.toLong() * 1000L
  }

  private fun applyJitter(baseSec: Int): Int {
    val min = (baseSec * 0.7).toInt()
    val max = (baseSec * 1.3).toInt()
    return if (max <= min) baseSec else min + random.nextInt(max - min + 1)
  }

  companion object {
    const val FAST_WINDOW_MS = 15 * 60 * 1000L
    const val BACKOFF_K = 4
    const val MAX_BACKOFF_MULTIPLIER = 4
    const val MIN_POLL_SEC = 15
    const val MAX_POLL_SEC = 600
    const val FAST_MIN_SEC = 30
    const val FAST_MAX_SEC = 60
  }
}
