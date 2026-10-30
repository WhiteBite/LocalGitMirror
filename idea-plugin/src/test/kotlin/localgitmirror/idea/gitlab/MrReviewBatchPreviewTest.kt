package localgitmirror.idea.gitlab

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.mirror.MirrorPostboxApi

class MrReviewBatchPreviewTest {

  private fun fixtureDoc(): MrReviewHandoff.Document {
    var dir: File? = File(System.getProperty("user.dir", "."))
    var fixture: File? = null
    while (dir != null && fixture == null) {
      val candidate = File(dir, "tests/fixtures/mr_review_publish/publish_batch.json")
      if (candidate.isFile) fixture = candidate
      dir = dir.parentFile
    }
    val batch = MrReviewProtocol.parse(requireNotNull(fixture).readText()).jsonObject.getValue("batch").jsonObject
    return MrReviewHandoff.parseAndValidate(
      MrReviewProtocol.canonicalJson(batch),
      expectedIid = 30,
      expectedBatchId = batch.getValue("batch_id").jsonPrimitive.content,
      want = "v2",
    )
  }

  private fun pendingBatch(doc: MrReviewHandoff.Document, batchId: String = doc.batchId!!): MrReplyPushService.PendingBatch =
    MrReplyPushService.PendingBatch(
      item = MirrorPostboxApi.FileSyncItem("id-1", "mr-replies/mr-!30-$batchId.review.json", 10, 10, 0),
      path = "mr-replies/mr-!30-$batchId.review.json",
      iid = 30,
      batchId = batchId,
      parsed = doc,
    )

  private fun state(batchId: String, gate: MrBatchGate, entries: Int): MrBatchPreviewState =
    MrBatchPreviewState(
      batchId = batchId,
      iid = 30,
      entries = (1..entries).map {
        MrBatchEntryPreview(batchId, "f-$it", 1, "general", null, false, null, null, null, "body $it")
      },
      identity = mapOf("snapshot_id" to "s".repeat(64), "code_id" to "c".repeat(64), "version_fingerprint" to "v".repeat(64)),
      gate = gate,
    )

  @Test
  fun `fixture document previews every kind including explicit general`() {
    val doc = fixtureDoc()
    val st = batchPreviewState(pendingBatch(doc), MrBatchGate.Ready)
    assertEquals(4, st.entries.size)
    assertEquals(listOf("anchored", "anchored", "thread", "general"), st.entries.map { it.kind })
    val general = st.entries.last()
    assertNull(general.anchorPath)
    assertNull(general.anchorLine)
    assertTrue(general.body.isNotBlank())
    val anchored = st.entries.first()
    assertEquals("new", anchored.anchorSide)
    assertEquals("src/config.py", anchored.anchorPath)
    assertEquals(12, anchored.anchorLine)
    val renamed = st.entries[1]
    assertEquals("old", renamed.anchorSide)
    assertEquals("src/renamed.py", renamed.anchorPath)
    assertTrue(st.entries.all { it.findingId.isNotBlank() && it.revision >= 1 })
    assertEquals(doc.identity["snapshot_id"], st.identity["snapshot_id"])
  }

  @Test
  fun `stale batch never enters the publish plan even when selected`() {
    val ready = state("b-ready", MrBatchGate.Ready, 2)
    val stale = state("b-stale", MrBatchGate.Stale(listOf("source_sha")), 3)
    val plan = batchPublishPlan(listOf(ready, stale), setOf("b-ready", "b-stale"))
    assertEquals(listOf("b-ready"), plan.publishIds)
    assertEquals(listOf("b-stale"), plan.blockedIds)
    assertEquals(listOf("b-stale"), plan.remainingIds)
    assertEquals(2, plan.publishEntries)
    assertEquals(3, plan.remainingEntries)
    assertEquals(5, plan.totalEntries)
  }

