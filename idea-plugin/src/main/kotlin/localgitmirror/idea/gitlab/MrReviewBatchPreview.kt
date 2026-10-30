package localgitmirror.idea.gitlab

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class MrBatchEntryPreview(
  val batchId: String,
  val findingId: String,
  val revision: Long,
  val kind: String,
  val threadId: String?,
  val resolve: Boolean,
  val anchorPath: String?,
  val anchorLine: Int?,
  val anchorSide: String?,
  val body: String,
)

internal sealed interface MrBatchGate {
  data object Ready : MrBatchGate
  data class Stale(val drift: List<String>) : MrBatchGate
  data class Unverified(val reason: String) : MrBatchGate
}

internal data class MrBatchPreviewState(
  val batchId: String,
  val iid: Int,
  val entries: List<MrBatchEntryPreview>,
  val identity: Map<String, String>,
  val gate: MrBatchGate,
  val posted: Map<Pair<String, Long>, MrReviewJournal.PostedIds> = emptyMap(),
  val result: MrReviewBatchPublisher.BatchResult? = null,
)

internal sealed interface MrBatchEntryStatus {
  data object Pending : MrBatchEntryStatus
  data class Posted(val url: String?, val noteId: Long?) : MrBatchEntryStatus
  data class Recovered(val url: String?, val noteId: Long?) : MrBatchEntryStatus
  data class Blocked(val code: String, val detail: String) : MrBatchEntryStatus
}

internal data class MrBatchPublishPlan(
  val publishIds: List<String>,
  val blockedIds: List<String>,
  val remainingIds: List<String>,
  val publishEntries: Int,
  val remainingEntries: Int,
  val totalEntries: Int,
)

internal fun batchEntryPreview(batchId: String, entry: MrReviewHandoff.Entry): MrBatchEntryPreview {
  val a = entry.anchor
  return MrBatchEntryPreview(
    batchId = batchId,
    findingId = entry.findingId.orEmpty(),
    revision = entry.revision,
    kind = entry.kind,
    threadId = entry.threadId,
    resolve = entry.resolve,
    anchorPath = a?.let { anchorString(it, "new_path") ?: anchorString(it, "old_path") },
    anchorLine = a?.get("line")?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull,
    anchorSide = a?.let { anchorString(it, "side") },
    body = entry.body,
  )
}

internal fun batchPreviewState(
  batch: MrReplyPushService.PendingBatch,
  gate: MrBatchGate,
  posted: Map<Pair<String, Long>, MrReviewJournal.PostedIds> = emptyMap(),
  result: MrReviewBatchPublisher.BatchResult? = null,
): MrBatchPreviewState = MrBatchPreviewState(
  batchId = batch.batchId,
  iid = batch.iid,
  entries = batch.parsed.entries.map { batchEntryPreview(batch.batchId, it) },
  identity = batch.parsed.identity,
  gate = gate,
  posted = posted,
  result = result,
)

internal fun entryStatus(state: MrBatchPreviewState, entry: MrBatchEntryPreview): MrBatchEntryStatus {
  val r = state.result?.entries?.firstOrNull {
    it.findingId == entry.findingId && it.revision == entry.revision
  }
  if (r != null) {
    return when (r.outcome) {
      "posted" -> MrBatchEntryStatus.Posted(r.url, r.noteId)
      "skipped-duplicate" -> MrBatchEntryStatus.Recovered(r.url, r.noteId)
      else -> MrBatchEntryStatus.Blocked(r.code, r.error ?: r.note)
    }
  }
  state.posted[entry.findingId to entry.revision]?.let { p ->
    return MrBatchEntryStatus.Recovered(p.url, p.noteId)
  }
  return MrBatchEntryStatus.Pending
}

/** Batches are immutable postbox artifacts published as whole documents: a gated
 *  batch never enters the plan even when selected, and everything not published
 *  stays pending in the postbox. */
internal fun batchPublishPlan(
  states: List<MrBatchPreviewState>,
  selectedIds: Set<String>,
): MrBatchPublishPlan {
  val publish = states.filter { it.gate == MrBatchGate.Ready && it.batchId in selectedIds }
  val publishIds = publish.map { it.batchId }.toSet()
  val blocked = states.filter { it.gate != MrBatchGate.Ready }
  val remaining = states.filter { it.batchId !in publishIds }
  return MrBatchPublishPlan(
    publishIds = publish.map { it.batchId },
    blockedIds = blocked.map { it.batchId },
    remainingIds = remaining.map { it.batchId },
    publishEntries = publish.sumOf { it.entries.size },
    remainingEntries = remaining.sumOf { it.entries.size },
    totalEntries = states.sumOf { it.entries.size },
  )
}

internal fun batchEntryKey(batchId: String, findingId: String, revision: Long): String =
  "batch:$batchId:$findingId:$revision"

private fun anchorString(anchor: kotlinx.serialization.json.JsonObject, key: String): String? =
  anchor[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
