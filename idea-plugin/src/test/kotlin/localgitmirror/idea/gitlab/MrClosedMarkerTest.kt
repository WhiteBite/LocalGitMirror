package localgitmirror.idea.gitlab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MrClosedMarkerTest {

  @Test
  fun `render then parse round-trips the marker data`() {
    val md = MrClosedMarker.render(46, "PHONYX-28 arch refactor", "refactor/PHONYX-28-arch-target",
      "2026-10-15T20:15:00+03:00")
    assertEquals(
      MrClosedMarker.Data(46, "PHONYX-28 arch refactor", "refactor/PHONYX-28-arch-target",
        "2026-10-15T20:15:00+03:00"),
      MrClosedMarker.parse(md),
    )
  }

  @Test
  fun `render falls back to a placeholder title`() {
    val md = MrClosedMarker.render(9, "", "old-branch", "2026-10-15T20:15:00+03:00")
    assertEquals("MR !9", MrClosedMarker.parse(md)?.title)
  }

  @Test
  fun `parse rejects a normal notes document`() {
    assertNull(MrClosedMarker.parse("# MR !24 — Llm-core\n- **Ветка:** `llm-core`\n"))
  }

  @Test
  fun `iidOf matches only closed markers`() {
    assertEquals(24, MrClosedMarker.iidOf("mr-notes/mr-!24.closed.md"))
    assertNull(MrClosedMarker.iidOf("mr-notes/mr-!24.md"))
    assertNull(MrClosedMarker.iidOf("mr-replies/mr-!24.md"))
  }
}