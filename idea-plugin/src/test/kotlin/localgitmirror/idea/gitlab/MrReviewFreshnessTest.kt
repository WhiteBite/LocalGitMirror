package localgitmirror.idea.gitlab

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.LocalDateTime

class MrReviewFreshnessTest {

  @Test
  fun `formatAge returns empty for blank or null`() {
    assertEquals("", MrReviewService.formatAge(null, LocalDateTime.now()))
    assertEquals("", MrReviewService.formatAge("", LocalDateTime.now()))
    assertEquals("", MrReviewService.formatAge("  ", LocalDateTime.now()))
  }

  @Test
  fun `formatAge returns empty for malformed input`() {
    val now = LocalDateTime.of(2026, 10, 19, 12, 0)
    assertEquals("", MrReviewService.formatAge("not-a-date", now))
    assertEquals("", MrReviewService.formatAge("2026-10-19 25:00", now))
    assertEquals("", MrReviewService.formatAge("2026/10/19 12:00", now))
  }

  @Test
  fun `formatAge returns under1m for less than one minute`() {
    val now = LocalDateTime.of(2026, 10, 19, 12, 0, 30)
    assertEquals("<1m", MrReviewService.formatAge("2026-10-19 12:00", now))
    assertEquals("<1m", MrReviewService.formatAge("2026-10-19T12:00:00", now))
  }

  @Test
  fun `formatAge returns Nm for minutes`() {
    val now = LocalDateTime.of(2026, 10, 19, 12, 0)
    assertEquals("1m", MrReviewService.formatAge("2026-10-19 11:59", now))
    assertEquals("5m", MrReviewService.formatAge("2026-10-19 11:55", now))
    assertEquals("59m", MrReviewService.formatAge("2026-10-19 11:01", now))
  }

  @Test
  fun `formatAge returns Nh for hours`() {
    val now = LocalDateTime.of(2026, 10, 19, 12, 0)
    assertEquals("1h", MrReviewService.formatAge("2026-10-19 10:59", now))
    assertEquals("3h", MrReviewService.formatAge("2026-10-19 09:00", now))
    assertEquals("23h", MrReviewService.formatAge("2026-10-18 13:00", now))
  }

  @Test
  fun `formatAge returns Nd for days and caps at days`() {
    val now = LocalDateTime.of(2026, 10, 19, 12, 0)
    assertEquals("1d", MrReviewService.formatAge("2026-10-18 12:00", now))
    assertEquals("4d", MrReviewService.formatAge("2026-10-15 12:00", now))
    assertEquals("30d", MrReviewService.formatAge("2026-09-19 12:00", now))
  }

  @Test
  fun `formatAge returns empty for future timestamps`() {
    val now = LocalDateTime.of(2026, 10, 19, 12, 0)
    assertEquals("", MrReviewService.formatAge("2026-10-19 12:01", now))
    assertEquals("", MrReviewService.formatAge("2026-10-20 11:00", now))
  }

  @Test
  fun `formatAge parses ISO-8601 without milliseconds`() {
    val now = LocalDateTime.of(2026, 10, 19, 12, 0)
    assertEquals("<1m", MrReviewService.formatAge("2026-10-19T11:59:30", now))
  }

  @Test
  fun `formatAge parses ISO-8601 with milliseconds`() {
    val now = LocalDateTime.of(2026, 10, 19, 12, 0)
    assertEquals("5m", MrReviewService.formatAge("2026-10-19T11:55:00.123", now))
    assertEquals("1h", MrReviewService.formatAge("2026-10-19T10:59:00.999", now))
    assertEquals("4d", MrReviewService.formatAge("2026-10-15T12:00:00.000", now))
  }

  @Test
  fun `formatAge parses notesSentAt format yyyy-MM-dd HH colon mm`() {
    val now = LocalDateTime.of(2026, 10, 19, 12, 0)
    assertEquals("1m", MrReviewService.formatAge("2026-10-19 11:59", now))
    assertEquals("30m", MrReviewService.formatAge("2026-10-19 11:30", now))
  }
}