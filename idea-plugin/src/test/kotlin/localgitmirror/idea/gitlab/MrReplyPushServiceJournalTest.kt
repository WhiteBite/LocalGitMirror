package localgitmirror.idea.gitlab

import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MrReplyPushServiceJournalTest {

  private lateinit var server: HttpServer
  private lateinit var conf: GitLabConfig.GitLabConf
  private lateinit var fixture: JsonObject
  private lateinit var liveDetail: JsonObject
  private lateinit var base: File
  private var discussionsJson: JsonArray = JsonArray(emptyList())
  private var posts = 0

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
    base = Files.createTempDirectory("lgm-journal-remediation").toFile()
  }

  @AfterTest
  fun down() {
    server.stop(0)
    base.deleteRecursively()
  }

  private fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

  private fun fixtureFile(): File {
    var dir: File? = File(System.getProperty("user.dir", "."))
    while (dir != null) {
      val candidate = File(dir, "tests/fixtures/mr_review_publish/publish_batch.json")
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    throw IllegalStateException("publish_batch.json fixture not found above the working directory")
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
        method == "GET" && path.startsWith("/api/v4/projects/p/repository/branches/") ->
          respond(ex, 200, """{"commit":{"id":"${targetTip()}"}}""")
        method == "GET" && path == "$detailPath/discussions" -> respond(ex, 200, discussionsJson.toString())
        method == "POST" && path == "$detailPath/discussions" -> {
          posts++
          respond(ex, 201, """{"id":"disc-$posts","notes":[{"id":${900 + posts}}]}""")
        }
        method == "POST" && threadNoteRe.matches(path) -> {
          posts++
          respond(ex, 201, """{"id":${700 + posts}}""")
        }
        method == "POST" && path == "$detailPath/notes" -> {
          posts++
          respond(ex, 201, """{"id":${800 + posts}}""")
        }
        method == "PUT" && resolveRe.matches(path) -> respond(ex, 200, """{"id":"resolved"}""")
        else -> respond(ex, 404, """{"message":"not found"}""")
      }
    } finally {
      ex.close()
    }
  }

  private fun str(obj: JsonObject, key: String): String = obj.getValue(key).jsonPrimitive.content

  private fun batchOf(): JsonObject = fixture.getValue("batch").jsonObject

  private fun batchId(): String = str(batchOf(), "batch_id")

  private fun targetTip(): String =
    fixture.getValue("live").jsonObject.getValue("target_tip").jsonPrimitive.content

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

  private fun docOf(): MrReviewHandoff.Document =
    MrReviewHandoff.parseAndValidate(
      MrReviewProtocol.canonicalJson(preparedBatch()),
      expectedIid = 30,
      expectedBatchId = batchId(),
      want = "v2",
    )

  private fun pendingBatch(doc: MrReviewHandoff.Document): MrReplyPushService.PendingBatch =
    MrReplyPushService.PendingBatch(
      item = localgitmirror.idea.mirror.MirrorPostboxApi.FileSyncItem(
        "id-1", "mr-replies/mr-!30-${batchId()}.review.json", 10, 10, 0),
      path = "mr-replies/mr-!30-${batchId()}.review.json",
      iid = 30,
      batchId = batchId(),
      parsed = doc,
    )

  private fun projectWithBase(path: String?): Project = java.lang.reflect.Proxy.newProxyInstance(
    Project::class.java.classLoader,
    arrayOf(Project::class.java),
  ) { _, method, _ -> if (method.name == "getBasePath") path else null } as Project

  private fun journalFileFor(repo: String): File =
    File(MrReviewJournal.journalDir(base.path, repo), "mr-!30-${batchId()}.journal.json")

  @Test
  fun `corrupt journal file is flagged as replaced on open`() {
    val jf = File(MrReviewJournal.journalDir(base.path, "repo"), "mr-!30-${batchId()}.journal.json")
    jf.parentFile.mkdirs()
    jf.writeText("{ this is not valid journal json", Charsets.UTF_8)
    val journal = MrReviewJournal.open(MrReviewJournal.journalDir(base.path, "repo"), 30, batchId(), docOf(), "b1", "p")
    assertTrue(journal.replacedCorrupt)
    assertTrue(journal.readable)
  }

  @Test
  fun `fresh journal is not flagged as replaced`() {
    val journal = MrReviewJournal.open(MrReviewJournal.journalDir(base.path, "repo"), 30, batchId(), docOf(), "b1", "p")
    assertFalse(journal.replacedCorrupt)
  }

  @Test
  fun `clean journal reopened is not flagged as replaced`() {
    val dir = MrReviewJournal.journalDir(base.path, "repo")
    val first = MrReviewJournal.open(dir, 30, batchId(), docOf(), "b1", "p")
    first.beginAttempt()
    val second = MrReviewJournal.open(dir, 30, batchId(), docOf(), "b1", "p")
    assertFalse(second.replacedCorrupt)
    assertTrue(second.readable)
  }

  @Test
  fun `v2 batch is skipped journal-less when the journal cannot be opened`() {
    val report = MrReplyPushService(projectWithBase(null))
      .pushReviewBatch(pendingBatch(docOf()), conf, "repo")
    assertEquals(0, report.posted)
    assertEquals(1, report.failed)
    assertNull(report.batch)
    assertTrue(report.details.any { it.contains("journal unavailable") }, report.details.toString())
  }

  @Test
  fun `corrupt journal push reports the replacement line`() {
    val jf = journalFileFor("repo")
    jf.parentFile.mkdirs()
    jf.writeText("{ corrupt", Charsets.UTF_8)
    val report = MrReplyPushService(projectWithBase(base.path))
      .pushReviewBatch(pendingBatch(docOf()), conf, "repo")
    assertTrue(
      report.details.any { it.contains("unreadable and has been replaced") },
      report.details.toString(),
    )
  }

  @Test
  fun `clean journal push does not report the replacement line`() {
    val report = MrReplyPushService(projectWithBase(base.path))
      .pushReviewBatch(pendingBatch(docOf()), conf, "repo")
    assertFalse(
      report.details.any { it.contains("unreadable and has been replaced") },
      report.details.toString(),
    )
  }
}
