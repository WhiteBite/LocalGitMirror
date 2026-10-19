package localgitmirror.idea.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import localgitmirror.idea.actions.PullLogic
import localgitmirror.idea.git.GitDirCache
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorCrypto
import localgitmirror.idea.mirror.MirrorSyncApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.SyncStateStore
import localgitmirror.idea.workkit.BundleCrypto
import java.io.File
import java.util.UUID
import javax.swing.JFileChooser

/** Pull selected branches (multi-select support). Falls back to single-branch picker if nothing selected. */
internal fun LocalGitMirrorPanel.pullSelectedBranches() {
  val selected = branchList.selectedValuesList
  if (selected.isEmpty()) {
    // No selection — use existing single-branch dialog
    pullFromMirror()
    return
  }
  if (selected.size == 1) {
    pullFromMirror(selected.first().name)
    return
  }
  // Multiple branches — pull each in sequence
  pullMultipleBranches(selected.map { it.name })
}

/** Send selected branches (multi-select support). Falls back to current branch if nothing selected. */
internal fun LocalGitMirrorPanel.sendSelectedBranches() {
  val selected = branchList.selectedValuesList
  if (selected.isEmpty()) {
    syncCurrentBranch()
    return
  }
  if (selected.size == 1) {
    syncBranch(selected.first().name)
    return
  }
  syncMultipleBranches(selected.map { it.name })
}

/** Pull multiple branches in sequence, one honest outcome per branch. */
internal fun LocalGitMirrorPanel.pullMultipleBranches(branches: List<String>) {
  if (isSyncing) {
    notify(LocalGitMirrorBundle.message("pull.notify.alreadyRunning"), NotificationType.WARNING)
    return
  }
  val dir = baseDir() ?: run {
    notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
    return
  }
  val settings = service<MirrorSettingsService>().state
  val repo = syncFacade.resolveRepo(dir, settings).sanitized.ifBlank { project.name }

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.multi.pullTask", branches.size), true) {
    override fun run(indicator: ProgressIndicator) {
      val outcomes = pullBranchesFromMirror(project, dir, settings, repo, branches, indicator)
      val summary = multiBranchSummary(LocalGitMirrorBundle.message("panel.multi.pullTitle"), repo, outcomes)
      notify(summary.message, summary.kind.notificationType())
    }

    override fun onSuccess() {
      isSyncing = false
      refreshBranchCombo(withMirror = true)
    }

    override fun onThrowable(error: Throwable) {
      isSyncing = false
      notify(LocalGitMirrorBundle.message("panel.multi.opError", error.message ?: ""), NotificationType.ERROR)
    }
  })
}

/** Sync a specific branch to Mirror. */
internal fun LocalGitMirrorPanel.syncBranch(branchName: String) {
  if (isSyncing) {
    notify(LocalGitMirrorBundle.message("pull.notify.alreadyRunning"), NotificationType.WARNING)
    return
  }

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("action.syncBranch.progress", branchName), true) {
    override fun run(indicator: ProgressIndicator) {
      val dir = baseDir() ?: run {
        notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
        return
      }
      val settings = service<MirrorSettingsService>().state

      indicator.text = LocalGitMirrorBundle.message("panel.multi.sendProgress", branchName)
      try {
        val result = syncFacade.runFullSync(dir, settings, additionalBranches = listOf(branchName))
        if (!result.step.ok) {
          notify(LocalGitMirrorBundle.message("panel.multi.sendBranchFail", branchName, result.step.message), NotificationType.ERROR)
        } else {
          notify(LocalGitMirrorBundle.message("notify.send.branch.ok", branchName), NotificationType.INFORMATION)
        }
      } catch (e: Exception) {
        notify(LocalGitMirrorBundle.message("panel.multi.sendBranchFail", branchName, e.message ?: ""), NotificationType.ERROR)
      }
    }

    override fun onSuccess() {
      isSyncing = false
      refreshBranchCombo(withMirror = true)
    }

    override fun onThrowable(error: Throwable) {
      isSyncing = false
      notify(LocalGitMirrorBundle.message("panel.multi.opError", error.message ?: ""), NotificationType.ERROR)
    }
  })
}

