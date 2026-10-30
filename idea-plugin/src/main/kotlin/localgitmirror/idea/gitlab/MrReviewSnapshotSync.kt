package localgitmirror.idea.gitlab

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import localgitmirror.idea.git.GitLocal
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * WORK-side review snapshot collector: MR detail A → exact object fetch into a
 * private staging bare → all discussion pages → MR detail B + target tip →
 * race gate → manifest (validated via [MrReviewProtocol]) → self-contained
 * code bundle. Blocking Git/HTTP work; must run off the EDT. One attempt per
 * call; any failure leaves no ready artifacts and never touches the user's
 * checkout or the ordinary mirror.
 */
object MrReviewSnapshotSync {

  sealed interface Preparation

  data class Ready(
    val manifestJson: String,
    val bundle: File,
    val snapshotId: String,
    val codeId: String,
    val versionFingerprint: String,
  ) : Preparation

  data class Failed(val code: String?, val message: String) : Preparation

  internal data class SnapshotFields(
    val repo: String,
    val gitlabUrl: String,
    val sourceProjectId: Long,
    val targetProjectId: Long,
    val iid: Long,
    val mrUrl: String,
    val title: String,
    val description: String?,
    val state: String,
    val draft: Boolean,
    val sourceBranch: String,
    val targetBranch: String,
    val sourceSha: String,
    val targetTipSha: String,
    val sourceFetchRef: String?,
    val diffRefs: JsonObject,
    val computedMergeBases: List<String>,
    val observedStartedAt: String,
    val observedFinishedAt: String,
    val mrUpdatedAt: String,
    val discussionsFetchedAt: String,
    val discussionsComplete: Boolean,
    val limitations: List<String>,
    val discussions: JsonArray,
  )

  private val SHA40 = Regex("[0-9a-f]{40}")
  private const val FORK_LIMITATION = "source branch fetched through target-project MR ref"

