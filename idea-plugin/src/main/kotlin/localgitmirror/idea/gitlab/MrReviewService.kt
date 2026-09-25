package localgitmirror.idea.gitlab

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import localgitmirror.idea.workkit.RepoFileSyncCrypto
import java.io.File

class MrReviewService(private val project: Project) {

  enum class Source { GITLAB, CACHE }

  data class MrRowItem(
    val iid: Int,
    val title: String,
    val sourceBranch: String,
    val updatedAt: String,
    val unresolved: Int,
    val totalThreads: Int,
    val discussions: List<GitLabApi.MrDiscussion>,
    val source: Source,
    val receivedMarkdown: String?,
    val replyState: String = "",
    val replyPosted: Int = 0,
    val replyFailed: Int = 0,
  )

  data class StatusReport(val posted: Int, val failed: Int)

  internal data class CacheData(
    val rows: List<MrRowItem>,
    val status: Map<Int, StatusReport>,
    val pendingIids: Set<Int>,
    val localIids: Set<Int>,
    val processingIids: Set<Int> = emptySet(),
  )

  @Volatile
  private var cache: List<MrRowItem> = emptyList()

  // postbox item id -> mtime of the last downloaded+parsed copy; unchanged items are not re-downloaded
  private val downloadedMtime = java.util.concurrent.ConcurrentHashMap<String, Long>()
  private val parsedCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
  @Volatile
  private var lastPendingIids: Set<Int> = emptySet()

  fun cachedRows(): List<MrRowItem> = cache

  fun rowsBySourceBranch(): Map<String, MrRowItem> = cache.associateBy { it.sourceBranch }

  fun refreshInBackground(notify: Boolean, onDone: ((List<MrRowItem>) -> Unit)? = null) {
    ApplicationManager.getApplication().executeOnPooledThread {
      val result = runCatching { fetchRows() }
      val rows = result.getOrElse { emptyList() }
      cache = rows

      UIUtil.invokeLaterIfNeeded {
        if (project.isDisposed) return@invokeLaterIfNeeded
        onDone?.invoke(rows)
        if (notify) {
          if (result.isSuccess) {
            notifyOk(rows.size)
          } else {
            val reason = result.exceptionOrNull()?.message ?: "error"
            notifyFail(reason)
          }
        }
      }
    }
  }

  private fun fetchRows(): List<MrRowItem> {
    val conf = GitLabConfig.resolve(project)
    val cache = runCatching { fetchCacheData() }.getOrDefault(CacheData(emptyList(), emptyMap(), emptySet(), emptySet()))
    if (!GitLabConfig.hasApi(conf)) {
      return decorate(cache.rows, cache)
    }
    val gitlab = fetchFromGitLab(conf)
    return decorate(mergeRows(gitlab, cache.rows), cache)
  }

  /** GitLab rows win; cache-only MRs are appended so a home with a GitLab token still sees transferred notes. */
  internal fun mergeRows(gitlab: List<MrRowItem>, cache: List<MrRowItem>): List<MrRowItem> {
    val known = gitlab.map { it.iid }.toSet()
    return gitlab + cache.filter { it.iid !in known }
  }

  internal fun decorate(rows: List<MrRowItem>, cache: CacheData): List<MrRowItem> = rows.map { row ->
    val st = cache.status[row.iid]
    row.copy(
      replyState = stateFor(row.iid, cache),
      replyPosted = st?.posted ?: 0,
      replyFailed = st?.failed ?: 0,
    )
  }

  /** Raw state key for the reply pipeline; the UI localizes and colors it. */
  internal fun stateFor(iid: Int, cache: CacheData): String {
    val st = cache.status[iid]
    return when {
      st != null && st.failed > 0 -> "failed"
      st != null && st.posted > 0 -> "posted"
      iid in cache.pendingIids -> "pending"
      iid in cache.processingIids -> "processing"
      iid in cache.localIids -> "local"
      else -> ""
    }
  }

  internal fun parseStatus(markdown: String): StatusReport {
    val posted = Regex("^- posted:\\s*(\\d+)", RegexOption.MULTILINE).find(markdown)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val failed = Regex("^- failed:\\s*(\\d+)", RegexOption.MULTILINE).find(markdown)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    return StatusReport(posted, failed)
  }