/** Send all selected branches (multi-select support). */
internal fun LocalGitMirrorPanel.syncSelectedBranches() {
  val selected = branchList.selectedValuesList
  if (selected.isEmpty()) {
    notify(LocalGitMirrorBundle.message("panel.multi.selectBranchesToSend"), NotificationType.WARNING)
    return
  }
  val branchNames = selected.map { it.name }
  if (branchNames.size == 1) {
    syncCurrentBranch()
  } else {
    // Send multiple branches
    syncMultipleBranches(branchNames)
  }
}

/** Send multiple branches as one sync: single bundle, single upload. */
internal fun LocalGitMirrorPanel.syncMultipleBranches(branches: List<String>) {
  if (isSyncing) {
    notify(LocalGitMirrorBundle.message("pull.notify.alreadyRunning"), NotificationType.WARNING)
    return
  }
  val dir = baseDir() ?: run {
    notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
    return
  }
  val settings = service<MirrorSettingsService>().state
  val repo = syncFacade.resolveRepo(dir, settings).sanitized.ifBlank { project.name }

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.multi.sendTask", branches.size), true) {
    override fun run(indicator: ProgressIndicator) {
      indicator.text = LocalGitMirrorBundle.message("panel.multi.sendProgressMany", branches.size, branches.joinToString(", "))
      val outcomes = try {
        val result = syncFacade.runFullSync(dir, settings, additionalBranches = branches)
        val error = if (result.step.ok) null else result.step.message
        branches.map { MultiBranchOutcome(it, result.step.ok, error) }
      } catch (e: Exception) {
        branches.map { MultiBranchOutcome(it, false, e.message) }
      }
      val summary = multiBranchSummary(LocalGitMirrorBundle.message("panel.multi.sendTitle"), repo, outcomes)
      notify(summary.message, summary.kind.notificationType())
    }

    override fun onSuccess() {
      isSyncing = false
      refreshBranchCombo(withMirror = true)
    }

    override fun onThrowable(error: Throwable) {
      isSyncing = false
      notify(LocalGitMirrorBundle.message("panel.multi.opError", error.message ?: ""), NotificationType.ERROR)
    }
  })
}

/** Export bundle for offline transfer. */
internal fun LocalGitMirrorPanel.exportBundle() {
  val dir = baseDir() ?: run {
    notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
    return
  }

  val selected = branchList.selectedValuesList
  if (selected.isEmpty()) {
    notify(LocalGitMirrorBundle.message("panel.bundle.exportNone"), NotificationType.WARNING)
    return
  }

  val branchNames = selected.map { it.name }

  // Ask user where to save
  val fileChooser = JFileChooser().apply {
    dialogTitle = LocalGitMirrorBundle.message("panel.bundle.saveTitle")
    selectedFile = File("${project.name}-${branchNames.joinToString("-")}.bundle")
    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Git Bundle", "bundle")
  }

  if (fileChooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
    return
  }

  val targetFile = fileChooser.selectedFile

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.bundle.exportTask"), true) {
    override fun run(indicator: ProgressIndicator) {
      indicator.text = LocalGitMirrorBundle.message("panel.bundle.exportProgress", branchNames.joinToString(", "))

      try {
        // Use git bundle create
        val refs = branchNames.map { "refs/heads/$it" }
        val cmd = mutableListOf("git", "bundle", "create", targetFile.absolutePath) + refs

        val proc = ProcessBuilder(cmd)
          .directory(dir)
          .redirectErrorStream(false)
          .start()

        val exitCode = proc.waitFor()
        val stderr = proc.errorStream.bufferedReader().readText()

        if (exitCode != 0) {
          notify(LocalGitMirrorBundle.message("panel.bundle.exportFail", stderr), NotificationType.ERROR)
          historyService.add(LocalGitMirrorBundle.message("history.op.exportBundle"), false,
            "branches=${branchNames.joinToString(",")} err=${stderr.take(300)}")
        } else {
          notify(LocalGitMirrorBundle.message("panel.bundle.exportOk", targetFile.absolutePath, targetFile.length() / 1024), NotificationType.INFORMATION)
          historyService.add(LocalGitMirrorBundle.message("history.op.exportBundle"), true,
            "branches=${branchNames.joinToString(",")} size=${targetFile.length() / 1024}KB -> ${targetFile.absolutePath}")
        }
      } catch (e: Exception) {
        notify(LocalGitMirrorBundle.message("panel.bundle.exportFail", e.message ?: ""), NotificationType.ERROR)
        historyService.add(LocalGitMirrorBundle.message("history.op.exportBundle"), false,
          "branches=${branchNames.joinToString(",")} err=${e.message?.take(300)}")
      }
    }

    override fun onSuccess() {
      isSyncing = false
    }

    override fun onThrowable(error: Throwable) {
      isSyncing = false
      notify(LocalGitMirrorBundle.message("panel.multi.opError", error.message ?: ""), NotificationType.ERROR)
    }
  })
}

