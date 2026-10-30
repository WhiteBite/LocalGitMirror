package localgitmirror.idea.gitlab

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorPostboxApi
import localgitmirror.idea.settings.MirrorProjectSettingsService
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.MrPublishReceipt
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Work-machine side of the review loop: downloads the agent's reply files
 * (`mr-replies/mr-!N.md`) from the Mirror postbox and pushes them to GitLab —
 * replies into existing threads, brand-new anchored discussions and general
 * notes.
 *
 * Exactly-once: every posted note carries an invisible `<!-- lgm:<hash> -->`
 * marker; before posting, existing discussion notes and the per-project
 * ledger are scanned for it, so retries (crash mid-batch, lost HTTP response,
 * re-click) never duplicate comments. The postbox item is acked (deleted)
 * once every reply in the file reached a terminal outcome (posted, duplicate,
 * dead thread); replies still retryable keep the file outstanding.
 * Anchored replies duplicating a live unresolved thread (±3 lines) are skipped.
 */
class MrReplyPushService(private val project: Project) {

  enum class Outcome { POSTED, DUP, DEAD, FAILED }

  data class FileReport(
    val path: String,
    val posted: Int,
    val dupSkipped: Int,
    val skipped: Int,
    val failed: Int,
    val details: List<String>,
    val parseErrors: Int = 0,
    val quiet: Boolean = false,
    val outcomes: Map<MrReplies.Reply, Outcome> = emptyMap(),
    val notAnchored: List<String> = emptyList(),
    val batch: MrReviewBatchPublisher.BatchResult? = null,
  ) {
    val clean: Boolean get() = failed == 0 && parseErrors == 0
  }

  data class SkippedItem(val path: String, val reason: String)

  sealed interface PendingRepliesResult {
    data class Available(
      val items: List<PendingReplies>,
      val skipped: List<SkippedItem>,
      val batches: List<PendingBatch> = emptyList(),
    ) : PendingRepliesResult
    data class Unavailable(val reason: String) : PendingRepliesResult
  }

  sealed interface PendingCount {
    data class Available(val count: Int) : PendingCount
    data class Unavailable(val reason: String) : PendingCount
  }

  fun pushInBackground(onDone: ((List<FileReport>) -> Unit)? = null) {
    ApplicationManager.getApplication().executeOnPooledThread {
      val reports = runCatching { pushAll() }
        .getOrElse { listOf(FileReport("(service)", 0, 0, 0, 1, listOf(it.message ?: "error"))) }
      UIUtil.invokeLaterIfNeeded {
        if (project.isDisposed) return@invokeLaterIfNeeded
        onDone?.invoke(reports)
        val visible = reports.filter { !it.quiet }
        if (visible.isNotEmpty()) notifySummary(visible)
      }
    }
  }

  /** Synchronous single pass over the postbox; for background pollers. */
  fun pushOnce(): List<FileReport> = runCatching { pushAll() }
    .getOrElse { listOf(FileReport("(service)", 0, 0, 0, 1, listOf(it.message ?: "error"))) }

  data class PendingReplies(
    val item: MirrorPostboxApi.FileSyncItem,
    val path: String,
    val parsed: MrReplies.RepliesFile,
    val siblings: List<MirrorPostboxApi.FileSyncItem>,
  )

  data class PendingBatch(
    val item: MirrorPostboxApi.FileSyncItem,
    val path: String,
    val iid: Int,
    val batchId: String,
    val parsed: MrReviewHandoff.Document,
  )

  /** Newest mr-replies item per MR plus every v2 review batch, downloaded and
   *  parsed, without posting anything. */
  internal fun fetchPendingReplies(): PendingRepliesResult {
    val settings = service<MirrorSettingsService>().state
    val repo = resolveRepo(settings) ?: return PendingRepliesResult.Unavailable("repo not resolved")
    val listResult = MirrorPostboxApi.fileSyncList(settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls)
    if (listResult.code !in 200..299) {
      return PendingRepliesResult.Unavailable("HTTP ${listResult.code} ${listResult.message}")
    }
    val skipped = mutableListOf<SkippedItem>()
    val batches = mutableListOf<PendingBatch>()
    val legacyItems = mutableListOf<MirrorPostboxApi.FileSyncItem>()
    for (item in listResult.items.filter { it.path.startsWith("mr-replies/") }) {
      val m = REVIEW_BATCH_RE.find(item.path)
      if (m == null) {
        legacyItems.add(item)
        continue
      }
      val iid = m.groupValues[1].toIntOrNull() ?: 0
      val batchId = m.groupValues[2]
      when (val parsed = downloadAndParseV2(item, settings, repo, iid, batchId)) {
        is Downloaded.Batch -> batches.add(PendingBatch(item, item.path, iid, batchId, parsed.doc))
        is Downloaded.Skipped -> skipped.add(SkippedItem(item.path, parsed.reason))
        is Downloaded.Parsed -> Unit
      }
    }
    val items = legacyItems
      .groupBy { item -> replyIid(item.path) }
      .mapNotNull { (iid, group) ->
        if (iid == null) {
          group.forEach { skipped.add(SkippedItem(it.path, "not an mr-!N.md reply file")) }
          return@mapNotNull null
        }
        val newest = group.maxByOrNull { it.mtime } ?: return@mapNotNull null
        when (val parsed = downloadAndParse(newest, settings, repo)) {
          is Downloaded.Parsed -> PendingReplies(newest, newest.path, parsed.replies, group)
          is Downloaded.Skipped -> {
            skipped.add(SkippedItem(newest.path, parsed.reason))
            null
          }
          is Downloaded.Batch -> null
        }
      }.sortedByDescending { it.item.mtime }
    return PendingRepliesResult.Available(items, skipped, batches)
  }

  internal fun countPending(): PendingCount {
    val settings = service<MirrorSettingsService>().state
    val repo = resolveRepo(settings) ?: return PendingCount.Unavailable("repo not resolved")
    val listResult = MirrorPostboxApi.fileSyncList(settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls)
    if (listResult.code !in 200..299) {
      return PendingCount.Unavailable("HTTP ${listResult.code} ${listResult.message}")
    }
    return PendingCount.Available(
      listResult.items
        .filter { it.path.startsWith("mr-replies/") }
        .mapNotNull { replyIid(it.path) }
        .toSet()
        .size
    )
  }

