package localgitmirror.idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.gitlab.GitLabApi
import localgitmirror.idea.gitlab.GitLabConfig
import localgitmirror.idea.gitlab.MrNotesWriter
import localgitmirror.idea.gitlab.MrSendPlanner
import localgitmirror.idea.gitlab.MrUploadResult
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorSyncApi
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

    val plan = planMrSend(project, projectDir, settings, syncFacade, remote, listOf(branch))
    if (branch in plan.skipped) {
      notify(project, LocalGitMirrorBundle.message("gitlab.notify.alreadyOnCache", branch), NotificationType.INFORMATION)
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

    val plan = planMrSend(project, projectDir, settings, syncFacade, remote, branches)

    val repoInfo = syncFacade.describeRepoTarget(projectDir, settings)
    notify(project, LocalGitMirrorBundle.message("action.syncBranch.starting", repoInfo), NotificationType.INFORMATION)

    val originalBranch = GitLocal.currentBranch(project, projectDir)
    var sent = 0
    var skipped = 0
    val failures = mutableListOf<String>()
    try {
      for ((branch, iid) in targets) {
        if (branch in plan.skipped) {
          skipped++
          continue
        }
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

    val summary = when {
      failures.isEmpty() && skipped == 0 ->
        LocalGitMirrorBundle.message("gitlab.notify.batchOk", sent, targets.size)
      failures.isEmpty() ->
        LocalGitMirrorBundle.message("gitlab.notify.batchOkSkipped", sent, targets.size, skipped)
      skipped == 0 ->
        LocalGitMirrorBundle.message("gitlab.notify.batchPartial", sent, targets.size, failures.joinToString("\n"))
      else ->
        LocalGitMirrorBundle.message("gitlab.notify.batchPartialSkipped", sent, targets.size, skipped, failures.joinToString("\n"))
    }
    notify(project, summary, if (failures.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
  }

  private fun planMrSend(
    project: Project,
    projectDir: File,
    settings: MirrorSettingsService.State,
    syncFacade: SyncFacadeService,
    remote: String,
    branches: List<String>,
  ): MrSendPlanner.Plan {
    val repo = runCatching { syncFacade.resolveRepo(projectDir, settings).sanitized }.getOrDefault("")
    if (repo.isBlank()) return MrSendPlanner.Plan(branches, emptyList(), emptyList())
    val refsResult = runCatching {
      MirrorSyncApi.getRefs(settings.baseUrl, SecretsStore.mirrorApiKey, repo, SecretsStore.syncPassword, settings.mirrorInsecureTls)
    }.getOrNull()
    val localTips = mutableMapOf<String, String>()
    for (branch in branches) {
      val tip = GitLocal.branchHash(project, projectDir, branch)
        ?: remoteTip(project, projectDir, remote, branch)
      if (tip != null) localTips[branch] = tip
    }
    return decideMrSend(mirrorRefsFrom(refsResult), localTips, branches)
  }

  internal fun decideMrSend(
    mirrorRefs: Map<String, String>,
    localTips: Map<String, String>,
    branches: List<String>,
  ): MrSendPlanner.Plan {
    val resolvable = branches.filter { it in localTips }
    val unresolvable = branches.filter { it !in localTips }
    val plan = MrSendPlanner.planSend(mirrorRefs, localTips, emptySet(), resolvable, Int.MAX_VALUE)
    return if (unresolvable.isEmpty()) plan else plan.copy(sent = plan.sent + unresolvable)
  }

  internal fun mirrorRefsFrom(res: MirrorSyncApi.RefsResult?): Map<String, String> {
    if (res == null || res.code !in 200..299) return emptyMap()
    return res.refs?.mapValues { it.value.sha } ?: emptyMap()
  }

  private fun remoteTip(project: Project, projectDir: File, remote: String, branch: String): String? {
    val res = GitLocal.run(project, projectDir, 30, "rev-parse", "--verify", "refs/remotes/$remote/$branch")
    if (!res.ok()) return null
    val h = res.stdout.trim()
    return if (h.length >= 7) h else null
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
      val notes = sendMrNotes(project, conf, iid, syncRes.repo, branch)
      if (notes is MrUploadResult.Failed) {
        notify(
          project,
          LocalGitMirrorBundle.message("gitlab.mrnotes.fail", iid, notes.reason),
          NotificationType.WARNING
        )
      }
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
  private fun sendMrNotes(project: Project, conf: GitLabConfig.GitLabConf, iid: Int, repo: String, branch: String): MrUploadResult {
    val res = GitLabApi.listMrDiscussions(conf, iid)
    if (res.code !in 200..299) {
      return MrUploadResult.Failed(res.code, "list discussions: HTTP ${res.code} ${res.message}")
    }
    if (res.discussions.isEmpty()) return MrUploadResult.Ok
    val settings = service<MirrorSettingsService>().state

    val mrsResult = GitLabApi.listOpenMrs(conf)
    val mrInfo = if (mrsResult.code in 200..299) mrsResult.mrs.firstOrNull { it.iid == iid } else null
    val title = mrInfo?.title ?: ""
    val updatedAt = mrInfo?.updatedAt ?: ""

    val markdown = renderDiscussions(project, iid, title, branch, updatedAt, res.discussions)
    return try {
      val plain = File.createTempFile("tmp-mrnotes-", ".md")
      val encrypted = File.createTempFile("tmp-mrnotes-", ".bin")
      try {
        plain.writeText(markdown, Charsets.UTF_8)
        val displayPath = "mr-notes/mr-!$iid.md"
        val sealed = localgitmirror.idea.mirror.MirrorCrypto.sealPostboxPayload(
          plain.readBytes(), SecretsStore.syncPassword, displayPath, 0L
        )
        encrypted.writeBytes(sealed.bytes)
        val (relPath, pathEnc) = localgitmirror.idea.mirror.MirrorCrypto.postboxRoute(
          displayPath, SecretsStore.syncPassword
        )
        val up = localgitmirror.idea.mirror.MirrorPostboxApi.fileSyncUpload(
          settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls,
          relPath, 0L, encrypted, pathEnc, sealed.epkB64, sealed.meta, null
        )
        if (up.code !in 200..299) {
          MrUploadResult.Failed(up.code, "HTTP ${up.code}: ${up.message}")
        } else {
          notify(
            project,
            LocalGitMirrorBundle.message("gitlab.mrnotes.sent", res.discussions.size, iid),
            NotificationType.INFORMATION
          )
          MrUploadResult.Ok
        }
      } finally {
        runCatching { plain.delete() }
        runCatching { encrypted.delete() }
      }
    } catch (e: Throwable) {
      MrUploadResult.Failed(0, "post notes: ${e.message ?: "error"}")
    }
  }

  /** Home asked via the postbox (mr-notes-request/mr-!N.md) for this MR's threads. */
  internal fun sendNotesOnRequest(project: Project, iid: Int): MrUploadResult {
    val conf = GitLabConfig.resolve(project)
    if (!GitLabConfig.hasApi(conf)) return MrUploadResult.Failed(0, "GitLab URL/project/token not configured")
    val settings = service<MirrorSettingsService>().state
    val baseDir = project.basePath ?: return MrUploadResult.Failed(0, "project base directory missing")
    val repo = runCatching {
      project.getService(SyncFacadeService::class.java).resolveRepo(File(baseDir), settings).sanitized
    }.getOrDefault("")
    if (repo.isBlank()) return MrUploadResult.Failed(0, "repo not resolved")
    val branch = runCatching {
      GitLabApi.listOpenMrs(conf).mrs.firstOrNull { it.iid == iid }?.sourceBranch
    }.getOrNull() ?: ""
    return sendMrNotes(project, conf, iid, repo, branch)
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
