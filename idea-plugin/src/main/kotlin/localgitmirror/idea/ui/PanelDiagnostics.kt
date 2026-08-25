package localgitmirror.idea.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import localgitmirror.idea.git.GitLocal
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
  val remote = GitLocal.defaultRemote(project, dir)

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
    null, arrayOf("new-branch", "ff-only"), "new-branch", null
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
  // PullFromMirrorAction handles its own Task lifecycle.
  // isSyncing will be reset in onFinished/onCancel of the inner task,
  // but we also guard with a finally in actionPerformed.
  setProgress(-1.0, "Получаем список веток с Mirror…")

  // Use the panel's branch selector as the single source of truth: pull the
  // branch chosen in the combo. PullFromMirrorAction falls back to its own
  // picker if this branch isn't present on Mirror (or selection is null).
  val preselected = selectedBranch()

  try {
    localgitmirror.idea.actions.PullFromMirrorAction(preselected) { indicator ->
        currentIndicator = indicator
      }.actionPerformed(
      com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
        "LocalGitMirrorToolWindow", null,
        com.intellij.openapi.actionSystem.DataContext { dataId ->
          if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(dataId)) project else null
        }
      )
    )
  } finally {
    // isSyncing will be turned off by the action's own task completion,
    // but release it here too in case the action returned early (no task started).
    isSyncing = false
  }
}

