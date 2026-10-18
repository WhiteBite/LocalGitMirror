package localgitmirror.idea.gitlab

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MrNotesSyncClosureTest {

  private val now: LocalDateTime = LocalDateTime.of(2026, 10, 15, 20, 0)

  @Test
  fun `closure marker uploads when never sent`() {
    assertFalse(MrNotesSync.shouldSkipClosure(null, now))
  }

  @Test
  fun `closure marker is skipped within the resync window`() {
    assertTrue(MrNotesSync.shouldSkipClosure("2026-10-14 10:00", now))
  }

  @Test
  fun `closure marker re-uploads after the resync window`() {
    assertFalse(MrNotesSync.shouldSkipClosure("2026-10-01 10:00", now))
  }

  @Test
  fun `closure marker re-uploads on a malformed stamp`() {
    assertFalse(MrNotesSync.shouldSkipClosure("garbage", now))
  }
}