  private fun replyIid(path: String): Int? =
    Regex("mr-!(\\d+)\\.md$").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()

  /** Post only the approved sections of a pending item; acks the MR's postbox entries only when the approval covers every parsed reply. */
  internal fun pushApproved(pending: PendingReplies, approved: List<MrReplies.Reply>): FileReport {
    if (!PUSH_GATE.tryEnter()) return busyReport(pending.path)
    try {
      val settings = service<MirrorSettingsService>().state
      val conf = GitLabConfig.resolve(project)
      if (!GitLabConfig.hasApi(conf)) {
        return FileReport(pending.path, 0, 0, 0, 1, listOf("GitLab URL/project/token not configured"))
      }
      val repo = resolveRepo(settings)
        ?: return FileReport(pending.path, 0, 0, 0, 1, listOf("repo not resolved"))
      val filtered = pending.parsed.copy(replies = approved)
      return pushParsed(pending, filtered, conf, settings, repo)
    } finally {
      PUSH_GATE.exit()
    }
  }

  /** Publishes user-selected v2 batches; each selected batch is posted as a whole
   *  immutable document, unselected batches stay pending in the postbox. */
  internal fun pushSelectedBatches(batches: List<PendingBatch>): List<FileReport> {
    if (batches.isEmpty()) return emptyList()
    if (!PUSH_GATE.tryEnter()) return batches.map { busyReport(it.path) }
    try {
      val settings = service<MirrorSettingsService>().state
      val conf = GitLabConfig.resolve(project)
      if (!GitLabConfig.hasApi(conf)) {
        return batches.map { FileReport(it.path, 0, 0, 0, 1, listOf("GitLab URL/project/token not configured")) }
      }
      val repo = resolveRepo(settings)
        ?: return batches.map { FileReport(it.path, 0, 0, 0, 1, listOf("repo not resolved")) }
      val reports = batches.map { pushReviewBatch(it, conf, repo) }
      runReceiptHandoff(repo, settings)
      return reports
    } finally {
      PUSH_GATE.exit()
    }
  }

  internal fun repoName(): String? = resolveRepo(service<MirrorSettingsService>().state)

  private fun resolveRepo(settings: MirrorSettingsService.State): String? {
    val baseDir = project.basePath ?: return null
    return runCatching {
      project.getService(SyncFacadeService::class.java).resolveRepo(File(baseDir), settings).sanitized
    }.getOrNull()?.takeIf { it.isNotBlank() }
  }

  private sealed interface Downloaded {
    data class Parsed(val replies: MrReplies.RepliesFile) : Downloaded
    data class Batch(val doc: MrReviewHandoff.Document) : Downloaded
    data class Skipped(val reason: String) : Downloaded
  }

  private fun downloadAndParseV2(
    item: MirrorPostboxApi.FileSyncItem,
    settings: MirrorSettingsService.State,
    repo: String,
    iid: Int,
    batchId: String,
  ): Downloaded {
    val tmpEnc = File.createTempFile("mr-review-batch-", ".bin")
    return try {
      val dl = MirrorPostboxApi.fileSyncDownload(
        settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls,
        item.id, tmpEnc,
      )
      if (dl.code !in 200..299 || dl.file == null) {
        return Downloaded.Skipped("download: HTTP ${dl.code} ${dl.message}")
      }
      val doc = MrReviewHandoff.parseAndValidate(
        tmpEnc.readText(Charsets.UTF_8),
        expectedIid = iid,
        expectedBatchId = batchId,
        want = "v2",
      )
      Downloaded.Batch(doc)
    } catch (e: MrReviewHandoff.HandoffRejection) {
      val refusal = if (e.code.isNotEmpty()) "${e.code}: ${e.message}" else (e.message ?: "")
      Downloaded.Skipped("rejected: $refusal")
    } catch (e: Throwable) {
      Downloaded.Skipped("decrypt/parse: ${e.message ?: "error"}")
    } finally {
      runCatching { tmpEnc.delete() }
    }
  }

  private fun downloadAndParse(
    item: MirrorPostboxApi.FileSyncItem,
    settings: MirrorSettingsService.State,
    repo: String,
  ): Downloaded {
    val tmpEnc = File.createTempFile("mr-replies-", ".bin")
    return try {
      val dl = MirrorPostboxApi.fileSyncDownload(
        settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls,
        item.id, tmpEnc,
      )
      if (dl.code !in 200..299 || dl.file == null) {
        return Downloaded.Skipped("download: HTTP ${dl.code} ${dl.message}")
      }
      val parsed = MrReplies.parse(tmpEnc.readText(Charsets.UTF_8))
      // The postbox path mr-replies/mr-!N.md already carries the iid, so a header-less file still posts.
      val withIid = if (parsed.iid != 0) parsed else replyIid(item.path)?.let { parsed.copy(iid = it) }
      if (withIid == null || withIid.iid == 0) Downloaded.Skipped("no '# MR !N' header and no iid in path")
      else Downloaded.Parsed(withIid)
    } catch (e: Throwable) {
      Downloaded.Skipped("decrypt/parse: ${e.message ?: "error"}")
    } finally {
      runCatching { tmpEnc.delete() }
    }
  }

