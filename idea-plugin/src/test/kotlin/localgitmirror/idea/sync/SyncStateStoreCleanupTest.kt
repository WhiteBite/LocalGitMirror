package localgitmirror.idea.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncStateStoreCleanupTest {

  @Test
  fun `cleanupOldDumps keeps only newest per repo`() {
    val root = createTempDir(prefix = "lgm-sync-state-")
    try {
      val tmp = File(root, ".localgitmirror/tmp")
      tmp.mkdirs()

      fun touch(name: String, ts: Long) {
        val f = File(tmp, name)
        f.writeText("x")
        f.setLastModified(ts)
      }

      // repoA: 3 dumps
      touch("dump_repoA_20260312_1200.dmp", 1000)
      touch("dump_repoA_20260312_1201.dmp", 2000)
      touch("dump_repoA_20260312_1202.dmp", 3000)

      // repoB: 2 dumps
      touch("dump_repoB_20260312_1200.dmp", 1000)
      touch("dump_repoB_20260312_1201.dmp", 2000)

      SyncStateStore.cleanupOldDumps(root, keepPerRepo = 1)

      val left = tmp.listFiles { f -> f.name.endsWith(".dmp") }!!.map { it.name }.sorted()
      assertEquals(listOf("dump_repoA_20260312_1202.dmp", "dump_repoB_20260312_1201.dmp"), left)
    } finally {
      root.deleteRecursively()
    }
  }
}
