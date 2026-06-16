package localgitmirror.idea.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.gitlab.GitLabApi
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore

// ── Diagnostics & pull/push extension functions for LocalGitMirrorPanel ──

internal fun LocalGitMirrorPanel.pullBack() {
  val dir = baseDir() ?: run {
    notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
    return
  }
  val s = service<MirrorSettingsService>().state
  val remote = s.gitRemoteName.ifBlank { "origin" }

  val branches = GitLocal.remoteBranches(project, dir, remote)
  if (branches.isEmpty()) {
    notify(LocalGitMirrorBundle.message("notify.noRemoteBranches", remote), NotificationType.WARNING)
    return
  }

  val selectedRemoteRef = Messages.showEditableChooseDialog(
    LocalGitMirrorBundle.message("dialog.pullBack.selectRemote"),
    LocalGitMirrorBundle.message("dialog.pullBack.localBranchTitle"),
    null, branches.toTypedArray(), branches.firstOrNull(), null
  ) ?: return

  val mode = Messages.showEditableChooseDialog(
    LocalGitMirrorBundle.message("dialog.pullBack.mode"),
    LocalGitMirrorBundle.message("dialog.pullBack.localBranchTitle"),
    null, arrayOf("new-branch", "ff-only"), s.pullBackDefaultMode, null
  )?.trim()?.lowercase().orEmpty()

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Pull back", false) {
    override fun run(indicator: ProgressIndicator) {
      try {
        val original = GitLocal.currentBranch(project, dir)
        append("Pull back from $selectedRemoteRef mode=$mode")

        val fetch = GitLocal.fetch(project, dir, remote)
        if (!fetch.ok()) {
          notify(LocalGitMirrorBundle.message("notify.gitFetchFailed", fetch.stderr), NotificationType.ERROR)
          markLastSync(LocalGitMirrorPanel.SyncOutcome.FAIL)
          historyService.add("Pull back", false, "git fetch failed")
          refreshHistoryLog()
          return
        }

        when (mode) {
          "ff-only" -> {
            val current = GitLocal.currentBranch(project, dir)
            if (current.isNullOrBlank()) {
              notify(LocalGitMirrorBundle.message("notify.currentBranch.missing"), NotificationType.ERROR)
              markLastSync(LocalGitMirrorPanel.SyncOutcome.FAIL)
              historyService.add("Pull back", false, LocalGitMirrorBundle.message("notify.currentBranch.missing"))
              refreshHistoryLog()
              return
            }
            val pull = GitLocal.pullFfOnly(project, dir, remote, current)
            if (!pull.ok()) {
              notify(LocalGitMirrorBundle.message("notify.gitPullFailed", pull.stderr), NotificationType.ERROR)
              markLastSync(LocalGitMirrorPanel.SyncOutcome.FAIL)
              historyService.add("Pull back", false, "git pull --ff-only failed")
              refreshHistoryLog()
              return
            }
            notify(LocalGitMirrorBundle.message("notify.pullBack.ok.ffonly"), NotificationType.INFORMATION)
            markLastSyncOk()
            historyService.add("Pull back", true, "ff-only")
            refreshHistoryLog()
          }

          else -> {
            val defaultName = "pullback-${System.currentTimeMillis()}"
            val localName = Messages.showInputDialog(
              project, LocalGitMirrorBundle.message("dialog.pullBack.localBranch"),
              LocalGitMirrorBundle.message("dialog.pullBack.localBranchTitle"),
              null, defaultName, null
            )?.trim().orEmpty()
            if (localName.isBlank()) return

            val co = GitLocal.checkoutNew(project, dir, localName, selectedRemoteRef)
            if (!co.ok()) {
              notify(LocalGitMirrorBundle.message("notify.createBranchFailed", co.stderr), NotificationType.ERROR)
              markLastSync(LocalGitMirrorPanel.SyncOutcome.FAIL)
              historyService.add("Pull back", false, "Failed to create local branch")
              refreshHistoryLog()
              return
            }
            if (!original.isNullOrBlank()) GitLocal.checkout(project, dir, original)
            notify(LocalGitMirrorBundle.message("notify.pullBack.ok.newBranch", localName), NotificationType.INFORMATION)
            markLastSyncOk()
            historyService.add("Pull back", true, "new branch '$localName'")
            refreshHistoryLog()
          }
        }
      } finally {
        isSyncing = false
      }
    }

    override fun onFinished() {
      isSyncing = false
    }
  })
}

