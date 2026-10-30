package localgitmirror.idea.gitlab

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Cross-language parse -> normalize -> validate pipeline for MR review handoffs.
 * Python twin: lgm_core/mr_review_handoff.py — both consume the shared fixtures
 * in tests/fixtures/mr_review_handoff/ and must accept/reject identically.
 *
 * Two formats, routed by content: legacy Markdown replies (the shipped v1
 * grammar, frozen — [MrReplies.parse] delegates here) and v2 JSON batches
 * validated through [MrReviewProtocol]. JSON-looking content is never
 * interpreted as legacy Markdown; v2 anchored entries keep their side-aware
 * anchors and allow no general-note fallback (legacy-only behavior).
 *
 * The posted-revision guard against the HOME review store exists only in the
 * Python twin (the store is HOME-Python-owned); publication gates against
 * GitLab are a separate work-side concern and not part of this pipeline.
 */
object MrReviewHandoff {

  enum class Format { LEGACY, V2 }

  /** Document-level refusal; [code] is a protocol-registry code or "" for legacy grammar refusals. */
  class HandoffRejection(
    val code: String,
    message: String,
    val errors: List<String> = emptyList(),
  ) : Exception(message)

  data class Entry(
    val kind: String,
    val threadId: String?,
    val file: String,
    val line: Int,
    val resolve: Boolean,
    val body: String,
    val findingId: String?,
    val revision: Long,
    val anchor: JsonObject?,
    val marker: String?,
  )

  data class Document(
    val format: Format,
    val iid: Int,
    val branch: String,
    val batchId: String?,
    val entries: List<Entry>,
    val legacy: MrReplies.RepliesFile?,
    val batch: JsonObject?,
    val identity: Map<String, String>,
    val refs: JsonObject?,
    val generalFallback: Boolean,
  )

  private val headerRe = Regex("^#\\s+MR\\s+!(\\d+)")
  private val threadRe = Regex("^##\\s+thread\\s+(\\S+)\\s*$")
  private val anchoredRe = Regex("^##\\s+new\\s+(\\S+?):(\\d+)\\s*$")
  private val generalRe = Regex("^##\\s+new\\s*$")
  private val resolveRe = Regex("^resolve:\\s*(yes|no)\\s*$", RegexOption.IGNORE_CASE)

  fun looksLikeJson(content: String): Boolean = content.trimStart().startsWith("{")

  fun kindName(kind: MrReplies.Kind): String = when (kind) {
    MrReplies.Kind.THREAD -> "thread"
    MrReplies.Kind.NEW_ANCHORED -> "new_anchored"
    MrReplies.Kind.NEW_GENERAL -> "new_general"
  }

  // ── Legacy Markdown (shipped grammar, frozen) ─────────────────────────────

  fun parseLegacy(markdown: String): MrReplies.RepliesFile {
    var iid = 0
    var branch = ""
    val replies = mutableListOf<MrReplies.Reply>()
    val errors = mutableListOf<String>()

    var kind: MrReplies.Kind? = null
    var threadId = ""
    var file = ""
    var line = 0
    var resolve = false
    var resolveSeen = false
    val body = StringBuilder()
    var sectionNo = 0

    fun flush() {
      val k = kind ?: return
      sectionNo++
      val text = body.toString().trim()
      when {
        text.isEmpty() -> errors.add("section $sectionNo: empty body, skipped")
        k == MrReplies.Kind.THREAD && threadId.isBlank() -> errors.add("section $sectionNo: blank thread id, skipped")
        k == MrReplies.Kind.NEW_ANCHORED && (file.isBlank() || line <= 0) -> errors.add("section $sectionNo: bad file:line, skipped")
        else -> replies.add(MrReplies.Reply(k, threadId, file, line, resolve && k == MrReplies.Kind.THREAD, text))
      }
      kind = null
      threadId = ""
      file = ""
      line = 0
      resolve = false
      resolveSeen = false
      body.setLength(0)
    }

    for (raw in markdown.lines()) {
      val lineText = raw.trimEnd()
      headerRe.find(lineText)?.let { iid = it.groupValues[1].toIntOrNull() ?: iid }
      if (lineText.startsWith("- branch:")) {
        branch = lineText.substringAfter("- branch:").trim()
        continue
      }
      val threadM = threadRe.find(lineText)
      val anchoredM = anchoredRe.find(lineText)
      val generalM = generalRe.find(lineText)
      if (threadM != null || anchoredM != null || generalM != null) {
        flush()
        when {
          threadM != null -> {
            kind = MrReplies.Kind.THREAD
            threadId = threadM.groupValues[1]
          }
          anchoredM != null -> {
            kind = MrReplies.Kind.NEW_ANCHORED
            file = anchoredM.groupValues[1]
            line = anchoredM.groupValues[2].toIntOrNull() ?: 0
          }
          else -> kind = MrReplies.Kind.NEW_GENERAL
        }
        continue
      }
      if (kind == null) continue
      val resolveM = resolveRe.find(lineText)
      if (resolveM != null && !resolveSeen && body.toString().isBlank()) {
        resolve = resolveM.groupValues[1].equals("yes", ignoreCase = true)
        resolveSeen = true
        continue
      }
      body.appendLine(lineText)
    }
    flush()

    return MrReplies.RepliesFile(iid, branch, replies, errors)
  }

