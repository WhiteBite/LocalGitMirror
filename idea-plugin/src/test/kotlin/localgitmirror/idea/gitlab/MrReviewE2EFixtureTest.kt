package localgitmirror.idea.gitlab

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Cross-language leg of tests/test_mr_review_e2e.py: consumes the batch, the
 * live GitLab state and the receipt envelope the Python end-to-end chain
 * produced (tests/fixtures/mr_review_e2e/e2e_batch.json) and must reach the
 * identical outcomes, ids, permalinks and markers through
 * MrReviewHandoff.parseAndValidate + MrReviewBatchPublisher.publish.
 */
class MrReviewE2EFixtureTest {

  private lateinit var server: HttpServer
  private lateinit var conf: GitLabConfig.GitLabConf
  private lateinit var fixture: JsonObject
  private lateinit var liveDetail: JsonObject
  private lateinit var discussions: MutableList<JsonObject>

  private var writeAttempts = 0
  private val positionedPayloads = mutableListOf<JsonObject>()
  private val threadNotes = mutableListOf<Pair<String, String>>()
  private val generalNotes = mutableListOf<String>()
  private val resolvedIds = mutableListOf<String>()

  private val threadNoteRe = Regex("^/api/v4/projects/p/merge_requests/30/discussions/([^/]+)/notes$")
  private val resolveRe = Regex("^/api/v4/projects/p/merge_requests/30/discussions/([^/]+)$")