  @Test
  fun `unverified batch is blocked and stays pending`() {
    val unverified = state("b-x", MrBatchGate.Unverified("GitLab API is not configured"), 4)
    val plan = batchPublishPlan(listOf(unverified), setOf("b-x"))
    assertTrue(plan.publishIds.isEmpty())
    assertEquals(listOf("b-x"), plan.blockedIds)
    assertEquals(0, plan.publishEntries)
    assertEquals(4, plan.remainingEntries)
  }

  @Test
  fun `unselected batch remains pending with truthful counts`() {
    val a = state("b-a", MrBatchGate.Ready, 2)
    val b = state("b-b", MrBatchGate.Ready, 5)
    val plan = batchPublishPlan(listOf(a, b), setOf("b-a"))
    assertEquals(listOf("b-a"), plan.publishIds)
    assertEquals(listOf("b-b"), plan.remainingIds)
    assertEquals(2, plan.publishEntries)
    assertEquals(5, plan.remainingEntries)
    assertEquals(7, plan.totalEntries)
  }

  @Test
  fun `entry status prefers the publication result over the journal`() {
    val doc = fixtureDoc()
    val first = doc.entries.first()
    val result = MrReviewBatchPublisher.BatchResult(
      iid = 30, batchId = doc.batchId!!, posted = 1, duplicates = 0, blocked = 0,
      entries = listOf(
        MrReviewBatchPublisher.EntryResult(
          label = "entry 1", kind = first.kind, target = "t",
          findingId = first.findingId!!, revision = first.revision, marker = "m",
          outcome = "posted", code = "", error = null, note = "n",
          discussionId = "d1", noteId = 901, url = "https://gl/mr/30#note_901",
        ),
      ),
      details = emptyList(), stale = null,
    )
    val st = batchPreviewState(pendingBatch(doc), MrBatchGate.Ready, result = result)
    val preview = st.entries.first()
    val status = entryStatus(st, preview)
    assertTrue(status is MrBatchEntryStatus.Posted)
    assertEquals("https://gl/mr/30#note_901", status.url)
    val journalOnly = batchPreviewState(
      pendingBatch(doc), MrBatchGate.Ready,
      posted = mapOf(
        (first.findingId!! to first.revision) to
          MrReviewJournal.PostedIds("d1", 901, doc.batchId!!, "https://gl/mr/30#note_901"),
      ),
    )
    val recovered = entryStatus(journalOnly, journalOnly.entries.first())
    assertTrue(recovered is MrBatchEntryStatus.Recovered)
    assertEquals(901L, recovered.noteId)
    val pending = entryStatus(journalOnly, journalOnly.entries.last())
    assertEquals(MrBatchEntryStatus.Pending, pending)
  }

  @Test
  fun `blocked entry status carries the typed code and detail`() {
    val doc = fixtureDoc()
    val first = doc.entries.first()
    val result = MrReviewBatchPublisher.BatchResult(
      iid = 30, batchId = doc.batchId!!, posted = 0, duplicates = 0, blocked = 1,
      entries = listOf(
        MrReviewBatchPublisher.EntryResult(
          label = "entry 1", kind = first.kind, target = "t",
          findingId = first.findingId!!, revision = first.revision, marker = "m",
          outcome = "blocked", code = "stale-snapshot", error = "source_sha drifted", note = "source_sha drifted",
          discussionId = null, noteId = null, url = null,
        ),
      ),
      details = emptyList(),
      stale = MrReviewBatchPublisher.StaleInfo(emptyMap(), emptyMap(), listOf("source_sha")),
    )
    val st = batchPreviewState(pendingBatch(doc), MrBatchGate.Stale(listOf("source_sha")), result = result)
    val status = entryStatus(st, st.entries.first())
    assertTrue(status is MrBatchEntryStatus.Blocked)
    assertEquals("stale-snapshot", status.code)
    assertEquals("source_sha drifted", status.detail)
  }
}
