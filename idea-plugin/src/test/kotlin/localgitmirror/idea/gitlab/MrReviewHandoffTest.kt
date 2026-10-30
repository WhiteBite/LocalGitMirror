package localgitmirror.idea.gitlab

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Fixture parity with tests/test_mr_review_handoff.py: both runners consume
 * tests/fixtures/mr_review_handoff/ and must pin identical acceptance and
 * rejection outcomes (codes, message fragments, normalized entry shapes).
 */
class MrReviewHandoffTest {

  private fun fixtureDir(): File {
    var dir: File? = File(System.getProperty("user.dir", "."))
    while (dir != null) {
      val candidate = File(dir, "tests/fixtures/mr_review_handoff")
      if (candidate.isDirectory) return candidate
      dir = dir.parentFile
    }
    throw IllegalStateException("tests/fixtures/mr_review_handoff not found above the working directory")
  }

  private fun fixtures(): List<JsonObject> =
    fixtureDir().listFiles { _, name -> name.endsWith(".json") }!!
      .sortedBy { it.name }
      .map { MrReviewProtocol.parse(it.readText()).jsonObject }

  private fun str(obj: JsonObject, key: String): String = obj.getValue(key).jsonPrimitive.content

  private fun optStr(obj: JsonObject, key: String): String? =
    obj[key]?.jsonPrimitive?.content

  private fun content(f: JsonObject): String =
    if (f.containsKey("input")) str(f, "input")
    else MrReviewProtocol.canonicalJson(f.getValue("input_object"))

  private fun parse(f: JsonObject): MrReviewHandoff.Document =
    MrReviewHandoff.parseAndValidate(
      content(f),
      expectedIid = optStr(f, "expected_iid")?.toIntOrNull() ?: 0,
      expectedBatchId = optStr(f, "expected_batch_id") ?: "",
      want = optStr(f, "want") ?: "",
    )

  @Test
  fun `fixture directory is complete`() {
    val names = fixtures().map { str(it, "fixture") }.toSet()
    assertEquals(
      setOf(
        "legacy_valid", "legacy_malformed_second_section", "legacy_empty_body_section",
        "legacy_header_mismatch", "legacy_rejected_for_v2_want",
        "v2_valid", "v2_version_mismatch", "v2_conflicting_revisions",
        "v2_duplicate_identical_pair", "v2_unknown_json", "v2_malformed_json_text",
        "v2_rejected_for_legacy_want", "v2_iid_mismatch", "v2_batch_id_mismatch",
        "v2_general_explicit", "v2_anchored_old_side_rename", "v2_resolve_on_anchored",
        "v2_refs_not_uniform",
      ),
      names,
    )
  }

  @Test
  fun `accepted fixtures normalize identically to the python runner`() {
    val accepted = fixtures().filter { str(it, "expect") == "accepted" }
    assertTrue(accepted.size >= 4)
    for (f in accepted) {
      val name = str(f, "fixture")
      val doc = parse(f)
      val format = if (doc.format == MrReviewHandoff.Format.V2) "v2" else "legacy"
      assertEquals(str(f, "expected_format"), format, name)
      assertEquals(str(f, "expected_entry_count").toInt(), doc.entries.size, name)
      f["expected_kinds"]?.jsonArray?.map { it.jsonPrimitive.content }?.let {
        assertEquals(it, doc.entries.map { e -> e.kind }, name)
      }
      f["expected_markers"]?.jsonArray?.map { it.jsonPrimitive.content }?.let {
        assertEquals(it, doc.entries.map { e -> e.marker }, name)
      }
      f["expected_identity"]?.jsonObject?.let { expected ->
        assertEquals(expected.mapValues { it.value.jsonPrimitive.content }, doc.identity, name)
      }
      f["expected_anchors"]?.jsonArray?.map { it.jsonObject }?.let { expected ->
        assertEquals(expected, doc.entries.map { e -> e.anchor }, name)
      }
      optStr(f, "expected_general_fallback")?.toBooleanStrict()?.let {
        assertEquals(it, doc.generalFallback, name)
      }
      val expectedIid = optStr(f, "expected_iid")?.toIntOrNull() ?: 0
      if (expectedIid != 0) assertEquals(expectedIid, doc.iid, name)
    }
  }

