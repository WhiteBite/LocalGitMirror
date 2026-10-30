package localgitmirror.idea.gitlab

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

class MrReviewSnapshotSyncTest {
  @TempDir lateinit var tmp: File

  private lateinit var server: HttpServer
  private lateinit var outputDir: File
  private lateinit var conf: GitLabConfig.GitLabConf

  private var detailProvider: (Int) -> String = { "" }
  private var branchTipProvider: (Int) -> String = { "" }
  private var discussionsPages: List<String> = emptyList()
  private var discussionsErrorPages: Set<Int> = emptySet()
  private var discussionsStarted: CountDownLatch? = null
  private var discussionsGate: CountDownLatch? = null
  private var detailCalls = 0
  private var branchTipCalls = 0

  @BeforeTest
  fun up() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/v4") { ex -> handle(ex) }
    server.start()
    outputDir = File(tmp, "out")
    conf = GitLabConfig.GitLabConf("http://127.0.0.1:${server.address.port}", "p", "t")
  }

  @AfterTest
  fun down() {
    server.stop(0)
  }

  private fun handle(ex: HttpExchange) {
    try {
      val path = ex.requestURI.path
      when {
        path == "/api/v4/projects/p/merge_requests/30" ->
          respond(ex, 200, detailProvider(++detailCalls))
        path == "/api/v4/projects/p/repository/branches/release/2.5" ->
          respond(ex, 200, branchTipProvider(++branchTipCalls))
        path == "/api/v4/projects/p/merge_requests/30/discussions" -> {
          val page = ex.requestURI.query?.substringAfter("page=")?.toIntOrNull() ?: 1
          discussionsStarted?.countDown()
          discussionsGate?.await(10, TimeUnit.SECONDS)
          if (page in discussionsErrorPages) {
            respond(ex, 500, "boom")
            return
          }
          val body = discussionsPages.getOrNull(page - 1) ?: "[]"
          if (page < discussionsPages.size) ex.responseHeaders.add("X-Next-Page", (page + 1).toString())
          respond(ex, 200, body)
        }
        else -> respond(ex, 404, "not found")
      }
    } finally {
      ex.close()
    }
  }

  private fun respond(ex: HttpExchange, code: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    ex.responseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
  }

  private data class Origin(
    val dir: File,
    val base: String,
    val featureTip: String,
    val targetTip: String,
  )

  private fun createOrigin(fork: Boolean = false): Origin {
    val scratch = File(tmp, "scratch")
    scratch.mkdirs()
    git(scratch, "init", "-b", "release/2.5")
    git(scratch, "config", "user.email", "review@example.com")
    git(scratch, "config", "user.name", "Review")
    git(scratch, "config", "core.autocrlf", "false")
    File(scratch, "a.txt").writeText("base\n")
    commit(scratch, "base")
    val base = git(scratch, "rev-parse", "HEAD").trim()
    git(scratch, "checkout", "-b", "feature-review")
    File(scratch, "b.txt").writeText("feature\n")
    commit(scratch, "feature")
    val featureTip = git(scratch, "rev-parse", "HEAD").trim()
    git(scratch, "checkout", "release/2.5")
    File(scratch, "c.txt").writeText("target\n")
    commit(scratch, "target advances")
    val targetTip = git(scratch, "rev-parse", "HEAD").trim()
    val origin = File(tmp, "origin.git")
    git(scratch, "init", "--bare", origin.absolutePath)
    git(scratch, "push", origin.absolutePath, "refs/heads/release/2.5:refs/heads/release/2.5")
    if (fork) {
      git(scratch, "push", origin.absolutePath, "refs/heads/feature-review:refs/merge-requests/30/head")
    } else {
      git(scratch, "push", origin.absolutePath, "refs/heads/feature-review:refs/heads/feature-review")
    }
    git(origin, "symbolic-ref", "HEAD", "refs/heads/release/2.5")
    return Origin(origin, base, featureTip, targetTip)
  }

  private fun cloneUser(origin: File): File {
    val user = File(tmp, "user")
    git(tmp, "clone", origin.absolutePath, user.absolutePath)
    return user
  }

  private fun mrDetailJson(
    sha: String,
    base: String,
    start: String,
    head: String,
    sourceProject: Long = 42,
    updatedAt: String = "2026-10-30T08:59:00Z",
    diffRefs: String? = null,
  ): String {
    val port = server.address.port
    val refs = diffRefs ?: """{"base_sha":"$base","start_sha":"$start","head_sha":"$head"}"""
    return """{"iid":30,"source_project_id":$sourceProject,"target_project_id":42,""" +
      """"web_url":"http://127.0.0.1:$port/group/p/-/merge_requests/30",""" +
      """"title":"Add review workspace","description":"First cut","state":"opened","draft":false,""" +
      """"source_branch":"feature-review","target_branch":"release/2.5","sha":"$sha",""" +
      """"updated_at":"$updatedAt","diff_refs":$refs}"""
  }

  private fun branchTipJson(tip: String): String = """{"commit":{"id":"$tip"}}"""

  private fun discussionsJson(base: String, start: String, head: String): String =
    """[{"id":"04ee218e8d3197c60a4ff9c175a5f35c7aeec8f9","notes":[""" +
      """{"id":901,"body":"Не переносить в release/2.5 без миграции",""" +
      """"author":{"name":"Иван Петров"},"created_at":"2026-10-30T09:10:00Z",""" +
      """"system":false,"resolvable":true,"resolved":false,""" +
      """"position":{"position_type":"text","old_path":"src/config.py","new_path":"src/config.py",""" +
      """"old_line":11,"new_line":12,"base_sha":"$base","start_sha":"$start","head_sha":"$head"}}]}]"""

  private fun fixtureDir(): File {
    var dir: File? = File(System.getProperty("user.dir", "."))
    while (dir != null) {
      val candidate = File(dir, "tests/fixtures/mr_review_protocol")
      if (candidate.isDirectory) return candidate
      dir = dir.parentFile
    }
    throw IllegalStateException("tests/fixtures/mr_review_protocol not found above the working directory")
  }

  private fun fixture(name: String): kotlinx.serialization.json.JsonObject =
    MrReviewProtocol.parse(File(fixtureDir(), "$name.json").readText()).jsonObject

  private fun expectedDigests(f: kotlinx.serialization.json.JsonObject): Map<String, String> =
    f.getValue("expected_digests").jsonObject.mapValues { it.value.jsonPrimitive.content }

  private fun fieldsFromFixture(obj: kotlinx.serialization.json.JsonObject): MrReviewSnapshotSync.SnapshotFields {
    fun s(k: String) = obj.getValue(k).jsonPrimitive.content
    fun l(k: String) = s(k).toLong()
    fun b(k: String) = s(k) == "true"
    return MrReviewSnapshotSync.SnapshotFields(
      repo = s("repo"),
      gitlabUrl = s("gitlab_url"),
      sourceProjectId = l("source_project_id"),
      targetProjectId = l("target_project_id"),
      iid = l("iid"),
      mrUrl = s("mr_url"),
      title = s("title"),
      description = if (obj.getValue("description") is JsonNull) null else s("description"),
      state = s("state"),
      draft = b("draft"),
      sourceBranch = s("source_branch"),
      targetBranch = s("target_branch"),
      sourceSha = s("source_sha"),
      targetTipSha = s("target_tip_sha"),
      sourceFetchRef = if (obj.getValue("source_fetch_ref") is JsonNull) null else s("source_fetch_ref"),
      diffRefs = obj.getValue("gitlab_diff_refs").jsonObject,
      computedMergeBases = obj.getValue("computed_merge_bases").jsonArray.map { it.jsonPrimitive.content },
      observedStartedAt = s("observed_started_at"),
      observedFinishedAt = s("observed_finished_at"),
      mrUpdatedAt = s("mr_updated_at"),
      discussionsFetchedAt = s("discussions_fetched_at"),
      discussionsComplete = b("discussions_complete"),
      limitations = obj.getValue("limitations").jsonArray.map { it.jsonPrimitive.content },
      discussions = obj.getValue("discussions").jsonArray,
    )
  }

  private fun refsOf(dir: File): String =
    git(dir, "for-each-ref", "--format=%(refname) %(objectname)").trim()

  private fun userState(dir: File): String =
    refsOf(dir) + "\nHEAD " + git(dir, "rev-parse", "HEAD").trim() +
      "\n" + git(dir, "status", "--porcelain").trim()

  private fun assertNothingReady() {
    val left = outputDir.listFiles()
    assertTrue(left.isNullOrEmpty(), "no artifacts may survive a failure, found: ${left?.joinToString { it.name }}")
  }

  private fun assertReposUnchanged(origin: File, user: File, userBefore: String, originBefore: String) {
    assertEquals(userBefore, userState(user), "user checkout must stay untouched")
    assertEquals(originBefore, refsOf(origin), "origin refs must stay untouched")
  }

  private fun prepare(origin: Origin, maxBytes: Long = MrReviewProtocol.CODE_ARTIFACT_MAX_BYTES) =
    MrReviewSnapshotSync.prepare(conf, origin.dir.absolutePath, "test-repo", 30, outputDir, maxBytes)

  @Test
  fun `manifest building reproduces the shared fixture digests`() {
    for (name in listOf(
      "snapshot_fork_mr", "snapshot_non_default_target", "snapshot_deletion",
      "snapshot_rename", "snapshot_unicode_null",
    )) {
      val f = fixture(name)
      val fields = fieldsFromFixture(f.getValue("object").jsonObject)
      val snapshot = MrReviewSnapshotSync.finalizeSnapshot(MrReviewSnapshotSync.buildSnapshotPayload(fields))
      assertEquals(expectedDigests(f), MrReviewProtocol.snapshotDigests(snapshot), name)
    }
  }

  @Test
  fun `prepare collects a consistent snapshot with an isolated self contained bundle`() {
    val origin = createOrigin()
    val user = cloneUser(origin.dir)
    val userBefore = userState(user)
    val originBefore = refsOf(origin.dir)
    detailProvider = { mrDetailJson(origin.featureTip, origin.base, origin.base, origin.featureTip) }
    branchTipProvider = { branchTipJson(origin.targetTip) }
    discussionsPages = listOf(discussionsJson(origin.base, origin.base, origin.featureTip))

    val result = prepare(origin)

    val ready = assertIs<MrReviewSnapshotSync.Ready>(result)
    val snapshot = MrReviewProtocol.verifySnapshot(MrReviewProtocol.parse(ready.manifestJson).jsonObject)
    assertEquals(ready.snapshotId, snapshot.getValue("snapshot_id").jsonPrimitive.content)
    assertEquals(ready.codeId, snapshot.getValue("code_id").jsonPrimitive.content)
    assertEquals(origin.featureTip, snapshot.getValue("source_sha").jsonPrimitive.content)
    assertEquals(origin.targetTip, snapshot.getValue("target_tip_sha").jsonPrimitive.content)
    assertEquals(listOf(origin.base), snapshot.getValue("computed_merge_bases").jsonArray.map { it.jsonPrimitive.content })
    assertEquals(JsonNull, snapshot.getValue("source_fetch_ref"))
    assertEquals(emptyList<String>(), snapshot.getValue("limitations").jsonArray.map { it.jsonPrimitive.content })
    val note = snapshot.getValue("discussions").jsonArray[0].jsonObject
      .getValue("notes").jsonArray[0].jsonObject
    assertEquals("Иван Петров", note.getValue("author").jsonPrimitive.content)
    assertEquals(901L, note.getValue("note_id").jsonPrimitive.content.toLong())
    val position = note.getValue("position").jsonObject
    assertEquals("new", position.getValue("side").jsonPrimitive.content)
    assertEquals(11L, position.getValue("old_line").jsonPrimitive.content.toLong())
    assertEquals(12L, position.getValue("new_line").jsonPrimitive.content.toLong())
    assertEquals(origin.base, position.getValue("base_sha").jsonPrimitive.content)

    assertEquals(File(outputDir, "${ready.codeId}.bundle"), ready.bundle)
    assertTrue(ready.bundle.isFile)
    assertTrue(ready.bundle.length() in 1..MrReviewProtocol.CODE_ARTIFACT_MAX_BYTES)
    val verifyRepo = File(tmp, "bundle-verify.git")
    git(tmp, "init", "--bare", verifyRepo.absolutePath)
    git(verifyRepo, "fetch", "-q", ready.bundle.absolutePath, "refs/review/*:refs/review/*")
    for (sha in listOf(origin.featureTip, origin.targetTip, origin.base)) {
      git(verifyRepo, "cat-file", "-e", "$sha^{commit}")
    }
    assertEquals(listOf(ready.bundle.name), outputDir.listFiles()!!.map { it.name })
    assertReposUnchanged(origin.dir, user, userBefore, originBefore)
  }

  @Test
  fun `fork source is fetched through the MR ref and recorded with a limitation`() {
    val origin = createOrigin(fork = true)
    val user = cloneUser(origin.dir)
    val userBefore = userState(user)
    val originBefore = refsOf(origin.dir)
    detailProvider = { mrDetailJson(origin.featureTip, origin.base, origin.base, origin.featureTip, sourceProject = 77) }
    branchTipProvider = { branchTipJson(origin.targetTip) }

    val result = prepare(origin)

    val ready = assertIs<MrReviewSnapshotSync.Ready>(result)
    val snapshot = MrReviewProtocol.verifySnapshot(MrReviewProtocol.parse(ready.manifestJson).jsonObject)
    assertEquals("refs/merge-requests/30/head", snapshot.getValue("source_fetch_ref").jsonPrimitive.content)
    assertEquals(
      listOf("source branch fetched through target-project MR ref"),
      snapshot.getValue("limitations").jsonArray.map { it.jsonPrimitive.content },
    )
    assertEquals(origin.featureTip, snapshot.getValue("source_sha").jsonPrimitive.content)
    assertReposUnchanged(origin.dir, user, userBefore, originBefore)
  }

  @Test
  fun `null and empty diff refs fail as diff-not-ready`() {
    val origin = createOrigin()
    val user = cloneUser(origin.dir)
    val userBefore = userState(user)
    val originBefore = refsOf(origin.dir)
    branchTipProvider = { branchTipJson(origin.targetTip) }

    detailProvider = { mrDetailJson(origin.featureTip, "", "", "") }
    val empty = prepare(origin)
    assertEquals("diff-not-ready", assertIs<MrReviewSnapshotSync.Failed>(empty).code)

    detailProvider = { mrDetailJson(origin.featureTip, origin.base, origin.base, origin.featureTip, diffRefs = "null") }
    val nullRefs = prepare(origin)
    assertEquals("diff-not-ready", assertIs<MrReviewSnapshotSync.Failed>(nullRefs).code)

    assertNothingReady()
    assertReposUnchanged(origin.dir, user, userBefore, originBefore)
  }

  @Test
  fun `MR mutation between detail A and B fails as snapshot-raced`() {
    val origin = createOrigin()
    val user = cloneUser(origin.dir)
    val userBefore = userState(user)
    val originBefore = refsOf(origin.dir)
    detailProvider = { call ->
      if (call == 1) mrDetailJson(origin.featureTip, origin.base, origin.base, origin.featureTip)
      else mrDetailJson(origin.featureTip, origin.base, origin.base, origin.featureTip, updatedAt = "2026-10-30T10:00:00Z")
    }
    branchTipProvider = { branchTipJson(origin.targetTip) }

    val result = prepare(origin)

    val failed = assertIs<MrReviewSnapshotSync.Failed>(result)
    assertEquals("snapshot-raced", failed.code)
    assertTrue(failed.message.contains("updated_at"), failed.message)
    assertNothingReady()
    assertReposUnchanged(origin.dir, user, userBefore, originBefore)
  }

  @Test
  fun `target tip movement between A and B fails as snapshot-raced`() {
    val origin = createOrigin()
    val user = cloneUser(origin.dir)
    val userBefore = userState(user)
    val originBefore = refsOf(origin.dir)
    detailProvider = { mrDetailJson(origin.featureTip, origin.base, origin.base, origin.featureTip) }
    branchTipProvider = { call -> branchTipJson(if (call == 1) origin.targetTip else origin.base) }

    val result = prepare(origin)

    val failed = assertIs<MrReviewSnapshotSync.Failed>(result)
    assertEquals("snapshot-raced", failed.code)
    assertTrue(failed.message.contains("target_tip"), failed.message)
    assertNothingReady()
    assertReposUnchanged(origin.dir, user, userBefore, originBefore)
  }

  @Test
  fun `source ref movement during fetch fails as snapshot-raced`() {
    val origin = createOrigin()
    val user = cloneUser(origin.dir)
    val userBefore = userState(user)
    val originBefore = refsOf(origin.dir)
    detailProvider = { mrDetailJson(origin.base, origin.base, origin.base, origin.base) }
    branchTipProvider = { branchTipJson(origin.targetTip) }

    val result = prepare(origin)

    val failed = assertIs<MrReviewSnapshotSync.Failed>(result)
    assertEquals("snapshot-raced", failed.code)
    assertTrue(failed.message.contains("source moved"), failed.message)
    assertNothingReady()
    assertReposUnchanged(origin.dir, user, userBefore, originBefore)
  }

  @Test
  fun `missing commit after fetch fails as review-object-missing`() {
    val origin = createOrigin()
    val user = cloneUser(origin.dir)
    val userBefore = userState(user)
    val originBefore = refsOf(origin.dir)
    val missing = "0".repeat(40)
    detailProvider = { mrDetailJson(origin.featureTip, missing, origin.base, origin.featureTip) }
    branchTipProvider = { branchTipJson(origin.targetTip) }

    val result = prepare(origin)

    val failed = assertIs<MrReviewSnapshotSync.Failed>(result)
    assertEquals("review-object-missing", failed.code)
    assertTrue(failed.message.contains(missing), failed.message)
    assertNothingReady()
    assertReposUnchanged(origin.dir, user, userBefore, originBefore)
  }

  @Test
  fun `oversized bundle fails as review-package-too-large before reading its bytes`() {
    val origin = createOrigin()
    val user = cloneUser(origin.dir)
    val userBefore = userState(user)
    val originBefore = refsOf(origin.dir)
    detailProvider = { mrDetailJson(origin.featureTip, origin.base, origin.base, origin.featureTip) }
    branchTipProvider = { branchTipJson(origin.targetTip) }

    val result = prepare(origin, maxBytes = 16)

    val failed = assertIs<MrReviewSnapshotSync.Failed>(result)
    assertEquals("review-package-too-large", failed.code)
    assertTrue(failed.message.contains("bytes"), failed.message)
    assertNothingReady()
    assertReposUnchanged(origin.dir, user, userBefore, originBefore)
  }

  @Test
  fun `discussion page failure fails closed without a snapshot`() {
    val origin = createOrigin()
    val user = cloneUser(origin.dir)
    val userBefore = userState(user)
    val originBefore = refsOf(origin.dir)
    detailProvider = { mrDetailJson(origin.featureTip, origin.base, origin.base, origin.featureTip) }
    branchTipProvider = { branchTipJson(origin.targetTip) }
    discussionsPages = listOf(
      discussionsJson(origin.base, origin.base, origin.featureTip),
      """[{"id":"second","notes":[{"id":902,"body":"page two","author":{"name":"R"},"created_at":"2026-10-30T09:11:00Z","system":false,"resolvable":false,"resolved":false,"position":null}]}]""",
    )
    discussionsErrorPages = setOf(2)

    val result = prepare(origin)

    val failed = assertIs<MrReviewSnapshotSync.Failed>(result)
    assertEquals(null, failed.code)
    assertTrue(failed.message.contains("discussions"), failed.message)
    assertNothingReady()
    assertReposUnchanged(origin.dir, user, userBefore, originBefore)
  }

  @Test
  fun `cancellation mid collect leaves refs untouched and no ready artifacts`() {
    val origin = createOrigin()
    val user = cloneUser(origin.dir)
    val userBefore = userState(user)
    val originBefore = refsOf(origin.dir)
    detailProvider = { mrDetailJson(origin.featureTip, origin.base, origin.base, origin.featureTip) }
    branchTipProvider = { branchTipJson(origin.targetTip) }
    discussionsPages = listOf(discussionsJson(origin.base, origin.base, origin.featureTip))
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    discussionsStarted = started
    discussionsGate = release
    var outcome: Any? = null
    val worker = Thread {
      outcome = try {
        prepare(origin)
      } catch (t: Throwable) {
        t
      }
    }
    worker.start()
    assertTrue(started.await(10, TimeUnit.SECONDS), "collector never reached the discussions call")
    worker.interrupt()
    release.countDown()
    worker.join(15000)
    assertTrue(outcome !is MrReviewSnapshotSync.Ready, "cancelled collection must not yield a snapshot, got $outcome")
    assertNothingReady()
    assertReposUnchanged(origin.dir, user, userBefore, originBefore)
  }

  @Test
  fun `cancellation propagates as ProcessCanceledException instead of a failure result`() {
    val origin = createOrigin()
    val user = cloneUser(origin.dir)
    val userBefore = userState(user)
    val originBefore = refsOf(origin.dir)
    detailProvider = { mrDetailJson(origin.featureTip, origin.base, origin.base, origin.featureTip) }
    branchTipProvider = { branchTipJson(origin.targetTip) }
    Thread.currentThread().interrupt()
    try {
      assertFailsWith<com.intellij.openapi.progress.ProcessCanceledException> { prepare(origin) }
    } finally {
      Thread.interrupted()
    }
    assertNothingReady()
    assertReposUnchanged(origin.dir, user, userBefore, originBefore)
  }

  @Test
  fun `gitlab base outside computed merge bases becomes a limitation`() {
    val origin = createOrigin()
    val user = cloneUser(origin.dir)
    val userBefore = userState(user)
    val originBefore = refsOf(origin.dir)
    detailProvider = { mrDetailJson(origin.featureTip, origin.targetTip, origin.base, origin.featureTip) }
    branchTipProvider = { branchTipJson(origin.targetTip) }

    val result = prepare(origin)

    val ready = assertIs<MrReviewSnapshotSync.Ready>(result)
    val snapshot = MrReviewProtocol.verifySnapshot(MrReviewProtocol.parse(ready.manifestJson).jsonObject)
    val limitations = snapshot.getValue("limitations").jsonArray.map { it.jsonPrimitive.content }
    assertEquals(1, limitations.size)
    assertTrue(limitations[0].contains(origin.targetTip), limitations[0])
    assertEquals(origin.targetTip, snapshot.getValue("comparison_base_sha").jsonPrimitive.content)
    assertEquals(listOf(origin.base), snapshot.getValue("computed_merge_bases").jsonArray.map { it.jsonPrimitive.content })
    assertReposUnchanged(origin.dir, user, userBefore, originBefore)
  }

  private fun commit(dir: File, message: String) {
    git(dir, "add", ".")
    git(dir, "commit", "-m", message)
  }

  private fun git(dir: File, vararg args: String): String {
    val process = ProcessBuilder(listOf("git", *args)).directory(dir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output }
    return output
  }
}