internal fun LocalGitMirrorPanel.pullFromMirror() {
  isSyncing = true
  try {
    localgitmirror.idea.actions.PullFromMirrorAction().actionPerformed(
      com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
        "LocalGitMirrorToolWindow", null,
        com.intellij.openapi.actionSystem.DataContext { dataId ->
          if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(dataId)) project else null
        }
      )
    )
  } finally {
    isSyncing = false
  }
}

internal fun LocalGitMirrorPanel.applyLocalDump() {
  localgitmirror.idea.actions.ApplyLocalDumpAction().actionPerformed(
    com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
      "LocalGitMirrorToolWindow", null,
      com.intellij.openapi.actionSystem.DataContext { dataId ->
        if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(dataId)) project else null
      }
    )
  )
}

internal fun LocalGitMirrorPanel.pushCurrent() {
  val dir = baseDir() ?: run {
    notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
    return
  }
  val branch = GitLocal.currentBranch(project, dir)
  if (branch.isNullOrBlank()) {
    notify("Cannot determine current branch", NotificationType.ERROR)
    return
  }
  val s = service<MirrorSettingsService>().state
  val remote = Messages.showInputDialog(project, "Remote name", "git push", null, s.gitRemoteName, null) ?: return

  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: git push", false) {
    override fun run(indicator: ProgressIndicator) {
      indicator.text = "Pushing $remote/$branch"
      append("git push $remote $branch")
      val res = GitLocal.push(project, dir, remote, branch, setUpstream = true)
      if (!res.ok()) {
        append("push failed: ${res.stderr}")
        notify("git push failed: ${res.stderr.take(500)}", NotificationType.ERROR)
        return
      }
      append("push ok: ${res.stdout}")
      notify("git push OK ($remote/$branch)", NotificationType.INFORMATION)
    }
  })
}

internal fun LocalGitMirrorPanel.testMirror() {
  val s = service<MirrorSettingsService>().state
  if (s.baseUrl.isBlank()) {
    notify(LocalGitMirrorBundle.message("toolwindow.badge.mirrorNotConfigured"), NotificationType.WARNING)
    return
  }

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Test Mirror", false) {
    override fun run(indicator: ProgressIndicator) {
      try {
        val res = MirrorApi.ping(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls)
        append("Mirror test HTTP ${res.code}: ${res.body.take(300)}")
        if (res.code !in 200..299) {
          val msg = if (res.code == 0)
            LocalGitMirrorBundle.message("notify.mirror.unreachable", res.body)
          else
            LocalGitMirrorBundle.message("notify.mirror.testFail", res.code.toString())
          notify(msg, NotificationType.ERROR)
          return
        }
        notify(LocalGitMirrorBundle.message("notify.mirror.testOk"), NotificationType.INFORMATION)
      } finally {
        isSyncing = false
      }
    }

    override fun onFinished() {
      isSyncing = false
    }
  })
}

internal fun LocalGitMirrorPanel.runPreflight() {
  val dir = baseDir() ?: run {
    notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
    return
  }
  val s = service<MirrorSettingsService>().state
  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Preflight", false) {
    override fun run(indicator: ProgressIndicator) {
      try {
        val r = syncFacade.runPreflight(dir, s)
        append("Preflight: target=${r.targetRepo ?: "(none)"} ok=${r.ok}")
        r.diagnostics.forEach { d ->
          append("[${d.severity}] ${d.code}: ${d.message}${if (d.hint.isNullOrBlank()) "" else " | hint: ${d.hint}"}")
        }
        if (r.ok) notify("Preflight OK", NotificationType.INFORMATION)
        else notify("Preflight found issues. See history details.", NotificationType.WARNING)
      } finally {
        isSyncing = false
      }
    }

    override fun onFinished() {
      isSyncing = false
    }
  })
}

