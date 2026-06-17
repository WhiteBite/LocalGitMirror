package localgitmirror.idea.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore

// ── Sync action extension functions for LocalGitMirrorPanel ──

internal fun LocalGitMirrorPanel.syncCurrentBranch() {
  val dir = baseDir() ?: run {
    notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
    return
  }
  val settings = service<MirrorSettingsService>().state
  val err = ensureConfigured(settings)
  if (err != null) {
    notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
    return
  }

  val chosenBranch = selectedBranch() ?: GitLocal.currentBranch(project, dir) ?: "(unknown)"
  val currentBranch = GitLocal.currentBranch(project, dir)
  val additionalBranches = selectedAdditionalBranches.toList()
  val needsCheckout = chosenBranch != currentBranch && !chosenBranch.isBlank()

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Send «$chosenBranch»", false) {
    override fun run(indicator: ProgressIndicator) {
      try {
        // If a different branch is selected in the combo — checkout first, then restore
        if (needsCheckout) {
          indicator.text = "Переключаемся на «$chosenBranch»…"
          val co = GitLocal.checkout(project, dir, chosenBranch)
          if (!co.ok()) {
            notify(LocalGitMirrorBundle.message("notify.checkoutFailed", co.stderr), NotificationType.ERROR)
            markLastSync(LocalGitMirrorPanel.SyncOutcome.FAIL)
            historyService.add("Send", false, "checkout failed: ${co.stderr}")
            return
          }
        }

        indicator.text = "Отправляем «$chosenBranch»…"
        append("Send: $chosenBranch")
        if (additionalBranches.isNotEmpty()) append("Send additional: ${additionalBranches.joinToString()}")
        append("Target: ${syncFacade.describeRepoTarget(dir, settings)}")

        val syncRes = try {
          syncFacade.runFullSync(dir, settings, additionalBranches)
        } finally {
          // Always restore original branch after checkout
          if (needsCheckout && !currentBranch.isNullOrBlank()) {
            val back = GitLocal.checkout(project, dir, currentBranch)
            if (!back.ok()) {
              notify(LocalGitMirrorBundle.message("notify.restoreBranchFailed", currentBranch, back.stderr), NotificationType.WARNING)
            }
          }
        }

        val res = syncRes.step
        if (!res.ok) {
          append("Failed: ${res.message} ${res.details}")
          historyService.add("Send", false, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}")
          notify("[trace=${syncRes.traceId}] repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}", NotificationType.ERROR)
          markLastSync(LocalGitMirrorPanel.SyncOutcome.FAIL)
          refreshHistoryLog()
          return
        }
        if (settings.offlineGenerateOnly) {
          append("Offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
          historyService.add("Send", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
          notify("[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: settings.repo}'", NotificationType.INFORMATION)
        } else {
          append("OK: ${syncRes.http?.code} ${syncRes.http?.body?.take(400) ?: ""}")
          historyService.add("Send", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${syncRes.http?.body ?: "OK"}")
          notify("[trace=${syncRes.traceId}] ${LocalGitMirrorBundle.message("notify.send.branch.ok", chosenBranch, syncRes.repo ?: settings.repo)}", NotificationType.INFORMATION)
        }
        markLastSyncOk()
        refreshHistoryLog()
      } finally {
        isSyncing = false
      }
    }

    override fun onFinished() {
      isSyncing = false
    }
  })
}

internal fun LocalGitMirrorPanel.syncBranch() {
  val dir = baseDir() ?: run {
    notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
    return
  }
  val settings = service<MirrorSettingsService>().state
  val err = ensureConfigured(settings)
  if (err != null) {
    notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
    return
  }

  val branches = GitLocal.localBranches(project, dir)
  val current = GitLocal.currentBranch(project, dir)
  val chosen = Messages.showEditableChooseDialog(
    LocalGitMirrorBundle.message("dialog.selectBranch.prompt"),
    LocalGitMirrorBundle.message("dialog.selectBranch.title"),
    null, branches.toTypedArray(), current, null
  ) ?: return

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Send branch", false) {
    override fun run(indicator: ProgressIndicator) {
      try {
        val original = GitLocal.currentBranch(project, dir)
        append("Send branch: $chosen")
        append("Target: ${syncFacade.describeRepoTarget(dir, settings)}")

        val co = GitLocal.checkout(project, dir, chosen)
        if (!co.ok()) {
          append(LocalGitMirrorBundle.message("notify.checkoutFailed", co.stderr))
          notify(LocalGitMirrorBundle.message("notify.checkoutFailed", co.stderr), NotificationType.ERROR)
          return
        }

        val syncRes = try {
          syncFacade.runFullSync(dir, settings)
        } finally {
          if (!original.isNullOrBlank() && original != chosen) {
            val back = GitLocal.checkout(project, dir, original)
            if (!back.ok()) {
              notify(LocalGitMirrorBundle.message("notify.restoreBranchFailed", original, back.stderr), NotificationType.WARNING)
            }
          }
        }

        val res = syncRes.step
        if (!res.ok) {
          append("Failed: ${res.message} ${res.details}")
          historyService.add("Send branch", false, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}")
          notify("[trace=${syncRes.traceId}] repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}", NotificationType.ERROR)
          markLastSync(LocalGitMirrorPanel.SyncOutcome.FAIL)
          refreshHistoryLog()
          return
        }
        if (settings.offlineGenerateOnly) {
          append("Offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
          historyService.add("Send branch", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
          notify("[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: settings.repo}'", NotificationType.INFORMATION)
        } else {
          append("OK: ${syncRes.http?.code} ${syncRes.http?.body?.take(400) ?: ""}")
          historyService.add("Send branch", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${syncRes.http?.body ?: "OK"}")
          notify("[trace=${syncRes.traceId}] ${LocalGitMirrorBundle.message("notify.send.branch.ok", chosen, syncRes.repo ?: settings.repo)}", NotificationType.INFORMATION)
        }
        markLastSyncOk()
        refreshHistoryLog()
      } finally {
        isSyncing = false
      }
    }

    override fun onFinished() {
      isSyncing = false
    }
  })
}

internal fun LocalGitMirrorPanel.syncSelectedCommits() {
  val dir = baseDir() ?: run {
    notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
    return
  }
  val settings = service<MirrorSettingsService>().state
  val err = ensureConfigured(settings)
  if (err != null) {
    notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
    return
  }

  val commits = GitLocal.recentCommits(project, dir, 30)
  if (commits.isEmpty()) {
    notify(LocalGitMirrorBundle.message("notify.noRecentCommits"), NotificationType.WARNING)
    return
  }
  val selectedHashes = pickCommitHashes(commits)
  if (selectedHashes.isEmpty()) return

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Send commits", false) {
    override fun run(indicator: ProgressIndicator) {
      try {
        val original = GitLocal.currentBranch(project, dir)
        val tempBranch = "sync-tmp-${System.currentTimeMillis()}"
        append("Send commits: ${selectedHashes.joinToString(" ")}")
        append("Target: ${syncFacade.describeRepoTarget(dir, settings)}")

        val create = GitLocal.checkoutNew(project, dir, tempBranch, "HEAD")
        if (!create.ok()) {
          notify("Failed to create temp branch: ${create.stderr}", NotificationType.ERROR)
          return
        }

        var cherryFailed = false
        for (hash in selectedHashes) {
          val cp = GitLocal.cherryPick(project, dir, hash)
          if (!cp.ok()) {
            cherryFailed = true
            GitLocal.cherryPickAbort(project, dir)
            notify("Cherry-pick failed for $hash: ${cp.stderr}", NotificationType.ERROR)
            markLastSync(LocalGitMirrorPanel.SyncOutcome.FAIL)
            historyService.add("Send commits", false, "Cherry-pick failed for $hash")
            break
          }
        }

        if (cherryFailed) {
          if (!original.isNullOrBlank()) GitLocal.checkout(project, dir, original)
          GitLocal.deleteLocalBranch(project, dir, tempBranch, force = true)
          return
        }

        val syncRes = try {
          syncFacade.runFullSync(dir, settings)
        } finally {
          if (!original.isNullOrBlank()) GitLocal.checkout(project, dir, original)
          GitLocal.deleteLocalBranch(project, dir, tempBranch, force = true)
        }

        val res = syncRes.step
        if (!res.ok) {
          append("Failed: ${res.message} ${res.details}")
          historyService.add("Send commits", false, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}")
          notify("[trace=${syncRes.traceId}] repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}", NotificationType.ERROR)
          markLastSync(LocalGitMirrorPanel.SyncOutcome.FAIL)
          refreshHistoryLog()
          return
        }
        if (settings.offlineGenerateOnly) {
          append("Offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
          historyService.add("Send commits", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
          notify("[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: settings.repo}'", NotificationType.INFORMATION)
        } else {
          append("OK: ${syncRes.http?.code} ${syncRes.http?.body?.take(400) ?: ""}")
          historyService.add("Send commits", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${syncRes.http?.body ?: "OK"}")
          notify("[trace=${syncRes.traceId}] Sent selected commits to Mirror repo '${syncRes.repo ?: settings.repo}'", NotificationType.INFORMATION)
        }
        markLastSyncOk()
        refreshHistoryLog()
      } finally {
        isSyncing = false
      }
    }

    override fun onFinished() {
      isSyncing = false
    }
  })
}

internal fun LocalGitMirrorPanel.pushAs() {
  val dir = baseDir() ?: run {
    notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
    return
  }
  val settings = service<MirrorSettingsService>().state
  val err = ensureConfigured(settings)
  if (err != null) {
    notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
    return
  }

  val currentBranch = GitLocal.currentBranch(project, dir)
  if (currentBranch.isNullOrBlank()) {
    notify(LocalGitMirrorBundle.message("notify.currentBranch.missing"), NotificationType.WARNING)
    return
  }

  val targetBranch = Messages.showInputDialog(
    project,
    LocalGitMirrorBundle.message("dialog.pushAs.prompt", currentBranch),
    LocalGitMirrorBundle.message("dialog.pushAs.title"),
    null, "", null
  )
  if (targetBranch.isNullOrBlank()) return

  // Basic branch name validation
  if (targetBranch.startsWith(".") || targetBranch.startsWith("-") ||
    targetBranch.contains("..") || targetBranch.contains("@{") ||
    targetBranch.any { it in setOf(' ', '~', '^', ':', '?', '*', '[', '\\') } ||
    targetBranch.endsWith(".lock") || targetBranch.endsWith("/") ||
    targetBranch.contains("//")
  ) {
    notify(LocalGitMirrorBundle.message("action.pushAs.invalidBranch", targetBranch), NotificationType.ERROR)
    return
  }

  val localBranches = GitLocal.localBranches(project, dir)
  if (localBranches.contains(targetBranch)) {
    notify(LocalGitMirrorBundle.message("notify.send.branch.alreadyExists", targetBranch), NotificationType.WARNING)
    return
  }

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Push as '$targetBranch'", false) {
    override fun run(indicator: ProgressIndicator) {
      try {
        append("Push as: $currentBranch -> $targetBranch")
        append("Target: ${syncFacade.describeRepoTarget(dir, settings)}")

        val create = GitLocal.checkoutNew(project, dir, targetBranch, "HEAD")
        if (!create.ok()) {
          notify("Failed to create branch '$targetBranch': ${create.stderr}", NotificationType.ERROR)
          markLastSync(LocalGitMirrorPanel.SyncOutcome.FAIL)
          return
        }

        val syncRes = try {
          syncFacade.runFullSync(dir, settings)
        } finally {
          if (!currentBranch.isNullOrBlank()) GitLocal.checkout(project, dir, currentBranch)
          GitLocal.deleteLocalBranch(project, dir, targetBranch, force = true)
        }

        val res = syncRes.step
        if (!res.ok) {
          append("Failed: ${res.message} ${res.details}")
          historyService.add("Push as", false, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}")
          notify("[trace=${syncRes.traceId}] repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}", NotificationType.ERROR)
          markLastSync(LocalGitMirrorPanel.SyncOutcome.FAIL)
          refreshHistoryLog()
          return
        }
        if (settings.offlineGenerateOnly) {
          append("Offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
          historyService.add("Push as", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
          notify("[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: settings.repo}'", NotificationType.INFORMATION)
        } else {
          append("OK: ${syncRes.http?.code} ${syncRes.http?.body?.take(400) ?: ""}")
          historyService.add("Push as", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${syncRes.http?.body ?: "OK"}")
          notify("[trace=${syncRes.traceId}] ${LocalGitMirrorBundle.message("notify.send.pushAs.ok", targetBranch, syncRes.repo ?: settings.repo)}", NotificationType.INFORMATION)
        }
        markLastSyncOk()
        refreshHistoryLog()
      } finally {
        isSyncing = false
      }
    }

    override fun onFinished() {
      isSyncing = false
    }
  })
}

internal fun LocalGitMirrorPanel.pickCommitHashes(commits: List<GitLocal.CommitSummary>): List<String> {
  val dlg = CommitPickerDialog(project, commits)
  if (!dlg.showAndGet()) return emptyList()
  return dlg.selectedHashes
}
