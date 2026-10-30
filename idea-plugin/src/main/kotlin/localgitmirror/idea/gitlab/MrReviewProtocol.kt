package localgitmirror.idea.gitlab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Snapshot-bound MR review protocol; normative spec docs/MR_REVIEW_PROTOCOL.md.
 * Python counterpart: lgm_core/mr_review_models.py. Canonical bytes, digests and
 * error codes must stay identical across both; shared fixtures live in
 * tests/fixtures/mr_review_protocol/.
 */
class MrReviewProtocolException(val code: String, message: String) : Exception("$code: $message")

data class ReviewMarker(val batchId: String, val findingId: String, val revision: Long)

object MrReviewProtocol {
  const val SCHEMA_VERSION = 1
  val SUPPORTED_SCHEMA_VERSIONS: Set<BigInteger> = setOf(BigInteger.ONE)

  const val BUDGET_BYTES_MIN = 4096
  const val BUDGET_BYTES_MAX = 48000
  const val CODE_ARTIFACT_MAX_BYTES = 128L * 1024 * 1024

  val ERROR_CODES: Set<String> = setOf(
    "snapshot-raced", "diff-not-ready", "review-object-missing",
    "review-package-too-large", "stale-snapshot", "possible-duplicate",
    "incomplete-checkout", "invalid-schema", "unknown-schema-version",
    "invalid-side", "invalid-line", "hash-mismatch", "invalid-marker",
    "invalid-budget", "revision-conflict", "store-locked",
  )

  val COMPARISON_KINDS = setOf("gitlab-diff")
  val MR_STATES = setOf("opened", "closed", "locked", "merged")
  val SIDES = setOf("old", "new")
  val VERIFICATION_STATES = setOf("hypothesis", "confirmed", "refuted")
  val APPLICABILITY_STATES = setOf("current", "needs-recheck", "withdrawn", "fixed")
  val DELIVERY_STATES = setOf("local", "queued", "received", "posted", "failed", "blocked")
  val BATCH_ITEM_KINDS = setOf("anchored", "thread", "general")
  val RECEIPT_OUTCOMES = setOf("posted", "failed", "blocked", "recovered")

  val SNAPSHOT_FIELDS = setOf(
    "schema_version", "snapshot_id", "code_id", "version_fingerprint",
    "repo", "gitlab_url", "source_project_id", "target_project_id", "iid",
    "mr_url", "title", "description", "state", "draft",
    "source_branch", "target_branch", "source_sha", "target_tip_sha",
    "source_fetch_ref", "gitlab_diff_refs", "computed_merge_bases",
    "comparison_base_sha", "comparison_kind",
    "observed_started_at", "observed_finished_at", "mr_updated_at",
    "discussions_fetched_at", "discussions_complete", "limitations",
    "discussions",
  )
  val DIFF_REFS_FIELDS = setOf("base_sha", "start_sha", "head_sha")
  val DISCUSSION_FIELDS = setOf("discussion_id", "notes")
  val NOTE_FIELDS = setOf(
    "note_id", "body", "author", "created_at",
    "system", "resolvable", "resolved", "position",
  )
  val POSITION_FIELDS = setOf(
    "old_path", "new_path", "old_line", "new_line", "side",
    "base_sha", "start_sha", "head_sha",
  )
  val ANCHOR_FIELDS = setOf("old_path", "new_path", "side", "line")
  val FINDING_FIELDS = setOf(
    "schema_version", "finding_id", "repo", "gitlab_url",
    "target_project_id", "iid", "revisions",
  )
  val REVISION_FIELDS = setOf(
    "revision", "created_at", "snapshot_id", "code_id", "version_fingerprint",
    "anchor", "thread_id", "comment", "trigger", "evidence", "limitations",
    "verification", "applicability", "delivery",
  )
  val BATCH_FIELDS = setOf(
    "schema_version", "batch_id", "repo", "gitlab_url",
    "target_project_id", "iid", "created_at", "items",
  )
  val BATCH_ITEM_FIELDS = setOf(
    "finding_id", "revision", "kind", "thread_id", "resolve", "body", "anchor",
    "snapshot_id", "code_id", "version_fingerprint",
    "source_sha", "target_tip_sha", "diff_refs",
  )
  val RECEIPT_FIELDS = setOf(
    "schema_version", "batch_id", "finding_id", "revision", "outcome", "kind",
    "snapshot_id", "code_id", "version_fingerprint",
    "discussion_id", "note_id", "url", "mr_url", "submitted_refs",
    "fallback", "attempted_at", "finished_at", "error",
  )
  val SUBMITTED_REFS_FIELDS = setOf(
    "source_sha", "target_tip_sha", "base_sha", "start_sha", "head_sha",
  )
  val ENVELOPE_FIELDS = setOf(
    "schema_version", "snapshot_id", "code_id", "code", "snapshot",
    "prepared_at", "limitations",
  )
  val ENVELOPE_CODE_FIELDS = setOf("bundle_sha256", "bundle_size")