  private fun fetchCacheData(): CacheData {
    val settings = service<MirrorSettingsService>().state
    val baseDir = project.basePath
    if (baseDir.isNullOrBlank()) return CacheData(emptyList(), emptyMap(), emptySet(), emptySet())

    val syncFacade = project.getService(SyncFacadeService::class.java)
    val repo = runCatching {
      syncFacade.resolveRepo(File(baseDir), settings).sanitized
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return CacheData(emptyList(), emptyMap(), emptySet(), localReplyIids(baseDir))

    val listResult = MirrorApi.fileSyncList(
      settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls
    )
    if (listResult.code !in 200..299) {
      throw RuntimeException(listResult.message.ifBlank { "Mirror API error ${listResult.code}" })
    }
    if (downloadedMtime.size > 500) {
      downloadedMtime.clear()
      parsedCache.clear()
    }

    val statusCandidates = mutableListOf<Triple<Int, MirrorApi.FileSyncItem, String>>()
    val pendingIids = mutableSetOf<Int>()
    val noteItems = mutableListOf<Pair<MirrorApi.FileSyncItem, String>>()
    for (item in listResult.items) {
      val path = displayPath(item)
      val iid = Regex("mr-!(\\d+)\\.md$").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
      when {
        path.startsWith("mr-replies-status/") -> {
          val md = cachedPlain(item, settings, repo) ?: continue
          statusCandidates.add(Triple(iid, item, md))
        }
        path.startsWith("mr-replies/") -> pendingIids.add(iid)
        path.startsWith("mr-notes/") -> noteItems.add(item to path)
      }
    }
    val status = mutableMapOf<Int, StatusReport>()
    for ((iid, group) in statusCandidates.groupBy { it.first }) {
      val newest = group.maxByOrNull { it.second.mtime } ?: continue
      status[iid] = parseStatus(newest.third)
      for (stale in group) {
        if (stale.second.id != newest.second.id) {
          runCatching { MirrorApi.fileSyncAck(settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls, stale.second.id) }
        }
      }
    }
    val rows = newestPerIid(noteItems).mapNotNull { (item, path) ->
      cachedRow(item, path, settings, repo)
    }
    val processedIids = lastPendingIids - pendingIids - status.keys
    lastPendingIids = pendingIids
    return CacheData(rows, status, pendingIids, localReplyIids(baseDir), processedIids)
  }

  private fun cachedPlain(item: MirrorApi.FileSyncItem, settings: MirrorSettingsService.State, repo: String): String? {
    val known = downloadedMtime[item.id]
    if (known != null && known == item.mtime) {
      (parsedCache[item.id] as? String)?.let { return it }
    }
    val md = downloadPlain(item, settings, repo) ?: return null
    downloadedMtime[item.id] = item.mtime
    parsedCache[item.id] = md
    return md
  }

  private fun cachedRow(
    item: MirrorApi.FileSyncItem,
    path: String,
    settings: MirrorSettingsService.State,
    repo: String,
  ): MrRowItem? {
    val known = downloadedMtime[item.id]
    if (known != null && known == item.mtime) {
      (parsedCache[item.id] as? MrRowItem)?.let { return it }
    }
    val row = parseCachedMr(item, path, settings, repo) ?: return null
    downloadedMtime[item.id] = item.mtime
    parsedCache[item.id] = row
    return row
  }

  private fun localReplyIids(baseDir: String): Set<Int> =
    File(baseDir, ".mr-notes")
      .listFiles { f -> f.isFile && f.name.startsWith("replies-!") && f.name.endsWith(".md") }
      ?.mapNotNull { Regex("^replies-!(\\d+)\\.md$").find(it.name)?.groupValues?.getOrNull(1)?.toIntOrNull() }
      ?.toSet()
      .orEmpty()

  private fun downloadPlain(item: MirrorApi.FileSyncItem, settings: MirrorSettingsService.State, repo: String): String? {
    val tmpEnc = File.createTempFile("mr-status-", ".bin")
    val tmpPlain = File.createTempFile("mr-status-", ".md")
    return try {
      val dl = MirrorApi.fileSyncDownload(
        settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls, item.id, tmpEnc,
      )
      if (dl.code !in 200..299 || dl.file == null) return null
      RepoFileSyncCrypto.decryptFile(tmpEnc, tmpPlain, SecretsStore.syncPassword, null)
      tmpPlain.readText(Charsets.UTF_8)
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { tmpEnc.delete() }
      runCatching { tmpPlain.delete() }
    }
  }

  private fun fetchFromGitLab(conf: GitLabConfig.GitLabConf): List<MrRowItem> {
    val mrsResult = GitLabApi.listOpenMrs(conf)
    if (mrsResult.code !in 200..299) {
      throw RuntimeException(mrsResult.message.ifBlank { "GitLab API error ${mrsResult.code}" })
    }
    if (mrsResult.mrs.isEmpty()) return emptyList()

    return mrsResult.mrs.take(10).map { mr ->
      val discussions = GitLabApi.listMrDiscussions(conf, mr.iid).discussions
      val unresolved = discussions.count { d -> !d.resolved && d.notes.any { !it.system } }
      MrRowItem(
        iid = mr.iid,
        title = mr.title,
        sourceBranch = mr.sourceBranch,
        updatedAt = mr.updatedAt,
        unresolved = unresolved,
        totalThreads = discussions.size,
        discussions = discussions,
        source = Source.GITLAB,
        receivedMarkdown = null,
      )
    }
  }

  /** Repeated sends stack several postbox entries per MR; only the newest one is shown. */
  internal fun newestPerIid(items: List<Pair<MirrorApi.FileSyncItem, String>>): List<Pair<MirrorApi.FileSyncItem, String>> =
    items
      .mapNotNull { pair ->
        val iid = Regex("mr-!(\\d+)\\.md$").find(pair.second)?.groupValues?.getOrNull(1)?.toIntOrNull()
          ?: return@mapNotNull null
        iid to pair
      }
      .groupBy({ it.first }, { it.second })
      .values
      .map { group -> group.maxByOrNull { it.first.mtime } ?: group.first() }

  /** Real path of a postbox item: decrypted path_enc when present, plaintext path for old entries. */
  private fun displayPath(item: MirrorApi.FileSyncItem): String {
    if (item.pathEnc.isBlank()) return item.path
    val plain = runCatching {
      localgitmirror.idea.workkit.ExchangeCrypto.decryptHint(item.pathEnc, SecretsStore.syncPassword)
    }.getOrDefault(item.path)
    return localgitmirror.idea.workkit.ExchangeMeta.parseName(plain).text.ifBlank { item.path }
  }

  private fun parseCachedMr(
    item: MirrorApi.FileSyncItem,
    path: String,
    settings: MirrorSettingsService.State,
    repo: String,
  ): MrRowItem? {
    val iid = Regex("mr-!(\\d+)\\.md$").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
      ?: return null

    val tmpEnc = File.createTempFile("mr-review-", ".bin")
    val tmpPlain = File.createTempFile("mr-review-", ".md")
    try {
      val dl = MirrorApi.fileSyncDownload(
        settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls,
        item.id, tmpEnc,
      )
      if (dl.code !in 200..299 || dl.file == null) return null

      RepoFileSyncCrypto.decryptFile(tmpEnc, tmpPlain, SecretsStore.syncPassword, null)
      val markdown = tmpPlain.readText(Charsets.UTF_8)
      return parseMrMarkdown(iid, markdown)
    } catch (_: Throwable) {
      return null
    } finally {
      runCatching { tmpEnc.delete() }
      runCatching { tmpPlain.delete() }
    }
  }

  internal fun parseMrMarkdown(iid: Int, markdown: String): MrRowItem {
    val lines = markdown.lines()

    val title = lines.firstOrNull { it.startsWith("# MR !") }
      ?.substringAfter(" \u2014 ", "")
      ?.trim()
      ?: ""

    val branch = lines.firstOrNull { it.contains("**Ветка:**") }
      ?.let { line -> Regex("`([^`]+)`").find(line)?.groupValues?.getOrNull(1) }
      ?: ""

    val threads = parseThreads(markdown)
    val unresolved = threads.count { !it.resolved && it.notes.any { n -> !n.system } }

    return MrRowItem(
      iid = iid,
      title = title,
      sourceBranch = branch,
      updatedAt = "",
      unresolved = unresolved,
      totalThreads = threads.size,
      discussions = threads,
      source = Source.CACHE,
      receivedMarkdown = markdown,
    )
  }

  private val authorLine = Regex("^\\*\\*(.+?)\\*\\* _\\(([^)]*)\\)_:\\s*$")
  private val replyLine = Regex("^&nbsp;&nbsp;↳ \\*\\*(.+?)\\*\\* _\\(([^)]*)\\)_:\\s*$")

  /** Rebuild structured threads from a transferred mr-notes markdown (home side has no GitLab API). */
  internal fun parseThreads(markdown: String): List<GitLabApi.MrDiscussion> {
    val out = mutableListOf<GitLabApi.MrDiscussion>()
    var id = ""
    var resolved = true
    var anchorFile: String? = null
    var anchorLine: Int? = null
    var inThread = false
    var inFence = false
    var current: MutableList<GitLabApi.MrNote>? = null
    var body: StringBuilder? = null

    fun flushNote() {
      val notes = current ?: return
      val b = body?.toString()?.trim().orEmpty()
      if (notes.isNotEmpty() && b.isNotEmpty()) {
        val last = notes.removeAt(notes.size - 1)
        notes.add(last.copy(body = b))
      }
      body = null
    }

    fun flushThread() {
      flushNote()
      val notes = current ?: return
      if (notes.isNotEmpty()) {
        out.add(
          GitLabApi.MrDiscussion(
            id = id,
            resolved = resolved,
            notes = notes.map { it.copy(filePath = anchorFile, line = anchorLine) },
          )
        )
      }
      current = null
    }

    for (raw in markdown.lines()) {
      val line = raw.trimEnd()
      if (line.startsWith("```")) {
        inFence = !inFence
        continue
      }
      if (inFence) continue
      when {
        line.startsWith("## \u26a0") -> {
          flushThread()
          inThread = true
          resolved = false
          id = ""
          anchorFile = null
          anchorLine = null
          current = mutableListOf()
        }
        line.startsWith("## ✓ решено") || line.startsWith("## ✓ resolved") -> {
          flushThread()
          inThread = true
          resolved = true
          id = ""
          anchorFile = null
          anchorLine = null
          current = mutableListOf()
        }
        line.startsWith("## ") -> {
          flushThread()
          inThread = false
        }
        !inThread -> Unit
        line.startsWith("<!-- lgm-thread:") -> id = line.substringAfter("lgm-thread:").substringBefore("-->").trim()
        line.startsWith("**Место:**") || line.startsWith("**Location:**") -> {
          val ref = Regex("`([^`]+)`").find(line)?.groupValues?.getOrNull(1)
          if (ref != null && ref.contains(":")) {
            anchorFile = ref.substringBeforeLast(':')
            anchorLine = ref.substringAfterLast(':').toIntOrNull()
          }
        }
        else -> {
          val notes = current ?: continue
          val authorM = authorLine.find(line)
          val replyM = replyLine.find(line)
          when {
            authorM != null || replyM != null -> {
              flushNote()
              val m = authorM ?: replyM!!
              notes.add(
                GitLabApi.MrNote(
                  author = m.groupValues[1],
                  createdAt = m.groupValues[2],
                  system = false,
                  body = "",
                  resolved = false,
                )
              )
              body = StringBuilder()
            }
            line == "---" -> flushNote()
            body != null -> body?.appendLine(line)
          }
        }
      }
    }
    flushThread()
    return out
  }

  private fun notifyOk(rowCount: Int) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("DocCache")
      .createNotification(
        LocalGitMirrorBundle.message("review.fetch.ok", rowCount),
        NotificationType.INFORMATION,
      )
      .notify(project)
  }

  private fun notifyFail(reason: String) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("DocCache")
      .createNotification(
        LocalGitMirrorBundle.message("review.fetch.fail", reason),
        NotificationType.WARNING,
      )
      .notify(project)
  }
}