  private fun pushAll(): List<FileReport> {
    if (!PUSH_GATE.tryEnter()) return listOf(busyReport("(busy)"))
    try {
      val settings = service<MirrorSettingsService>().state
      val conf = GitLabConfig.resolve(project)
      if (!GitLabConfig.hasApi(conf)) {
        return listOf(FileReport("(gitlab)", 0, 0, 0, 1, listOf("GitLab URL/project/token not configured")))
      }
      val repo = resolveRepo(settings) ?: return listOf(FileReport("(repo)", 0, 0, 0, 1, listOf("repo not resolved")))
      return when (val pending = fetchPendingReplies()) {
        is PendingRepliesResult.Unavailable ->
          listOf(FileReport("(postbox)", 0, 0, 0, 1, listOf("list mr-replies: ${pending.reason}")))
        is PendingRepliesResult.Available -> {
          val reports = mutableListOf<FileReport>()
          for (s in pending.skipped) {
            reports.add(markQuiet(FileReport(s.path, 0, 0, 1, 0, listOf(s.reason))))
          }
          for (p in pending.items) {
            reports.add(pushParsed(p, p.parsed, conf, settings, repo))
          }
          for (b in pending.batches) {
            reports.add(markQuiet(pushReviewBatch(b, conf, repo)))
          }
          runReceiptHandoff(repo, settings)
          reports
        }
      }
    } finally {
      PUSH_GATE.exit()
    }
  }

  private fun busyReport(path: String) = FileReport(path, 0, 0, 0, 0, listOf("push already in progress"))

  private fun markQuiet(report: FileReport): FileReport {
    val signature = "${report.posted}/${report.dupSkipped}/${report.skipped}/${report.failed}/${report.parseErrors}/${report.details.joinToString("|")}"
    return if (REPORT_DEDUP.isNew(report.path, signature)) report else report.copy(quiet = true)
  }

  private fun pushParsed(
    pending: PendingReplies,
    parsed: MrReplies.RepliesFile,
    conf: GitLabConfig.GitLabConf,
    settings: MirrorSettingsService.State,
    repo: String,
  ): FileReport {
    val iid = parsed.iid
    val path = pending.path
    val discussions = GitLabApi.listMrDiscussions(conf, iid)
    if (discussions.code !in 200..299) {
      return FileReport(path, 0, 0, 0, 1, listOf("list discussions: HTTP ${discussions.code} ${discussions.message}"))
    }
    val detail = GitLabApi.getMrDetail(conf, iid)
    val diffRefs = detail.diffRefs
    val changedRanges = if (detail.code in 200..299 && detail.targetBranch.isNotBlank() && detail.sourceBranch.isNotBlank()) {
      val cmp = GitLabApi.compareChangedRanges(conf, detail.targetBranch, detail.sourceBranch)
      if (cmp.code in 200..299) cmp.ranges else null
    } else {
      null
    }

    val projectState = project.service<MirrorProjectSettingsService>().state
    val ledger = projectState.mrReplyLedger
    val seenMarkers = HashSet(ledger)
    discussions.discussions.flatMap { d -> d.notes }.mapNotNullTo(seenMarkers) { note ->
      MARKER_RE.find(note.body)?.value
    }

    var posted = 0
    var dupSkipped = 0
    var skipped = 0
    var failed = 0
    val outcomes = LinkedHashMap<MrReplies.Reply, Outcome>()
    val details = mutableListOf<String>()
    val notAnchored = mutableListOf<String>()
    parsed.errors.forEach { details.add("parse: $it") }

    for ((idx, reply) in parsed.replies.withIndex()) {
      val label = "reply ${idx + 1}"
      val marker = marker(iid, reply)
      val thread = if (reply.kind == MrReplies.Kind.THREAD) {
        discussions.discussions.firstOrNull { it.id == reply.threadId }
      } else {
        null
      }
      when (resolveDecision(seenMarkers.contains(marker), reply.resolve && thread != null, thread?.resolved ?: true)) {
        ResolveAction.SKIP -> {
          dupSkipped++
          outcomes[reply] = Outcome.DUP
          details.add("$label: duplicate, already in GitLab")
        }
        ResolveAction.RESOLVE_ONLY -> {
          val res = GitLabApi.resolveDiscussion(conf, iid, reply.threadId)
          if (res.ok()) {
            dupSkipped++
            outcomes[reply] = Outcome.DUP
            details.add("$label: duplicate, thread ${reply.threadId} resolved")
          } else {
            failed++
            outcomes[reply] = Outcome.FAILED
            details.add("$label: duplicate, resolve ${reply.threadId} failed HTTP ${res.code}")
          }
        }
        ResolveAction.POST -> {
          val body = "$marker\n\n${reply.body}"
          val result: GitLabApi.WriteResult
          var fallbackNote = ""
          when (reply.kind) {
            MrReplies.Kind.THREAD -> {
              if (thread == null) {
                skipped++
                outcomes[reply] = Outcome.DEAD
                details.add("$label: thread ${reply.threadId} no longer exists")
                continue
              }
              result = GitLabApi.postThreadNote(conf, iid, reply.threadId, body)
              if (result.ok() && reply.resolve) {
                val res = GitLabApi.resolveDiscussion(conf, iid, reply.threadId)
                if (!res.ok()) {
                  failed++
                  details.add("$label: posted, resolve failed HTTP ${res.code}")
                }
              }
            }
            MrReplies.Kind.NEW_ANCHORED -> {
              val clash = anchorClash(reply, discussions.discussions)
              if (clash != null) {
                skipped++
                outcomes[reply] = Outcome.DEAD
                details.add("$label: unresolved thread ${clash.id} already at ${reply.file}:${clash.anchorLine} — reply into it, don't duplicate")
                continue
              }
              val preReason = anchorabilityReason(reply, changedRanges)
              val anchored = GitLabApi.postNewDiscussion(conf, iid, body, diffRefs, GitLabApi.DiffPosition(reply.file, reply.line))
              if (anchored.ok()) {
                result = anchored
              } else if (anchored.code in setOf(400, 422)) {
                fallbackNote = "line not in MR diff"
                notAnchored.add("$label: ${reply.file}:${reply.line} — ${preReason ?: "line not in MR diff"}")
                result = GitLabApi.postMrNote(conf, iid, "`${reply.file}:${reply.line}`:\n\n$body")
              } else {
                result = anchored
              }
            }
            MrReplies.Kind.NEW_GENERAL -> result = GitLabApi.postMrNote(conf, iid, body)
          }
          if (result.ok()) {
            posted++
            outcomes[reply] = Outcome.POSTED
            seenMarkers.add(marker)
            ledger.add(marker)
            while (ledger.size > LEDGER_CAP) ledger.removeAt(0)
            if (fallbackNote.isNotEmpty()) details.add("$label: $fallbackNote, posted as general note")
          } else if (reply.kind == MrReplies.Kind.THREAD && result.code == 404) {
            skipped++
            outcomes[reply] = Outcome.DEAD
            details.add("$label: thread ${reply.threadId} no longer exists")
          } else {
            failed++
            outcomes[reply] = Outcome.FAILED
            details.add("$label: HTTP ${result.code} ${result.message}")
          }
        }
      }
    }

    val report = FileReport(path, posted, dupSkipped, skipped, failed, details, parsed.errors.size, outcomes = outcomes, notAnchored = notAnchored)
    if (shouldAck(report, parsed.replies, pending.parsed.replies, seenMarkers, iid)) {
      pending.siblings.forEach { s ->
        MirrorPostboxApi.fileSyncAck(settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls, s.id)
      }
    }
    if (posted > 0 || failed > 0) {
      val status = MrRepliesTransport.uploadStatusResult(project, iid, renderStatus(iid, report))
      if (status is MrUploadResult.Failed) {
        details.add("status report upload failed: ${status.reason}")
      }
      persistReceipt(iid, report)
    }
    return markQuiet(report)
  }