  // ── V2 JSON batches ────────────────────────────────────────────────────────

  fun parseV2(text: String, expectedIid: Int = 0, expectedBatchId: String = ""): Document {
    val value = try {
      MrReviewProtocol.parse(text)
    } catch (e: IllegalArgumentException) {
      throw MrReviewProtocolException("invalid-schema", "v2 handoff is not valid JSON: ${e.message}")
    }
    val batch = MrReviewProtocol.validateBatch(value)
    checkV2Conflicts(batch)
    val batchId = content(batch, "batch_id")
    val iid = content(batch, "iid").toInt()
    if (expectedIid != 0 && iid != expectedIid) {
      throw MrReviewProtocolException("invalid-schema", "batch document says MR !$iid but iid=$expectedIid")
    }
    if (expectedBatchId.isNotEmpty() && batchId != expectedBatchId) {
      throw MrReviewProtocolException(
        "invalid-schema",
        "batch document id $batchId does not match the artifact path id $expectedBatchId",
      )
    }
    val items = batch.getValue("items").jsonArray
    val entries = items.map { el ->
      val o = el.jsonObject
      val findingId = content(o, "finding_id")
      val revision = content(o, "revision").toLong()
      Entry(
        kind = content(o, "kind"),
        threadId = if (o.getValue("thread_id") is JsonNull) null else content(o, "thread_id"),
        file = "",
        line = 0,
        resolve = content(o, "resolve") == "true",
        body = content(o, "body"),
        findingId = findingId,
        revision = revision,
        anchor = o.getValue("anchor") as? JsonObject,
        marker = MrReviewProtocol.reviewMarker(batchId, findingId, revision),
      )
    }
    val first = items[0].jsonObject
    return Document(
      format = Format.V2,
      iid = iid,
      branch = "",
      batchId = batchId,
      entries = entries,
      legacy = null,
      batch = batch,
      identity = MrReviewProtocol.batchIdentity(batch),
      refs = buildJsonObject {
        put("source_sha", content(first, "source_sha"))
        put("target_tip_sha", content(first, "target_tip_sha"))
        put("diff_refs", first.getValue("diff_refs"))
      },
      generalFallback = false,
    )
  }

  private fun checkV2Conflicts(batch: JsonObject) {
    val items = batch.getValue("items").jsonArray
    val seen = LinkedHashMap<String, Long>()
    for (el in items) {
      val o = el.jsonObject
      val findingId = content(o, "finding_id")
      val revision = content(o, "revision").toLong()
      val prev = seen.put(findingId, revision)
      if (prev != null) {
        throw MrReviewProtocolException(
          "possible-duplicate",
          "finding $findingId appears twice in one submission (revisions $prev and $revision)",
        )
      }
    }
    val refs = refsOf(items[0].jsonObject)
    items.drop(1).forEachIndexed { idx, el ->
      if (refsOf(el.jsonObject) != refs) {
        throw MrReviewProtocolException(
          "invalid-schema",
          "batch.items[${idx + 1}]: all items must carry one snapshot's refs",
        )
      }
    }
  }

  private fun refsOf(item: JsonObject): Triple<String, String, String> = Triple(
    content(item, "source_sha"),
    content(item, "target_tip_sha"),
    MrReviewProtocol.canonicalJson(item.getValue("diff_refs")),
  )

  // ── Unified entry point ────────────────────────────────────────────────────

  fun parseAndValidate(
    content: String,
    expectedIid: Int = 0,
    expectedBatchId: String = "",
    want: String = "",
  ): Document {
    if (looksLikeJson(content)) {
      if (want == "legacy") {
        throw HandoffRejection(
          "invalid-schema",
          "JSON document where legacy Markdown replies are required; " +
            "unknown JSON is never parsed as legacy Markdown",
        )
      }
      try {
        return parseV2(content, expectedIid, expectedBatchId)
      } catch (e: MrReviewProtocolException) {
        throw HandoffRejection(e.code, e.message ?: "")
      }
    }
    if (want == "v2") {
      throw HandoffRejection(
        "invalid-schema",
        "a v2 review batch must be a JSON document (mr_review_findings " +
          "create_batch output); got legacy Markdown",
      )
    }
    val parsed = parseLegacy(content)
    if (expectedIid != 0 && parsed.iid != 0 && parsed.iid != expectedIid) {
      throw HandoffRejection("", "content header says MR !${parsed.iid} but iid=$expectedIid")
    }
    if (parsed.errors.isNotEmpty()) {
      throw HandoffRejection(
        "",
        "malformed reply sections: ${parsed.errors.joinToString("; ")}",
        parsed.errors,
      )
    }
    return Document(
      format = Format.LEGACY,
      iid = if (parsed.iid != 0) parsed.iid else expectedIid,
      branch = parsed.branch,
      batchId = null,
      entries = parsed.replies.map { r ->
        Entry(kindName(r.kind), r.threadId, r.file, r.line, r.resolve, r.body,
          findingId = null, revision = 0, anchor = null, marker = null)
      },
      legacy = parsed,
      batch = null,
      identity = emptyMap(),
      refs = null,
      generalFallback = true,
    )
  }

  private fun content(obj: JsonObject, key: String): String =
    obj.getValue(key).jsonPrimitive.content
}
