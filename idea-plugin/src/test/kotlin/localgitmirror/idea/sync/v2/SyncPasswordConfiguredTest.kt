package localgitmirror.idea.sync.v2

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncPasswordConfiguredTest {

  @Test
  fun `password present is configured regardless of pinning`() {
    assertTrue(syncPasswordConfigured("pw", v3Pinned = false))
    assertTrue(syncPasswordConfigured("pw", v3Pinned = true))
  }

  @Test
  fun `blank password is configured only when v3 key is pinned`() {
    assertTrue(syncPasswordConfigured("", v3Pinned = true))
    assertFalse(syncPasswordConfigured("", v3Pinned = false))
  }
}
