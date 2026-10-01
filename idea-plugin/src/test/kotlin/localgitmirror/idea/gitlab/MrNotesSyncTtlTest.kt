package localgitmirror.idea.gitlab

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class MrNotesSyncTtlTest {

  private val now: LocalDateTime = LocalDateTime.of(2026, 10, 1, 12, 0)

  @Test
  fun `unchanged hash within the window skips the upload`() {
    assertTrue(MrNotesSync.shouldSkipUpload("h", "h", "2026-09-26 12:00", now))
  }

  @Test
  fun `unchanged hash older than the postbox ttl re-uploads`() {
    assertFalse(MrNotesSync.shouldSkipUpload("h", "h", "2026-09-24 12:00", now))
  }

  @Test
  fun `missing sent-at re-uploads despite unchanged hash`() {
    assertFalse(MrNotesSync.shouldSkipUpload("h", "h", null, now))
  }

  @Test
  fun `unparseable sent-at re-uploads despite unchanged hash`() {
    assertFalse(MrNotesSync.shouldSkipUpload("h", "h", "yesterday", now))
  }

  @Test
  fun `changed hash re-uploads regardless of sent-at`() {
    assertFalse(MrNotesSync.shouldSkipUpload("old", "new", "2026-10-01 11:00", now))
  }
}
