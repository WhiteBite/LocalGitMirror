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
    return if (GitLabConfig.hasApi(conf)) {
      fetchFromGitLab(conf)
    } else {
      fetchFromCache()
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

  private fun fetchFromCache(): List<MrRowItem> {
    val settings = service<MirrorSettingsService>().state
    val baseDir = project.basePath
    if (baseDir.isNullOrBlank()) return emptyList()

    val syncFacade = project.getService(SyncFacadeService::class.java)
    val repo = runCatching {
      syncFacade.resolveRepo(File(baseDir), settings).sanitized
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()

    val listResult = MirrorApi.fileSyncList(
      settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls
    )
    if (listResult.code !in 200..299) {
      throw RuntimeException(listResult.message.ifBlank { "Mirror API error ${listResult.code}" })
    }

    val mrItems = listResult.items.filter {
      it.path.startsWith("mr-notes/") && it.path.endsWith(".md")
    }
    if (mrItems.isEmpty()) return emptyList()

    return mrItems.mapNotNull { item -> parseCachedMr(item, settings, repo) }
  }

  private fun parseCachedMr(
    item: MirrorApi.FileSyncItem,
    settings: MirrorSettingsService.State,
    repo: String,
  ): MrRowItem? {
    val iid = Regex("mr-!(\\d+)\\.md$").find(item.path)?.groupValues?.getOrNull(1)?.toIntOrNull()
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
