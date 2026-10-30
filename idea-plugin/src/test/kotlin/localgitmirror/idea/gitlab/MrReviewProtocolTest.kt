package localgitmirror.idea.gitlab

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MrReviewProtocolTest {
  private val identityKeys = listOf("snapshot_id", "code_id", "version_fingerprint")

  private fun fixtureDir(): File {
    var dir: File? = File(System.getProperty("user.dir", "."))
    while (dir != null) {
      val candidate = File(dir, "tests/fixtures/mr_review_protocol")
      if (candidate.isDirectory) return candidate
      dir = dir.parentFile
    }
    throw IllegalStateException("tests/fixtures/mr_review_protocol not found above the working directory")
  }

  private fun fixtures(): List<JsonObject> =
    fixtureDir().listFiles { _, name -> name.endsWith(".json") }!!
      .sortedBy { it.name }
      .map { MrReviewProtocol.parse(it.readText()).jsonObject }

  private fun fixture(name: String): JsonObject =
    MrReviewProtocol.parse(File(fixtureDir(), "$name.json").readText()).jsonObject

  private fun str(obj: JsonObject, key: String): String = obj.getValue(key).jsonPrimitive.content

  private fun content(obj: JsonObject, key: String): String = obj.getValue(key).jsonPrimitive.content

  private fun expectedDigests(f: JsonObject): Map<String, String> =
    f.getValue("expected_digests").jsonObject.mapValues { it.value.jsonPrimitive.content }

  private fun runValidator(kind: String, obj: JsonObject) {
    when (kind) {
      "snapshot" -> MrReviewProtocol.verifySnapshot(obj)
      "finding" -> MrReviewProtocol.validateFinding(obj)
      "batch" -> MrReviewProtocol.validateBatch(obj)
      "receipt" -> MrReviewProtocol.validateReceipt(obj)
      else -> throw IllegalStateException("unexpected object_kind $kind")
    }
  }

  @Test
  fun `fixture directory is complete`() {
    val names = fixtures().map { str(it, "fixture") }.toSet()
    assertEquals(
      setOf(
        "snapshot_non_default_target", "snapshot_fork_mr", "snapshot_deletion",
        "snapshot_rename", "snapshot_unicode_null", "finding_stale", "batch_v2",
        "receipt_recovery", "canonical_probe",
        "invalid_tampered_snapshot_id", "invalid_side", "invalid_negative_line",
        "invalid_unknown_schema_version", "invalid_diff_not_ready",
      ),
      names,
    )
  }

  @Test
  fun `valid fixtures pass and digests match the python-computed expectations`() {
    val valid = fixtures().filter { str(it, "expect") == "valid" && str(it, "object_kind") != "probe" }
    assertTrue(valid.size >= 5)
    for (f in valid) {
      val obj = f.getValue("object").jsonObject
      val kind = str(f, "object_kind")
      val actual: Map<String, String> = when (kind) {
        "snapshot" -> MrReviewProtocol.snapshotDigests(MrReviewProtocol.verifySnapshot(obj))
        "finding" -> {
          MrReviewProtocol.validateFinding(obj)
          identityKeys.associateWith { content(MrReviewProtocol.findingLatestRevision(obj), it) }
        }
        "batch" -> {
          MrReviewProtocol.validateBatch(obj)
          MrReviewProtocol.batchIdentity(obj)
        }
        else -> {
          MrReviewProtocol.validateReceipt(obj)
          identityKeys.associateWith { content(obj, it) }
        }
      }
      assertEquals(expectedDigests(f), actual, str(f, "fixture"))
    }
  }

  @Test
  fun `invalid fixtures are rejected with the exact expected error code`() {
    val invalid = fixtures().filter { str(it, "expect") == "invalid" }
    assertTrue(invalid.size >= 5)
    for (f in invalid) {
      val ex = assertThrows(MrReviewProtocolException::class.java) {
        runValidator(str(f, "object_kind"), f.getValue("object").jsonObject)
      }
      assertEquals(str(f, "expected_error"), ex.code, str(f, "fixture"))
    }
  }

  @Test
  fun `tampered hash is structurally valid but rejected by the digest gate`() {
    val obj = fixture("invalid_tampered_snapshot_id").getValue("object").jsonObject
    MrReviewProtocol.validateSnapshot(obj)
    val ex = assertThrows(MrReviewProtocolException::class.java) { MrReviewProtocol.verifySnapshot(obj) }
    assertEquals("hash-mismatch", ex.code)
  }

  @Test
  fun `stale finding is detected against the newer snapshot`() {
    val f = fixture("finding_stale")
    val finding = f.getValue("object").jsonObject
    val context = f.getValue("context_snapshot").jsonObject
    MrReviewProtocol.validateFinding(finding)
    MrReviewProtocol.verifySnapshot(context)
    assertEquals(expectedDigests(f), MrReviewProtocol.snapshotDigests(context))
    assertTrue(MrReviewProtocol.findingStaleAgainst(finding, context))

    val revisions = finding.getValue("revisions").jsonArray.toMutableList()
    val last = revisions.last().jsonObject.toMutableMap()
    f.getValue("expected_digests").jsonObject.forEach { (k, v) -> last[k] = v }
    revisions[revisions.size - 1] = JsonObject(last)
    val fresh = JsonObject(finding.toMutableMap().also { it["revisions"] = JsonArray(revisions) })
    assertFalse(MrReviewProtocol.findingStaleAgainst(fresh, context))
  }

  @Test
  fun `empty string diff refs are diff-not-ready`() {
    val obj = fixture("invalid_diff_not_ready").getValue("object").jsonObject.toMutableMap()
    obj["gitlab_diff_refs"] = buildJsonObject {
      put("base_sha", "")
      put("head_sha", "")
      put("start_sha", "")
    }
    val ex = assertThrows(MrReviewProtocolException::class.java) {
      MrReviewProtocol.verifySnapshot(JsonObject(obj))
    }
    assertEquals("diff-not-ready", ex.code)
  }

  @Test
  fun `canonical probe is byte identical to the python serializer output`() {
    val f = fixture("canonical_probe")
    assertEquals(
      str(f, "expected_canonical"),
      MrReviewProtocol.canonicalJson(f.getValue("object")),
    )
  }

  @Test
  fun `canonical json rejects floats and non ascii keys`() {
    val floatEx = assertThrows(MrReviewProtocolException::class.java) {
      MrReviewProtocol.canonicalJson(MrReviewProtocol.parse("{\"a\":1.5}"))
    }
    assertEquals("invalid-schema", floatEx.code)
    assertThrows(MrReviewProtocolException::class.java) {
      MrReviewProtocol.canonicalJson(MrReviewProtocol.parse("{\"ключ\":1}"))
    }
  }

  @Test
  fun `receipt url is the note permalink`() {
    val receipt = fixture("receipt_recovery").getValue("object").jsonObject
    val noteId = receipt.getValue("note_id").jsonPrimitive.content
    assertEquals(
      "${content(receipt, "mr_url")}#note_$noteId",
      content(receipt, "url"),
    )
  }

  @Test
  fun `error registry is closed and matches the python set`() {
    assertEquals(
      setOf(
        "snapshot-raced", "diff-not-ready", "review-object-missing",
        "review-package-too-large", "stale-snapshot", "possible-duplicate",
        "incomplete-checkout", "invalid-schema", "unknown-schema-version",
        "invalid-side", "invalid-line", "hash-mismatch", "invalid-marker",
        "invalid-budget", "revision-conflict", "store-locked",
      ),
      MrReviewProtocol.ERROR_CODES,
    )
  }

  @Test
  fun `marker roundtrip and rejections`() {
    val batchId = "9f1e8d7c-6b5a-4938-2716-05f4e3d2c1b0"
    val findingId = "7d444840-9dc0-11d1-b245-5ffdce74fad2"
    val marker = MrReviewProtocol.reviewMarker(batchId, findingId, 2)
    assertEquals("lgm-review:$batchId:$findingId:2", marker)
    assertEquals(
      listOf(ReviewMarker(batchId, findingId, 2)),
      MrReviewProtocol.parseReviewMarkers("body text <!-- $marker --> tail"),
    )
    assertEquals(emptyList<ReviewMarker>(), MrReviewProtocol.parseReviewMarkers("no markers here"))
    val upper = assertThrows(MrReviewProtocolException::class.java) {
      MrReviewProtocol.reviewMarker(batchId.uppercase(), findingId, 2)
    }
    assertEquals("invalid-marker", upper.code)
    val zero = assertThrows(MrReviewProtocolException::class.java) {
      MrReviewProtocol.reviewMarker(batchId, findingId, 0)
    }
    assertEquals("invalid-marker", zero.code)
  }

  @Test
  fun `artifact paths match the protocol`() {
    val batchId = "9f1e8d7c-6b5a-4938-2716-05f4e3d2c1b0"
    val digests = expectedDigests(fixture("snapshot_non_default_target"))
    assertEquals(
      "mr-replies/mr-!30-$batchId.review.json",
      MrReviewProtocol.batchArtifactPath(30, batchId),
    )
    assertEquals(
      "mr-review/code/${digests.getValue("code_id")}.bundle",
      MrReviewProtocol.codeArtifactPath(digests.getValue("code_id")),
    )
    assertEquals(
      "mr-review/snapshots/${digests.getValue("snapshot_id")}.json",
      MrReviewProtocol.snapshotArtifactPath(digests.getValue("snapshot_id")),
    )
    val bad = assertThrows(MrReviewProtocolException::class.java) {
      MrReviewProtocol.codeArtifactPath("nothex")
    }
    assertEquals("invalid-schema", bad.code)
  }

  @Test
  fun `budget bounds are enforced`() {
    assertEquals(4096L, MrReviewProtocol.validateBudget(4096))
    assertEquals(48000L, MrReviewProtocol.validateBudget(48000))
    for (bad in listOf(4095L, 48001L, 0L, -1L)) {
      val ex = assertThrows(MrReviewProtocolException::class.java) {
        MrReviewProtocol.validateBudget(bad)
      }
      assertEquals("invalid-budget", ex.code)
    }
  }
}