  /** v2 review batch: gated publication with a per-entry journal; the receipt
   *  handoff at the end of the pass uploads receipts and acks terminal batches. */
  private fun pushReviewBatch(pending: PendingBatch, conf: GitLabConfig.GitLabConf, repo: String): FileReport {
    val journal = pending.batchId.takeIf { it.isNotBlank() }?.let { bid ->
      project.basePath?.let { base ->
        runCatching {
          MrReviewJournal.open(
            MrReviewJournal.journalDir(base, repo), pending.iid, bid,
            pending.parsed, pending.item.id, pending.path,
          )
        }.getOrNull()
      }
    }
    val result = MrReviewBatchPublisher.publish(conf, pending.parsed, journal)
    return FileReport(
      path = pending.path,
      posted = result.posted,
      dupSkipped = result.duplicates,
      skipped = 0,
      failed = result.blocked,
      details = result.details,
      batch = result,
    )
  }

  private fun runReceiptHandoff(repo: String, settings: MirrorSettingsService.State) {
    val base = project.basePath ?: return
    runCatching {
      MrReviewReceiptHandoff.processPending(
        MrReviewJournal.journalDir(base, repo),
        upload = { path, json -> MrRepliesTransport.uploadResult(project, path, json).ok },
        ack = { id ->
          MirrorPostboxApi.fileSyncAck(
            settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls, id,
          ).code in 200..299
        },
      )
    }
  }

  private fun persistReceipt(iid: Int, report: FileReport) {
    val at = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    val receipt = MrPublishReceipt(
      posted = report.posted,
      failed = report.failed,
      skipped = report.skipped,
      notAnchored = report.notAnchored.size,
      at = at,
      details = (report.details + report.notAnchored).toMutableList(),
    )
    val state = project.service<MirrorProjectSettingsService>().state
    if (state.mrPublishReceipts[iid.toString()] != receipt) {
      state.mrPublishReceipts[iid.toString()] = receipt
    }
  }

  internal fun renderStatus(iid: Int, r: FileReport): String {
    val at = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    return buildString {
      appendLine("<!-- lgm-replies-status v1 -->")
      appendLine("# MR !$iid — status")
      appendLine("- posted: ${r.posted}")
      appendLine("- duplicates: ${r.dupSkipped}")
      appendLine("- skipped: ${r.skipped}")
      appendLine("- failed: ${r.failed}")
      appendLine("- not anchored: ${r.notAnchored.size}")
      appendLine("- at: $at")
      r.details.forEach { appendLine("  - $it") }
      r.notAnchored.forEach { appendLine("  - $it") }
    }
  }

  internal fun notifySummary(reports: List<FileReport>) {
    val posted = reports.sumOf { it.posted }
    val dup = reports.sumOf { it.dupSkipped }
    val skip = reports.sumOf { it.skipped }
    val fail = reports.sumOf { it.failed }
    val notAnchored = reports.sumOf { it.notAnchored.size }
    val type = if (fail > 0) NotificationType.WARNING else NotificationType.INFORMATION
    var content = LocalGitMirrorBundle.message("mrreplies.push.summary", posted, dup, skip, fail)
    if (notAnchored > 0) {
      content += "\n" + LocalGitMirrorBundle.message("mrreplies.push.notanchored", notAnchored)
      content += "\n" + reports.flatMap { it.notAnchored }.joinToString("\n") { "- $it" }
    }
    if (skip > 0) {
      content += "\n" + reports.filter { it.skipped > 0 }.flatMap { it.details }.joinToString("\n") { "- $it" }
    }
    val history = pushSummaryHistory(reports)
    service<OperationsHistoryService>().add(
      LocalGitMirrorBundle.message("history.op.pushMrReplies"),
      history.success,
      history.details,
    )
    notify(content, type)
  }

  private fun notify(content: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("DocCache")
      .createNotification(content, type)
      .notify(project)
  }

