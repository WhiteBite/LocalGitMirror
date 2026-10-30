package localgitmirror.idea.gitlab

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * WORK-side per-batch publication journal (Python twin: lgm_core/mr_review_receipts.py).
 *
 * Every mutation rewrites the file atomically (temp + fd.sync + atomic move), so a crash
 * leaves either the previous or the new valid document. An attempt becomes eligible for
 * the receipt handoff only once [AttemptRecord.completedAt] is set; entry outcomes with
 * discussion/note ids and the note permalink are recorded before any receipt or ack step.
 * Entries a previous attempt really posted are never POSTed again, and an entry whose
 * previous POST ended without a recorded response is only re-looked-up through its
 * marker. An unreadable journal file is replaced by a fresh one: protocol marker scans
 * remain the backstop against duplicate posts.
 */
class MrReviewJournal private constructor(val file: File, private var state: State?) {

  data class PostedIds(val discussionId: String?, val noteId: Long?, val batchId: String, val url: String?)

  class EntryRecord(
    val findingId: String,
    val revision: Long,
    val kind: String,
    val marker: String?,
    var state: String = "post_start",
    var postAttempted: Boolean = false,
    var startedAt: String = nowIso(),
    var finishedAt: String? = null,
    var outcome: String? = null,
    var code: String = "",
    var error: String? = null,
    var discussionId: String? = null,
    var noteId: Long? = null,
    var url: String? = null,
    var recoveredFrom: String? = null,
  )

  class AttemptRecord(
    val attempt: Int,
    val postboxItemId: String,
    val postboxPath: String,
    val startedAt: String = nowIso(),
    var completedAt: String? = null,
    var webUrl: String = "",
    var receiptUploadedAt: String? = null,
    var receiptPath: String? = null,
    var ackedAt: String? = null,
    val entries: MutableList<EntryRecord> = mutableListOf(),
  )

  class State(
    val repo: String,
    val iid: Int,
    val batchId: String,
    val gitlabUrl: String,
    val targetProjectId: Long,
    val identity: Map<String, String>,
    val refs: JsonObject,
    val attempts: MutableList<AttemptRecord> = mutableListOf(),
  )

  private var pendingItemId: String = ""
  private var pendingPath: String = ""

  val readable: Boolean get() = state != null
  var replacedCorrupt: Boolean = false
    private set
  val iid: Int get() = requireState().iid
  val batchId: String get() = requireState().batchId
  val attempts: List<AttemptRecord> get() = requireState().attempts

  private fun requireState(): State =
    state ?: throw IllegalStateException("journal ${file.name} is not readable")

  fun beginAttempt(): AttemptRecord {
    val s = requireState()
    val attempt = AttemptRecord(
      attempt = (s.attempts.maxOfOrNull { it.attempt } ?: 0) + 1,
      postboxItemId = pendingItemId,
      postboxPath = pendingPath,
    )
    s.attempts.add(attempt)
    persist()
    return attempt
  }

  fun setWebUrl(attempt: AttemptRecord, webUrl: String) {
    if (webUrl.isNotBlank() && attempt.webUrl != webUrl) {
      attempt.webUrl = webUrl
      persist()
    }
  }

  private fun entry(attempt: AttemptRecord, findingId: String, revision: Long): EntryRecord? =
    attempt.entries.firstOrNull { it.findingId == findingId && it.revision == revision }

  fun recordPostStart(attempt: AttemptRecord, entry: MrReviewHandoff.Entry) {
    val findingId = entry.findingId.orEmpty()
    val rec = entry(attempt, findingId, entry.revision)
      ?: EntryRecord(findingId, entry.revision, entry.kind, entry.marker).also { attempt.entries.add(it) }
    rec.state = "post_start"
    rec.postAttempted = true
    rec.startedAt = nowIso()
    rec.finishedAt = null
    rec.outcome = null
    rec.code = ""
    rec.error = null
    rec.discussionId = null
    rec.noteId = null
    rec.url = null
    rec.recoveredFrom = null
    persist()
  }

  fun recordResult(attempt: AttemptRecord, result: MrReviewBatchPublisher.EntryResult,
                   postAttempted: Boolean, recoveredFrom: String? = null) {
    val rec = entry(attempt, result.findingId, result.revision)
      ?: EntryRecord(result.findingId, result.revision, result.kind, result.marker,
                     startedAt = attempt.startedAt).also { attempt.entries.add(it) }
    rec.state = "done"
    rec.postAttempted = postAttempted || rec.postAttempted
    rec.outcome = result.outcome
    rec.code = result.code
    rec.error = result.error
    rec.discussionId = result.discussionId
    rec.noteId = result.noteId
    rec.url = result.url
    rec.finishedAt = nowIso()
    rec.recoveredFrom = recoveredFrom
    persist()
  }