  @BeforeTest
  fun up() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/v4") { ex -> handle(ex) }
    server.start()
    conf = GitLabConfig.GitLabConf(baseUrl(), "p", "t")
    fixture = MrReviewProtocol.parse(fixtureFile().readText()).jsonObject
    liveDetail = fixture.getValue("live").jsonObject.getValue("detail").jsonObject
    discussions = fixture.getValue("live").jsonObject.getValue("discussions").jsonArray
      .map { it.jsonObject }.toMutableList()
  }

  @AfterTest
  fun down() {
    server.stop(0)
  }

  private fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

  private fun fixtureFile(): File {
    var dir: File? = File(System.getProperty("user.dir", "."))
    while (dir != null) {
      val candidate = File(dir, "tests/fixtures/mr_review_e2e/e2e_batch.json")
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    throw IllegalStateException("tests/fixtures/mr_review_e2e/e2e_batch.json not found above the working directory")
  }

  private fun respond(ex: HttpExchange, code: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    ex.responseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
  }

  private fun noteJson(id: Long, body: String): String =
    """{"id":$id,"author":{"name":"Agent"},"created_at":"2026-10-30T12:00:00Z","system":false,"body":${MrReviewProtocol.canonicalJson(JsonPrimitive(body))},"resolvable":false}"""

  private fun handle(ex: HttpExchange) {
    try {
      val path = ex.requestURI.path
      val method = ex.requestMethod
      val detailPath = "/api/v4/projects/p/merge_requests/30"
      when {
        method == "GET" && path == detailPath ->
          respond(ex, 200, liveDetail.toString())
        method == "GET" && path == "/api/v4/projects/p/repository/branches/master" ->
          respond(ex, 200, """{"commit":{"id":"${targetTip()}"}}""")
        method == "GET" && path == "$detailPath/discussions" ->
          respond(ex, 200, JsonArray(discussions).toString())
        method == "POST" && path == "$detailPath/discussions" -> {
          writeAttempts++
          val payload = MrReviewProtocol.parse(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
          positionedPayloads.add(payload)
          val n = positionedPayloads.size
          val noteId = 900L + n
          val body = payload.getValue("body").jsonPrimitive.content
          discussions.add(MrReviewProtocol.parse(
            """{"id":"disc-$n","notes":[${noteJson(noteId, body)}]}""").jsonObject)
          respond(ex, 201, """{"id":"disc-$n","notes":[{"id":$noteId}]}""")
        }
        method == "POST" && threadNoteRe.matches(path) -> {
          writeAttempts++
          val tid = threadNoteRe.matchEntire(path)!!.groupValues[1]
          val payload = MrReviewProtocol.parse(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
          val body = payload.getValue("body").jsonPrimitive.content
          threadNotes.add(tid to body)
          val noteId = 700L + threadNotes.size
          val target = discussions.first { it.getValue("id").jsonPrimitive.content == tid }
          val notes = target.getValue("notes").jsonArray.toMutableList()
          notes.add(MrReviewProtocol.parse(noteJson(noteId, body)).jsonObject)
          discussions[discussions.indexOf(target)] =
            JsonObject(target.toMutableMap().apply { put("notes", JsonArray(notes)) })
          respond(ex, 201, """{"id":$noteId}""")
        }
        method == "POST" && path == "$detailPath/notes" -> {
          writeAttempts++
          val payload = MrReviewProtocol.parse(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
          val body = payload.getValue("body").jsonPrimitive.content
          generalNotes.add(body)
          val noteId = 800L + generalNotes.size
          discussions.add(MrReviewProtocol.parse(
            """{"id":"d-gen-${generalNotes.size}","notes":[${noteJson(noteId, body)}]}""").jsonObject)
          respond(ex, 201, """{"id":$noteId}""")
        }
        method == "PUT" && resolveRe.matches(path) -> {
          writeAttempts++
          val tid = resolveRe.matchEntire(path)!!.groupValues[1]
          resolvedIds.add(tid)
          respond(ex, 200, """{"id":"$tid"}""")
        }
        else -> respond(ex, 404, """{"message":"not found"}""")
      }
    } finally {
      ex.close()
    }
  }

  private fun str(obj: JsonObject, key: String): String = obj.getValue(key).jsonPrimitive.content

  private fun targetTip(): String =
    fixture.getValue("live").jsonObject.getValue("target_tip").jsonPrimitive.content

  private fun batchOf(): JsonObject = fixture.getValue("batch").jsonObject

  private fun expectedOutcomes(): List<JsonObject> =
    fixture.getValue("expected").jsonObject.getValue("outcomes").jsonArray.map { it.jsonObject }

  private fun preparedBatch(): JsonObject {
    val batch = batchOf()
    val url = baseUrl()
    val fingerprint = MrReviewProtocol.computeVersionFingerprint(buildJsonObject {
      put("gitlab_url", url)
      put("iid", batch.getValue("iid"))
      put("source_project_id", liveDetail.getValue("source_project_id"))
      put("target_project_id", liveDetail.getValue("target_project_id"))
      put("source_sha", liveDetail.getValue("sha"))
      put("target_tip_sha", targetTip())
      put("target_branch", liveDetail.getValue("target_branch"))
      put("gitlab_diff_refs", liveDetail.getValue("diff_refs"))
    })
    val m = batch.toMutableMap()
    m["gitlab_url"] = JsonPrimitive(url)
    m["items"] = JsonArray(batch.getValue("items").jsonArray.map { el ->
      JsonObject(el.jsonObject.toMutableMap().apply { put("version_fingerprint", JsonPrimitive(fingerprint)) })
    })
    return JsonObject(m)
  }

  private fun publish(): MrReviewBatchPublisher.BatchResult {
    val batch = preparedBatch()
    val doc = MrReviewHandoff.parseAndValidate(
      MrReviewProtocol.canonicalJson(batch),
      expectedIid = 30,
      expectedBatchId = str(batch, "batch_id"),
      want = "v2",
    )
    return MrReviewBatchPublisher.publish(conf, doc)
  }

  @Test
  fun `python produced batch publishes with identical outcomes and markers`() {
    val result = publish()
    val expected = expectedOutcomes()

    assertEquals(3, result.posted)
    assertEquals(0, result.blocked)
    assertEquals(0, result.duplicates)
    assertNull(result.stale)
    assertEquals(3, expected.size)
    assertEquals(4, writeAttempts)

    result.entries.forEachIndexed { i, entry ->
      val exp = expected[i]
      assertEquals(str(exp, "kind"), entry.kind)
      assertEquals(str(exp, "outcome"), entry.outcome)
      assertEquals(str(exp, "discussion_id"), entry.discussionId)
      assertEquals(exp.getValue("note_id").jsonPrimitive.long, entry.noteId)
      assertEquals(str(exp, "url"), entry.url)
      assertEquals(str(exp, "marker"), entry.marker)
    }

    val batchId = str(batchOf(), "batch_id")
    batchOf().getValue("items").jsonArray.forEachIndexed { i, el ->
      val item = el.jsonObject
      assertEquals(
        str(expected[i], "marker"),
        MrReviewProtocol.reviewMarker(batchId, str(item, "finding_id"), item.getValue("revision").jsonPrimitive.long),
      )
    }

    val refs = liveDetail.getValue("diff_refs").jsonObject
    val pos = positionedPayloads[0].getValue("position").jsonObject
    assertEquals(str(refs, "base_sha"), str(pos, "base_sha"))
    assertEquals(str(refs, "start_sha"), str(pos, "start_sha"))
    assertEquals(str(refs, "head_sha"), str(pos, "head_sha"))
    assertEquals("text", str(pos, "position_type"))
    assertEquals("src/config.py", str(pos, "old_path"))
    assertEquals("src/config.py", str(pos, "new_path"))
    assertEquals(12L, pos.getValue("new_line").jsonPrimitive.long)
    assertNull(pos["old_line"])

    val items = batchOf().getValue("items").jsonArray.map { it.jsonObject }
    assertEquals(
      listOf("d-thread-1" to "<!-- ${str(expected[1], "marker")} -->\n\n${str(items[1], "body")}"),
      threadNotes,
    )
    assertEquals(listOf("d-thread-1"), resolvedIds)
    assertEquals(listOf("<!-- ${str(expected[2], "marker")} -->\n\n${str(items[2], "body")}"), generalNotes)
  }

  @Test
  fun `republish recovers every entry as duplicate without new writes`() {
    val first = publish()
    assertEquals(3, first.posted)
    val writesAfterFirst = writeAttempts

    val second = publish()
    val expected = expectedOutcomes()

    assertEquals(0, second.posted)
    assertEquals(3, second.duplicates)
    assertEquals(0, second.blocked)
    assertEquals(writesAfterFirst, writeAttempts)
    second.entries.forEachIndexed { i, entry ->
      val exp = expected[i]
      assertEquals("skipped-duplicate", entry.outcome)
      assertEquals("possible-duplicate", entry.code)
      assertEquals(str(exp, "discussion_id"), entry.discussionId)
      assertEquals(exp.getValue("note_id").jsonPrimitive.long, entry.noteId)
      assertEquals(str(exp, "url"), entry.url)
      assertEquals(str(exp, "marker"), entry.marker)
    }
  }

  @Test
  fun `python receipt envelope validates and carries the same ids`() {
    val envelope = fixture.getValue("receipt_envelope").jsonObject
    assertEquals(str(batchOf(), "batch_id"), str(envelope, "batch_id"))
    assertEquals(1L, envelope.getValue("attempt").jsonPrimitive.long)
    assertEquals(30L, envelope.getValue("iid").jsonPrimitive.long)

    val byFinding = expectedOutcomes().associateBy { str(it, "finding_id") }
    val receipts = envelope.getValue("receipts").jsonArray
    assertEquals(byFinding.size, receipts.size)
    receipts.forEach { el ->
      val receipt = MrReviewProtocol.validateReceipt(el)
      val exp = byFinding.getValue(str(receipt, "finding_id"))
      assertEquals("posted", str(receipt, "outcome"))
      assertEquals(str(exp, "outcome"), str(receipt, "outcome"))
      assertEquals(exp.getValue("note_id").jsonPrimitive.long, receipt.getValue("note_id").jsonPrimitive.long)
      assertEquals(str(exp, "discussion_id"), str(receipt, "discussion_id"))
      assertEquals(str(exp, "url"), str(receipt, "url"))
      assertEquals(str(exp, "marker").substringAfter("lgm-review:"),
        "${str(receipt, "batch_id")}:${str(receipt, "finding_id")}:${receipt.getValue("revision").jsonPrimitive.long}")
    }
  }
}