  @Test
  fun `rejected fixtures refuse with the same code and message as the python runner`() {
    val rejected = fixtures().filter { str(it, "expect") == "rejected" }
    assertTrue(rejected.size >= 10)
    for (f in rejected) {
      val name = str(f, "fixture")
      val rej = assertThrows(MrReviewHandoff.HandoffRejection::class.java, { parse(f) }, name)
      assertEquals(optStr(f, "expected_error") ?: "", rej.code, name)
      optStr(f, "expected_error_contains")?.let {
        assertTrue(rej.message.orEmpty().contains(it), "$name: ${rej.message}")
      }
      f["expected_errors"]?.jsonArray?.map { it.jsonPrimitive.content }?.let {
        assertEquals(it, rej.errors, name)
      }
    }
  }

  @Test
  fun `legacy parse stays the single shipped grammar`() {
    val markdown = """
      # MR !42 — answers
      - branch: refactor/x

      ## thread 8f2e4c1d
      resolve: yes
      Moved reset before fetch.

      ## new a.kt:0
      bad line
    """.trimIndent()
    assertEquals(MrReviewHandoff.parseLegacy(markdown), MrReplies.parse(markdown))
    val parsed = MrReplies.parse(markdown)
    assertEquals(42, parsed.iid)
    assertEquals(listOf("section 2: bad file:line, skipped"), parsed.errors)
  }

  @Test
  fun `v2 entries keep side-aware anchors and refuse the general fallback`() {
    val batch = MrReviewProtocol.parse(
      File(fixtureDir().parentFile, "mr_review_protocol/batch_v2.json").readText(),
    ).jsonObject.getValue("object").jsonObject
    val doc = MrReviewHandoff.parseAndValidate(MrReviewProtocol.canonicalJson(batch))
    assertEquals(MrReviewHandoff.Format.V2, doc.format)
    assertFalse(doc.generalFallback)
    val anchored = doc.entries.first { it.kind == "anchored" }
    val anchor = anchored.anchor!!
    assertEquals("new", MrReviewHandoffTest.anchorStr(anchor, "side"))
    assertEquals("src/config.py", MrReviewHandoffTest.anchorStr(anchor, "new_path"))
    assertEquals("src/config.py", MrReviewHandoffTest.anchorStr(anchor, "old_path"))
    assertNull(anchored.threadId)
    val thread = doc.entries.first { it.kind == "thread" }
    assertNull(thread.anchor)
    assertTrue(thread.resolve)
    for (e in doc.entries) {
      val expected = MrReviewProtocol.reviewMarker(doc.batchId!!, e.findingId!!, e.revision)
      assertEquals(expected, e.marker, "marker of ${e.findingId}")
      val found = MrReviewProtocol.parseReviewMarkers("<!-- ${e.marker} -->")
      assertTrue(found.any {
        it.batchId == doc.batchId && it.findingId == e.findingId && it.revision == e.revision
      })
    }
  }

  @Test
  fun `legacy documents carry no v2 identity and allow the general fallback`() {
    val doc = MrReviewHandoff.parseAndValidate("# MR !7\n\n## new A.java:5\nok\n")
    assertEquals(MrReviewHandoff.Format.LEGACY, doc.format)
    assertTrue(doc.generalFallback)
    assertNull(doc.batchId)
    assertNull(doc.batch)
    assertTrue(doc.identity.isEmpty())
    assertEquals(7, doc.iid)
    assertEquals(listOf("new_anchored"), doc.entries.map { it.kind })
    assertEquals(doc.legacy, MrReplies.parse("# MR !7\n\n## new A.java:5\nok\n"))
  }

  companion object {
    private fun anchorStr(anchor: JsonObject, key: String): String =
      anchor.getValue(key).jsonPrimitive.content
  }
}