  fun prepare(
    conf: GitLabConfig.GitLabConf,
    gitRemoteUrl: String,
    repo: String,
    iid: Int,
    outputDir: File,
    maxCodeArtifactBytes: Long = MrReviewProtocol.CODE_ARTIFACT_MAX_BYTES,
  ): Preparation {
    if (gitRemoteUrl.isBlank()) return Failed(null, "git remote URL is blank")
    val startedAt = nowIso()
    var bundleFile: File? = null
    var succeeded = false
    var staging: File? = null
    try {
      val stagingDir = Files.createTempDirectory("lgm-review-staging-").toFile()
      staging = stagingDir
      val detailA = GitLabApi.getMrReviewDetail(conf, iid)
      if (detailA.code !in 200..299) {
        return Failed(null, "MR detail: HTTP ${detailA.code} ${detailA.message}")
      }
      diffRefsOrNotReady(detailA)?.let { return it }
      val refsA = detailA.diffRefs!!
      if (detailA.sourceSha.isBlank()) {
        return Failed("review-object-missing", "MR detail carries no source head SHA")
      }
      val tipResA = GitLabApi.getBranchTip(conf, detailA.targetBranch)
      if (tipResA.code !in 200..299 || tipResA.tip.isNullOrBlank()) {
        return Failed(null, "target branch ${detailA.targetBranch}: HTTP ${tipResA.code} ${tipResA.message}")
      }
      val tipA = tipResA.tip

      val tips = listOf(
        "source" to detailA.sourceSha,
        "target_tip" to tipA,
        "diff_base" to refsA.baseSha,
        "diff_start" to refsA.startSha,
        "diff_head" to refsA.headSha,
      )
      for ((label, sha) in tips) {
        if (!SHA40.matches(sha)) {
          return Failed("invalid-schema", "$label SHA from GitLab is not a 40-char lowercase hex: $sha")
        }
      }

      val fork = detailA.sourceProjectId != detailA.targetProjectId
      val sourceRemoteRef = if (fork) "refs/merge-requests/$iid/head" else "refs/heads/${detailA.sourceBranch}"
      val bare = File(staging, "review.git")
      val init = git(staging, 30, "init", "--bare", "review.git")
      if (!init.ok()) return Failed(null, "git init --bare failed: ${init.stderr.take(300)}")

      val fetch = git(
        bare, 300, "fetch", "--no-tags", gitRemoteUrl,
        "+$sourceRemoteRef:refs/review/source",
        "+refs/heads/${detailA.targetBranch}:refs/review/target",
      )
      if (!fetch.ok()) {
        return Failed(
          "review-object-missing",
          "fetch $sourceRemoteRef from $gitRemoteUrl failed: ${fetch.stderr.ifBlank { fetch.stdout }.take(300)}",
        )
      }
      val fetchedSource = git(bare, 10, "rev-parse", "refs/review/source")
      if (!fetchedSource.ok()) {
        return Failed("review-object-missing", "fetched source ref is missing: ${fetchedSource.stderr.take(300)}")
      }
      if (fetchedSource.stdout.trim() != detailA.sourceSha) {
        return Failed(
          "snapshot-raced",
          "source moved during collection: expected ${detailA.sourceSha}, fetched ${fetchedSource.stdout.trim()}",
        )
      }
      val fetchedTarget = git(bare, 10, "rev-parse", "refs/review/target")
      if (!fetchedTarget.ok()) {
        return Failed("review-object-missing", "fetched target ref is missing: ${fetchedTarget.stderr.take(300)}")
      }
      if (fetchedTarget.stdout.trim() != tipA) {
        return Failed(
          "snapshot-raced",
          "target branch moved during collection: expected $tipA, fetched ${fetchedTarget.stdout.trim()}",
        )
      }

      val discussions = GitLabApi.listMrDiscussionsRaw(conf, iid)
      if (discussions.code !in 200..299) {
        return Failed(null, "discussions: HTTP ${discussions.code} ${discussions.message}")
      }
      val discussionsFetchedAt = nowIso()

      val detailB = GitLabApi.getMrReviewDetail(conf, iid)
      if (detailB.code !in 200..299) {
        return Failed(null, "MR detail B: HTTP ${detailB.code} ${detailB.message}")
      }
      diffRefsOrNotReady(detailB)?.let { return it }
      val refsB = detailB.diffRefs!!
      val tipResB = GitLabApi.getBranchTip(conf, detailB.targetBranch)
      if (tipResB.code !in 200..299 || tipResB.tip.isNullOrBlank()) {
        return Failed(null, "target branch ${detailB.targetBranch}: HTTP ${tipResB.code} ${tipResB.message}")
      }
      val tipB = tipResB.tip

      raceDetail(detailA, refsA, tipA, detailB, refsB, tipB)?.let { return it }

      for ((label, sha) in tips) {
        val exists = git(bare, 10, "cat-file", "-e", "$sha^{commit}")
        if (!exists.ok()) {
          return Failed("review-object-missing", "$label commit $sha is not available after fetch")
        }
      }

      val mergeBases = git(bare, 30, "merge-base", "--all", detailB.sourceSha, tipB)
      val bases = when {
        mergeBases.ok() -> mergeBases.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        mergeBases.exitCode == 1 -> emptyList()
        else -> return Failed(null, "merge-base failed: ${mergeBases.stderr.take(300)}")
      }

      val limitations = mutableListOf<String>()
      if (fork) limitations += FORK_LIMITATION
      if (refsB.baseSha !in bases) {
        limitations += "gitlab diff base ${refsB.baseSha} is not among computed merge bases ${bases.joinToString(",")}"
      }

      val fields = SnapshotFields(
        repo = repo,
        gitlabUrl = conf.url.trim().trimEnd('/'),
        sourceProjectId = detailB.sourceProjectId,
        targetProjectId = detailB.targetProjectId,
        iid = detailB.iid,
        mrUrl = detailB.webUrl,
        title = detailB.title,
        description = detailB.description,
        state = detailB.state,
        draft = detailB.draft,
        sourceBranch = detailB.sourceBranch,
        targetBranch = detailB.targetBranch,
        sourceSha = detailB.sourceSha,
        targetTipSha = tipB,
        sourceFetchRef = if (fork) "refs/merge-requests/${detailB.iid}/head" else null,
        diffRefs = buildJsonObject {
          put("base_sha", refsB.baseSha)
          put("start_sha", refsB.startSha)
          put("head_sha", refsB.headSha)
        },
        computedMergeBases = bases,
        observedStartedAt = startedAt,
        observedFinishedAt = nowIso(),
        mrUpdatedAt = detailB.updatedAt,
        discussionsFetchedAt = discussionsFetchedAt,
        discussionsComplete = true,
        limitations = limitations,
        discussions = protocolDiscussions(discussions.discussions),
      )
      val snapshot = finalizeSnapshot(buildSnapshotPayload(fields))
      val snapshotId = snapshot.getValue("snapshot_id").jsonPrimitive.content
      val codeId = snapshot.getValue("code_id").jsonPrimitive.content
      val fingerprint = snapshot.getValue("version_fingerprint").jsonPrimitive.content

      for (ref in listOf("refs/review/diff-base", "refs/review/diff-start", "refs/review/diff-head")) {
        val sha = when (ref) {
          "refs/review/diff-base" -> refsB.baseSha
          "refs/review/diff-start" -> refsB.startSha
          else -> refsB.headSha
        }
        val update = git(bare, 10, "update-ref", ref, sha)
        if (!update.ok()) return Failed(null, "git update-ref $ref failed: ${update.stderr.take(300)}")
      }
      if (!outputDir.isDirectory && !outputDir.mkdirs()) {
        return Failed(null, "cannot create output directory ${outputDir.path}")
      }
      val bundle = File(outputDir, "$codeId.bundle")
      bundleFile = bundle
      val create = git(
        bare, 300, "bundle", "create", bundle.absolutePath,
        "refs/review/source", "refs/review/target",
        "refs/review/diff-base", "refs/review/diff-start", "refs/review/diff-head",
      )
      if (!create.ok()) {
        return Failed(null, "git bundle create failed: ${create.stderr.ifBlank { create.stdout }.take(300)}")
      }
      val size = bundle.length()
      if (size > maxCodeArtifactBytes) {
        return Failed(
          "review-package-too-large",
          "code bundle is $size bytes, above the $maxCodeArtifactBytes limit",
        )
      }
      succeeded = true
      return Ready(
        manifestJson = MrReviewProtocol.canonicalJson(snapshot),
        bundle = bundle,
        snapshotId = snapshotId,
        codeId = codeId,
        versionFingerprint = fingerprint,
      )
    } catch (e: MrReviewProtocolException) {
      return Failed(e.code, e.message ?: e.code)
    } catch (t: Throwable) {
      localgitmirror.idea.net.Cancellation.rethrow(t)
      return Failed(null, t.message ?: "error")
    } finally {
      if (!succeeded) bundleFile?.delete()
      staging?.let { runCatching { it.deleteRecursively() } }
    }
  }

