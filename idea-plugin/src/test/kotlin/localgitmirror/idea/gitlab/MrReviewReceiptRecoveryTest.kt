package localgitmirror.idea.gitlab

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
import kotlinx.serialization.json.putJsonArray

/**
 * Restart recovery and receipt handoff around MrReviewBatchPublisher (Python
 * twin: tests/test_mr_review_receipts.py). Shares the publication fixture with
 * MrReviewPublishTest; the journal lives in a temp dir, so a "restart" is
 * simulated by reopening the journal file from disk.
 */
class MrReviewReceiptRecoveryTest {

  private lateinit var server: HttpServer
  private lateinit var conf: GitLabConfig.GitLabConf
  private lateinit var fixture: JsonObject
  private lateinit var liveDetail: JsonObject
  private lateinit var journalDir: File

  private var discussionsJson: JsonArray = JsonArray(emptyList())
  private var growGeneralNotes = false
  private val failPositionFor = mutableSetOf<String>()
  private var positionFailCode = 400

  private var writeAttempts = 0
  private val positionedPayloads = mutableListOf<JsonObject>()
  private val threadNotes = mutableListOf<Pair<String, String>>()
  private val generalNotes = mutableListOf<String>()
  private val resolvedIds = mutableListOf<String>()
  private val uploads = mutableListOf<Pair<String, String>>()
  private val acks = mutableListOf<String>()

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
    discussionsJson = fixture.getValue("live").jsonObject.getValue("discussions").jsonArray
    journalDir = Files.createTempDirectory("lgm-receipt-recovery").toFile()
  }

  @AfterTest
  fun down() {
    server.stop(0)
    journalDir.deleteRecursively()
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

  private fun handle(ex: HttpExchange) {
    try {
      val path = ex.requestURI.path
      val method = ex.requestMethod
      val detailPath = "/api/v4/projects/p/merge_requests/30"
      when {
        method == "GET" && path == detailPath -> respond(ex, 200, liveDetail.toString())
        method == "GET" && path == "/api/v4/projects/p/repository/branches/release/2.5" ->
          respond(ex, 200, """{"commit":{"id":"${targetTip()}"}}""")
        method == "GET" && path == "$detailPath/discussions" -> respond(ex, 200, discussionsJson.toString())
        method == "POST" && path == "$detailPath/discussions" -> {
          writeAttempts++
          val payload = MrReviewProtocol.parse(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
          val newPath = payload.getValue("position").jsonObject.getValue("new_path").jsonPrimitive.content
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
          val body = payload.getValue("body").jsonPrimitive.content
          generalNotes.add(body)
          val noteId = 800 + generalNotes.size
          if (growGeneralNotes) {
            discussionsJson = JsonArray(discussionsJson + MrReviewProtocol.parse(
              """{"id":"disc-gen-$noteId","notes":[{"id":$noteId,"body":${JsonPrimitive(body)}}]}""").jsonObject)
          }
          respond(ex, 201, """{"id":$noteId}""")
        }
        method == "PUT" && resolveRe.matches(path) -> {
          writeAttempts++
          resolvedIds.add(resolveRe.matchEntire(path)!!.groupValues[1])
          respond(ex, 200, """{"id":"${resolvedIds.last()}"}""")
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

  private fun fid(index: Int): String = str(item(index), "finding_id")

  private fun revisionOf(index: Int): Long = item(index).getValue("revision").jsonPrimitive.long

  private fun targetTip(): String =
    fixture.getValue("live").jsonObject.getValue("target_tip").jsonPrimitive.content

  private fun webUrl(): String = str(liveDetail, "web_url")

  private fun markerOf(index: Int): String =
    MrReviewProtocol.reviewMarker(batchId(), fid(index), revisionOf(index))

  private fun preparedBatch(): JsonObject {
    val batch = batchOf()
    val detail = liveDetail
    val url = baseUrl()
    val fingerprint = MrReviewProtocol.computeVersionFingerprint(buildJsonObject {
      put("gitlab_url", url)
      put("iid", batch.getValue("iid"))
      put("source_project_id", detail.getValue("source_project_id"))
      put("target_project_id", detail.getValue("target_project_id"))
      put("source_sha", detail.getValue("sha"))
      put("target_tip_sha", fixture.getValue("live").jsonObject.getValue("target_tip"))
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

  private fun docOf(batch: JsonObject = preparedBatch()): MrReviewHandoff.Document =
    MrReviewHandoff.parseAndValidate(
      MrReviewProtocol.canonicalJson(batch),
      expectedIid = 30,
      expectedBatchId = str(batch, "batch_id"),
      want = "v2",
    )

  private fun journalFile(): File = File(journalDir, "mr-!30-${batchId()}.journal.json")

  private fun openJournal(doc: MrReviewHandoff.Document = docOf()): MrReviewJournal =
    MrReviewJournal.open(journalDir, 30, batchId(), doc, "b1",
                         "mr-replies/mr-!30-${batchId()}.review.json")

  private fun publishJournalled(batch: JsonObject = preparedBatch()): MrReviewBatchPublisher.BatchResult {
    val doc = docOf(batch)
    return MrReviewBatchPublisher.publish(conf, doc, openJournal(doc))
  }

  private fun reloaded(): MrReviewJournal = MrReviewJournal.load(journalFile())

  private fun handoff(uploadOk: Boolean = true, ackOk: Boolean = true): MrReviewReceiptHandoff.Report =
    MrReviewReceiptHandoff.processPending(
      journalDir,
      upload = { path, json -> uploads.add(path to json); uploadOk },
      ack = { id -> if (ackOk) acks.add(id); ackOk },
    )

  private fun uploadedEnvelopes(): List<JsonObject> =
    uploads.map { MrReviewProtocol.parse(it.second).jsonObject }

  private fun outcomesOf(envelope: JsonObject): List<String> =
    envelope.getValue("receipts").jsonArray.map { it.jsonObject.getValue("outcome").jsonPrimitive.content }

  private fun markerDiscussion(index: Int, discussionId: String, noteId: Long?): JsonObject {
    val idPart = if (noteId == null) "null" else noteId.toString()
    return MrReviewProtocol.parse(
      """{"id":"$discussionId","notes":[{"id":$idPart,"body":"<!-- ${markerOf(index)} -->\n\nposted"}]}"""
    ).jsonObject
  }

  private fun writeUncertainJournal() {
    val batch = preparedBatch()
    val item0 = item(0)
    val json = buildJsonObject {
      put("schema_version", 1)
      put("repo", "r1")
      put("iid", 30)
      put("batch_id", str(batch, "batch_id"))
      put("gitlab_url", str(batch, "gitlab_url"))
      put("target_project_id", batch.getValue("target_project_id"))
      put("identity", buildJsonObject {
        put("snapshot_id", str(item0, "snapshot_id"))
        put("code_id", str(item0, "code_id"))
        put("version_fingerprint", str(item0, "version_fingerprint"))
      })
      put("refs", buildJsonObject {
        put("source_sha", str(item0, "source_sha"))
        put("target_tip_sha", str(item0, "target_tip_sha"))
        put("diff_refs", item0.getValue("diff_refs"))
      })
      putJsonArray("attempts") {
        add(buildJsonObject {
          put("attempt", 1)
          put("started_at", "2026-10-30T10:00:00Z")
          put("completed_at", JsonNull)
          put("web_url", webUrl())
          put("postbox_item_id", "b1")
          put("postbox_path", "mr-replies/mr-!30-x.review.json")
          put("receipt_uploaded_at", JsonNull)
          put("receipt_path", JsonNull)
          put("acked_at", JsonNull)
          putJsonArray("entries") {
            add(buildJsonObject {
              put("finding_id", fid(0))
              put("revision", revisionOf(0))
              put("kind", "anchored")
              put("marker", markerOf(0))
              put("state", "post_start")
              put("post_attempted", true)
              put("started_at", "2026-10-30T10:00:00Z")
              put("finished_at", JsonNull)
              put("outcome", JsonNull)
              put("code", "")
              put("error", JsonNull)
              put("discussion_id", JsonNull)
              put("note_id", JsonNull)
              put("url", JsonNull)
              put("recovered_from", JsonNull)
            })
          }
        })
      }
    }
    journalDir.mkdirs()
    journalFile().writeText(json.toString(), Charsets.UTF_8)
  }

  @Test
  fun `fresh publish journals every entry with ids before any receipt step`() {
    val result = publishJournalled()

    assertEquals(4, result.posted)
    assertTrue(journalFile().isFile)
    val journal = reloaded()
    assertTrue(journal.readable)
    assertEquals(1, journal.attempts.size)
    val attempt = journal.attempts[0]
    assertNotNull(attempt.completedAt)
    assertEquals("b1", attempt.postboxItemId)
    assertEquals(webUrl(), attempt.webUrl)
    assertNull(attempt.receiptUploadedAt)
    assertNull(attempt.ackedAt)
    assertEquals(4, attempt.entries.size)
    val first = attempt.entries.first { it.findingId == fid(0) }
    assertEquals("done", first.state)
    assertEquals("posted", first.outcome)
    assertEquals("disc-1", first.discussionId)
    assertEquals(901L, first.noteId)
    assertEquals("${webUrl()}#note_901", first.url)
    assertEquals(emptyList(), uploads)
    assertEquals(emptyList(), acks)
  }

  @Test
  fun `journaled posted entries are never reposted even without markers`() {
    growGeneralNotes = true
    publishJournalled()
    val writesAfterFirst = writeAttempts
    assertEquals(5, writesAfterFirst)

    discussionsJson = JsonArray(emptyList())
    val second = publishJournalled()

    assertEquals(0, second.posted)
    assertEquals(4, second.duplicates)
    assertEquals(writesAfterFirst, writeAttempts)
    assertTrue(second.entries.all { it.outcome == "skipped-duplicate" })
    val first = second.entries.first { it.findingId == fid(0) }
    assertEquals("disc-1", first.discussionId)
    assertEquals(901L, first.noteId)
    assertEquals("${webUrl()}#note_901", first.url)
    val journal = reloaded()
    assertEquals(2, journal.attempts.size)
    assertEquals("journal", journal.attempts[1].entries.first { it.findingId == fid(0) }.recoveredFrom)
  }

  @Test
  fun `uncertain post recovers through the marker without a second post`() {
    writeUncertainJournal()
    discussionsJson = JsonArray(discussionsJson + markerDiscussion(0, "disc-crash", 555))

    val result = publishJournalled()

    val first = result.entries.first { it.findingId == fid(0) }
    assertEquals("skipped-duplicate", first.outcome)
    assertEquals("disc-crash", first.discussionId)
    assertEquals(555L, first.noteId)
    assertEquals("${webUrl()}#note_555", first.url)
    assertEquals(4, writeAttempts)
    assertTrue(positionedPayloads.none { str(it.getValue("position").jsonObject, "new_path") == "src/config.py" })
  }

  @Test
  fun `uncertain post without a marker blocks and refuses a second post`() {
    writeUncertainJournal()

    val result = publishJournalled()

    val first = result.entries.first { it.findingId == fid(0) }
    assertEquals("blocked", first.outcome)
    assertTrue(first.error!!.contains("uncertain publication outcome"))
    assertTrue(first.error!!.contains("refusing a second POST"))
    assertEquals(4, writeAttempts)
    assertTrue(positionedPayloads.none { str(it.getValue("position").jsonObject, "new_path") == "src/config.py" })

    val report = handoff()
    assertEquals(1, report.uploaded.size)
    assertEquals(emptyList(), report.acked)
    assertEquals("blocked", outcomesOf(uploadedEnvelopes()[0])[0])
  }

  @Test
  fun `general note without a recoverable discussion id yields a blocked incomplete receipt`() {
    growGeneralNotes = false
    val result = publishJournalled()
    assertNull(result.entries[3].discussionId)

    val journal = reloaded()
    val envelope = journal.buildReceiptEnvelope(journal.attempts[0])
    assertEquals(listOf("posted", "posted", "posted", "blocked"), outcomesOf(envelope))
    val general = envelope.getValue("receipts").jsonArray[3].jsonObject
    assertEquals(801L, general.getValue("note_id").jsonPrimitive.long)
    assertEquals("${webUrl()}#note_801", str(general, "url"))
    assertTrue(str(general, "error").contains("receipt incomplete"))

    val report = handoff()
    assertEquals(1, report.uploaded.size)
    assertEquals(emptyList(), report.acked)
  }

  @Test
  fun `backfilled general note completes the receipt and handoff uploads then acks`() {
    growGeneralNotes = true
    val result = publishJournalled()
    assertEquals("disc-gen-801", result.entries[3].discussionId)

    val report = handoff()

    assertEquals(listOf(MrReviewJournal.receiptArtifactPath(30, batchId(), 1)), report.uploaded)
    assertEquals(listOf("b1"), report.acked)
    val envelope = uploadedEnvelopes()[0]
    MrReviewProtocol.validateReceipt(envelope.getValue("receipts").jsonArray[3])
    assertEquals(listOf("posted", "posted", "posted", "posted"), outcomesOf(envelope))
    assertEquals(batchId(), str(envelope, "batch_id"))
    assertEquals(1L, envelope.getValue("attempt").jsonPrimitive.long)
    val journal = reloaded()
    assertNotNull(journal.attempts[0].receiptUploadedAt)
    assertNotNull(journal.attempts[0].ackedAt)
  }

  @Test
  fun `receipt upload failure means no ack and a clean retry`() {
    growGeneralNotes = true
    publishJournalled()

    val failed = handoff(uploadOk = false)
    assertEquals(emptyList(), failed.uploaded)
    assertEquals(emptyList(), acks)
    assertTrue(failed.failed.any { it.contains("receipt upload failed") })
    assertNull(reloaded().attempts[0].receiptUploadedAt)

    val retry = handoff()
    assertEquals(1, retry.uploaded.size)
    assertEquals(listOf("b1"), retry.acked)
  }

  @Test
  fun `crash between upload and ack resumes without reupload`() {
    growGeneralNotes = true
    publishJournalled()

    val first = handoff(ackOk = false)
    assertEquals(1, first.uploaded.size)
    assertEquals(emptyList(), first.acked)
    assertNotNull(reloaded().attempts[0].receiptUploadedAt)
    assertNull(reloaded().attempts[0].ackedAt)

    val second = handoff()
    assertEquals(emptyList(), second.uploaded)
    assertEquals(1, uploads.size)
    assertEquals(listOf("b1"), second.acked)
    assertNotNull(reloaded().attempts[0].ackedAt)
  }

  @Test
  fun `mixed batch uploads the receipt but keeps the batch unacked`() {
    growGeneralNotes = true
    failPositionFor.add("src/config.py")
    positionFailCode = 500

    val result = publishJournalled()
    assertEquals("blocked", result.entries[0].outcome)
    assertEquals(3, result.posted)

    val report = handoff()
    assertEquals(1, report.uploaded.size)
    assertEquals(emptyList(), report.acked)
    val outcomes = outcomesOf(uploadedEnvelopes()[0])
    assertEquals("failed", outcomes[0])
    assertEquals(listOf("posted", "posted", "posted"), outcomes.drop(1))
  }

  @Test
  fun `envelope rebuild is byte identical`() {
    growGeneralNotes = true
    publishJournalled()

    val first = MrReviewProtocol.canonicalJson(reloaded().buildReceiptEnvelope(reloaded().attempts[0]))
    val second = MrReviewProtocol.canonicalJson(reloaded().buildReceiptEnvelope(reloaded().attempts[0]))
    assertEquals(first, second)
  }

  @Test
  fun `all duplicate retry produces recovered receipts and acks`() {
    growGeneralNotes = true
    publishJournalled()
    discussionsJson = JsonArray((0..3).map { idx -> markerDiscussion(idx, "disc-$idx", 600L + idx) })

    val second = publishJournalled()
    assertEquals(0, second.posted)
    assertEquals(4, second.duplicates)
    assertEquals(5, writeAttempts)

    val report = handoff()
    assertEquals(2, report.uploaded.size)
    assertEquals(listOf("b1", "b1"), report.acked)
    val retry = uploadedEnvelopes()[1]
    assertEquals(2L, retry.getValue("attempt").jsonPrimitive.long)
    assertEquals(listOf("recovered", "recovered", "recovered", "recovered"), outcomesOf(retry))
    assertEquals(listOf(600L, 601L, 602L, 603L),
                 retry.getValue("receipts").jsonArray.map { it.jsonObject.getValue("note_id").jsonPrimitive.long })
    assertTrue(retry.getValue("receipts").jsonArray.all { str(it.jsonObject, "url").isNotBlank() })
  }

  @Test
  fun `corrupt journal is replaced and markers still protect`() {
    growGeneralNotes = true
    publishJournalled()
    journalFile().writeText("{not json", Charsets.UTF_8)
    discussionsJson = JsonArray((0..3).map { idx -> markerDiscussion(idx, "disc-$idx", 600L + idx) })

    val result = publishJournalled()

    assertEquals(0, result.posted)
    assertEquals(4, result.duplicates)
    assertEquals(5, writeAttempts)
    assertTrue(reloaded().readable)
    assertEquals(1, reloaded().attempts.size)
  }
}
