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
      // Initialize a bare git repo so SyncStateStore can resolve .git/
      ProcessBuilder(listOf("git", "init")).directory(root).start().waitFor()

      assertNull(SyncStateStore.readLastPulledHead(root))

      SyncStateStore.writeLastPulledHead(root, "abc1234")
      assertEquals("abc1234", SyncStateStore.readLastPulledHead(root))

      SyncStateStore.writeLastPulledHead(root, "def5678")
      assertEquals("def5678", SyncStateStore.readLastPulledHead(root))

      val file = File(root, ".git/lgm/state/last_pulled_head.txt")
      assertEquals(true, file.exists())
    } finally {
      root.deleteRecursively()
    }
  }
}