/** Import bundle file. */
internal fun LocalGitMirrorPanel.importBundle() {
  val dir = baseDir() ?: run {
    notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
    return
  }

  // Ask user to select bundle file
  val fileChooser = JFileChooser().apply {
    dialogTitle = LocalGitMirrorBundle.message("panel.bundle.importTitle")
    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Git Bundle", "bundle")
  }

  if (fileChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
    return
  }

  val bundleFile = fileChooser.selectedFile

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.bundle.importTask"), true) {
    override fun run(indicator: ProgressIndicator) {
      indicator.text = LocalGitMirrorBundle.message("panel.bundle.importProgress", bundleFile.name)

      try {
        // Use git bundle verify first
        val verifyProc = ProcessBuilder("git", "bundle", "verify", bundleFile.absolutePath)
          .directory(dir)
          .redirectErrorStream(false)
          .start()

        val verifyExit = verifyProc.waitFor()
        val verifyOutput = verifyProc.inputStream.bufferedReader().readText()

        if (verifyExit != 0) {
          notify(LocalGitMirrorBundle.message("panel.bundle.invalid", verifyOutput), NotificationType.ERROR)
          historyService.add(LocalGitMirrorBundle.message("history.op.importBundle"), false,
            "file=${bundleFile.name} verify failed: ${verifyOutput.take(300)}")
          return
        }

        // Extract branch names from verify output
        val branches = verifyOutput.lines()
          .filter { it.contains("refs/heads/") }
          .map { it.substringAfter("refs/heads/").trim() }

        // Use git fetch to import
        val fetchProc = ProcessBuilder("git", "fetch", bundleFile.absolutePath)
          .directory(dir)
          .redirectErrorStream(false)
          .start()

        val fetchExit = fetchProc.waitFor()
        val fetchStderr = fetchProc.errorStream.bufferedReader().readText()

        if (fetchExit != 0) {
          notify(LocalGitMirrorBundle.message("panel.bundle.importFail", fetchStderr), NotificationType.ERROR)
          historyService.add(LocalGitMirrorBundle.message("history.op.importBundle"), false,
            "file=${bundleFile.name} fetch err=${fetchStderr.take(300)}")
        } else {
          notify(LocalGitMirrorBundle.message("panel.bundle.importOk", branches.size, branches.joinToString(", ")), NotificationType.INFORMATION)
          historyService.add(LocalGitMirrorBundle.message("history.op.importBundle"), true,
            "file=${bundleFile.name} branches=${branches.joinToString(",")}")
        }
      } catch (e: Exception) {
        notify(LocalGitMirrorBundle.message("panel.bundle.importFail", e.message ?: ""), NotificationType.ERROR)
        historyService.add(LocalGitMirrorBundle.message("history.op.importBundle"), false,
          "file=${bundleFile.name} err=${e.message?.take(300)}")
      }
    }

    override fun onSuccess() {
      isSyncing = false
      refreshBranchCombo(withMirror = true)
    }

    override fun onThrowable(error: Throwable) {
      isSyncing = false
      notify(LocalGitMirrorBundle.message("panel.multi.opError", error.message ?: ""), NotificationType.ERROR)
    }
  })
}