  fun updateEntryIds(attempt: AttemptRecord, findingId: String, revision: Long,
                     discussionId: String?, url: String?) {
    val rec = entry(attempt, findingId, revision) ?: return
    rec.discussionId = discussionId
    if (!url.isNullOrEmpty()) rec.url = url
    persist()
  }

  fun completeAttempt(attempt: AttemptRecord) {
    attempt.completedAt = nowIso()
    persist()
  }

  fun markReceiptUploaded(attempt: AttemptRecord, path: String) {
    attempt.receiptUploadedAt = nowIso()
    attempt.receiptPath = path
    persist()
  }

  fun markAcked(attempt: AttemptRecord) {
    attempt.ackedAt = nowIso()
    persist()
  }

  fun postedEntries(): Map<Pair<String, Long>, PostedIds> {
    val s = requireState()
    val out = LinkedHashMap<Pair<String, Long>, PostedIds>()
    for (a in s.attempts) {
      for (e in a.entries) {
        if (e.state == "done" && e.outcome == "posted") {
          out[e.findingId to e.revision] = PostedIds(e.discussionId, e.noteId, s.batchId, e.url)
        }
      }
    }
    return out
  }

  fun uncertainEntries(): Map<Pair<String, Long>, Int> {
    val latest = LinkedHashMap<Pair<String, Long>, Pair<Int, String>>()
    for (a in requireState().attempts) {
      for (e in a.entries) {
        val key = e.findingId to e.revision
        if (e.state == "done" || (latest[key]?.first ?: -1) <= a.attempt) {
          latest[key] = a.attempt to e.state
        }
      }
    }
    return latest.filterValues { it.second == "post_start" }.mapValues { it.value.first }
  }

  internal fun mappedOutcome(e: EntryRecord): Pair<String, String?> {
    var mapped = when (e.outcome) {
      "posted" -> "posted"
      "skipped-duplicate" -> "recovered"
      else -> if (e.postAttempted) "failed" else "blocked"
    }
    var error = e.error
    if (mapped in TERMINAL_OUTCOMES) {
      if (e.discussionId.isNullOrEmpty() || e.noteId == null || e.url.isNullOrEmpty()) {
        mapped = "blocked"
        error = "note published but receipt incomplete: discussion_id=${e.discussionId}, " +
          "note_id=${e.noteId}, url=${e.url} — ids must be recovered before ack"
      } else {
        error = null
      }
    }
    if (mapped !in TERMINAL_OUTCOMES && error.isNullOrEmpty()) {
      error = "entry ended ${e.outcome} without a recorded reason"
    }
    return mapped to error
  }

  fun attemptReceiptOutcomes(attempt: AttemptRecord): List<String> =
    attempt.entries.map { mappedOutcome(it).first }

  /**
   * Deterministic receipt envelope for one completed attempt: rebuilding from the same
   * journal state yields byte-identical canonical JSON, so a re-upload after a crash
   * can never fork the immutable (batch, attempt) receipt. Every receipt passes
   * [MrReviewProtocol.validateReceipt] before the envelope is returned.
   */
  fun buildReceiptEnvelope(attempt: AttemptRecord): JsonObject {
    val s = requireState()
    val completedAt = attempt.completedAt
      ?: throw IllegalStateException("attempt ${attempt.attempt} is not completed")
    val mrUrl = attempt.webUrl.ifBlank { s.gitlabUrl }
    val receipts = attempt.entries.map { receiptFor(s, it, mrUrl, attempt) }
    if (receipts.isEmpty()) {
      throw IllegalStateException("attempt ${attempt.attempt} of batch ${s.batchId} has no entries")
    }
    return buildJsonObject {
      put("schema_version", SCHEMA_VERSION)
      put("kind", ENVELOPE_KIND)
      put("repo", s.repo)
      put("gitlab_url", s.gitlabUrl)
      put("target_project_id", s.targetProjectId)
      put("iid", s.iid)
      put("batch_id", s.batchId)
      put("attempt", attempt.attempt)
      put("created_at", completedAt)
      putJsonArray("receipts") { receipts.forEach { add(it) } }
    }
  }