  companion object {
    private val MARKER_RE = Regex("<!-- lgm:[0-9a-f]{8} -->")
    internal val REVIEW_BATCH_RE = Regex("mr-!(\\d+)-([^/]+)\\.review\\.json$")
    private const val LEDGER_CAP = 500
    private const val ANCHOR_WINDOW = 3

    // Shared so every ad-hoc MrReplyPushService(project) instance is mutually exclusive.
    private val PUSH_GATE = SingleFlight()
    private val REPORT_DEDUP = ReportDedup()

    /** A new anchored reply within [ANCHOR_WINDOW] lines of a live unresolved thread on the same file duplicates it; answer that thread instead. */
    internal fun anchorClash(reply: MrReplies.Reply, discussions: List<GitLabApi.MrDiscussion>): GitLabApi.MrDiscussion? =
      discussions.firstOrNull { d ->
        !d.resolved && d.notes.any { !it.system } &&
          d.anchorFile == reply.file &&
          d.anchorLine != null && kotlin.math.abs(d.anchorLine!! - reply.line) <= ANCHOR_WINDOW
      }

    /** null = anchorable or unknown (no compare data); a reason means GitLab will downgrade the remark to a general note. */
    internal fun anchorabilityReason(reply: MrReplies.Reply, ranges: Map<String, List<IntRange>>?): String? {
      if (ranges == null) return null
      val fileRanges = ranges[reply.file] ?: return "file not in MR diff"
      return if (fileRanges.any { reply.line in it }) null else "line outside diff hunks"
    }

    /** A pass may ack (delete) the postbox item only when every file reply is terminal: posted/dup this pass, dead, or already in the ledger from an earlier pass. */
    internal fun shouldAck(
      report: FileReport,
      pushed: List<MrReplies.Reply>,
      fileReplies: List<MrReplies.Reply>,
      seenMarkers: Set<String> = emptySet(),
      iid: Int = 0,
    ): Boolean {
      if (report.parseErrors > 0) return false
      val terminal = pushed.toSet().filterTo(HashSet()) { r ->
        when (report.outcomes[r]) {
          Outcome.POSTED, Outcome.DUP, Outcome.DEAD -> true
          else -> false
        } || seenMarkers.contains(marker(iid, r))
      }
      val retryable = fileReplies.filterNot { it in terminal }
      if (retryable.isEmpty()) return true
      // no per-reply ledger yet: a clean full-file pass still acks; partial approval must not
      return report.clean && pushed == fileReplies
    }

    fun marker(iid: Int, reply: MrReplies.Reply): String {
      val key = when (reply.kind) {
        MrReplies.Kind.THREAD -> "t:${reply.threadId}"
        MrReplies.Kind.NEW_ANCHORED -> "a:${reply.file}:${reply.line}"
        MrReplies.Kind.NEW_GENERAL -> "g"
      }
      val digest = MessageDigest.getInstance("SHA-1")
        .digest("$iid|$key|${reply.body}".toByteArray(Charsets.UTF_8))
      val hex = digest.take(4).joinToString("") { b -> "%02x".format(b) }
      return "<!-- lgm:$hex -->"
    }

    internal data class PushHistoryRow(val success: Boolean, val details: String)

    internal fun pushSummaryHistory(reports: List<FileReport>): PushHistoryRow = PushHistoryRow(
      success = reports.sumOf { it.failed } == 0,
      details = "posted=${reports.sumOf { it.posted }} dup=${reports.sumOf { it.dupSkipped }} " +
        "skip=${reports.sumOf { it.skipped }} fail=${reports.sumOf { it.failed }} files=${reports.size}"
    )
  }
}

internal class SingleFlight {
  private val held = AtomicBoolean(false)
  fun tryEnter(): Boolean = held.compareAndSet(false, true)
  fun exit() {
    held.set(false)
  }
}

internal enum class ResolveAction { POST, RESOLVE_ONLY, SKIP }

internal fun resolveDecision(markerSeen: Boolean, wantsResolve: Boolean, threadResolved: Boolean): ResolveAction = when {
  !markerSeen -> ResolveAction.POST
  threadResolved -> ResolveAction.SKIP
  wantsResolve -> ResolveAction.RESOLVE_ONLY
  else -> ResolveAction.SKIP
}

internal class ReportDedup(private val cap: Int = 200) {
  private val seen = object : LinkedHashMap<String, String>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > cap
  }

  @Synchronized
  fun isNew(key: String, signature: String): Boolean = seen.put(key, signature) != signature
}

/**
 * Snapshot-bound publication of validated v2 review batches (Python twin:
 * lgm_core/ops_mr.py _publish_review_batch). Read-only marker scan first, then
 * the fresh-fingerprint gate; any drift or unreachable API halts the whole
 * batch with zero writes. The gate re-runs before every entry whose
 * predecessor attempted a write, so a mid-batch head change blocks the
 * remainder while posted entries keep their ids. Anchored entries post with
 * full side-aware positions built from the BATCH refs and never downgrade to
 * general notes; already-posted finding revisions are recovered through
 * protocol markers instead of re-posted. When a journal is given, every
 * attempt and entry outcome (ids and permalink included) is persisted before
 * any receipt step: entries a previous attempt posted are never re-POSTed,
 * and an entry whose previous POST ended without a recorded response is
 * re-looked-up through its marker only — a second POST is refused and the
 * entry stays blocked.
 */
object MrReviewBatchPublisher {

  data class EntryResult(
    val label: String,
    val kind: String,
    val target: String,
    val findingId: String,
    val revision: Long,
    val marker: String,
    val outcome: String,
    val code: String,
    val error: String?,
    val note: String,
    val discussionId: String?,
    val noteId: Long?,
    val url: String?,
  )

  data class StaleInfo(
    val expected: Map<String, String>,
    val fresh: Map<String, String>,
    val drift: List<String>,
  )

  data class BatchResult(
    val iid: Int,
    val batchId: String,
    val posted: Int,
    val duplicates: Int,
    val blocked: Int,
    val entries: List<EntryResult>,
    val details: List<String>,
    val stale: StaleInfo?,
  )

  private data class Recovered(val discussionId: String?, val noteId: Long?, val batchId: String,
                               val url: String? = null)

  private data class GateRead(
    val expected: Map<String, String>,
    val fresh: Map<String, String>,
    val drift: List<String>,
    val webUrl: String,
    val error: String?,
  )

  private val GATE_FIELDS = listOf(
    "state", "source_sha", "target_tip_sha", "target_project_id",
    "gitlab_url", "diff_refs", "version_fingerprint",
  )

  data class Precheck(val stale: StaleInfo?, val error: String?, val webUrl: String)

  /** Read-only freshness gate for UI previews: same check [publish] runs before
   *  any write, without posting anything. */
  fun precheck(conf: GitLabConfig.GitLabConf, doc: MrReviewHandoff.Document): Precheck {
    val refs = requireNotNull(doc.refs) { "v2 document carries refs" }
    val g = gate(conf, doc, refs)
    return Precheck(
      stale = if (g.drift.isNotEmpty()) StaleInfo(g.expected, g.fresh, g.drift) else null,
      error = g.error,
      webUrl = g.webUrl,
    )
  }