  private fun diffRefsOrNotReady(detail: GitLabApi.MrReviewDetail): Failed? {
    val refs = detail.diffRefs ?: return Failed("diff-not-ready", "MR !${detail.iid} diff refs are not computed yet")
    if (refs.baseSha.isBlank() || refs.startSha.isBlank() || refs.headSha.isBlank()) {
      return Failed("diff-not-ready", "MR !${detail.iid} diff refs are not computed yet")
    }
    return null
  }

  private fun raceDetail(
    a: GitLabApi.MrReviewDetail,
    refsA: GitLabApi.DiffRefs,
    tipA: String,
    b: GitLabApi.MrReviewDetail,
    refsB: GitLabApi.DiffRefs,
    tipB: String,
  ): Failed? {
    val diffs = listOf(
      "state" to (a.state to b.state),
      "draft" to (a.draft to b.draft),
      "updated_at" to (a.updatedAt to b.updatedAt),
      "source_sha" to (a.sourceSha to b.sourceSha),
      "source_project_id" to (a.sourceProjectId to b.sourceProjectId),
      "target_project_id" to (a.targetProjectId to b.targetProjectId),
      "target_branch" to (a.targetBranch to b.targetBranch),
      "diff_base" to (refsA.baseSha to refsB.baseSha),
      "diff_start" to (refsA.startSha to refsB.startSha),
      "diff_head" to (refsA.headSha to refsB.headSha),
      "target_tip" to (tipA to tipB),
    )
    for ((field, pair) in diffs) {
      if (pair.first != pair.second) {
        return Failed("snapshot-raced", "MR changed during collection: $field ${pair.first} -> ${pair.second}")
      }
    }
    return null
  }

