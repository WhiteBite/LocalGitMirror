package localgitmirror.idea.gitlab

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorPostboxApi
import localgitmirror.idea.settings.MirrorProjectSettingsService
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File
import java.security.MessageDigest

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
 * only when the entire file was pushed without failures; partial approvals never ack.
 */
class MrReplyPushService(private val project: Project) {

  data class FileReport(
    val path: String,
    val posted: Int,
    val dupSkipped: Int,
    val skipped: Int,
    val failed: Int,
    val details: List<String>,
    val parseErrors: Int = 0,
  ) {
    val clean: Boolean get() = failed == 0 && parseErrors == 0
  }

  data class SkippedItem(val path: String, val reason: String)

  sealed interface PendingRepliesResult {
    data class Available(val items: List<PendingReplies>, val skipped: List<SkippedItem>) : PendingRepliesResult
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
        notifySummary(reports)
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

  /** Newest mr-replies item per MR, downloaded and parsed, without posting anything. */
  internal fun fetchPendingReplies(): PendingRepliesResult {
    val settings = service<MirrorSettingsService>().state
    val repo = resolveRepo(settings) ?: return PendingRepliesResult.Unavailable("repo not resolved")
    val listResult = MirrorPostboxApi.fileSyncList(settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls)
    if (listResult.code !in 200..299) {
      return PendingRepliesResult.Unavailable("HTTP ${listResult.code} ${listResult.message}")
    }
    val skipped = mutableListOf<SkippedItem>()
    val items = listResult.items
      .filter { it.path.startsWith("mr-replies/") }
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
        }
      }.sortedByDescending { it.item.mtime }
    return PendingRepliesResult.Available(items, skipped)
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
    val settings = service<MirrorSettingsService>().state
    val conf = GitLabConfig.resolve(project)
    if (!GitLabConfig.hasApi(conf)) {
      return FileReport(pending.path, 0, 0, 0, 1, listOf("GitLab URL/project/token not configured"))
    }
    val repo = resolveRepo(settings)
      ?: return FileReport(pending.path, 0, 0, 0, 1, listOf("repo not resolved"))
    val filtered = pending.parsed.copy(replies = approved)
    return pushParsed(pending, filtered, conf, settings, repo)
  }

  private fun resolveRepo(settings: MirrorSettingsService.State): String? {
    val baseDir = project.basePath ?: return null
    return runCatching {
      project.getService(SyncFacadeService::class.java).resolveRepo(File(baseDir), settings).sanitized
    }.getOrNull()?.takeIf { it.isNotBlank() }
  }

  private sealed interface Downloaded {
    data class Parsed(val replies: MrReplies.RepliesFile) : Downloaded
    data class Skipped(val reason: String) : Downloaded
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
          reports.add(FileReport(s.path, 0, 0, 1, 0, listOf(s.reason)))
        }
        for (p in pending.items) {
          reports.add(pushParsed(p, p.parsed, conf, settings, repo))
        }
        reports
      }
    }
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
    val details = mutableListOf<String>()
    parsed.errors.forEach { details.add("parse: $it") }

    for ((idx, reply) in parsed.replies.withIndex()) {
      val label = "reply ${idx + 1}"
      val marker = marker(iid, reply)
      if (seenMarkers.contains(marker)) {
        dupSkipped++
        details.add("$label: duplicate, already in GitLab")
        continue
      }
      val body = "$marker\n\n${reply.body}"
      val result: GitLabApi.WriteResult
      var fallbackNote = ""
      when (reply.kind) {
        MrReplies.Kind.THREAD -> {
          if (discussions.discussions.none { it.id == reply.threadId }) {
            skipped++
            details.add("$label: thread ${reply.threadId} no longer exists")
            continue
          }
          result = GitLabApi.postThreadNote(conf, iid, reply.threadId, body)
          if (result.ok() && reply.resolve) {
            val res = GitLabApi.resolveDiscussion(conf, iid, reply.threadId)
            if (!res.ok()) details.add("$label: posted, resolve failed HTTP ${res.code}")
          }
        }
        MrReplies.Kind.NEW_ANCHORED -> {
          val anchored = GitLabApi.postNewDiscussion(conf, iid, body, diffRefs, GitLabApi.DiffPosition(reply.file, reply.line))
          if (anchored.ok()) {
            result = anchored
          } else if (anchored.code in setOf(400, 422)) {
            fallbackNote = "line not in MR diff"
            result = GitLabApi.postMrNote(conf, iid, "`${reply.file}:${reply.line}`:\n\n$body")
          } else {
            result = anchored
          }
        }
        MrReplies.Kind.NEW_GENERAL -> result = GitLabApi.postMrNote(conf, iid, body)
      }
      if (result.ok()) {
        posted++
        seenMarkers.add(marker)
        ledger.add(marker)
        while (ledger.size > LEDGER_CAP) ledger.removeAt(0)
        if (fallbackNote.isNotEmpty()) details.add("$label: $fallbackNote, posted as general note")
      } else if (reply.kind == MrReplies.Kind.THREAD && result.code == 404) {
        skipped++
        details.add("$label: thread ${reply.threadId} no longer exists")
      } else {
        failed++
        details.add("$label: HTTP ${result.code} ${result.message}")
      }
    }

    val report = FileReport(path, posted, dupSkipped, skipped, failed, details, parsed.errors.size)
    if (shouldAck(report, parsed.replies, pending.parsed.replies)) {
      pending.siblings.forEach { s ->
        MirrorPostboxApi.fileSyncAck(settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls, s.id)
      }
    }
    if (posted > 0 || failed > 0) {
      val status = MrRepliesTransport.uploadStatusResult(project, iid, renderStatus(iid, report))
      if (status is MrUploadResult.Failed) {
        details.add("status report upload failed: ${status.reason}")
      }
    }
    return report
  }

  private fun renderStatus(iid: Int, r: FileReport): String {
    val at = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    return buildString {
      appendLine("<!-- lgm-replies-status v1 -->")
      appendLine("# MR !$iid — status")
      appendLine("- posted: ${r.posted}")
      appendLine("- duplicates: ${r.dupSkipped}")
      appendLine("- skipped: ${r.skipped}")
      appendLine("- failed: ${r.failed}")
      appendLine("- at: $at")
      r.details.forEach { appendLine("  - $it") }
    }
  }

  internal fun notifySummary(reports: List<FileReport>) {
    val posted = reports.sumOf { it.posted }
    val dup = reports.sumOf { it.dupSkipped }
    val skip = reports.sumOf { it.skipped }
    val fail = reports.sumOf { it.failed }
    val type = if (fail > 0) NotificationType.WARNING else NotificationType.INFORMATION
    var content = LocalGitMirrorBundle.message("mrreplies.push.summary", posted, dup, skip, fail)
    if (skip > 0) {
      content += "\n" + reports.filter { it.skipped > 0 }.flatMap { it.details }.joinToString("\n") { "- $it" }
    }
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
    private const val LEDGER_CAP = 500

    /** A pass may ack (delete) the postbox item only when it is clean and pushed every parsed reply; thread-gone skips leave replies dead and do not block the ack. */
    internal fun shouldAck(
      report: FileReport,
      pushed: List<MrReplies.Reply>,
      fileReplies: List<MrReplies.Reply>,
    ): Boolean = report.clean && pushed == fileReplies

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
  }
}
