package localgitmirror.idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.gitlab.GitLabApi
import localgitmirror.idea.gitlab.GitLabConfig
import localgitmirror.idea.gitlab.MrNotesWriter
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File

/**
 * Shared tail of the MR transfer: fetch the source branch from origin and run
 * the usual send-branch pipeline (checkout -> full sync -> restore). Called on
 * a background thread by both [SendGitLabMrAction] and the panel MR list dialog.
 */
object GitLabMrSender {

  fun precheck(project: Project): Boolean {
    val settings = service<MirrorSettingsService>().state
    val baseDir = project.basePath
    if (baseDir.isNullOrBlank()) {
      notify(project, LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
      return false
    }
    if (settings.baseUrl.isBlank()) {
      notify(project, LocalGitMirrorBundle.message("action.syncBranch.urlMissing"), NotificationType.WARNING)
      return false
    }
    if (SecretsStore.syncPassword.isBlank()) {
      notify(project, LocalGitMirrorBundle.message("action.syncBranch.syncPasswordMissing"), NotificationType.WARNING)
      return false
    }
    if (!GitLocal.isCleanWorkTree(project, File(baseDir))) {
      notify(project, LocalGitMirrorBundle.message("notify.worktree.dirty"), NotificationType.WARNING)
      return false
    }
    return true
  }

  fun send(project: Project, conf: GitLabConfig.GitLabConf, branch: String, iid: Int?) {
    if (!precheck(project)) return
    val projectDir = File(project.basePath!!)
    val settings = service<MirrorSettingsService>().state
    val syncFacade = project.getService(SyncFacadeService::class.java)
    val history = service<OperationsHistoryService>()

    val remote = GitLocal.defaultRemote(project, projectDir)
    val fetchRes = GitLocal.run(project, projectDir, 300, "fetch", remote, branch)
    if (!fetchRes.ok()) {
      val err = fetchRes.stderr.ifBlank { fetchRes.stdout }
      notify(project, LocalGitMirrorBundle.message("notify.gitFetchFailed", err), NotificationType.ERROR)
      history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), false,
        "branch=$branch iid=$iid err=${err.take(300)}")
      return
    }

    val repoInfo = syncFacade.describeRepoTarget(projectDir, settings)
    notify(project, LocalGitMirrorBundle.message("action.syncBranch.starting", repoInfo), NotificationType.INFORMATION)

    val originalBranch = GitLocal.currentBranch(project, projectDir)
    val co = GitLocal.checkout(project, projectDir, branch)
    if (!co.ok()) {
      notify(
        project,
        LocalGitMirrorBundle.message("action.syncBranch.checkoutFailed", branch, co.stderr),
        NotificationType.ERROR
      )
      history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), false,
        "branch=$branch iid=$iid err=${co.stderr.take(300)}")
      return
    }

    val syncRes = try {
      syncFacade.runFullSync(projectDir, settings)
    } finally {
      if (!originalBranch.isNullOrBlank() && originalBranch != branch) {
        val restore = GitLocal.checkout(project, projectDir, originalBranch)
        if (!restore.ok()) {
          notify(
            project,
            LocalGitMirrorBundle.message("notify.restoreBranchFailed", originalBranch, restore.stderr),
            NotificationType.WARNING
          )
        }
      }
    }

    val result = syncRes.step
    if (!result.ok) {
      notify(
        project,
        "[trace=${syncRes.traceId}] repo='${syncRes.repo ?: "?"}' ${result.message}. ${result.details}",
        NotificationType.ERROR
      )
      history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), false,
        "branch=$branch iid=$iid err=${result.message.take(300)}")
      return
    }

    if (settings.offlineGenerateOnly) {
      notify(
        project,
        "[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: "?"}' at ${syncRes.dump?.absolutePath ?: result.details}",
        NotificationType.INFORMATION
      )
      history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), true,
        "offline dump=${syncRes.dump?.absolutePath ?: "?"} branch=$branch iid=$iid")
      return
    }

    val iidSuffix = if (iid != null) " (MR !$iid)" else ""
    notify(
      project,
      "[trace=${syncRes.traceId}] ${LocalGitMirrorBundle.message("gitlab.notify.sentOk", branch, syncRes.repo ?: "?", iidSuffix)}. ${syncRes.http?.body?.take(500) ?: ""}",
      NotificationType.INFORMATION
    )
    if (iid != null && GitLabConfig.hasApi(conf) && !syncRes.repo.isNullOrBlank()) {
      runCatching { sendMrNotes(project, conf, iid, syncRes.repo, branch) }
    }
    history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), true,
      "repo=${syncRes.repo ?: "?"} branch=$branch iid=$iid")
  }

  /** All MR discussions (open/resolved/system) as markdown into the repo file postbox. */
  private fun sendMrNotes(project: Project, conf: GitLabConfig.GitLabConf, iid: Int, repo: String, branch: String) {
    val res = GitLabApi.listMrDiscussions(conf, iid)
    if (res.code !in 200..299 || res.discussions.isEmpty()) return
    val settings = service<MirrorSettingsService>().state

    val mrsResult = GitLabApi.listOpenMrs(conf)
    val mrInfo = if (mrsResult.code in 200..299) mrsResult.mrs.firstOrNull { it.iid == iid } else null
    val title = mrInfo?.title ?: ""
    val updatedAt = mrInfo?.updatedAt ?: ""

    val markdown = renderDiscussions(project, iid, title, branch, updatedAt, res.discussions)
    val plain = File.createTempFile("tmp-mrnotes-", ".md")
    val encrypted = File.createTempFile("tmp-mrnotes-", ".bin")
    try {
      plain.writeText(markdown, Charsets.UTF_8)
      localgitmirror.idea.workkit.RepoFileSyncCrypto.encryptFile(plain, encrypted, SecretsStore.syncPassword, null)
      val up = localgitmirror.idea.mirror.MirrorApi.fileSyncUpload(
        settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls,
        "mr-notes/mr-!$iid.md", plain.length(), encrypted, null
      )
      if (up.code in 200..299) {
        notify(
          project,
          LocalGitMirrorBundle.message("gitlab.mrnotes.sent", res.discussions.size, iid),
          NotificationType.INFORMATION
        )
      }
    } finally {
      runCatching { plain.delete() }
      runCatching { encrypted.delete() }
    }
  }

  private fun renderDiscussions(
    project: Project,
    iid: Int,
    title: String,
    branch: String,
    updatedAt: String,
    discussions: List<GitLabApi.MrDiscussion>,
  ): String {
    val unresolved = discussions.count { d -> !d.resolved && d.notes.any { !it.system } }
    val totalThreads = discussions.count { d -> d.notes.any { !it.system } }
    return MrNotesWriter.renderMarkdown(project, iid, title, branch, updatedAt, unresolved, totalThreads, discussions)
  }

  private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("DocCache")
      .createNotification(message, type)
      .notify(project)
  }
}
