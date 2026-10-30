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
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Kotlin mirror of tests/test_mr_review_publish.py: both runners consume
 * tests/fixtures/mr_review_publish/publish_batch.json and must reach identical
 * outcomes (fresh publication with exact side-aware positions, stale-snapshot
 * zero-write refusals, no general fallback, possible-duplicate recovery).
 */
class MrReviewPublishTest {

  private lateinit var server: HttpServer
  private lateinit var conf: GitLabConfig.GitLabConf
  private lateinit var fixture: JsonObject
  private lateinit var liveDetail: JsonObject

  private var detailCode = 200
  private var tipCode = 200
  private var discussionsCode = 200
  private var discussionsBody = "[]"
  private var tipOverride: String? = null
  private var detailOverride: JsonObject? = null
  private val failPositionFor = mutableSetOf<String>()
  private var positionFailCode = 400
  private var driftAfterWrites: Int? = null
  private var apiDownAfterWrites: Int? = null

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
    discussionsBody = fixture.getValue("live").jsonObject.getValue("discussions").toString()
  }

  @AfterTest
  fun down() {
    server.stop(0)
  }

  private fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

  private fun fixtureFile(): File {
    var dir: File? = File(System.getProperty("user.dir", "."))
    while (dir != null) {
      val candidate = File(dir, "tests/fixtures/mr_review_publish/publish_batch.json")
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    throw IllegalStateException("tests/fixtures/mr_review_publish/publish_batch.json not found above the working directory")
  }

  private fun respond(ex: HttpExchange, code: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    ex.responseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
  }

  private fun apiDown(): Boolean = apiDownAfterWrites != null && writeAttempts >= apiDownAfterWrites!!

  private fun currentDetail(): JsonObject {
    val base = detailOverride ?: liveDetail
    return if (driftAfterWrites != null && writeAttempts >= driftAfterWrites!!) {
      JsonObject(base.toMutableMap().apply { put("sha", JsonPrimitive("e".repeat(40))) })
    } else {
      base
    }
  }

  private fun currentTip(): String =
    tipOverride ?: fixture.getValue("live").jsonObject.getValue("target_tip").jsonPrimitive.content

  private fun handle(ex: HttpExchange) {
    try {
      val path = ex.requestURI.path
      val method = ex.requestMethod
      val detailPath = "/api/v4/projects/p/merge_requests/30"
      when {
        method == "GET" && path == detailPath ->
          respond(ex, if (apiDown()) 500 else detailCode, currentDetail().toString())
        method == "GET" && path == "/api/v4/projects/p/repository/branches/release/2.5" ->
          respond(ex, if (apiDown()) 500 else tipCode, """{"commit":{"id":"${currentTip()}"}}""")
        method == "GET" && path == "$detailPath/discussions" ->
          respond(ex, discussionsCode, discussionsBody)
        method == "POST" && path == "$detailPath/discussions" -> {
          writeAttempts++
          val payload = MrReviewProtocol.parse(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
          val position = payload.getValue("position").jsonObject
          val newPath = position.getValue("new_path").jsonPrimitive.content
          if (newPath in failPositionFor) {
            respond(ex, positionFailCode, """{"message":"invalid position"}""")
            return
          }
          positionedPayloads.add(payload)
          val n = positionedPayloads.size
          respond(ex, 201, """{"id":"disc-$n","notes":[{"id":${900 + n}}]}""")
        }
        method == "POST" && threadNoteRe.matches(path) -> {
          writeAttempts++
          val tid = threadNoteRe.matchEntire(path)!!.groupValues[1]
          val payload = MrReviewProtocol.parse(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
          threadNotes.add(tid to payload.getValue("body").jsonPrimitive.content)
          respond(ex, 201, """{"id":${700 + threadNotes.size}}""")
        }
        method == "POST" && path == "$detailPath/notes" -> {
          writeAttempts++
          val payload = MrReviewProtocol.parse(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
          generalNotes.add(payload.getValue("body").jsonPrimitive.content)
          respond(ex, 201, """{"id":${800 + generalNotes.size}}""")
        }
        method == "PUT" && resolveRe.matches(path) -> {
          writeAttempts++
          resolvedIds.add(resolveRe.matchEntire(path)!!.groupValues[1])
          val tid = resolvedIds.last()
          respond(ex, 200, """{"id":"$tid"}""")
        }
        else -> respond(ex, 404, """{"message":"not found"}""")
      }
    } finally {
      ex.close()
    }
  }

  private fun str(obj: JsonObject, key: String): String = obj.getValue(key).jsonPrimitive.content

  private fun batchOf(): JsonObject = fixture.getValue("batch").jsonObject

  private fun batchId(): String = str(batchOf(), "batch_id")

  private fun item(index: Int): JsonObject = batchOf().getValue("items").jsonArray[index].jsonObject

  private fun markerOf(index: Int, batchId: String = batchId()): String =
    MrReviewProtocol.reviewMarker(batchId, str(item(index), "finding_id"), item(index).getValue("revision").jsonPrimitive.long)

  private fun preparedBatch(rewriteUrl: Boolean = true): JsonObject {
    val batch = batchOf()
    val live = fixture.getValue("live").jsonObject
    val detail = live.getValue("detail").jsonObject
    val url = if (rewriteUrl) baseUrl() else str(batch, "gitlab_url")
    val fingerprint = MrReviewProtocol.computeVersionFingerprint(buildJsonObject {
      put("gitlab_url", url)
      put("iid", batch.getValue("iid"))
      put("source_project_id", detail.getValue("source_project_id"))
      put("target_project_id", detail.getValue("target_project_id"))
      put("source_sha", detail.getValue("sha"))
      put("target_tip_sha", live.getValue("target_tip"))
      put("target_branch", detail.getValue("target_branch"))
      put("gitlab_diff_refs", detail.getValue("diff_refs"))
    })
    val m = batch.toMutableMap()
    m["gitlab_url"] = JsonPrimitive(url)
    m["items"] = JsonArray(batch.getValue("items").jsonArray.map { el ->
      JsonObject(el.jsonObject.toMutableMap().apply { put("version_fingerprint", JsonPrimitive(fingerprint)) })
    })
    return JsonObject(m)
  }

  private fun publish(batch: JsonObject = preparedBatch()): MrReviewBatchPublisher.BatchResult {
    val doc = MrReviewHandoff.parseAndValidate(
      MrReviewProtocol.canonicalJson(batch),
      expectedIid = 30,
      expectedBatchId = str(batch, "batch_id"),
      want = "v2",
    )
    return MrReviewBatchPublisher.publish(conf, doc)
  }

  private fun webUrl(): String = str(liveDetail, "web_url")

  private fun diffRefs(): JsonObject = liveDetail.getValue("diff_refs").jsonObject

  private fun discussionsWithMarker(index: Int, discussionId: String, noteId: Long, batchId: String = batchId()): String {
    val discs = fixture.getValue("live").jsonObject.getValue("discussions").jsonArray.toMutableList()
    discs.add(MrReviewProtocol.parse(
      """{"id":"$discussionId","notes":[{"id":$noteId,"body":"<!-- ${markerOf(index, batchId)} -->\n\nposted"}]}"""))
    return JsonArray(discs).toString()
  }

  private fun assertZeroWrites() {
    assertEquals(0, writeAttempts)
    assertTrue(positionedPayloads.isEmpty())
    assertTrue(threadNotes.isEmpty())
    assertTrue(generalNotes.isEmpty())
    assertTrue(resolvedIds.isEmpty())
  }

  @Test
  fun `fresh batch publishes every kind with exact side-aware positions`() {
    val result = publish()

    assertEquals(4, result.posted)
    assertEquals(0, result.blocked)
    assertEquals(0, result.duplicates)
    assertNull(result.stale)

    assertEquals(2, positionedPayloads.size)
    val first = positionedPayloads[0]
    assertEquals("<!-- ${markerOf(0)} -->\n\n${str(item(0), "body")}", str(first, "body"))
    val pos = first.getValue("position").jsonObject
    assertEquals(str(diffRefs(), "base_sha"), str(pos, "base_sha"))
    assertEquals(str(diffRefs(), "start_sha"), str(pos, "start_sha"))
    assertEquals(str(diffRefs(), "head_sha"), str(pos, "head_sha"))
    assertEquals("text", str(pos, "position_type"))
    assertEquals("src/config.py", str(pos, "old_path"))
    assertEquals("src/config.py", str(pos, "new_path"))
    assertEquals(12L, pos.getValue("new_line").jsonPrimitive.long)
    assertNull(pos["old_line"])

    val second = positionedPayloads[1].getValue("position").jsonObject
    assertEquals("src/legacy.py", str(second, "old_path"))
    assertEquals("src/renamed.py", str(second, "new_path"))
    assertEquals(7L, second.getValue("old_line").jsonPrimitive.long)
    assertNull(second["new_line"])

    val threadId = str(item(2), "thread_id")
    assertEquals(listOf(threadId to "<!-- ${markerOf(2)} -->\n\n${str(item(2), "body")}"), threadNotes)
    assertEquals(listOf(threadId), resolvedIds)
    assertEquals(listOf("<!-- ${markerOf(3)} -->\n\n${str(item(3), "body")}"), generalNotes)

    assertEquals("disc-1", result.entries[0].discussionId)
    assertEquals(901L, result.entries[0].noteId)
    assertEquals("${webUrl()}#note_901", result.entries[0].url)
    assertEquals("posted", result.entries[0].outcome)
    assertEquals(902L, result.entries[1].noteId)
    assertEquals(threadId, result.entries[2].discussionId)
    assertEquals(701L, result.entries[2].noteId)
    assertEquals("${webUrl()}#note_701", result.entries[2].url)
    assertNull(result.entries[3].discussionId)
    assertEquals(801L, result.entries[3].noteId)
    assertEquals("${webUrl()}#note_801", result.entries[3].url)
  }

  @Test
  fun `moved target tip blocks the whole batch with zero writes`() {
    tipOverride = "c".repeat(40)

    val result = publish()

    assertEquals(0, result.posted)
    assertEquals(4, result.blocked)
    assertTrue(result.entries.all { it.outcome == "blocked" && it.code == "stale-snapshot" })
    assertEquals(listOf("target_tip_sha", "version_fingerprint"), result.stale?.drift)
    assertEquals(str(batchOf().getValue("items").jsonArray[0].jsonObject, "target_tip_sha"), result.stale?.expected?.get("target_tip_sha"))
    assertEquals("c".repeat(40), result.stale?.fresh?.get("target_tip_sha"))
    assertTrue(result.details[0].contains("stale-snapshot"))
    assertZeroWrites()
  }

  @Test
  fun `moved head blocks the whole batch with zero writes`() {
    detailOverride = JsonObject(liveDetail.toMutableMap().apply { put("sha", JsonPrimitive("e".repeat(40))) })

    val result = publish()

    assertEquals(4, result.blocked)
    assertEquals(listOf("source_sha", "version_fingerprint"), result.stale?.drift)
    assertZeroWrites()
  }

  @Test
  fun `closed mr blocks the whole batch with zero writes`() {
    detailOverride = JsonObject(liveDetail.toMutableMap().apply { put("state", JsonPrimitive("merged")) })

    val result = publish()

    assertEquals(4, result.blocked)
    assertEquals(listOf("state"), result.stale?.drift)
    assertTrue(result.entries.all { it.code == "stale-snapshot" })
    assertZeroWrites()
  }

  @Test
  fun `moved diff start blocks the whole batch with zero writes`() {
    val movedRefs = JsonObject(diffRefs().toMutableMap().apply { put("start_sha", JsonPrimitive("d".repeat(40))) })
    detailOverride = JsonObject(liveDetail.toMutableMap().apply { put("diff_refs", movedRefs) })

    val result = publish()

    assertEquals(4, result.blocked)
    assertEquals(listOf("diff_refs", "version_fingerprint"), result.stale?.drift)
    assertZeroWrites()
  }

  @Test
  fun `foreign gitlab url blocks the whole batch with zero writes`() {
    val result = publish(preparedBatch(rewriteUrl = false))

    assertEquals(4, result.blocked)
    assertTrue(result.stale!!.drift.containsAll(listOf("gitlab_url", "version_fingerprint")))
    assertZeroWrites()
  }

  @Test
  fun `unavailable gate blocks every entry without writes`() {
    detailCode = 500

    val result = publish()

    assertEquals(4, result.blocked)
    assertTrue(result.details[0].contains("freshness gate"))
    assertTrue(result.entries.all { it.outcome == "blocked" && it.code == "" })
    assertZeroWrites()
  }

  @Test
  fun `discussions fetch failure blocks everything without writes`() {
    discussionsCode = 500

    val result = publish()

    assertEquals(4, result.blocked)
    assertTrue(result.details[0].contains("list discussions"))
    assertZeroWrites()
  }

  @Test
  fun `mid-batch head change keeps posted ids and blocks the remainder`() {
    driftAfterWrites = 1

    val result = publish()

    assertEquals("posted", result.entries[0].outcome)
    assertEquals("disc-1", result.entries[0].discussionId)
    assertEquals(901L, result.entries[0].noteId)
    assertTrue(result.entries.drop(1).all { it.outcome == "blocked" && it.code == "stale-snapshot" })
    assertTrue(result.stale!!.drift.contains("source_sha"))
    assertEquals(1, positionedPayloads.size)
    assertEquals(1, writeAttempts)
  }

  @Test
  fun `api loss mid-batch keeps posted ids and blocks the remainder`() {
    apiDownAfterWrites = 1

    val result = publish()

    assertEquals("posted", result.entries[0].outcome)
    assertEquals(901L, result.entries[0].noteId)
    assertTrue(result.entries.drop(1).all { it.outcome == "blocked" && it.error!!.contains("freshness gate") })
    assertEquals(1, positionedPayloads.size)
  }

  @Test
  fun `rejected anchor blocks without general fallback and the rest proceed`() {
    failPositionFor.add("src/config.py")
    positionFailCode = 400

    val result = publish()

    assertEquals("blocked", result.entries[0].outcome)
    assertTrue(result.entries[0].error!!.contains("GitLab rejected the anchor: HTTP 400"))
    assertTrue(result.entries[0].error!!.contains("no general fallback"))
    assertTrue(generalNotes.none { it.contains(str(item(0), "body")) })
    assertEquals(3, result.posted)
    assertEquals(1, result.blocked)
  }

  @Test
  fun `old-side rename anchor rejection never downgrades to a general note`() {
    failPositionFor.add("src/renamed.py")
    positionFailCode = 422

    val result = publish()

    assertEquals("blocked", result.entries[1].outcome)
    assertTrue(result.entries[1].error!!.contains("HTTP 422"))
    assertTrue(generalNotes.none { it.contains(str(item(1), "body")) })
    assertEquals(3, result.posted)
  }

  @Test
  fun `missing thread blocks only that entry`() {
    discussionsBody = "[]"

    val result = publish()

    assertEquals("blocked", result.entries[2].outcome)
    assertTrue(result.entries[2].error!!.contains("no longer exists"))
    assertTrue(threadNotes.isEmpty())
    assertTrue(resolvedIds.isEmpty())
    assertEquals("posted", result.entries[0].outcome)
    assertEquals("posted", result.entries[1].outcome)
    assertEquals("posted", result.entries[3].outcome)
  }

  @Test
  fun `file-level anchor blocks invalid-line without posting`() {
    val batch = preparedBatch()
    val items = batch.getValue("items").jsonArray.toMutableList()
    val preparedFirst = items[0].jsonObject
    val anchor = JsonObject(preparedFirst.getValue("anchor").jsonObject.toMutableMap().apply { put("line", JsonNull) })
    items[0] = JsonObject(preparedFirst.toMutableMap().apply { put("anchor", anchor) })
    val mutated = JsonObject(batch.toMutableMap().apply { put("items", JsonArray(items)) })

    val result = publish(mutated)

    assertEquals("blocked", result.entries[0].outcome)
    assertEquals("invalid-line", result.entries[0].code)
    assertTrue(positionedPayloads.none { str(it.getValue("position").jsonObject, "new_path") == "src/config.py" })
    assertEquals(3, result.posted)
  }

  @Test
  fun `repeated identical revision is blocked as possible-duplicate with recovered ids`() {
    discussionsBody = discussionsWithMarker(0, "disc-prev", 555)

    val result = publish()

    val first = result.entries[0]
    assertEquals("skipped-duplicate", first.outcome)
    assertEquals("possible-duplicate", first.code)
    assertEquals("disc-prev", first.discussionId)
    assertEquals(555L, first.noteId)
    assertEquals("${webUrl()}#note_555", first.url)
    assertTrue(positionedPayloads.none { str(it.getValue("position").jsonObject, "new_path") == "src/config.py" })
    assertEquals(3, result.posted)
    assertEquals(1, result.duplicates)
    assertEquals(0, result.blocked)
  }

  @Test
  fun `marker from another batch still deduplicates`() {
    discussionsBody = discussionsWithMarker(0, "disc-other", 556, batchId = "9e8d7c6b-5a49-4837-2615-0f4e3d2c1b09")

    val result = publish()

    assertEquals("skipped-duplicate", result.entries[0].outcome)
    assertEquals(556L, result.entries[0].noteId)
    assertTrue(positionedPayloads.none { str(it.getValue("position").jsonObject, "new_path") == "src/config.py" })
  }

  @Test
  fun `second publish recovers every marker and posts nothing`() {
    val first = publish()
    assertEquals(4, first.posted)
    val writesAfterFirst = writeAttempts
    assertEquals(5, writesAfterFirst)

    discussionsBody = "[${(0..3).joinToString(",") { idx ->
      """{"id":"disc-$idx","notes":[{"id":${600 + idx},"body":"<!-- ${markerOf(idx)} -->\n\nposted"}]}"""
    }}]"

    val second = publish()

    assertEquals(0, second.posted)
    assertEquals(4, second.duplicates)
    assertEquals(0, second.blocked)
    assertTrue(second.entries.all { it.outcome == "skipped-duplicate" && it.code == "possible-duplicate" })
    assertEquals(listOf(600L, 601L, 602L, 603L), second.entries.map { it.noteId })
    assertTrue(second.entries.all { it.url != null })
    assertEquals(writesAfterFirst, writeAttempts)
  }

  @Test
  fun `duplicate entries survive a stale halt with recovered ids`() {
    discussionsBody = discussionsWithMarker(0, "disc-prev", 555)
    detailOverride = JsonObject(liveDetail.toMutableMap().apply { put("state", JsonPrimitive("merged")) })

    val result = publish()

    assertEquals("skipped-duplicate", result.entries[0].outcome)
    assertEquals(555L, result.entries[0].noteId)
    assertTrue(result.entries.drop(1).all { it.outcome == "blocked" && it.code == "stale-snapshot" })
    assertZeroWrites()
  }
}