  fun publish(conf: GitLabConfig.GitLabConf, doc: MrReviewHandoff.Document,
              journal: MrReviewJournal? = null): BatchResult {
    val batchId = requireNotNull(doc.batchId) { "v2 document carries a batch id" }
    val refs = requireNotNull(doc.refs) { "v2 document carries refs" }
    val attempt = journal?.beginAttempt()
    val journalKeys = HashSet<Pair<String, Long>>()

    fun finishWith(result: BatchResult): BatchResult {
      if (journal != null && attempt != null) journal.completeAttempt(attempt)
      return result
    }

    fun record(result: EntryResult, postAttempted: Boolean, recoveredFrom: String? = null) {
      if (journal != null && attempt != null) {
        journal.recordResult(attempt, result, postAttempted, recoveredFrom)
      }
    }

    fun blocked(label: String, entry: MrReviewHandoff.Entry, code: String, error: String): EntryResult {
      val result = blockedResult(label, entry, code, error)
      record(result, postAttempted = false)
      return result
    }

    fun haltJournalled(postedMap: Map<Pair<String, Long>, Recovered>, webUrl: String,
                       error: String, code: String, stale: StaleInfo?): BatchResult {
      val result = halt(doc, batchId, postedMap, webUrl, error, code, stale)
      result.entries.forEach { r ->
        val from = if (r.outcome == "skipped-duplicate") {
          if ((r.findingId to r.revision) in journalKeys) "journal" else "scan"
        } else {
          null
        }
        record(r, postAttempted = false, recoveredFrom = from)
      }
      return finishWith(result)
    }

    val raw = GitLabApi.listMrDiscussionsRaw(conf, doc.iid)
    if (raw.code !in 200..299) {
      val known = LinkedHashMap<Pair<String, Long>, Recovered>()
      journal?.postedEntries()?.forEach { (k, p) ->
        known[k] = Recovered(p.discussionId, p.noteId, p.batchId, p.url)
      }
      journalKeys.addAll(known.keys)
      return haltJournalled(known, "", "list discussions: HTTP ${raw.code} ${raw.message}", "", null)
    }
    val postedMap = LinkedHashMap<Pair<String, Long>, Recovered>()
    val threadIds = HashSet<String>()
    for (d in raw.discussions) {
      val did = d["id"]?.jsonPrimitive?.contentOrNull ?: ""
      threadIds.add(did)
      for (n in d["notes"]?.jsonArray ?: emptyList()) {
        val noteObj = n.jsonObject
        val noteId = noteObj["id"]?.jsonPrimitive?.longOrNull
        val body = noteObj["body"]?.jsonPrimitive?.contentOrNull ?: ""
        for (m in MrReviewProtocol.parseReviewMarkers(body)) {
          postedMap.putIfAbsent(m.findingId to m.revision, Recovered(did, noteId, m.batchId))
        }
      }
    }
    journal?.postedEntries()?.forEach { (k, p) ->
      if (postedMap.putIfAbsent(k, Recovered(p.discussionId, p.noteId, p.batchId, p.url)) == null) {
        journalKeys.add(k)
      }
    }
    val uncertain = journal?.uncertainEntries() ?: emptyMap()
    val g = gate(conf, doc, refs)
    if (journal != null && attempt != null) journal.setWebUrl(attempt, g.webUrl)
    if (g.error != null) return haltJournalled(postedMap, g.webUrl, g.error, "", null)
    if (g.drift.isNotEmpty()) {
      val error = "stale-snapshot: ${g.drift.joinToString(", ")} drifted since the snapshot — zero posts/resolves"
      return haltJournalled(postedMap, g.webUrl, error, "stale-snapshot", StaleInfo(g.expected, g.fresh, g.drift))
    }
    val results = mutableListOf<EntryResult>()
    val details = mutableListOf<String>()
    var webUrl = g.webUrl
    var stale: StaleInfo? = null
    var haltWith: Pair<String, String>? = null
    var needRecheck = false
    for ((idx, entry) in doc.entries.withIndex()) {
      val label = "entry ${idx + 1}"
      val key = (entry.findingId ?: "") to entry.revision
      val dup = postedMap[key]
      if (dup != null) {
        val result = dupResult(label, entry, dup, webUrl)
        results.add(result)
        record(result, postAttempted = false,
               recoveredFrom = if (key in journalKeys) "journal" else "scan")
        continue
      }
      val h = haltWith
      if (h != null) {
        results.add(blocked(label, entry, h.first, h.second))
        continue
      }
      val uncertainAttempt = uncertain[key]
      if (uncertainAttempt != null) {
        results.add(blocked(
          label, entry, "",
          "uncertain publication outcome: attempt $uncertainAttempt started a POST " +
            "without a recorded response and the marker re-lookup found nothing — " +
            "refusing a second POST",
        ))
        continue
      }
      if (needRecheck) {
        val rg = gate(conf, doc, refs)
        if (journal != null && attempt != null) journal.setWebUrl(attempt, rg.webUrl)
        if (rg.error != null) {
          haltWith = "" to rg.error
          results.add(blocked(label, entry, "", rg.error))
          continue
        }
        if (rg.drift.isNotEmpty()) {
          stale = StaleInfo(rg.expected, rg.fresh, rg.drift)
          val error = "stale-snapshot: ${rg.drift.joinToString(", ")} drifted mid-batch — remaining entries not posted"
          haltWith = "stale-snapshot" to error
          results.add(blocked(label, entry, "stale-snapshot", error))
          continue
        }
        webUrl = rg.webUrl
        needRecheck = false
      }
      val (result, attempted) = postEntry(conf, doc, refs, entry, label, threadIds, webUrl, details,
                                          journal, attempt)
      results.add(result)
      record(result, postAttempted = attempted)
      needRecheck = attempted
    }
    backfillIds(conf, doc.iid, results, webUrl, journal, attempt)
    val summary = "v2 batch $batchId: ${results.count { it.outcome == "posted" }} posted, " +
      "${results.count { it.outcome == "skipped-duplicate" }} duplicate, " +
      "${results.count { it.outcome == "blocked" }} blocked"
    return finishWith(finish(doc, batchId, results, listOf(summary) + details, stale))
  }