/** Delete selected branches (locally and on Mirror). */
internal fun LocalGitMirrorPanel.deleteSelectedBranches() {
  val selected = branchList.selectedValuesList
  if (selected.isEmpty()) {
    notify(LocalGitMirrorBundle.message("panel.delete.selectBranches"), NotificationType.WARNING)
    return
  }

  val branchNames = selected.map { it.name }
  val currentBranch = GitLocal.currentBranch(project, baseDir() ?: return)

  // Don't allow deleting current branch
  if (currentBranch != null && branchNames.contains(currentBranch)) {
    notify(LocalGitMirrorBundle.message("panel.delete.currentBranch", currentBranch), NotificationType.WARNING)
    return
  }

  val confirm = Messages.showYesNoDialog(
    project,
    LocalGitMirrorBundle.message("panel.delete.confirmMessage", branchNames.size, branchNames.joinToString("\n")),
    LocalGitMirrorBundle.message("panel.delete.confirmTitle"),
    LocalGitMirrorBundle.message("panel.delete.confirmYes"),
    LocalGitMirrorBundle.message("panel.delete.confirmNo"),
    Messages.getWarningIcon()
  )

  if (confirm != Messages.YES) return

  val dir = baseDir() ?: return
  val settings = service<MirrorSettingsService>().state
  val repo = syncFacade.resolveRepo(dir, settings).sanitized

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.delete.task"), true) {
    override fun run(indicator: ProgressIndicator) {
      val deleted = mutableListOf<String>()
      val errors = mutableListOf<String>()

      for (branch in branchNames) {
        indicator.checkCanceled()
        indicator.text = LocalGitMirrorBundle.message("panel.delete.progress", branch)

        // Delete locally
        val localResult = GitLocal.deleteLocalBranch(project, dir, branch, force = true)
        if (!localResult.ok()) {
          errors.add(LocalGitMirrorBundle.message("panel.delete.localFail", branch, localResult.stderr))
        }

        // Delete on Mirror
        val mirrorResult = MirrorSyncApi.deleteRef(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repo,
          branch = branch,
          syncPassword = SecretsStore.syncPassword,
          insecureTls = settings.mirrorInsecureTls
        )
        if (mirrorResult.code !in 200..299) {
          errors.add(LocalGitMirrorBundle.message("panel.delete.mirrorFail", branch, mirrorResult.body.take(100)))
        }

        if (localResult.ok() || mirrorResult.code in 200..299) {
          deleted.add(branch)
        }
      }

      if (deleted.isNotEmpty()) {
        notify(LocalGitMirrorBundle.message("panel.delete.ok", deleted.size, deleted.joinToString(", ")), NotificationType.INFORMATION)
      }
      if (errors.isNotEmpty()) {
        notify(LocalGitMirrorBundle.message("panel.delete.errors", errors.joinToString("; ")), NotificationType.WARNING)
      }
    }

    override fun onSuccess() {
      isSyncing = false
      refreshBranchCombo(withMirror = true)
    }

    override fun onThrowable(error: Throwable) {
      isSyncing = false
      notify(LocalGitMirrorBundle.message("panel.multi.opError", error.message ?: ""), NotificationType.ERROR)
    }
  })
}

internal enum class MultiBranchSummaryKind { SUCCESS, PARTIAL, FAILED }

internal data class MultiBranchOutcome(val branch: String, val ok: Boolean, val error: String? = null)

internal data class MultiBranchSummary(val kind: MultiBranchSummaryKind, val message: String)

internal fun multiBranchSummary(opTitle: String, repo: String, outcomes: List<MultiBranchOutcome>): MultiBranchSummary {
  val failed = outcomes.filter { !it.ok }
  val failureList = failed.joinToString("; ") { o ->
    if (o.error.isNullOrBlank()) o.branch else "${o.branch}: ${o.error}"
  }
  return when {
    failed.isEmpty() -> MultiBranchSummary(
      MultiBranchSummaryKind.SUCCESS,
      LocalGitMirrorBundle.message("panel.multi.ok", opTitle, outcomes.size, repo, outcomes.joinToString(", ") { it.branch })
    )
    failed.size == outcomes.size -> MultiBranchSummary(
      MultiBranchSummaryKind.FAILED,
      LocalGitMirrorBundle.message("panel.multi.failed", opTitle, outcomes.size, repo, failureList)
    )
    else -> MultiBranchSummary(
      MultiBranchSummaryKind.PARTIAL,
      LocalGitMirrorBundle.message("panel.multi.partial", opTitle, outcomes.size - failed.size, outcomes.size, repo, failureList)
    )
  }
}

