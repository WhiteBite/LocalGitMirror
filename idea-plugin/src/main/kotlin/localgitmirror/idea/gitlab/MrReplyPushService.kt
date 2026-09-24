package localgitmirror.idea.gitlab

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorProjectSettingsService
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import localgitmirror.idea.workkit.RepoFileSyncCrypto
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
 * only when the whole file went through without failures.
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

  private fun pushAll(): List<FileReport> {
    val settings = service<MirrorSettingsService>().state
    val conf = GitLabConfig.resolve(project)
    if (!GitLabConfig.hasApi(conf)) {
      return listOf(FileReport("(gitlab)", 0, 0, 0, 1, listOf("GitLab URL/project/token not configured")))
    }
    val baseDir = project.basePath ?: return emptyList()
    val syncFacade = project.getService(SyncFacadeService::class.java)
    val repo = runCatching { syncFacade.resolveRepo(File(baseDir), settings).sanitized }
      .getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()

    val listResult = MirrorApi.fileSyncList(settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls)
    if (listResult.code !in 200..299) {
      return listOf(FileReport("(postbox)", 0, 0, 0, 1, listOf(listResult.message.ifBlank { "HTTP ${listResult.code}" })))
    }
    val items = listResult.items.mapNotNull { item ->
      displayPath(item).takeIf { it.startsWith("mr-replies/") && it.endsWith(".md") }?.let { item to it }
    }
    if (items.isEmpty()) return emptyList()

    return items.map { (item, path) -> pushOne(item, path, settings, repo, conf) }
  }

  private fun pushOne(
    item: MirrorApi.FileSyncItem,
    path: String,
    settings: MirrorSettingsService.State,
    repo: String,
    conf: GitLabConfig.GitLabConf,
  ): FileReport {
    val tmpEnc = File.createTempFile("mr-replies-", ".bin")
    val tmpPlain = File.createTempFile("mr-replies-", ".md")
    try {
      val dl = MirrorApi.fileSyncDownload(
        settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls,
        item.id, tmpEnc,
      )
      if (dl.code !in 200..299 || dl.file == null) {
        return FileReport(path, 0, 0, 0, 1, listOf("download failed: HTTP ${dl.code}"))
      }
      RepoFileSyncCrypto.decryptFile(tmpEnc, tmpPlain, SecretsStore.syncPassword, null)
      val parsed = MrReplies.parse(tmpPlain.readText(Charsets.UTF_8))
      if (parsed.iid == 0) {
        return FileReport(path, 0, 0, 0, 1, listOf("no '# MR !N' header"))
      }
      return pushParsed(item, path, parsed, conf, settings, repo)
    } catch (t: Throwable) {
      return FileReport(path, 0, 0, 0, 1, listOf(t.message ?: "error"))
    } finally {
      runCatching { tmpEnc.delete() }
      runCatching { tmpPlain.delete() }
    }
  }

  private fun pushParsed(
    item: MirrorApi.FileSyncItem,
    path: String,
    parsed: MrReplies.RepliesFile,
    conf: GitLabConfig.GitLabConf,
    settings: MirrorSettingsService.State,
    repo: String,
  ): FileReport {
    val iid = parsed.iid
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
    if (report.clean) {
      MirrorApi.fileSyncAck(settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls, item.id)
    }
    return report
  }

  private fun displayPath(item: MirrorApi.FileSyncItem): String {
    if (item.pathEnc.isBlank()) return item.path
    val plain = runCatching {
      localgitmirror.idea.workkit.ExchangeCrypto.decryptHint(item.pathEnc, SecretsStore.syncPassword)
    }.getOrDefault(item.path)
    return localgitmirror.idea.workkit.ExchangeMeta.parseName(plain).text.ifBlank { item.path }
  }

  internal fun notifySummary(reports: List<FileReport>) {
    val posted = reports.sumOf { it.posted }
    val dup = reports.sumOf { it.dupSkipped }
    val skip = reports.sumOf { it.skipped }
    val fail = reports.sumOf { it.failed }
    val type = if (fail > 0) NotificationType.WARNING else NotificationType.INFORMATION
    notify(LocalGitMirrorBundle.message("mrreplies.push.summary", posted, dup, skip, fail), type)
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
