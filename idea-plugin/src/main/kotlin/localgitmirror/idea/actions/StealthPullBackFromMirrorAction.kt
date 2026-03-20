package localgitmirror.idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.SyncStateStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import localgitmirror.idea.workkit.WorkKit
import java.io.File
import java.util.UUID

class StealthPullBackFromMirrorAction : AnAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val baseDir = project.basePath
    if (baseDir.isNullOrBlank()) {
      notify(project, "Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val dir = File(baseDir)
    val settings = service<MirrorSettingsService>().state
    val syncFacade = project.getService(SyncFacadeService::class.java)
    val history = service<OperationsHistoryService>()

    if (settings.baseUrl.isBlank()) {
      notify(project, "Configure Mirror URL in settings", NotificationType.WARNING)
      return
    }

    val repoName = syncFacade.inferRepoName(dir, settings)
    if (repoName.isBlank()) {
      notify(project, "Configured Mirror Repo is invalid after sanitization", NotificationType.ERROR)
      return
    }

    val ensureRepo = syncFacade.ensureRemoteRepo(
      baseUrl = settings.baseUrl,
      apiKey = SecretsStore.mirrorApiKey,
      repo = repoName,
      insecureTls = settings.mirrorInsecureTls
    )
    if (!ensureRepo.ok) {
      notify(project, "${ensureRepo.message}. ${ensureRepo.details}", NotificationType.ERROR)
      return
    }
    if (SecretsStore.syncPassword.isBlank()) {
      notify(project, "Configure Sync Password in settings", NotificationType.WARNING)
      return
    }
    if (!GitLocal.isCleanWorkTree(project, dir)) {
      notify(project, "Working tree has uncommitted changes. Commit/stash before syncing.", NotificationType.WARNING)
      return
    }

    // --- NEW: Diff Preview ---
    val sinceForPreview = SyncStateStore.readLastPulledHead(dir) ?: run {
        val legacyFile = File(dir, ".last_sync_pull")
        if (legacyFile.exists()) legacyFile.readText().trim().ifBlank { null } else null
    }

    val previewDetails = MirrorApi.previewPullDetails(
        baseUrl = settings.baseUrl,
        apiKey = SecretsStore.mirrorApiKey,
        repo = repoName,
        since = sinceForPreview,
        insecureTls = settings.mirrorInsecureTls
    )

    if (previewDetails.code in 200..299 && previewDetails.commits.isNotEmpty()) {
        val commitList = previewDetails.commits.joinToString("\n") { "- ${it.hash.take(7)}: ${it.message}" }
        val message = "Incoming changes from Mirror:\n\n$commitList\n\nDiffstat:\n${previewDetails.diffstat}\n\nProceed with stealth pull?"
        val res = Messages.showYesNoDialog(project, message, "LocalGitMirror: Pull Preview", null)
        if (res != Messages.YES) return
    } else if (previewDetails.code in 200..299 && previewDetails.commits.isEmpty()) {
        val res = Messages.showYesNoDialog(project, "No new commits found on mirror. Force pull anyway?", "LocalGitMirror: Pull Preview", null)
        if (res != Messages.YES) return
    }
    // --- End Diff Preview ---

    val defaultMode = settings.pullBackDefaultMode.ifBlank { "auto" }
    val mode = Messages.showEditableChooseDialog(
      "Stealth pull mode\n\nauto = create/update branch matching sender's branch\nnew-branch = create a new branch with custom name\nff-only = fast-forward merge into current branch",
      "LocalGitMirror: Stealth Pull Back",
      null,
      arrayOf("auto", "new-branch", "ff-only"),
      defaultMode,
      null
    )?.trim()?.lowercase().orEmpty()

    if (mode.isBlank()) return

    val branchName: String? = if (mode == "new-branch") {
      val defaultName = "stealth-pull-${System.currentTimeMillis()}"
      Messages.showInputDialog(project, "New branch name", "LocalGitMirror: Stealth Pull Back", null, defaultName, null)?.trim()
    } else null

    if (mode == "new-branch" && branchName.isNullOrBlank()) return

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Stealth Pull Back", false) {
      override fun run(indicator: ProgressIndicator) {
        // Read last pulled head if present to request incremental export.
        val legacySinceFile = File(dir, ".last_sync_pull")
        val sinceFromState = SyncStateStore.readLastPulledHead(dir)
        val sinceFromLegacy = if (legacySinceFile.exists()) legacySinceFile.readText().trim().ifBlank { null } else null
        val since = sinceFromState ?: sinceFromLegacy
        val traceId = UUID.randomUUID().toString().take(8)

        // Cleanup legacy file after successful read/migration
        if (legacySinceFile.exists()) {
            try {
                if (sinceFromLegacy != null) {
                    SyncStateStore.writeLastPulledHead(dir, sinceFromLegacy)
                }
                legacySinceFile.delete()
            } catch (_: Exception) {}
        }

        notify(project, "[trace=$traceId] Starting stealth pull: repo=$repoName, since=${since?.take(12) ?: "(none)"}, mode=$mode, branch=${branchName ?: "(auto)"}", NotificationType.INFORMATION)

        indicator.text = "Downloading dump from Mirror"
        val tmpDir = File(dir, ".localgitmirror/tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()
        val dumpOut = File(tmpDir, "${UUID.randomUUID()}.dmp")

        val dl = MirrorApi.exportDump(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repoName,
          since = since,
          insecureTls = settings.mirrorInsecureTls,
          outFile = dumpOut
        )

        if (dl.code == 204) {
          if (!dl.head.isNullOrBlank()) {
            SyncStateStore.writeLastPulledHead(dir, dl.head)
          }
          history.add("Stealth Pull Back", true, "trace=$traceId No new commits (head=${dl.head?.take(12) ?: "?"})")
          notify(project, "[trace=$traceId] No new commits on mirror since last pull (head=${dl.head?.take(12) ?: "?"})", NotificationType.INFORMATION)
          return
        }
        if (dl.code !in 200..299 || dl.file == null) {
          history.add("Stealth Pull Back", false, "trace=$traceId HTTP ${dl.code}: ${dl.message}")
          notify(project, "[trace=$traceId] Mirror export failed HTTP ${dl.code}: ${dl.message}", NotificationType.ERROR)
          return
        }

        indicator.text = "Applying dump locally"
        val apply = WorkKit.runStealthApply(
          workDir = dir,
          password = SecretsStore.syncPassword,
          dumpFile = dl.file,
          mode = mode,
          newBranchName = branchName
        )

        // Cleanup downloaded dump (best-effort).
        try {
          dumpOut.delete()
        } catch (_: Exception) {}

        if (!apply.ok()) {
          val hint = when {
            apply.stderr.contains("already exists", ignoreCase = true) -> " | hint: branch name collision, try a different name"
            apply.stderr.contains("not a valid", ignoreCase = true) -> " | hint: check that git is available on PATH"
            apply.stderr.contains("InvalidTag", ignoreCase = true) -> " | hint: password mismatch between plugin and backend"
            else -> ""
          }
          history.add("Stealth Pull Back", false, "trace=$traceId exit=${apply.exitCode} ${apply.stderr}")
          notify(project, "[trace=$traceId] Stealth apply failed: ${apply.stderr.take(400)}$hint", NotificationType.ERROR)
          return
        }

        if (!dl.head.isNullOrBlank()) {
          SyncStateStore.writeLastPulledHead(dir, dl.head)
        }
        if (!dl.repo.isNullOrBlank() && !dl.repo.equals(repoName, ignoreCase = true)) {
          history.add("Stealth Pull Back", false, "trace=$traceId Repo mismatch: requested='$repoName' response='${dl.repo}'")
          notify(project, "[trace=$traceId] Stealth pull warning: requested repo '$repoName', response repo '${dl.repo}'", NotificationType.WARNING)
        }

        history.add("Stealth Pull Back", true, "trace=$traceId mode=$mode ${apply.stdout.take(400)}")
        notify(project, "[trace=$traceId] Stealth pull applied: mode=$mode ${apply.stdout.take(200)}", NotificationType.INFORMATION)
      }
    })
  }

  private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("LocalGitMirror")
      .createNotification(message, type)
      .notify(project)
  }
}