internal fun MultiBranchSummaryKind.notificationType(): NotificationType = when (this) {
  MultiBranchSummaryKind.SUCCESS -> NotificationType.INFORMATION
  MultiBranchSummaryKind.PARTIAL -> NotificationType.WARNING
  MultiBranchSummaryKind.FAILED -> NotificationType.ERROR
}

private fun pullBranchesFromMirror(
  project: Project,
  dir: File,
  settings: MirrorSettingsService.State,
  repo: String,
  branches: List<String>,
  indicator: ProgressIndicator
): List<MultiBranchOutcome> {
  if (!GitLocal.isCleanWorkTree(project, dir)) {
    val err = LocalGitMirrorBundle.message("notify.worktree.dirty")
    return branches.map { MultiBranchOutcome(it, false, err) }
  }
  if (settings.baseUrl.isBlank()) {
    val err = LocalGitMirrorBundle.message("pull.notify.urlMissing")
    return branches.map { MultiBranchOutcome(it, false, err) }
  }
  if (SecretsStore.syncPassword.isBlank() && !MirrorCrypto.isV3Pinned()) {
    val err = LocalGitMirrorBundle.message("pull.notify.passwordMissing")
    return branches.map { MultiBranchOutcome(it, false, err) }
  }

  val refs = MirrorSyncApi.getRefs(
    baseUrl = settings.baseUrl,
    apiKey = SecretsStore.mirrorApiKey,
    repo = repo,
    syncPassword = SecretsStore.syncPassword,
    insecureTls = settings.mirrorInsecureTls
  )
  if (refs.code !in 200..299 || refs.refs == null) {
    val err = LocalGitMirrorBundle.message("pull.notify.refsFailed", refs.code, refs.message)
    return branches.map { MultiBranchOutcome(it, false, err) }
  }
  val remoteRefs = refs.refs.orEmpty().mapValues { it.value.sha }

  val outcomes = branches.mapIndexed { index, branch ->
    indicator.checkCanceled()
    indicator.fraction = index.toDouble() / branches.size
    indicator.text = LocalGitMirrorBundle.message("panel.multi.pullProgress", branch, index + 1, branches.size)
    pullOneBranchFromMirror(project, dir, settings, repo, branch, remoteRefs)
  }
  if (outcomes.any { it.ok }) {
    refs.head?.let { SyncStateStore.writeLastPulledHead(dir, it) }
  }
  return outcomes
}

private fun pullOneBranchFromMirror(
  project: Project,
  dir: File,
  settings: MirrorSettingsService.State,
  repo: String,
  branch: String,
  remoteRefs: Map<String, String>
): MultiBranchOutcome {
  val targetHash = remoteRefs[branch]
    ?: return MultiBranchOutcome(branch, false, LocalGitMirrorBundle.message("pull.notify.branchNotFound", branch))

  val hasObject = GitLocal.run(project, dir, 30, "cat-file", "-e", targetHash).ok()
  val localTip = GitLocal.run(project, dir, 30, "rev-parse", "--verify", branch)
    .let { if (it.ok()) it.stdout.trim() else null }
  if (hasObject && localTip == targetHash) return MultiBranchOutcome(branch, true)

  try {
    if (!hasObject) {
      val fetchError = fetchBranchBundle(project, dir, settings, repo, branch, remoteRefs)
      if (fetchError != null) return MultiBranchOutcome(branch, false, fetchError)
      if (!GitLocal.run(project, dir, 30, "cat-file", "-e", targetHash).ok()) {
        return MultiBranchOutcome(branch, false, LocalGitMirrorBundle.message("pull.notify.objectsMissing", branch))
      }
    }

    val localBranches = GitLocal.listBranches(project, dir).toSet()
    PullLogic.applyBranch(
      branchName = branch,
      newHash = targetHash,
      localBranches = localBranches,
      revParse = { ref -> GitLocal.run(project, dir, 30, "rev-parse", ref).stdout.trim() },
      isAncestor = { a, d -> GitLocal.run(project, dir, 30, "merge-base", "--is-ancestor", a, d).ok() },
      updateRef = { ref, hash -> GitLocal.run(project, dir, 30, "update-ref", "refs/heads/$ref", hash) },
      createBranch = { ref, hash -> GitLocal.run(project, dir, 30, "branch", ref, hash) },
      createSuffixedBranch = { base, hash -> createSuffixedBranch(project, dir, base, hash) }
    )

    GitLocal.run(project, dir, 30, "for-each-ref", "--format=%(refname)", "refs/fetched/").stdout
      .lines().filter { it.isNotBlank() }
      .forEach { GitLocal.run(project, dir, 30, "update-ref", "-d", it.trim()) }

    if (GitLocal.currentBranch(project, dir) == branch && GitLocal.isCleanWorkTree(project, dir)) {
      // tree was only verified clean at start; edits made during the pull must survive
      GitLocal.run(project, dir, 60, "reset", "--hard", "HEAD")
    }
    return MultiBranchOutcome(branch, true)
  } catch (t: Throwable) {
    if (localTip != null) {
      GitLocal.run(project, dir, 30, "update-ref", "refs/heads/$branch", localTip)
    }
    return MultiBranchOutcome(branch, false, t.message ?: t::class.java.simpleName)
  }
}

