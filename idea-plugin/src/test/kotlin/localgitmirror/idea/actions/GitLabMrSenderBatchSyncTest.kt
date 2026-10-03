package localgitmirror.idea.actions

import localgitmirror.idea.gitlab.MrSendPlanner
import localgitmirror.idea.mirror.HttpResult
import localgitmirror.idea.sync.v2.SyncEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GitLabMrSenderBatchSyncTest {

  private class FakeSyncFacade {
    val calls = mutableListOf<List<String>>()

    fun runFullSync(additionalBranches: List<String>): SyncEngine.FullSyncResult {
      calls += additionalBranches
      return SyncEngine.FullSyncResult(
        step = SyncEngine.StepResult(true, "Sync completed"),
        http = HttpResult(200, "ok"),
        dump = null,
        repo = "onyx-platform",
        traceId = "t-1",
        diagnostics = emptyList()
      )
    }
  }

  @Test
  fun `batch send calls runFullSync exactly once with all non-skipped branches`() {
    val facade = FakeSyncFacade()
    val plan = MrSendPlanner.Plan(
      sent = listOf("feat-a", "feat-c"),
      skipped = listOf("feat-b"),
      excludeShas = emptyList(),
    )

    val (toSend, res) = GitLabMrSender.sendBatch(listOf("feat-a", "feat-b", "feat-c"), plan) {
      facade.runFullSync(it)
    }

    assertEquals(1, facade.calls.size)
    assertEquals(listOf("feat-a", "feat-c"), facade.calls.first())
    assertEquals(listOf("feat-a", "feat-c"), toSend)
    assertNotNull(res)
    assertEquals(true, res.step.ok)
  }

  @Test
  fun `batch send passes the branch list as-is when nothing is skipped`() {
    val facade = FakeSyncFacade()
    val plan = MrSendPlanner.Plan(sent = listOf("main", "feat"), skipped = emptyList(), excludeShas = emptyList())

    GitLabMrSender.sendBatch(listOf("main", "feat"), plan) { facade.runFullSync(it) }

    assertEquals(1, facade.calls.size)
    assertEquals(listOf("main", "feat"), facade.calls.first())
  }

  @Test
  fun `batch send never calls runFullSync when every branch is already on cache`() {
    val facade = FakeSyncFacade()
    val plan = MrSendPlanner.Plan(
      sent = emptyList(),
      skipped = listOf("feat-a", "feat-b"),
      excludeShas = emptyList(),
    )

    val (toSend, res) = GitLabMrSender.sendBatch(listOf("feat-a", "feat-b"), plan) { facade.runFullSync(it) }

    assertEquals(0, facade.calls.size)
    assertEquals(emptyList<String>(), toSend)
    assertNull(res)
  }

  @Test
  fun `batch send reports failure result back to the caller`() {
    val plan = MrSendPlanner.Plan(sent = listOf("feat"), skipped = emptyList(), excludeShas = emptyList())

    val (_, res) = GitLabMrSender.sendBatch(listOf("feat"), plan) {
      SyncEngine.FullSyncResult(
        step = SyncEngine.StepResult(false, "upload rejected"),
        http = HttpResult(500, "err"),
        dump = null,
        repo = "onyx-platform",
        traceId = "t-2",
        diagnostics = emptyList()
      )
    }

    assertNotNull(res)
    assertEquals(false, res.step.ok)
    assertEquals("upload rejected", res.step.message)
  }

  @Test
  fun `materialize creates a local branch only for remote-only branches`() {
    val created = mutableListOf<Pair<String, String>>()

    val unresolved = GitLabMrSender.materializeRemoteRefs(
      listOf("local-a", "remote-b"),
      branchHash = { if (it == "local-a") "aaa1111" else null },
      remoteTip = { if (it == "remote-b") "bbb2222" else null },
      createBranch = { branch, tip -> created += branch to tip; true },
    )

    assertEquals(emptyList<String>(), unresolved)
    assertEquals(listOf("remote-b" to "bbb2222"), created)
  }

  @Test
  fun `materialize reports branch unresolved when remote tip is missing`() {
    val unresolved = GitLabMrSender.materializeRemoteRefs(
      listOf("ghost"),
      branchHash = { null },
      remoteTip = { null },
      createBranch = { _, _ -> true },
    )

    assertEquals(listOf("ghost"), unresolved)
  }

  @Test
  fun `materialize reports branch unresolved when creation fails`() {
    val unresolved = GitLabMrSender.materializeRemoteRefs(
      listOf("remote-b"),
      branchHash = { null },
      remoteTip = { "bbb2222" },
      createBranch = { _, _ -> false },
    )

    assertEquals(listOf("remote-b"), unresolved)
  }
}