  private fun receiptFor(s: State, e: EntryRecord, mrUrl: String, attempt: AttemptRecord): JsonObject {
    val (outcome, error) = mappedOutcome(e)
    val terminal = outcome in TERMINAL_OUTCOMES
    val refs: JsonElement = if (e.postAttempted || terminal) {
      val diff = s.refs.getValue("diff_refs").jsonObject
      buildJsonObject {
        put("source_sha", s.refs.getValue("source_sha"))
        put("target_tip_sha", s.refs.getValue("target_tip_sha"))
        put("base_sha", diff.getValue("base_sha"))
        put("start_sha", diff.getValue("start_sha"))
        put("head_sha", diff.getValue("head_sha"))
      }
    } else {
      JsonNull
    }
    val receipt = buildJsonObject {
      put("schema_version", MrReviewProtocol.SCHEMA_VERSION)
      put("batch_id", s.batchId)
      put("finding_id", e.findingId)
      put("revision", e.revision)
      put("outcome", outcome)
      put("kind", e.kind)
      put("snapshot_id", s.identity.getValue("snapshot_id"))
      put("code_id", s.identity.getValue("code_id"))
      put("version_fingerprint", s.identity.getValue("version_fingerprint"))
      putOrNull("discussion_id", e.discussionId)
      if (e.noteId == null) put("note_id", JsonNull) else put("note_id", e.noteId)
      putOrNull("url", e.url)
      put("mr_url", mrUrl)
      put("submitted_refs", refs)
      put("fallback", false)
      put("attempted_at", e.startedAt.ifBlank { attempt.startedAt })
      putOrNull("finished_at", e.finishedAt)
      putOrNull("error", error)
    }
    return MrReviewProtocol.validateReceipt(receipt)
  }

  private fun persist() {
    atomicWrite(file, MrReviewProtocol.canonicalJson(toJson()))
  }

  private fun toJson(): JsonObject {
    val s = requireState()
    return buildJsonObject {
      put("schema_version", SCHEMA_VERSION)
      put("repo", s.repo)
      put("iid", s.iid)
      put("batch_id", s.batchId)
      put("gitlab_url", s.gitlabUrl)
      put("target_project_id", s.targetProjectId)
      put("identity", buildJsonObject { s.identity.forEach { (k, v) -> put(k, v) } })
      put("refs", s.refs)
      putJsonArray("attempts") { s.attempts.forEach { add(attemptJson(it)) } }
    }
  }

  private fun attemptJson(a: AttemptRecord): JsonObject = buildJsonObject {
    put("attempt", a.attempt)
    put("started_at", a.startedAt)
    putOrNull("completed_at", a.completedAt)
    put("web_url", a.webUrl)
    put("postbox_item_id", a.postboxItemId)
    put("postbox_path", a.postboxPath)
    putOrNull("receipt_uploaded_at", a.receiptUploadedAt)
    putOrNull("receipt_path", a.receiptPath)
    putOrNull("acked_at", a.ackedAt)
    putJsonArray("entries") { a.entries.forEach { add(entryJson(it)) } }
  }

  private fun entryJson(e: EntryRecord): JsonObject = buildJsonObject {
    put("finding_id", e.findingId)
    put("revision", e.revision)
    put("kind", e.kind)
    putOrNull("marker", e.marker)
    put("state", e.state)
    put("post_attempted", e.postAttempted)
    put("started_at", e.startedAt)
    putOrNull("finished_at", e.finishedAt)
    putOrNull("outcome", e.outcome)
    put("code", e.code)
    putOrNull("error", e.error)
    putOrNull("discussion_id", e.discussionId)
    if (e.noteId == null) put("note_id", JsonNull) else put("note_id", e.noteId)
    putOrNull("url", e.url)
    putOrNull("recovered_from", e.recoveredFrom)
  }

