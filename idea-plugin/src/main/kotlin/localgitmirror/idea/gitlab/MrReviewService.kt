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
    val replyStatus: String = "",
  )

  data class StatusReport(val posted: Int, val failed: Int)

  internal data class CacheData(
    val rows: List<MrRowItem>,
    val status: Map<Int, StatusReport>,
    val pendingIids: Set<Int>,
    val localIids: Set<Int>,
  )

  @Volatile
  private var cache: List<MrRowItem> = emptyList()

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
    row.copy(replyStatus = stateFor(row.iid, cache))
  }

  internal fun stateFor(iid: Int, cache: CacheData): String {
    val st = cache.status[iid]
    return when {
      st != null && st.failed > 0 -> localgitmirror.idea.i18n.LocalGitMirrorBundle.message("review.state.failed", st.failed)
      st != null && st.posted > 0 -> localgitmirror.idea.i18n.LocalGitMirrorBundle.message("review.state.posted", st.posted)
      iid in cache.pendingIids -> localgitmirror.idea.i18n.LocalGitMirrorBundle.message("review.state.pending")
      iid in cache.localIids -> localgitmirror.idea.i18n.LocalGitMirrorBundle.message("review.state.local")
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

    val status = mutableMapOf<Int, StatusReport>()
    val pendingIids = mutableSetOf<Int>()
    val noteItems = mutableListOf<Pair<MirrorApi.FileSyncItem, String>>()
    for (item in listResult.items) {
      val path = displayPath(item)
      val iid = Regex("mr-!(\\d+)\\.md$").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
      when {
        path.startsWith("mr-replies-status/") -> {
          val md = downloadPlain(item, settings, repo) ?: continue
          status[iid] = parseStatus(md)
        }
        path.startsWith("mr-replies/") -> pendingIids.add(iid)
        path.startsWith("mr-notes/") -> noteItems.add(item to path)
      }
    }
    val rows = newestPerIid(noteItems).mapNotNull { (item, path) -> parseCachedMr(item, path, settings, repo) }
    return CacheData(rows, status, pendingIids, localReplyIids(baseDir))
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

    val branch = lines.firstOrNull { it.contains("**\u0412\u0435\u0442\u043a\u0430:**") }
      ?.let { line -> Regex("`([^`]+)`").find(line)?.groupValues?.getOrNull(1) }
      ?: ""

    val unresolved = lines.count { it.startsWith("## \u26a0") }
    val resolved = lines.count { it.startsWith("## ✓ решено") }
    val totalThreads = unresolved + resolved

    return MrRowItem(
      iid = iid,
      title = title,
      sourceBranch = branch,
      updatedAt = "",
      unresolved = unresolved,
      totalThreads = totalThreads,
      discussions = emptyList(),
      source = Source.CACHE,
      receivedMarkdown = markdown,
    )
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