  private fun backfillIds(conf: GitLabConfig.GitLabConf, iid: Int, results: MutableList<EntryResult>,
                          webUrl: String, journal: MrReviewJournal?,
                          attempt: MrReviewJournal.AttemptRecord?) {
    val missing = results.indices.filter { i ->
      val r = results[i]
      (r.outcome == "posted" || r.outcome == "skipped-duplicate") && r.noteId != null &&
        (r.discussionId.isNullOrBlank() || r.url.isNullOrBlank())
    }
    if (missing.isEmpty()) return
    val raw = GitLabApi.listMrDiscussionsRaw(conf, iid)
    if (raw.code !in 200..299) return
    val scanned = LinkedHashMap<Pair<String, Long>, Recovered>()
    for (d in raw.discussions) {
      val did = d["id"]?.jsonPrimitive?.contentOrNull ?: ""
      for (n in d["notes"]?.jsonArray ?: emptyList()) {
        val noteObj = n.jsonObject
        val noteId = noteObj["id"]?.jsonPrimitive?.longOrNull
        val body = noteObj["body"]?.jsonPrimitive?.contentOrNull ?: ""
        for (m in MrReviewProtocol.parseReviewMarkers(body)) {
          scanned.putIfAbsent(m.findingId to m.revision, Recovered(did, noteId, m.batchId))
        }
      }
    }
    for (i in missing) {
      val r = results[i]
      val hit = scanned[r.findingId to r.revision] ?: continue
      if (hit.noteId != r.noteId) continue
      val did = if (r.discussionId.isNullOrBlank()) hit.discussionId else r.discussionId
      val url = r.url ?: if (webUrl.isNotBlank()) MrReviewProtocol.receiptNoteUrl(webUrl, r.noteId!!) else null
      results[i] = r.copy(discussionId = did, url = url)
      if (journal != null && attempt != null) {
        journal.updateEntryIds(attempt, r.findingId, r.revision, did, url)
      }
    }
  }

  private fun gate(conf: GitLabConfig.GitLabConf, doc: MrReviewHandoff.Document, refs: JsonObject): GateRead {
    val detail = GitLabApi.getMrReviewDetail(conf, doc.iid)
    if (detail.code !in 200..299) {
      return GateRead(emptyMap(), emptyMap(), emptyList(), "", "freshness gate: HTTP ${detail.code} ${detail.message}")
    }
    val targetBranch = detail.targetBranch
    if (targetBranch.isBlank()) {
      return GateRead(emptyMap(), emptyMap(), emptyList(), detail.webUrl, "freshness gate: MR detail has no target branch")
    }
    val tipRes = GitLabApi.getBranchTip(conf, targetBranch)
    if (tipRes.code !in 200..299 || tipRes.tip == null) {
      return GateRead(emptyMap(), emptyMap(), emptyList(), detail.webUrl, "freshness gate: HTTP ${tipRes.code} ${tipRes.message}")
    }
    val tip = tipRes.tip
    val liveDiffJson = buildJsonObject {
      put("base_sha", detail.diffRefs?.baseSha ?: "")
      put("start_sha", detail.diffRefs?.startSha ?: "")
      put("head_sha", detail.diffRefs?.headSha ?: "")
    }
    val baseUrl = conf.url.trimEnd('/')
    val fingerprint = MrReviewProtocol.computeVersionFingerprint(buildJsonObject {
      put("gitlab_url", baseUrl)
      put("iid", doc.iid.toLong())
      put("source_project_id", detail.sourceProjectId)
      put("target_project_id", detail.targetProjectId)
      put("source_sha", detail.sourceSha)
      put("target_tip_sha", tip)
      put("target_branch", targetBranch)
      put("gitlab_diff_refs", liveDiffJson)
    })
    val batch = requireNotNull(doc.batch) { "v2 document carries a batch" }
    val expected = linkedMapOf(
      "state" to "opened",
      "source_sha" to primitive(refs, "source_sha"),
      "target_tip_sha" to primitive(refs, "target_tip_sha"),
      "target_project_id" to primitive(batch, "target_project_id"),
      "gitlab_url" to primitive(batch, "gitlab_url"),
      "diff_refs" to MrReviewProtocol.canonicalJson(refs.getValue("diff_refs")),
      "version_fingerprint" to (doc.identity["version_fingerprint"] ?: ""),
    )
    val fresh = linkedMapOf(
      "state" to detail.state,
      "source_sha" to detail.sourceSha,
      "target_tip_sha" to tip,
      "target_branch" to targetBranch,
      "target_project_id" to detail.targetProjectId.toString(),
      "gitlab_url" to baseUrl,
      "diff_refs" to MrReviewProtocol.canonicalJson(liveDiffJson),
      "version_fingerprint" to fingerprint,
    )
    val drift = GATE_FIELDS.filter { fresh[it] != expected[it] }
    return GateRead(expected, fresh, drift, detail.webUrl, null)
  }