internal fun LocalGitMirrorPanel.runDryRun() {
  val dir = baseDir() ?: run {
    notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
    return
  }
  val s = service<MirrorSettingsService>().state
  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Dry-run (Send)", false) {
    override fun run(indicator: ProgressIndicator) {
      try {
        val r = syncFacade.runDryRun(dir, s)
        append("Dry-run (Send): ok=${r.ok} target=${r.targetRepo ?: "(none)"} branch=${r.branch ?: "(none)"}")
        append("Mode=${r.predictedMode} range=${r.commitRange} count=${r.commitCount}")
        r.diagnostics.forEach { d ->
          append("[${d.severity}] ${d.code}: ${d.message}${if (d.hint.isNullOrBlank()) "" else " | hint: ${d.hint}"}")
        }
        if (r.ok) notify("Dry-run (Send): ${r.predictedMode}, ${r.commitCount} commits", NotificationType.INFORMATION)
        else notify("Dry-run (Send) failed. See history details.", NotificationType.WARNING)
      } finally {
        isSyncing = false
      }
    }

    override fun onFinished() {
      isSyncing = false
    }
  })
}

internal fun LocalGitMirrorPanel.runPullDryRun() {
  val dir = baseDir() ?: run {
    notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
    return
  }
  val s = service<MirrorSettingsService>().state
  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Dry-run (Pull)", false) {
    override fun run(indicator: ProgressIndicator) {
      try {
        val r = syncFacade.runPullDryRun(dir, s)
        append("Dry-run (Pull): ok=${r.ok} target=${r.targetRepo ?: "(none)"}")
        append("hasUpdates=${r.hasUpdates} remoteHead=${r.remoteHead?.take(12) ?: "(empty)"} reason=${r.reason}")
        r.diagnostics.forEach { d ->
          append("[${d.severity}] ${d.code}: ${d.message}${if (d.hint.isNullOrBlank()) "" else " | hint: ${d.hint}"}")
        }
        if (r.ok) notify("Dry-run (Pull): hasUpdates=${r.hasUpdates}, reason=${r.reason}", NotificationType.INFORMATION)
        else notify("Dry-run (Pull) failed. See history details.", NotificationType.WARNING)
      } finally {
        isSyncing = false
      }
    }

    override fun onFinished() {
      isSyncing = false
    }
  })
}

internal fun LocalGitMirrorPanel.testGitLab() {
  val s = service<MirrorSettingsService>().state
  if (s.gitLabBaseUrl.isBlank() || SecretsStore.gitLabToken.isBlank() || s.gitLabProject.isBlank()) {
    notify(LocalGitMirrorBundle.message("toolwindow.badge.gitlabNotConfigured"), NotificationType.WARNING)
    return
  }

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Test GitLab", false) {
    override fun run(indicator: ProgressIndicator) {
      try {
        val (res, mrs) = GitLabApi.listOpenMergeRequests(
          baseUrl = s.gitLabBaseUrl, token = SecretsStore.gitLabToken,
          projectIdOrPath = s.gitLabProject, insecureTls = s.gitLabInsecureTls, perPage = 5
        )
        append("GitLab test HTTP ${res.code}. Open MRs loaded: ${mrs.size}")
        if (res.code !in 200..299) {
          val msg = if (res.code == 0)
            LocalGitMirrorBundle.message("notify.gitlab.unreachable", res.body)
          else
            LocalGitMirrorBundle.message("notify.gitlab.testFail", res.code.toString())
          notify(msg, NotificationType.ERROR)
          return
        }
        notify(LocalGitMirrorBundle.message("notify.gitlab.testOk", mrs.size.toString()), NotificationType.INFORMATION)
      } finally {
        isSyncing = false
      }
    }

    override fun onFinished() {
      isSyncing = false
    }
  })
}
