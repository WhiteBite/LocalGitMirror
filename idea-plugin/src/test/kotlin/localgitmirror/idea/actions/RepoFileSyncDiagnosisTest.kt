package localgitmirror.idea.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RepoFileSyncDiagnosisTest {

  @Test
  fun `unpinned server key is its own diagnosis, not generic missing config`() {
    assertEquals(
      FileSyncConfigStatus.SERVER_KEY_NOT_PINNED,
      fileSyncConfigStatus(baseUrlBlank = false, v3Pinned = false)
    )
  }

  @Test
  fun `blank base url stays the genuine missing-config diagnosis`() {
    assertEquals(
      FileSyncConfigStatus.CONFIG_MISSING,
      fileSyncConfigStatus(baseUrlBlank = true, v3Pinned = false)
    )
    assertEquals(
      FileSyncConfigStatus.CONFIG_MISSING,
      fileSyncConfigStatus(baseUrlBlank = true, v3Pinned = true)
    )
  }

  @Test
  fun `configured url with pinned key passes`() {
    assertEquals(
      FileSyncConfigStatus.OK,
      fileSyncConfigStatus(baseUrlBlank = false, v3Pinned = true)
    )
  }

  @Test
  fun `not-pinned diagnosis names the pin, not url or password`() {
    val key = fileSyncConfigNotifyKey(FileSyncConfigStatus.SERVER_KEY_NOT_PINNED)
    assertEquals(FILESYNC_KEY_NOT_PINNED_KEY, key)
    assertNotEquals("notify.config.missing", key)
  }

  @Test
  fun `missing-config diagnosis keeps the generic settings key`() {
    assertEquals("notify.config.missing", fileSyncConfigNotifyKey(FileSyncConfigStatus.CONFIG_MISSING))
  }

  @Test
  fun `healthy config has nothing to notify`() {
    assertNull(fileSyncConfigNotifyKey(FileSyncConfigStatus.OK))
  }
}