/** Pull a specific branch by name (for multi-select pull). */
internal fun LocalGitMirrorPanel.pullFromMirror(branchName: String) {
  isSyncing = true
  setProgress(-1.0, "Получаем список веток с Mirror…")

  try {
    localgitmirror.idea.actions.PullFromMirrorAction(branchName) { indicator ->
        currentIndicator = indicator
      }.actionPerformed(
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
  val remote = Messages.showInputDialog(project, "Remote name", "git push", null,
    GitLocal.defaultRemote(project, dir), null) ?: return

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


/**
 * Pulls the freshest plugin .zip the Mirror has built and drops it into the
 * user's Downloads folder. The IDE plugin can't install zips silently
 * (IntelliJ requires an explicit user gesture for that), so we save the file
 * and offer two follow-ups in the notification:
 *  - "Open folder"          → reveal in Explorer/Finder
 *  - "Open Plugins settings" → user clicks ⚙ → Install Plugin from Disk…
 *
 * Auth uses the same API_KEY/Bearer token as every other /api request,
 * so an unconfigured Mirror gets the same scanner-resistant 404 as the rest
 * of the API surface.
 */
internal fun LocalGitMirrorPanel.downloadLatestPlugin() {
  val settings = service<MirrorSettingsService>().state
  if (settings.baseUrl.isBlank()) {
    notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
    return
  }

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: \u0421\u043a\u0430\u0447\u0438\u0432\u0430\u043d\u0438\u0435 \u043f\u043b\u0430\u0433\u0438\u043d\u0430", true) {
    override fun run(indicator: ProgressIndicator) {
      try {
        indicator.isIndeterminate = true
        indicator.text = "\u041f\u0440\u043e\u0432\u0435\u0440\u044f\u0435\u043c \u043d\u0430\u043b\u0438\u0447\u0438\u0435 \u0441\u0431\u043e\u0440\u043a\u0438\u2026"

        val info = MirrorApi.pluginInfo(settings.baseUrl, SecretsStore.mirrorApiKey, settings.mirrorInsecureTls)
        if (info.code == 404 || !info.available) {
          notify(
            "\u041d\u0430 Mirror \u043d\u0435\u0442 \u0441\u043e\u0431\u0440\u0430\u043d\u043d\u043e\u0433\u043e \u043f\u043b\u0430\u0433\u0438\u043d\u0430. \u0417\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u0435 'gradle buildPlugin' \u043d\u0430 \u0441\u0435\u0440\u0432\u0435\u0440\u0435.",
            NotificationType.WARNING
          )
          return
        }
        if (info.code !in 200..299) {
          notify("Mirror \u043d\u0435\u0434\u043e\u0441\u0442\u0443\u043f\u0435\u043d (HTTP ${info.code}): ${info.message.take(200)}", NotificationType.ERROR)
          return
        }

        // Where to save: ~/Downloads if it exists, otherwise user.home.
        val home = java.io.File(System.getProperty("user.home"))
        val downloads = java.io.File(home, "Downloads").takeIf { it.isDirectory } ?: home
        val outName = info.filename ?: ("localgitmirror-${info.version ?: "latest"}.zip")
        val outFile = java.io.File(downloads, outName)

        indicator.isIndeterminate = false
        indicator.text = "\u0421\u043a\u0430\u0447\u0438\u0432\u0430\u043d\u0438\u0435 $outName\u2026"

        val res = MirrorApi.pluginDownload(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          insecureTls = settings.mirrorInsecureTls,
          outFile = outFile,
          onProgress = { read, total ->
            if (total > 0) {
              indicator.fraction = (read.toDouble() / total).coerceIn(0.0, 1.0)
              val readMb = "%.1f".format(read / 1_048_576.0)
              val totalMb = "%.1f".format(total / 1_048_576.0)
              indicator.text = "\u0421\u043a\u0430\u0447\u0438\u0432\u0430\u043d\u0438\u0435\u2026 $readMb / $totalMb \u041c\u0411"
            }
          }
        )

        if (res.code !in 200..299 || res.file == null) {
          notify("Не удалось скачать плагин (HTTP ${res.code}): ${res.message.take(200)}", NotificationType.ERROR)
          historyService.add("Plugin download", false, "HTTP ${res.code} ${res.message.take(200)}")
          return
        }

        // Integrity gate: the server publishes sha256 in /api/plugin/info;
        // refuse (and remove) a download that doesn't match.
        if (info.sha256 != null) {
          val md = java.security.MessageDigest.getInstance("SHA-256")
          outFile.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
              val n = ins.read(buf)
              if (n < 0) break
              md.update(buf, 0, n)
            }
          }
          val actual = md.digest().joinToString("") { b -> "%02x".format(b) }
          if (!actual.equals(info.sha256, ignoreCase = true)) {
            outFile.delete()
            notify(
              "Контрольная сумма плагина не совпадает с сервером (ожидалась ${info.sha256.take(16)}…, получена ${actual.take(16)}…). Файл удалён — установка небезопасна.",
              NotificationType.ERROR
            )
            historyService.add("Plugin download", false, "sha256 mismatch: $actual != ${info.sha256}")
            return
          }
        }

        val sameVersion = info.version != null &&
          pluginVersionText.removePrefix("v") == info.version
        val title = if (sameVersion)
          "\u041f\u043b\u0430\u0433\u0438\u043d \u043d\u0430 Mirror \u0441\u043e\u0432\u043f\u0430\u0434\u0430\u0435\u0442 \u0441 \u0432\u0430\u0448\u0438\u043c (v${info.version})"
        else
          "\u0421\u043a\u0430\u0447\u0430\u043d v${info.version ?: "?"} (\u0432\u044b: $pluginVersionText)"

        val msg = "$title\n${outFile.absolutePath}"
        val notif = com.intellij.notification.NotificationGroupManager.getInstance()
          .getNotificationGroup("LocalGitMirror")
          .createNotification(msg, NotificationType.INFORMATION)
        notif.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring(
          "\u041e\u0442\u043a\u0440\u044b\u0442\u044c \u043f\u0430\u043f\u043a\u0443"
        ) {
          try {
            com.intellij.ide.actions.RevealFileAction.openFile(outFile)
          } catch (_: Throwable) {
            try { java.awt.Desktop.getDesktop().open(downloads) } catch (_: Throwable) {}
          }
        })
        notif.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring(
          "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438 \u043f\u043b\u0430\u0433\u0438\u043d\u043e\u0432\u2026"
        ) {
          com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, "preferences.pluginManager")
        })
        notif.notify(project)
        historyService.add("Plugin download", true, "v${info.version ?: "?"} -> ${outFile.absolutePath}")
        append("Plugin downloaded: ${outFile.absolutePath}")
      } finally {
        isSyncing = false
      }
    }

    override fun onFinished() { isSyncing = false }
  })
}
