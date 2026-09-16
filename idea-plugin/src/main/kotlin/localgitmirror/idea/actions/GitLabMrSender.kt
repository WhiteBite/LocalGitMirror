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
import localgitmirror.idea.sync.v2.SyncEngine
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File

/**
 * Shared tail of the MR transfer: fetch source branches from origin and run
 * the usual send-branch pipeline (checkout -> full sync -> restore), for a
 * single MR or a batch. Called on a background thread by [SendGitLabMrAction]
 * and the panel MR list dialog.
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
      restoreBranch(project, projectDir, originalBranch)
    }
    reportSyncOutcome(project, conf, settings, syncRes, branch, iid, history)
  }

  fun sendAll(project: Project, conf: GitLabConfig.GitLabConf, targets: List<Pair<String, Int?>>) {
    if (targets.isEmpty()) return
    if (!precheck(project)) return
    val projectDir = File(project.basePath!!)
    val settings = service<MirrorSettingsService>().state
    val syncFacade = project.getService(SyncFacadeService::class.java)
    val history = service<OperationsHistoryService>()

    val remote = GitLocal.defaultRemote(project, projectDir)
    val branches = targets.map { it.first }.distinct()
    val fetchRes = GitLocal.run(project, projectDir, 300, "fetch", remote, *branches.toTypedArray())
    if (!fetchRes.ok()) {
      val err = fetchRes.stderr.ifBlank { fetchRes.stdout }
      notify(project, LocalGitMirrorBundle.message("notify.gitFetchFailed", err), NotificationType.ERROR)
      for ((branch, iid) in targets) {
        history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), false,
          "branch=$branch iid=$iid err=${err.take(300)}")
      }
      return
    }

    val repoInfo = syncFacade.describeRepoTarget(projectDir, settings)
    notify(project, LocalGitMirrorBundle.message("action.syncBranch.starting", repoInfo), NotificationType.INFORMATION)

    val originalBranch = GitLocal.currentBranch(project, projectDir)
    var sent = 0
    val failures = mutableListOf<String>()
    try {
      for ((branch, iid) in targets) {
        val co = GitLocal.checkout(project, projectDir, branch)
        if (!co.ok()) {
          notify(
            project,
            LocalGitMirrorBundle.message("action.syncBranch.checkoutFailed", branch, co.stderr),
            NotificationType.ERROR
          )
          history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), false,
            "branch=$branch iid=$iid err=${co.stderr.take(300)}")
          failures.add("${targetLabel(branch, iid)}: ${co.stderr.take(200)}")
          continue
        }
        val syncRes = syncFacade.runFullSync(projectDir, settings)
        if (reportSyncOutcome(project, conf, settings, syncRes, branch, iid, history)) {
          sent++
        } else {
          failures.add("${targetLabel(branch, iid)}: ${syncRes.step.message.take(200)}")
        }
      }
    } finally {
      restoreBranch(project, projectDir, originalBranch)
    }

    if (failures.isEmpty()) {
      notify(
        project,
        LocalGitMirrorBundle.message("gitlab.notify.batchOk", sent, targets.size),
        NotificationType.INFORMATION
      )
    } else {
      notify(
        project,
        LocalGitMirrorBundle.message("gitlab.notify.batchPartial", sent, targets.size, failures.joinToString("\n")),
        NotificationType.WARNING
      )
    }
  }

  private fun reportSyncOutcome(
    project: Project,
    conf: GitLabConfig.GitLabConf,
    settings: MirrorSettingsService.State,
    syncRes: SyncEngine.FullSyncResult,
    branch: String,
    iid: Int?,
    history: OperationsHistoryService,
  ): Boolean {
    val result = syncRes.step
    if (!result.ok) {
      notify(
        project,
        "[trace=${syncRes.traceId}] repo='${syncRes.repo ?: "?"}' ${result.message}. ${result.details}",
        NotificationType.ERROR
      )
      history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), false,
        "branch=$branch iid=$iid err=${result.message.take(300)}")
      return false
    }

    if (settings.offlineGenerateOnly) {
      notify(
        project,
        "[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: "?"}' at ${syncRes.dump?.absolutePath ?: result.details}",
        NotificationType.INFORMATION
      )
      history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), true,
        "offline dump=${syncRes.dump?.absolutePath ?: "?"} branch=$branch iid=$iid")
      return true
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
    return true
  }

  private fun restoreBranch(project: Project, projectDir: File, originalBranch: String?) {
    if (originalBranch.isNullOrBlank()) return
    if (GitLocal.currentBranch(project, projectDir) == originalBranch) return
    val restore = GitLocal.checkout(project, projectDir, originalBranch)
    if (!restore.ok()) {
      notify(
        project,
        LocalGitMirrorBundle.message("notify.restoreBranchFailed", originalBranch, restore.stderr),
        NotificationType.WARNING
      )
    }
  }

  private fun targetLabel(branch: String, iid: Int?): String =
    if (iid != null) "!$iid ($branch)" else branch

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
      val pathEnc = localgitmirror.idea.workkit.ExchangeCrypto.encryptHint(
        "mr-notes/mr-!$iid.md", SecretsStore.syncPassword
      )
      val up = localgitmirror.idea.mirror.MirrorApi.fileSyncUpload(
        settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls,
        "x/${java.util.UUID.randomUUID().toString().take(8)}", 0L, encrypted, pathEnc, null
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
