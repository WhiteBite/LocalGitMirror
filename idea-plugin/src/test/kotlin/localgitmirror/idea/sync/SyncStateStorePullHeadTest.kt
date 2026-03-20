package localgitmirror.idea.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncStateStorePullHeadTest {

  @Test
  fun `read write last pulled head`() {
    val root = createTempDir(prefix = "lgm-sync-pull-")
    try {
      assertNull(SyncStateStore.readLastPulledHead(root))

      SyncStateStore.writeLastPulledHead(root, "abc1234")
      assertEquals("abc1234", SyncStateStore.readLastPulledHead(root))

      SyncStateStore.writeLastPulledHead(root, "def5678")
      assertEquals("def5678", SyncStateStore.readLastPulledHead(root))

      val file = File(root, ".localgitmirror/state/last_pulled_head.txt")
      assertEquals(true, file.exists())
    } finally {
      root.deleteRecursively()
    }
  }
}