private fun fetchBranchBundle(
  project: Project,
  dir: File,
  settings: MirrorSettingsService.State,
  repo: String,
  branch: String,
  remoteRefs: Map<String, String>
): String? {
  val gitDir = GitDirCache.gitDir(dir)
  val cacheDir = File(gitDir, ".cache").also { if (!it.exists()) it.mkdirs() }
  val dumpOut = File(cacheDir, ".tmp_${UUID.randomUUID().toString().take(8)}")
  val tmpBundle = File(cacheDir, ".bundle_${UUID.randomUUID().toString().take(8)}")
  try {
    val since = PullLogic.findSinceHash(dir, remoteRefs)
    var dl = MirrorSyncApi.exportDump(
      baseUrl = settings.baseUrl,
      apiKey = SecretsStore.mirrorApiKey,
      repo = repo,
      since = since,
      syncPassword = SecretsStore.syncPassword,
      insecureTls = settings.mirrorInsecureTls,
      outFile = dumpOut,
      branch = branch
    )
    if (dl.code == 204) {
      dl = MirrorSyncApi.exportDump(
        baseUrl = settings.baseUrl,
        apiKey = SecretsStore.mirrorApiKey,
        repo = repo,
        since = null,
        syncPassword = SecretsStore.syncPassword,
        insecureTls = settings.mirrorInsecureTls,
        outFile = dumpOut,
        branch = branch
      )
    }
    val file = dl.file
    if (dl.code == 204 || dl.code !in 200..299 || file == null) {
      return LocalGitMirrorBundle.message("pull.notify.serverNoObjects", dl.code)
    }
    // v3 delivers a raw bundle, legacy mode a password-encrypted dump
    val raw = file.readBytes()
    val bundleBytes = if (PullLogic.looksLikeGitBundle(raw)) raw
    else BundleCrypto.decryptDumpBytes(raw, SecretsStore.syncPassword)
    tmpBundle.writeBytes(bundleBytes)
    val fetch = GitLocal.run(project, dir, 300, "fetch", tmpBundle.absolutePath, "+refs/heads/*:refs/fetched/mirror/*")
    if (!fetch.ok()) {
      return LocalGitMirrorBundle.message(
        "pull.notify.bundleUnpackFailed",
        fetch.stderr.ifBlank { fetch.stdout }.take(500)
      )
    }
    return null
  } finally {
    runCatching { dumpOut.delete() }
    runCatching { tmpBundle.delete() }
  }
}

private fun createSuffixedBranch(project: Project, dir: File, baseName: String, hash: String): String {
  for (i in 1..99) {
    val name = "$baseName-local-$i"
    if (GitLocal.run(project, dir, 30, "branch", name, hash).ok()) return name
  }
  return "$baseName-local"
}