  private val IDENTITY_KEYS = listOf("snapshot_id", "code_id", "version_fingerprint")
  private val SHA_RE = Regex("[0-9a-f]{40}")
  private val HEX64_RE = Regex("[0-9a-f]{64}")
  private val UUID_RE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
  private val ISO_RE = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})")
  private val KEY_RE = Regex("[A-Za-z0-9_]+")
  private val INT_RE = Regex("-?(0|[1-9][0-9]*)")
  private val MARKER_RE = Regex("lgm-review:(${UUID_RE.pattern}):(${UUID_RE.pattern}):([0-9]+)")

  fun parse(json: String): JsonElement = Json.parseToJsonElement(json)

  private fun err(code: String, message: String): MrReviewProtocolException {
    check(code in ERROR_CODES) { "error code outside the registry: $code" }
    return MrReviewProtocolException(code, message)
  }

  // ── Canonical JSON ─────────────────────────────────────────────────────────

  /**
   * Canonical encoding: UTF-8, sorted ASCII object keys, compact separators,
   * no floats, non-ASCII literal (never \uXXXX), RFC 8259 minimal escaping.
   * Byte-identical to lgm_core.mr_review_models.canonical_json.
   */
  fun canonicalJson(value: JsonElement): String {
    val out = StringBuilder()
    writeCanonical(value, out)
    return out.toString()
  }

  private fun writeCanonical(value: JsonElement, out: StringBuilder) {
    when (value) {
      is JsonNull -> out.append("null")
      is JsonObject -> {
        out.append('{')
        var first = true
        for (key in value.keys.sorted()) {
          if (!KEY_RE.matches(key)) {
            throw err("invalid-schema", "object keys must be ASCII [A-Za-z0-9_]: $key")
          }
          if (!first) out.append(',')
          first = false
          escapeString(key, out)
          out.append(':')
          writeCanonical(value.getValue(key), out)
        }
        out.append('}')
      }
      is JsonArray -> {
        out.append('[')
        var first = true
        for (item in value) {
          if (!first) out.append(',')
          first = false
          writeCanonical(item, out)
        }
        out.append(']')
      }
      is JsonPrimitive -> {
        if (value.isString) {
          escapeString(value.content, out)
        } else when (value.content) {
          "true" -> out.append("true")
          "false" -> out.append("false")
          else -> {
            if (!INT_RE.matches(value.content)) {
              throw err("invalid-schema", "floating-point numbers are forbidden in canonical payloads")
            }
            out.append(BigInteger(value.content).toString())
          }
        }
      }
    }
  }

  private fun escapeString(text: String, out: StringBuilder) {
    out.append('"')
    for (ch in text) {
      when (ch) {
        '"' -> out.append("\\\"")
        '\\' -> out.append("\\\\")
        '\b' -> out.append("\\b")
        '\u000C' -> out.append("\\f")
        '\n' -> out.append("\\n")
        '\r' -> out.append("\\r")
        '\t' -> out.append("\\t")
        else ->
          if (ch.code < 0x20) {
            out.append("\\u")
            val hex = Integer.toHexString(ch.code)
            repeat(4 - hex.length) { out.append('0') }
            out.append(hex)
          } else {
            out.append(ch)
          }
      }
    }
    out.append('"')
  }

  fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val out = StringBuilder(64)
    for (b in digest) {
      val hex = Integer.toHexString(b.toInt() and 0xFF)
      if (hex.length == 1) out.append('0')
      out.append(hex)
    }
    return out.toString()
  }

  // ── Digests ────────────────────────────────────────────────────────────────

  /** SHA-256 over the canonical payload with the snapshot_id key removed. */
  fun computeSnapshotId(snapshot: JsonObject): String =
    sha256Hex(canonicalJson(JsonObject(snapshot.filterKeys { it != "snapshot_id" })))

  /** SHA-256 over the sorted unique labeled tips; never dates or discussions. */
  fun computeCodeId(snapshot: JsonObject): String {
    val refs = asObject(snapshot.getValue("gitlab_diff_refs"), "snapshot.gitlab_diff_refs")
    val tips = listOf(
      listOf("diff_base", primitiveContent(refs, "base_sha", "snapshot.gitlab_diff_refs")),
      listOf("diff_head", primitiveContent(refs, "head_sha", "snapshot.gitlab_diff_refs")),
      listOf("diff_start", primitiveContent(refs, "start_sha", "snapshot.gitlab_diff_refs")),
      listOf("source", primitiveContent(snapshot, "source_sha", "snapshot")),
      listOf("target_tip", primitiveContent(snapshot, "target_tip_sha", "snapshot")),
    ).distinct().sortedWith(compareBy({ it[0] }, { it[1] }))
    val payload = JsonArray(tips.map { pair -> JsonArray(pair.map { JsonPrimitive(it) }) })
    return sha256Hex(canonicalJson(payload))
  }

  /** SHA-256 over GitLab identity, source SHA, target tip/branch/project and all diff refs. */
  fun computeVersionFingerprint(snapshot: JsonObject): String {
    val refs = asObject(snapshot.getValue("gitlab_diff_refs"), "snapshot.gitlab_diff_refs")
    val payload = buildJsonObject {
      put("diff_refs", buildJsonObject {
        put("base_sha", refs.getValue("base_sha"))
        put("head_sha", refs.getValue("head_sha"))
        put("start_sha", refs.getValue("start_sha"))
      })
      put("gitlab_url", snapshot.getValue("gitlab_url"))
      put("iid", snapshot.getValue("iid"))
      put("source_project_id", snapshot.getValue("source_project_id"))
      put("source_sha", snapshot.getValue("source_sha"))
      put("target_branch", snapshot.getValue("target_branch"))
      put("target_project_id", snapshot.getValue("target_project_id"))
      put("target_tip_sha", snapshot.getValue("target_tip_sha"))
    }
    return sha256Hex(canonicalJson(payload))
  }

  fun snapshotDigests(snapshot: JsonObject): Map<String, String> = linkedMapOf(
    "snapshot_id" to computeSnapshotId(snapshot),
    "code_id" to computeCodeId(snapshot),
    "version_fingerprint" to computeVersionFingerprint(snapshot),
  )

  private fun primitiveContent(obj: JsonObject, key: String, field: String): String {
    val v = obj.getValue(key)
    if (v !is JsonPrimitive || !v.isString) {
      throw err("invalid-schema", "$field.$key must be a string")
    }
    return v.content
  }

  // ── Strict field primitives ────────────────────────────────────────────────

  private fun asObject(value: JsonElement, field: String): JsonObject {
    if (value !is JsonObject) throw err("invalid-schema", "$field must be a JSON object")
    return value
  }

  private fun asArray(value: JsonElement, field: String): JsonArray {
    if (value !is JsonArray) throw err("invalid-schema", "$field must be an array")
    return value
  }

  private fun exactKeys(obj: JsonObject, allowed: Set<String>, field: String) {
    val missing = allowed - obj.keys
    val unknown = obj.keys - allowed
    if (missing.isNotEmpty()) {
      throw err("invalid-schema", "$field: missing keys ${missing.sorted()}")
    }
    if (unknown.isNotEmpty()) {
      throw err("invalid-schema", "$field: unknown keys ${unknown.sorted()}")
    }
  }

  private fun intOrNull(value: JsonElement): BigInteger? {
    if (value !is JsonPrimitive || value.isString || !INT_RE.matches(value.content)) return null
    return value.content.toBigIntegerOrNull()
  }

  private fun checkSchemaVersion(obj: JsonObject, field: String): BigInteger {
    val n = intOrNull(obj.getValue("schema_version"))
      ?: throw err("invalid-schema", "$field.schema_version must be an integer")
    if (n !in SUPPORTED_SCHEMA_VERSIONS) {
      throw err("unknown-schema-version", "$field.schema_version $n is not in ${SUPPORTED_SCHEMA_VERSIONS.sorted()}")
    }
    return n
  }

  private fun JsonObject.reqStr(key: String, field: String): String {
    val v = getValue(key)
    if (v !is JsonPrimitive || !v.isString || v.content.isEmpty()) {
      throw err("invalid-schema", "$field.$key must be a non-empty string")
    }
    return v.content
  }

  private fun JsonObject.reqStrOrNull(key: String, field: String, allowEmpty: Boolean = false): String? {
    val v = getValue(key)
    if (v is JsonNull) return null
    if (v !is JsonPrimitive || !v.isString || (!allowEmpty && v.content.isEmpty())) {
      throw err("invalid-schema", "$field.$key must be null or a non-empty string")
    }
    return v.content
  }

  private fun JsonObject.reqInt(key: String, field: String, minimum: BigInteger? = null): BigInteger {
    val n = intOrNull(getValue(key))
      ?: throw err("invalid-schema", "$field.$key must be an integer")
    if (minimum != null && n < minimum) {
      throw err("invalid-schema", "$field.$key must be >= $minimum")
    }
    return n
  }

  private fun JsonObject.reqLine(key: String, field: String): BigInteger? {
    val v = getValue(key)
    if (v is JsonNull) return null
    val n = intOrNull(v)
      ?: throw err("invalid-schema", "$field.$key must be null or an integer")
    if (n < BigInteger.ONE) {
      throw err("invalid-line", "$field.$key must be null or >= 1, got $n")
    }
    return n
  }

  private fun JsonObject.reqBool(key: String, field: String): Boolean {
    val v = getValue(key)
    if (v !is JsonPrimitive || v.isString || (v.content != "true" && v.content != "false")) {
      throw err("invalid-schema", "$field.$key must be a boolean")
    }
    return v.content == "true"
  }

  private fun JsonObject.reqPattern(key: String, field: String, regex: Regex, what: String): String {
    val value = reqStr(key, field)
    if (!regex.matches(value)) {
      throw err("invalid-schema", "$field.$key must be $what, got $value")
    }
    return value
  }

  private fun JsonObject.reqSha(key: String, field: String): String =
    reqPattern(key, field, SHA_RE, "a 40-char lowercase hex git SHA")

  private fun JsonObject.reqHex64(key: String, field: String): String =
    reqPattern(key, field, HEX64_RE, "a 64-char lowercase hex SHA-256")

  private fun JsonObject.reqUuid(key: String, field: String): String =
    reqPattern(key, field, UUID_RE, "a lowercase canonical UUID")

  private fun JsonObject.reqIso(key: String, field: String): String =
    reqPattern(key, field, ISO_RE, "an ISO-8601 timestamp")

  private fun JsonObject.reqEnum(key: String, field: String, allowed: Set<String>, code: String = "invalid-schema"): String {
    val v = getValue(key)
    if (v !is JsonPrimitive || !v.isString || v.content !in allowed) {
      val got = if (v is JsonPrimitive) v.content else v.toString()
      throw err(code, "$field.$key must be one of ${allowed.sorted()}, got $got")
    }
    return v.content
  }

  private fun JsonObject.reqUrl(key: String, field: String): String {
    val value = reqStr(key, field)
    if (!(value.startsWith("http://") || value.startsWith("https://")) ||
      value.endsWith("/") || value.any { it.isWhitespace() }
    ) {
      throw err("invalid-schema", "$field.$key must be a normalized http(s) URL without trailing slash, got $value")
    }
    return value
  }

  private fun JsonObject.reqStrList(key: String, field: String): List<String> =
    asArray(getValue(key), "$field.$key").map {
      if (it !is JsonPrimitive || !it.isString) {
        throw err("invalid-schema", "$field.$key must be an array of strings")
      }
      it.content
    }

  private fun JsonObject.reqShaList(key: String, field: String): List<String> =
    reqStrList(key, field).onEach {
      if (!SHA_RE.matches(it)) {
        throw err("invalid-schema", "$field.$key must be an array of 40-char hex git SHAs")
      }
    }

  // ── Snapshot ───────────────────────────────────────────────────────────────

  /** Empty/async GitLab diff refs are diff-not-ready, not a schema error. */
  fun validateDiffRefs(value: JsonElement, field: String): JsonObject {
    if (value is JsonNull) {
      throw err("diff-not-ready", "$field is null: GitLab diff refs are not computed yet")
    }
    val refs = asObject(value, field)
    exactKeys(refs, DIFF_REFS_FIELDS, field)
    for (key in DIFF_REFS_FIELDS.sorted()) {
      val v = refs.getValue(key)
      if (v !is JsonPrimitive || !v.isString) {
        throw err("invalid-schema", "$field.$key must be a string")
      }
      if (v.content.isEmpty()) {
        throw err("diff-not-ready", "$field.$key is empty: GitLab diff refs are not computed yet")
      }
    }
    for (key in DIFF_REFS_FIELDS.sorted()) {
      if (!SHA_RE.matches(refs.reqStr(key, field))) {
        throw err("invalid-schema", "$field.$key must be a 40-char lowercase hex git SHA")
      }
    }
    return refs
  }

  fun validatePosition(value: JsonElement, field: String): JsonObject? {
    if (value is JsonNull) return null
    val pos = asObject(value, field)
    exactKeys(pos, POSITION_FIELDS, field)
    val side = pos.reqEnum("side", field, SIDES, code = "invalid-side")
    val oldPath = pos.reqStrOrNull("old_path", field)
    val newPath = pos.reqStrOrNull("new_path", field)
    val oldLine = pos.reqLine("old_line", field)
    val newLine = pos.reqLine("new_line", field)
    if (oldPath == null && newPath == null) {
      throw err("invalid-schema", "$field: old_path and new_path cannot both be null")
    }
    if (side == "old" && (oldPath == null || oldLine == null)) {
      throw err(
        if (oldPath != null) "invalid-line" else "invalid-schema",
        "$field: side=old requires old_path and old_line",
      )
    }
    if (side == "new" && (newPath == null || newLine == null)) {
      throw err(
        if (newPath != null) "invalid-line" else "invalid-schema",
        "$field: side=new requires new_path and new_line",
      )
    }
    for (key in DIFF_REFS_FIELDS.sorted()) {
      pos.reqSha(key, field)
    }
    return pos
  }

  fun validateNote(value: JsonElement, field: String): JsonObject {
    val note = asObject(value, field)
    exactKeys(note, NOTE_FIELDS, field)
    note.reqInt("note_id", field, BigInteger.ONE)
    val body = note.getValue("body")
    if (body !is JsonPrimitive || !body.isString) {
      throw err("invalid-schema", "$field.body must be a string")
    }
    note.reqStr("author", field)
    note.reqIso("created_at", field)
    for (key in listOf("system", "resolvable", "resolved")) {
      note.reqBool(key, field)
    }
    validatePosition(note.getValue("position"), "$field.position")
    return note
  }

  fun validateDiscussion(value: JsonElement, field: String): JsonObject {
    val disc = asObject(value, field)
    exactKeys(disc, DISCUSSION_FIELDS, field)
    disc.reqStr("discussion_id", field)
    val notes = asArray(disc.getValue("notes"), "$field.notes")
    notes.forEachIndexed { i, note -> validateNote(note, "$field.notes[$i]") }
    return disc
  }

  /** Structural validation only; digests are checked by [verifySnapshot]. */
  fun validateSnapshot(value: JsonElement): JsonObject {
    val snap = asObject(value, "snapshot")
    exactKeys(snap, SNAPSHOT_FIELDS, "snapshot")
    checkSchemaVersion(snap, "snapshot")
    for (key in IDENTITY_KEYS) {
      snap.reqHex64(key, "snapshot")
    }
    snap.reqStr("repo", "snapshot")
    snap.reqUrl("gitlab_url", "snapshot")
    val sourceProject = snap.reqInt("source_project_id", "snapshot", BigInteger.ONE)
    val targetProject = snap.reqInt("target_project_id", "snapshot", BigInteger.ONE)
    val iid = snap.reqInt("iid", "snapshot", BigInteger.ONE)
    snap.reqUrl("mr_url", "snapshot")
    snap.reqStr("title", "snapshot")
    snap.reqStrOrNull("description", "snapshot", allowEmpty = true)
    snap.reqEnum("state", "snapshot", MR_STATES)
    snap.reqBool("draft", "snapshot")
    snap.reqStr("source_branch", "snapshot")
    snap.reqStr("target_branch", "snapshot")
    snap.reqSha("source_sha", "snapshot")
    snap.reqSha("target_tip_sha", "snapshot")

    val fetchRef = snap.reqStrOrNull("source_fetch_ref", "snapshot")
    val expectedRef = "refs/merge-requests/$iid/head"
    if (sourceProject != targetProject) {
      if (fetchRef != expectedRef) {
        throw err("invalid-schema", "snapshot.source_fetch_ref must be $expectedRef for a fork MR, got $fetchRef")
      }
    } else if (fetchRef != null) {
      throw err("invalid-schema", "snapshot.source_fetch_ref must be null when source and target projects match")
    }

    val refs = validateDiffRefs(snap.getValue("gitlab_diff_refs"), "snapshot.gitlab_diff_refs")
    snap.reqShaList("computed_merge_bases", "snapshot")
    val comparisonBase = snap.reqSha("comparison_base_sha", "snapshot")
    snap.reqEnum("comparison_kind", "snapshot", COMPARISON_KINDS)
    if (comparisonBase != primitiveContent(refs, "base_sha", "snapshot.gitlab_diff_refs")) {
      throw err(
        "invalid-schema",
        "snapshot.comparison_base_sha must equal gitlab_diff_refs.base_sha for comparison_kind=gitlab-diff",
      )
    }
    for (key in listOf("observed_started_at", "observed_finished_at", "mr_updated_at", "discussions_fetched_at")) {
      snap.reqIso(key, "snapshot")
    }
    snap.reqBool("discussions_complete", "snapshot")
    snap.reqStrList("limitations", "snapshot")
    val discussions = asArray(snap.getValue("discussions"), "snapshot.discussions")
    discussions.forEachIndexed { i, disc -> validateDiscussion(disc, "snapshot.discussions[$i]") }
    return snap
  }

  /** Validate structure, then reject hash-mismatch before any persistence. */
  fun verifySnapshot(value: JsonElement): JsonObject {
    val snap = validateSnapshot(value)
    for ((key, digest) in snapshotDigests(snap)) {
      if (primitiveContent(snap, key, "snapshot") != digest) {
        throw err("hash-mismatch", "snapshot.$key does not match the recomputed digest")
      }
    }
    return snap
  }

  // ── Transfer envelope ─────────────────────────────────────────────────────

  /**
   * Envelope stored at mr-review/snapshots/<snapshot_id>.json: the validated
   * snapshot plus the code bundle digest/size (the closed snapshot schema has
   * no bundle digest field). Byte-identical to Python
   * build_transfer_envelope for the same inputs.
   */
  fun buildTransferEnvelope(
    snapshot: JsonObject,
    bundleSha256: String,
    bundleSize: Long,
    preparedAt: String,
    limitations: List<String>,
  ): JsonObject {
    verifySnapshot(snapshot)
    if (!HEX64_RE.matches(bundleSha256)) {
      throw err("invalid-schema", "bundle_sha256 must be a 64-char lowercase hex SHA-256, got $bundleSha256")
    }
    if (bundleSize < 1) {
      throw err("invalid-schema", "bundle_size must be an integer >= 1, got $bundleSize")
    }
    if (!ISO_RE.matches(preparedAt)) {
      throw err("invalid-schema", "prepared_at must be an ISO-8601 timestamp, got $preparedAt")
    }
    return buildJsonObject {
      put("schema_version", 1)
      put("snapshot_id", snapshot.getValue("snapshot_id"))
      put("code_id", snapshot.getValue("code_id"))
      put("code", buildJsonObject {
        put("bundle_sha256", bundleSha256)
        put("bundle_size", bundleSize)
      })
      put("snapshot", snapshot)
      put("prepared_at", preparedAt)
      put("limitations", JsonArray(limitations.map { JsonPrimitive(it) }))
    }
  }

  /** Validate the envelope and its digest chain (snapshot digests plus the identity binding) before any use. */
  fun verifyTransferEnvelope(value: JsonElement): JsonObject {
    val env = asObject(value, "envelope")
    exactKeys(env, ENVELOPE_FIELDS, "envelope")
    checkSchemaVersion(env, "envelope")
    env.reqHex64("snapshot_id", "envelope")
    env.reqHex64("code_id", "envelope")
    val code = asObject(env.getValue("code"), "envelope.code")
    exactKeys(code, ENVELOPE_CODE_FIELDS, "envelope.code")
    code.reqHex64("bundle_sha256", "envelope.code")
    code.reqInt("bundle_size", "envelope.code", BigInteger.ONE)
    val snap = verifySnapshot(env.getValue("snapshot"))
    if (primitiveContent(env, "snapshot_id", "envelope") != primitiveContent(snap, "snapshot_id", "snapshot") ||
      primitiveContent(env, "code_id", "envelope") != primitiveContent(snap, "code_id", "snapshot")
    ) {
      throw err("invalid-schema", "envelope identity does not match the snapshot")
    }
    env.reqIso("prepared_at", "envelope")
    env.reqStrList("limitations", "envelope")
    return env
  }

  // ── Findings ───────────────────────────────────────────────────────────────

  fun validateAnchor(value: JsonElement, field: String): JsonObject? {
    if (value is JsonNull) return null
    val anchor = asObject(value, field)
    exactKeys(anchor, ANCHOR_FIELDS, field)
    val side = anchor.reqEnum("side", field, SIDES, code = "invalid-side")
    anchor.reqLine("line", field)
    val oldPath = anchor.reqStrOrNull("old_path", field)
    val newPath = anchor.reqStrOrNull("new_path", field)
    if (oldPath == null && newPath == null) {
      throw err("invalid-schema", "$field: old_path and new_path cannot both be null")
    }
    if (side == "old" && oldPath == null) {
      throw err("invalid-schema", "$field: side=old requires old_path")
    }
    if (side == "new" && newPath == null) {
      throw err("invalid-schema", "$field: side=new requires new_path")
    }
    return anchor
  }

  fun validateFindingRevision(value: JsonElement, field: String, expectedRevision: Int): JsonObject {
    val rev = asObject(value, field)
    exactKeys(rev, REVISION_FIELDS, field)
    val revision = rev.reqInt("revision", field, BigInteger.ONE)
    if (revision != BigInteger.valueOf(expectedRevision.toLong())) {
      throw err("invalid-schema", "$field.revision must be $expectedRevision (revisions are contiguous from 1)")
    }
    rev.reqIso("created_at", field)
    for (key in IDENTITY_KEYS) {
      rev.reqHex64(key, field)
    }
    validateAnchor(rev.getValue("anchor"), "$field.anchor")
    val threadId = rev.reqStrOrNull("thread_id", field)
    if (rev.getValue("anchor") !is JsonNull && threadId != null) {
      throw err("invalid-schema", "$field: anchor and thread_id are mutually exclusive")
    }
    rev.reqStr("comment", field)
    val trigger = rev.getValue("trigger")
    if (trigger !is JsonPrimitive || !trigger.isString) {
      throw err("invalid-schema", "$field.trigger must be a string")
    }
    rev.reqStrList("evidence", field)
    rev.reqStrList("limitations", field)
    rev.reqEnum("verification", field, VERIFICATION_STATES)
    val applicability = rev.reqEnum("applicability", field, APPLICABILITY_STATES)
    rev.reqEnum("delivery", field, DELIVERY_STATES)
    if (applicability == "fixed" && rev.reqStrList("evidence", field).isEmpty()) {
      throw err("invalid-schema", "$field: applicability=fixed requires evidence")
    }
    return rev
  }

  fun validateFinding(value: JsonElement): JsonObject {
    val finding = asObject(value, "finding")
    exactKeys(finding, FINDING_FIELDS, "finding")
    checkSchemaVersion(finding, "finding")
    finding.reqUuid("finding_id", "finding")
    finding.reqStr("repo", "finding")
    finding.reqUrl("gitlab_url", "finding")
    finding.reqInt("target_project_id", "finding", BigInteger.ONE)
    finding.reqInt("iid", "finding", BigInteger.ONE)
    val revisions = asArray(finding.getValue("revisions"), "finding.revisions")
    if (revisions.isEmpty()) {
      throw err("invalid-schema", "finding.revisions must be a non-empty array")
    }
    revisions.forEachIndexed { i, rev -> validateFindingRevision(rev, "finding.revisions[$i]", i + 1) }
    return finding
  }

  fun findingLatestRevision(finding: JsonObject): JsonObject =
    asObject(asArray(finding.getValue("revisions"), "finding.revisions").last(), "finding.revisions[-1]")

  /**
   * True when the latest checked revision no longer matches the snapshot's
   * serialized identity (snapshot_id, code_id or version_fingerprint).
   */
  fun findingStaleAgainst(finding: JsonObject, snapshot: JsonObject): Boolean =
    IDENTITY_KEYS.any { primitiveContent(findingLatestRevision(finding), it, "finding") != primitiveContent(snapshot, it, "snapshot") }

  // ── V2 publication batch ───────────────────────────────────────────────────

  fun validateBatchItem(value: JsonElement, field: String): JsonObject {
    val item = asObject(value, field)
    exactKeys(item, BATCH_ITEM_FIELDS, field)
    item.reqUuid("finding_id", field)
    item.reqInt("revision", field, BigInteger.ONE)
    val kind = item.reqEnum("kind", field, BATCH_ITEM_KINDS)
    val threadId = item.reqStrOrNull("thread_id", field)
    val resolve = item.reqBool("resolve", field)
    val anchor = validateAnchor(item.getValue("anchor"), "$field.anchor")
    if (kind == "anchored" && (anchor == null || threadId != null)) {
      throw err("invalid-schema", "$field: kind=anchored requires anchor and null thread_id")
    }
    if (kind == "thread" && (anchor != null || threadId == null)) {
      throw err("invalid-schema", "$field: kind=thread requires thread_id and null anchor")
    }
    if (kind == "general" && (anchor != null || threadId != null)) {
      throw err("invalid-schema", "$field: kind=general requires null anchor and thread_id")
    }
    if (resolve && kind != "thread") {
      throw err("invalid-schema", "$field: resolve=true is only valid for kind=thread")
    }
    item.reqStr("body", field)
    for (key in IDENTITY_KEYS) {
      item.reqHex64(key, field)
    }
    item.reqSha("source_sha", field)
    item.reqSha("target_tip_sha", field)
    validateDiffRefs(item.getValue("diff_refs"), "$field.diff_refs")
    return item
  }

  fun validateBatch(value: JsonElement): JsonObject {
    val batch = asObject(value, "batch")
    exactKeys(batch, BATCH_FIELDS, "batch")
    checkSchemaVersion(batch, "batch")
    batch.reqUuid("batch_id", "batch")
    batch.reqStr("repo", "batch")
    batch.reqUrl("gitlab_url", "batch")
    batch.reqInt("target_project_id", "batch", BigInteger.ONE)
    batch.reqInt("iid", "batch", BigInteger.ONE)
    batch.reqIso("created_at", "batch")
    val items = asArray(batch.getValue("items"), "batch.items")
    if (items.isEmpty()) {
      throw err("invalid-schema", "batch.items must be a non-empty array")
    }
    val seen = mutableSetOf<Pair<String, BigInteger>>()
    var identity: Triple<String, String, String>? = null
    items.forEachIndexed { i, element ->
      val item = validateBatchItem(element, "batch.items[$i]")
      val key = item.reqStr("finding_id", "batch") to item.reqInt("revision", "batch")
      if (!seen.add(key)) {
        throw err("invalid-schema", "batch.items[$i]: duplicate finding revision $key")
      }
      val triple = Triple(
        primitiveContent(item, "snapshot_id", "batch.items[$i]"),
        primitiveContent(item, "code_id", "batch.items[$i]"),
        primitiveContent(item, "version_fingerprint", "batch.items[$i]"),
      )
      if (identity == null) {
        identity = triple
      } else if (identity != triple) {
        throw err("invalid-schema", "batch.items[$i]: all items must reference one snapshot identity")
      }
    }
    return batch
  }

  fun batchIdentity(batch: JsonObject): Map<String, String> {
    val item = asObject(asArray(batch.getValue("items"), "batch.items").first(), "batch.items[0]")
    return IDENTITY_KEYS.associateWith { primitiveContent(item, it, "batch.items[0]") }
  }

  // ── Receipts ───────────────────────────────────────────────────────────────

  fun receiptNoteUrl(mrUrl: String, noteId: Any): String = "$mrUrl#note_$noteId"

  fun validateReceipt(value: JsonElement): JsonObject {
    val receipt = asObject(value, "receipt")
    exactKeys(receipt, RECEIPT_FIELDS, "receipt")
    checkSchemaVersion(receipt, "receipt")
    receipt.reqUuid("batch_id", "receipt")
    receipt.reqUuid("finding_id", "receipt")
    receipt.reqInt("revision", "receipt", BigInteger.ONE)
    val outcome = receipt.reqEnum("outcome", "receipt", RECEIPT_OUTCOMES)
    receipt.reqEnum("kind", "receipt", BATCH_ITEM_KINDS)
    for (key in IDENTITY_KEYS) {
      receipt.reqHex64(key, "receipt")
    }
    val discussionId = receipt.reqStrOrNull("discussion_id", "receipt")
    val noteIdEl = receipt.getValue("note_id")
    val noteId: BigInteger? = if (noteIdEl is JsonNull) {
      null
    } else {
      val n = intOrNull(noteIdEl)
      if (n == null || n < BigInteger.ONE) {
        throw err("invalid-schema", "receipt.note_id must be null or an integer >= 1")
      }
      n
    }
    val url = receipt.reqStrOrNull("url", "receipt")
    val mrUrl = receipt.reqUrl("mr_url", "receipt")
    val refs = receipt.getValue("submitted_refs")
    if (refs !is JsonNull) {
      val refsObj = asObject(refs, "receipt.submitted_refs")
      exactKeys(refsObj, SUBMITTED_REFS_FIELDS, "receipt.submitted_refs")
      for (key in SUBMITTED_REFS_FIELDS.sorted()) {
        refsObj.reqSha(key, "receipt.submitted_refs")
      }
    }
    receipt.reqBool("fallback", "receipt")
    receipt.reqIso("attempted_at", "receipt")
    val finished = receipt.getValue("finished_at")
    if (finished !is JsonNull) {
      if (finished !is JsonPrimitive || !finished.isString || !ISO_RE.matches(finished.content)) {
        throw err("invalid-schema", "receipt.finished_at must be null or an ISO-8601 timestamp")
      }
    }
    val error = receipt.reqStrOrNull("error", "receipt")
    if (outcome == "posted" || outcome == "recovered") {
      if (discussionId == null || noteId == null || url == null) {
        throw err("invalid-schema", "receipt.outcome=$outcome requires discussion_id, note_id and url")
      }
      if (url != receiptNoteUrl(mrUrl, noteId)) {
        throw err("invalid-schema", "receipt.url must be the note permalink ${receiptNoteUrl(mrUrl, noteId)}")
      }
      if (refs is JsonNull) {
        throw err("invalid-schema", "receipt.outcome=$outcome requires submitted_refs")
      }
      if (error != null) {
        throw err("invalid-schema", "receipt.outcome=$outcome requires error=null")
      }
    } else if (error == null) {
      throw err("invalid-schema", "receipt.outcome=$outcome requires a non-null error")
    }
    return receipt
  }

  // ── Markers, artifact paths, budget ────────────────────────────────────────

  fun reviewMarker(batchId: String, findingId: String, revision: Long): String {
    if (!UUID_RE.matches(batchId)) {
      throw err("invalid-marker", "batch_id must be a canonical UUID, got $batchId")
    }
    if (!UUID_RE.matches(findingId)) {
      throw err("invalid-marker", "finding_id must be a canonical UUID, got $findingId")
    }
    if (revision < 1) {
      throw err("invalid-marker", "revision must be an integer >= 1, got $revision")
    }
    return "lgm-review:$batchId:$findingId:$revision"
  }

  fun parseReviewMarkers(text: String): List<ReviewMarker> =
    MARKER_RE.findAll(text).map {
      ReviewMarker(it.groupValues[1], it.groupValues[2], it.groupValues[3].toLong())
    }.toList()

  fun batchArtifactPath(iid: Long, batchId: String): String {
    if (iid < 1) throw err("invalid-schema", "iid must be an integer >= 1, got $iid")
    if (!UUID_RE.matches(batchId)) {
      throw err("invalid-schema", "batch_id must be a canonical UUID, got $batchId")
    }
    return "mr-replies/mr-!$iid-$batchId.review.json"
  }

  fun codeArtifactPath(codeId: String): String {
    if (!HEX64_RE.matches(codeId)) {
      throw err("invalid-schema", "code_id must be a 64-char hex SHA-256, got $codeId")
    }
    return "mr-review/code/$codeId.bundle"
  }

  fun snapshotArtifactPath(snapshotId: String): String {
    if (!HEX64_RE.matches(snapshotId)) {
      throw err("invalid-schema", "snapshot_id must be a 64-char hex SHA-256, got $snapshotId")
    }
    return "mr-review/snapshots/$snapshotId.json"
  }

  fun validateBudget(budgetBytes: Long): Long {
    if (budgetBytes < BUDGET_BYTES_MIN || budgetBytes > BUDGET_BYTES_MAX) {
      throw err("invalid-budget", "budget_bytes must be within $BUDGET_BYTES_MIN..$BUDGET_BYTES_MAX, got $budgetBytes")
    }
    return budgetBytes
  }
}
