package localgitmirror.idea.deps

import localgitmirror.idea.mirror.MirrorDepsApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The manual respond/apply flows must enumerate every queued item: the plan
 * covers the whole list (not just the first) and the batch history line
 * accounts for each outcome, itemizing failures.
 */
class DepsBatchEnumerationTest {

  private fun item(id: String) = MirrorDepsApi.DepsItem(id, 10L, 0L)

  @Test
  fun `respond plan covers every pending item not just the first`() {
    val items = listOf(item("req-a"), item("req-b"), item("req-c"))
    assertEquals(listOf("req-a", "req-b", "req-c"), depsItemsToProcess(items).map { it.id })
  }

  @Test
  fun `apply plan covers every ready response not just the first`() {
    val items = listOf(item("resp-1"), item("resp-2"))
    assertEquals(listOf("resp-1", "resp-2"), depsItemsToProcess(items).map { it.id })
  }

  @Test
  fun `single item plan keeps that item`() {
    assertEquals(listOf("only"), depsItemsToProcess(listOf(item("only"))).map { it.id })
  }

  @Test
  fun `empty queue yields empty plan`() {
    assertTrue(depsItemsToProcess(emptyList()).isEmpty())
  }

  @Test
  fun `batch history line accounts for every item and itemizes failures`() {
    val outcomes = listOf(
      DepsItemOutcome("req-a", true, "shipped=3"),
      DepsItemOutcome("req-b", true, "shipped=5"),
      DepsItemOutcome("req-c", false, "manifest download HTTP 500")
    )
    val line = depsBatchHistoryLine(outcomes)
    assertTrue(line.contains("2/3"), line)
    assertTrue(line.contains("req-c"), line)
    assertTrue(line.contains("manifest download HTTP 500"), line)
  }

  @Test
  fun `batch history line with no failures has no failure section`() {
    val outcomes = listOf(DepsItemOutcome("a", true, "ok"), DepsItemOutcome("b", true, "ok"))
    val line = depsBatchHistoryLine(outcomes)
    assertTrue(line.contains("2/2"), line)
    assertFalse(line.contains("failed"), line)
  }
}