  companion object {
    const val SCHEMA_VERSION = 1
    const val ENVELOPE_KIND = "review-receipts"
    const val RECEIPT_DIR = "mr-review/receipts/"
    val TERMINAL_OUTCOMES: Set<String> = setOf("posted", "recovered")
    private val SAFE_REPO = Regex("[^A-Za-z0-9._-]")

    internal fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    fun sanitizeRepo(repo: String): String {
      val name = SAFE_REPO.replace(repo, "_")
      if (name.isEmpty() || name == "." || name == "..") {
        throw IllegalArgumentException("repo is not usable as a directory: $repo")
      }
      return name
    }

    fun journalDir(basePath: String, repo: String): File =
      File(basePath, ".localgitmirror/state/review-journal/${sanitizeRepo(repo)}")

    fun receiptArtifactPath(iid: Int, batchId: String, attempt: Int): String =
      "$RECEIPT_DIR/mr-!$iid-$batchId.attempt-$attempt.receipt.json"

    fun load(file: File): MrReviewJournal {
      val journal = MrReviewJournal(file, null)
      if (!file.isFile) return journal
      journal.state = try {
        parseState(file.readText(Charsets.UTF_8))
      } catch (e: Throwable) {
        null
      }
      return journal
    }

    fun open(dir: File, iid: Int, batchId: String, doc: MrReviewHandoff.Document,
             postboxItemId: String, postboxPath: String): MrReviewJournal {
      val file = File(dir, "mr-!$iid-$batchId.journal.json")
      val journal = load(file)
      journal.pendingItemId = postboxItemId
      journal.pendingPath = postboxPath
      if (journal.state == null) {
        journal.replacedCorrupt = file.isFile
        val batch = requireNotNull(doc.batch) { "v2 document carries a batch" }
        val refs = requireNotNull(doc.refs) { "v2 document carries refs" }
        journal.state = State(
          repo = batch.getValue("repo").jsonPrimitive.content,
          iid = iid,
          batchId = batchId,
          gitlabUrl = batch.getValue("gitlab_url").jsonPrimitive.content,
          targetProjectId = batch.getValue("target_project_id").jsonPrimitive.long,
          identity = doc.identity,
          refs = refs,
        )
      }
      return journal
    }

    private fun parseState(json: String): State? {
      val root = MrReviewProtocol.parse(json).jsonObject
      if (root["schema_version"]?.jsonPrimitive?.intOrNull != SCHEMA_VERSION) return null
      val attemptsRaw = root["attempts"]?.jsonArray ?: return null
      return State(
        repo = root.getValue("repo").jsonPrimitive.content,
        iid = root.getValue("iid").jsonPrimitive.int,
        batchId = root.getValue("batch_id").jsonPrimitive.content,
        gitlabUrl = root.getValue("gitlab_url").jsonPrimitive.content,
        targetProjectId = root.getValue("target_project_id").jsonPrimitive.long,
        identity = root.getValue("identity").jsonObject.let { o ->
          mapOf(
            "snapshot_id" to o.getValue("snapshot_id").jsonPrimitive.content,
            "code_id" to o.getValue("code_id").jsonPrimitive.content,
            "version_fingerprint" to o.getValue("version_fingerprint").jsonPrimitive.content,
          )
        },
        refs = root.getValue("refs").jsonObject,
        attempts = attemptsRaw.map { parseAttempt(it.jsonObject) }.toMutableList(),
      )
    }

    private fun parseAttempt(o: JsonObject): AttemptRecord {
      val attempt = AttemptRecord(
        attempt = o.getValue("attempt").jsonPrimitive.int,
        postboxItemId = o["postbox_item_id"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
        postboxPath = o["postbox_path"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
        startedAt = o["started_at"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
        completedAt = o["completed_at"]?.jsonPrimitive?.contentOrNullSafe(),
        webUrl = o["web_url"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
        receiptUploadedAt = o["receipt_uploaded_at"]?.jsonPrimitive?.contentOrNullSafe(),
        receiptPath = o["receipt_path"]?.jsonPrimitive?.contentOrNullSafe(),
        ackedAt = o["acked_at"]?.jsonPrimitive?.contentOrNullSafe(),
      )
      for (el in o["entries"]?.jsonArray ?: JsonArray(emptyList())) {
        attempt.entries.add(parseEntry(el.jsonObject))
      }
      return attempt
    }

    private fun parseEntry(o: JsonObject): EntryRecord {
      val rec = EntryRecord(
        findingId = o.getValue("finding_id").jsonPrimitive.content,
        revision = o.getValue("revision").jsonPrimitive.long,
        kind = o["kind"]?.jsonPrimitive?.contentOrNullSafe() ?: "general",
        marker = o["marker"]?.jsonPrimitive?.contentOrNullSafe(),
      )
      rec.state = o["state"]?.jsonPrimitive?.contentOrNullSafe() ?: "post_start"
      rec.postAttempted = o["post_attempted"]?.jsonPrimitive?.contentOrNullSafe()?.toBooleanStrictOrNull() == true
      rec.startedAt = o["started_at"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
      rec.finishedAt = o["finished_at"]?.jsonPrimitive?.contentOrNullSafe()
      rec.outcome = o["outcome"]?.jsonPrimitive?.contentOrNullSafe()
      rec.code = o["code"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
      rec.error = o["error"]?.jsonPrimitive?.contentOrNullSafe()
      rec.discussionId = o["discussion_id"]?.jsonPrimitive?.contentOrNullSafe()
      rec.noteId = o["note_id"]?.jsonPrimitive?.longOrNull
      rec.url = o["url"]?.jsonPrimitive?.contentOrNullSafe()
      rec.recoveredFrom = o["recovered_from"]?.jsonPrimitive?.contentOrNullSafe()
      return rec
    }

    private fun atomicWrite(file: File, text: String) {
      file.parentFile?.mkdirs()
      val temp = File(file.parentFile, ".${file.name}.${ProcessHandle.current().pid()}.${UUID.randomUUID()}.tmp")
      try {
        FileOutputStream(temp).use { fos ->
          fos.write(text.toByteArray(Charsets.UTF_8))
          fos.flush()
          fos.fd.sync()
        }
        try {
          Files.move(temp.toPath(), file.toPath(),
                     StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
          Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
      } finally {
        runCatching { if (temp.exists()) temp.delete() }
      }
    }
  }
}

private fun JsonObjectBuilder.putOrNull(key: String, value: String?) {
  if (value == null) put(key, JsonNull) else put(key, value)
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
  if (this is JsonNull) null else content

/**
 * WORK receipt handoff in strict order: persist journal -> upload the detailed
 * receipt -> ack the batch item. An upload failure means no ack; the batch item
 * is acked only when the receipt upload succeeded and every entry of the attempt
 * reached a terminal outcome (posted/recovered). Blocked or failed entries keep
 * the batch pending for a protected retry — journaled-posted entries are never
 * re-POSTed. Re-running after a crash between upload and ack acks without
 * re-uploading; a crash before the upload mark re-uploads byte-identical content.
 */
object MrReviewReceiptHandoff {

  class Report {
    val uploaded = mutableListOf<String>()
    val acked = mutableListOf<String>()
    val failed = mutableListOf<String>()
    val pending = mutableListOf<String>()
  }

  fun processPending(dir: File, dryRun: Boolean = false,
                     upload: (path: String, json: String) -> Boolean,
                     ack: (itemId: String) -> Boolean): Report {
    val report = Report()
    if (!dir.isDirectory) return report
    val files = dir.listFiles { _, name -> name.startsWith("mr-!") && name.endsWith(".journal.json") }
      ?.sortedBy { it.name } ?: emptyList()
    for (f in files) {
      val journal = MrReviewJournal.load(f)
      if (!journal.readable) {
        report.failed.add("${f.name}: journal is unreadable")
        continue
      }
      for (attempt in journal.attempts) {
        val where = "batch ${journal.batchId} attempt ${attempt.attempt}"
        if (attempt.completedAt == null) continue
        if (attempt.receiptUploadedAt == null) {
          val envelope = try {
            journal.buildReceiptEnvelope(attempt)
          } catch (e: Throwable) {
            report.failed.add("$where: receipt: ${e.message ?: "error"}")
            continue
          }
          val path = MrReviewJournal.receiptArtifactPath(journal.iid, journal.batchId, attempt.attempt)
          if (dryRun) {
            report.pending.add("upload $path")
            continue
          }
          if (!upload(path, MrReviewProtocol.canonicalJson(envelope))) {
            report.failed.add("$where: receipt upload failed")
            continue
          }
          journal.markReceiptUploaded(attempt, path)
          report.uploaded.add(path)
        }
        val outcomes = journal.attemptReceiptOutcomes(attempt)
        if (outcomes.isEmpty() || outcomes.any { it !in MrReviewJournal.TERMINAL_OUTCOMES }) continue
        if (attempt.ackedAt != null) continue
        if (attempt.postboxItemId.isEmpty()) {
          report.failed.add("$where: attempt carries no postbox item id")
          continue
        }
        if (dryRun) {
          report.pending.add("ack ${attempt.postboxItemId}")
          continue
        }
        if (!ack(attempt.postboxItemId)) {
          report.failed.add("$where: ack failed")
          continue
        }
        journal.markAcked(attempt)
        report.acked.add(attempt.postboxItemId)
      }
    }
    return report
  }
}
