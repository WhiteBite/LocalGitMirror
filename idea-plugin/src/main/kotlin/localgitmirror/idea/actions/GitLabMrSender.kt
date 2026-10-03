package localgitmirror.idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.gitlab.MrSendPlanner
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorSyncApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.SyncEngine
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File

/** Shared tail of the MR transfer: fetch source branches from origin, then send. Must run on a background thread. */
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

  fun send(project: Project, branch: String, iid: Int?) {
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
    reportSyncOutcome(project, settings, syncRes, branch, iid, history)
  }

  fun sendAll(project: Project, targets: List<Pair<String, Int?>>) {
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

    val (toSend, syncRes) = sendBatch(branches, plan) {
      syncFacade.runFullSync(projectDir, settings, additionalBranches = it)
    }
    val attempted = targets.filter { it.first in toSend.toSet() }
    val skipped = targets.size - attempted.size

    if (syncRes == null) {
      notify(
        project,
        LocalGitMirrorBundle.message("gitlab.notify.batchOkSkipped", 0, targets.size, skipped),
        NotificationType.INFORMATION
      )
      return
    }

    val result = syncRes.step
    val failures = mutableListOf<String>()
    if (!result.ok) {
      notify(
        project,
        "[trace=${syncRes.traceId}] repo='${syncRes.repo ?: "?"}' ${result.message}. ${result.details}",
        NotificationType.ERROR
      )
      for ((branch, iid) in attempted) {
        history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), false,
          "branch=$branch iid=$iid err=${result.message.take(300)}")
        failures.add("${targetLabel(branch, iid)}: ${result.message.take(200)}")
      }
    } else if (settings.offlineGenerateOnly) {
      notify(
        project,
        "[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: "?"}' at ${syncRes.dump?.absolutePath ?: result.details}",
        NotificationType.INFORMATION
      )
      for ((branch, iid) in attempted) {
        history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), true,
          "offline dump=${syncRes.dump?.absolutePath ?: "?"} branch=$branch iid=$iid")
      }
    } else {
      for ((branch, iid) in attempted) {
        history.add(LocalGitMirrorBundle.message("history.op.sendGitLabMr"), true,
          "repo=${syncRes.repo ?: "?"} branch=$branch iid=$iid")
      }
    }

    val sent = if (result.ok) attempted.size else 0
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

  internal fun sendBatch(
    branches: List<String>,
    plan: MrSendPlanner.Plan,
    runSync: (List<String>) -> SyncEngine.FullSyncResult,
  ): Pair<List<String>, SyncEngine.FullSyncResult?> {
    val toSend = branches.filter { it !in plan.skipped }
    if (toSend.isEmpty()) return toSend to null
    return toSend to runSync(toSend)
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

  private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("DocCache")
      .createNotification(message, type)
      .notify(project)
  }
}