  internal fun buildSnapshotPayload(f: SnapshotFields): JsonObject = buildJsonObject {
    put("schema_version", 1)
    put("repo", f.repo)
    put("gitlab_url", f.gitlabUrl)
    put("source_project_id", f.sourceProjectId)
    put("target_project_id", f.targetProjectId)
    put("iid", f.iid)
    put("mr_url", f.mrUrl)
    put("title", f.title)
    put("description", JsonPrimitive(f.description))
    put("state", f.state)
    put("draft", f.draft)
    put("source_branch", f.sourceBranch)
    put("target_branch", f.targetBranch)
    put("source_sha", f.sourceSha)
    put("target_tip_sha", f.targetTipSha)
    put("source_fetch_ref", JsonPrimitive(f.sourceFetchRef))
    put("gitlab_diff_refs", f.diffRefs)
    put("computed_merge_bases", JsonArray(f.computedMergeBases.map { JsonPrimitive(it) }))
    put("comparison_base_sha", f.diffRefs.getValue("base_sha").jsonPrimitive.content)
    put("comparison_kind", "gitlab-diff")
    put("observed_started_at", f.observedStartedAt)
    put("observed_finished_at", f.observedFinishedAt)
    put("mr_updated_at", f.mrUpdatedAt)
    put("discussions_fetched_at", f.discussionsFetchedAt)
    put("discussions_complete", f.discussionsComplete)
    put("limitations", JsonArray(f.limitations.map { JsonPrimitive(it) }))
    put("discussions", f.discussions)
  }

  /** Computes the three digests over the digest-less payload, inserts them and verifies the closed snapshot. */
  internal fun finalizeSnapshot(payload: JsonObject): JsonObject {
    val snapshot = payload.toMutableMap()
    snapshot["code_id"] = JsonPrimitive(MrReviewProtocol.computeCodeId(payload))
    snapshot["version_fingerprint"] = JsonPrimitive(MrReviewProtocol.computeVersionFingerprint(payload))
    snapshot["snapshot_id"] = JsonPrimitive(MrReviewProtocol.computeSnapshotId(JsonObject(snapshot)))
    return MrReviewProtocol.verifySnapshot(JsonObject(snapshot))
  }

  internal fun protocolDiscussions(raw: List<JsonObject>): JsonArray =
    JsonArray(raw.map(::protocolDiscussion))

  private fun protocolDiscussion(disc: JsonObject): JsonObject = buildJsonObject {
    put("discussion_id", disc["id"]?.jsonPrimitive?.contentOrNull ?: "")
    val notes = disc["notes"]?.jsonArray ?: JsonArray(emptyList())
    put("notes", JsonArray(notes.map { protocolNote(it.jsonObject) }))
  }

  private fun protocolNote(note: JsonObject): JsonObject = buildJsonObject {
    put("note_id", note["id"]?.jsonPrimitive?.longOrNull ?: 0L)
    put("body", note["body"]?.jsonPrimitive?.contentOrNull ?: "")
    put("author", note["author"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: "?")
    put("created_at", note["created_at"]?.jsonPrimitive?.contentOrNull ?: "")
    put("system", note["system"]?.jsonPrimitive?.booleanOrNull ?: false)
    put("resolvable", note["resolvable"]?.jsonPrimitive?.booleanOrNull ?: false)
    put("resolved", note["resolved"]?.jsonPrimitive?.booleanOrNull ?: false)
    put("position", protocolPosition(note["position"]) ?: JsonNull)
  }

  /**
   * GitLab text positions map to the protocol shape; the side is derived from
   * which line GitLab set. Positions that cannot be represented (no line on
   * the derived side, missing paths or refs) become null rather than a
   * distorted anchor.
   */
  private fun protocolPosition(el: JsonElement?): JsonElement? {
    if (el !is JsonObject) return null
    val oldPath = el["old_path"]?.jsonPrimitive?.contentOrNull
    val newPath = el["new_path"]?.jsonPrimitive?.contentOrNull
    val oldLine = el["old_line"]?.jsonPrimitive?.longOrNull
    val newLine = el["new_line"]?.jsonPrimitive?.longOrNull
    val side = if (newLine != null) "new" else "old"
    if (oldPath == null && newPath == null) return null
    if (side == "new" && (newPath == null || newLine == null)) return null
    if (side == "old" && (oldPath == null || oldLine == null)) return null
    val base = el["base_sha"]?.jsonPrimitive?.contentOrNull ?: return null
    val start = el["start_sha"]?.jsonPrimitive?.contentOrNull ?: return null
    val head = el["head_sha"]?.jsonPrimitive?.contentOrNull ?: return null
    return buildJsonObject {
      put("old_path", JsonPrimitive(oldPath))
      put("new_path", JsonPrimitive(newPath))
      put("old_line", JsonPrimitive(oldLine))
      put("new_line", JsonPrimitive(newLine))
      put("side", side)
      put("base_sha", base)
      put("start_sha", start)
      put("head_sha", head)
    }
  }

  private fun nowIso(): String = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

  private fun git(workDir: File, timeoutSeconds: Long, vararg args: String): GitLocal.Result =
    GitLocal.runRaw(workDir, timeoutSeconds, *args)
}
