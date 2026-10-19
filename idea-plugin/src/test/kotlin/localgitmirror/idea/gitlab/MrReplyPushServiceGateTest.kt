package localgitmirror.idea.gitlab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class MrReplyPushServiceGateTest {

  @Test
  fun `unseen marker posts regardless of resolve flags`() {
    assertEquals(ResolveAction.POST, resolveDecision(markerSeen = false, wantsResolve = false, threadResolved = false))
    assertEquals(ResolveAction.POST, resolveDecision(markerSeen = false, wantsResolve = false, threadResolved = true))
    assertEquals(ResolveAction.POST, resolveDecision(markerSeen = false, wantsResolve = true, threadResolved = false))
    assertEquals(ResolveAction.POST, resolveDecision(markerSeen = false, wantsResolve = true, threadResolved = true))
  }

  @Test
  fun `seen marker without resolve skips`() {
    assertEquals(ResolveAction.SKIP, resolveDecision(markerSeen = true, wantsResolve = false, threadResolved = false))
    assertEquals(ResolveAction.SKIP, resolveDecision(markerSeen = true, wantsResolve = false, threadResolved = true))
  }

  @Test
  fun `seen marker with resolve on an unresolved thread is resolve-only`() {
    assertEquals(ResolveAction.RESOLVE_ONLY, resolveDecision(markerSeen = true, wantsResolve = true, threadResolved = false))
  }

  @Test
  fun `seen marker with resolve on a resolved thread skips`() {
    assertEquals(ResolveAction.SKIP, resolveDecision(markerSeen = true, wantsResolve = true, threadResolved = true))
  }

  @Test
  fun `single flight lets one caller in until exit`() {
    val gate = SingleFlight()
    assertTrue(gate.tryEnter())
    assertFalse(gate.tryEnter())
    gate.exit()
    assertTrue(gate.tryEnter())
    gate.exit()
  }

  @Test
  fun `single flight race has exactly one winner`() {
    val gate = SingleFlight()
    val start = CountDownLatch(1)
    val wins = AtomicInteger(0)
    val threads = (1..8).map {
      Thread {
        start.await()
        if (gate.tryEnter()) wins.incrementAndGet()
      }
    }
    threads.forEach { it.start() }
    start.countDown()
    threads.forEach { it.join() }
    assertEquals(1, wins.get())
  }

  @Test
  fun `dedup first report is new and its exact repeat is not`() {
    val dedup = ReportDedup()
    assertTrue(dedup.isNew("mr-replies/mr-!1.md", "0/1/0/0"))
    assertFalse(dedup.isNew("mr-replies/mr-!1.md", "0/1/0/0"))
  }

  @Test
  fun `dedup treats changed signature or new key as new`() {
    val dedup = ReportDedup()
    assertTrue(dedup.isNew("mr-replies/mr-!1.md", "0/1/0/0"))
    assertTrue(dedup.isNew("mr-replies/mr-!1.md", "0/2/0/0"))
    assertTrue(dedup.isNew("mr-replies/mr-!2.md", "0/2/0/0"))
  }

  @Test
  fun `dedup evicts beyond the bound`() {
    val dedup = ReportDedup(cap = 2)
    assertTrue(dedup.isNew("a", "s"))
    assertTrue(dedup.isNew("b", "s"))
    assertTrue(dedup.isNew("c", "s"))
    assertFalse(dedup.isNew("b", "s"))
    assertTrue(dedup.isNew("a", "s"))
    assertFalse(dedup.isNew("c", "s"))
    assertTrue(dedup.isNew("b", "s"))
  }
}