  private fun postEntry(
    conf: GitLabConfig.GitLabConf,
    doc: MrReviewHandoff.Document,
    refs: JsonObject,
    entry: MrReviewHandoff.Entry,
    label: String,
    threadIds: Set<String>,
    webUrl: String,
    details: MutableList<String>,
    journal: MrReviewJournal? = null,
    attempt: MrReviewJournal.AttemptRecord? = null,
  ): Pair<EntryResult, Boolean> {
    val kind = entry.kind
    val threadId = entry.threadId
    if (kind == "thread" && (threadId == null || threadId !in threadIds)) {
      return blockedResult(label, entry, "", "thread $threadId no longer exists") to false
    }
    if (kind == "anchored") {
      val line = entry.anchor?.get("line")
      if (line == null || line is JsonNull) {
        return blockedResult(
          label, entry, "invalid-line",
          "anchor has no side line; GitLab diff notes require one and v2 " +
            "anchored entries never fall back to a general note",
        ) to false
      }
    }
    val body = "<!-- ${entry.marker} -->\n\n${entry.body}"
    if (journal != null && attempt != null) journal.recordPostStart(attempt, entry)
    val res: GitLabApi.WriteBodyResult
    val discussionId: String?
    val noteId: Long?
    when (kind) {
      "anchored" -> {
        val a = requireNotNull(entry.anchor) { "anchored v2 entry carries an anchor" }
        val diffRefs = refs.getValue("diff_refs").jsonObject
        val oldPath = primitiveOrNull(a, "old_path") ?: primitiveOrNull(a, "new_path") ?: ""
        res = GitLabApi.postNewDiscussionPositioned(
          conf, doc.iid, body,
          GitLabApi.ReviewPosition(
            baseSha = primitive(diffRefs, "base_sha"),
            startSha = primitive(diffRefs, "start_sha"),
            headSha = primitive(diffRefs, "head_sha"),
            oldPath = oldPath,
            newPath = primitiveOrNull(a, "new_path") ?: oldPath,
            side = primitive(a, "side"),
            line = a.getValue("line").jsonPrimitive.long.toInt(),
          ),
        )
        discussionId = res.body?.get("id")?.jsonPrimitive?.contentOrNull
        noteId = res.body?.get("notes")?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
      }
      "thread" -> {
        val tid = requireNotNull(threadId) { "thread v2 entry carries a thread id" }
        res = GitLabApi.postThreadNoteBody(conf, doc.iid, tid, body)
        discussionId = tid
        noteId = res.body?.get("id")?.jsonPrimitive?.longOrNull
      }
      else -> {
        res = GitLabApi.postMrNoteBody(conf, doc.iid, body)
        discussionId = null
        noteId = res.body?.get("id")?.jsonPrimitive?.longOrNull
      }
    }
    if (!res.ok()) {
      val error = if (kind == "anchored" && (res.code == 400 || res.code == 422)) {
        "GitLab rejected the anchor: HTTP ${res.code} ${res.message} — no general fallback for v2"
      } else {
        "HTTP ${res.code} ${res.message}"
      }
      return blockedResult(label, entry, "", error) to true
    }
    val url = if (webUrl.isNotBlank() && noteId != null) MrReviewProtocol.receiptNoteUrl(webUrl, noteId) else null
    var note = "posted $kind at ${target(entry)}"
    if (kind == "thread" && entry.resolve) {
      val tid = requireNotNull(threadId) { "thread v2 entry carries a thread id" }
      val r = GitLabApi.resolveDiscussion(conf, doc.iid, tid)
      if (r.ok()) {
        note += " (resolved)"
      } else {
        note += " (resolve failed HTTP ${r.code})"
        details.add("$label: posted, resolve failed HTTP ${r.code}")
      }
    }
    return EntryResult(
      label = label, kind = kind, target = target(entry),
      findingId = entry.findingId.orEmpty(), revision = entry.revision,
      marker = entry.marker.orEmpty(), outcome = "posted", code = "", error = null,
      note = note, discussionId = discussionId, noteId = noteId, url = url,
    ) to true
  }

  private fun dupResult(label: String, entry: MrReviewHandoff.Entry, dup: Recovered, webUrl: String): EntryResult {
    val url = dup.url
      ?: if (webUrl.isNotBlank() && dup.noteId != null) MrReviewProtocol.receiptNoteUrl(webUrl, dup.noteId) else null
    return EntryResult(
      label = label, kind = entry.kind, target = target(entry),
      findingId = entry.findingId.orEmpty(), revision = entry.revision,
      marker = entry.marker.orEmpty(), outcome = "skipped-duplicate", code = "possible-duplicate",
      error = null,
      note = "finding revision already posted (discussion ${dup.discussionId}, note ${dup.noteId}, " +
        "batch ${dup.batchId}) — ids recovered, no new POST",
      discussionId = dup.discussionId, noteId = dup.noteId, url = url,
    )
  }

  private fun blockedResult(label: String, entry: MrReviewHandoff.Entry, code: String, error: String): EntryResult =
    EntryResult(
      label = label, kind = entry.kind, target = target(entry),
      findingId = entry.findingId.orEmpty(), revision = entry.revision,
      marker = entry.marker.orEmpty(), outcome = "blocked", code = code, error = error,
      note = error, discussionId = null, noteId = null, url = null,
    )

  private fun halt(
    doc: MrReviewHandoff.Document,
    batchId: String,
    postedMap: Map<Pair<String, Long>, Recovered>,
    webUrl: String,
    error: String,
    code: String,
    stale: StaleInfo?,
  ): BatchResult {
    val results = doc.entries.mapIndexed { idx, entry ->
      val label = "entry ${idx + 1}"
      val dup = postedMap[(entry.findingId ?: "") to entry.revision]
      if (dup != null) dupResult(label, entry, dup, webUrl) else blockedResult(label, entry, code, error)
    }
    return finish(doc, batchId, results, listOf(error), stale)
  }

  private fun finish(
    doc: MrReviewHandoff.Document,
    batchId: String,
    results: List<EntryResult>,
    details: List<String>,
    stale: StaleInfo?,
  ): BatchResult = BatchResult(
    iid = doc.iid,
    batchId = batchId,
    posted = results.count { it.outcome == "posted" },
    duplicates = results.count { it.outcome == "skipped-duplicate" },
    blocked = results.count { it.outcome == "blocked" },
    entries = results,
    details = details,
    stale = stale,
  )

  private fun target(entry: MrReviewHandoff.Entry): String = when (entry.kind) {
    "anchored" -> {
      val a = entry.anchor
      if (a == null) {
        "anchored"
      } else {
        val path = primitiveOrNull(a, "new_path") ?: primitiveOrNull(a, "old_path") ?: ""
        val line = a["line"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull ?: "null"
        "$path:$line (${primitiveOrNull(a, "side") ?: "?"} side)"
      }
    }
    "thread" -> entry.threadId ?: ""
    else -> "general"
  }

  private fun primitive(obj: JsonObject, key: String): String = obj.getValue(key).jsonPrimitive.content

  private fun primitiveOrNull(obj: JsonObject, key: String): String? =
    obj[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
}
