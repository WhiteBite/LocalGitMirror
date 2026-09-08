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
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.ui.PruneBranchPickerDialog

/**
 * «Prune merged branches on Mirror…» — smart cleanup of stale branches.
 *
 * Flow (server contract usage is intentionally minimal):
 *  1. Dry-run [MirrorApi.pruneBranches] (apply=false) asks the server which
 *     branches are already merged into the base branches — the candidates.
 *  2. The user reviews them in a multi-select picker (all pre-selected) and
 *     may deselect branches to keep.
 *  3. Deletion itself reuses the existing [MirrorApi.deleteRef] loop — the
 *     exact same path as [ManageMirrorBranchesAction] — so exactly the user's
 *     final selection is deleted, nothing more.
 *
 * Safety rules (also enforced server-side):
 *  - HEAD branch / last branch are never returned as candidates.
 *  - Explicit confirmation required before the deletion batch.
 */
class PruneMirrorBranchesAction : AnAction() {
  override fun update(e: AnActionEvent) {
    LocalGitMirrorBundle.localizePresentation(e, "LocalGitMirror.PruneMirrorBranches")
    e.presentation.isEnabled = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val settings = service<MirrorSettingsService>().state
    if (settings.baseUrl.isBlank()) {
      notify(project, LocalGitMirrorBundle.message("prune.notify.noUrl"), NotificationType.WARNING)
      return
    }
    val repo = localgitmirror.idea.sync.v2.RepoResolver
      .resolve(project, java.io.File(project.basePath ?: "."), "")
      .sanitized
      .ifBlank { project.name }
    val insecure = settings.mirrorInsecureTls
    val apiKey = SecretsStore.mirrorApiKey
    val history = service<OperationsHistoryService>()

    ProgressManager.getInstance().run(
      object : Task.Backgroundable(project, LocalGitMirrorBundle.message("prune.progress.title"), true) {
        override fun run(indicator: ProgressIndicator) {
          indicator.isIndeterminate = true

          // 1. Dry-run: ask the server for merged-branch candidates
          val dry = MirrorApi.pruneBranches(
            settings.baseUrl, apiKey, repo,
            bases = BASES, olderDays = 0, keep = emptyList(), apply = false,
            insecureTls = insecure, syncPassword = SecretsStore.syncPassword
          )
          if (dry.code !in 200..299) {
            notify(project, LocalGitMirrorBundle.message("prune.notify.loadFailed", dry.code, dry.message), NotificationType.ERROR)
            return
          }
          if (!dry.success) {
            notify(project, LocalGitMirrorBundle.message("prune.notify.serverError", dry.message), NotificationType.ERROR)
            return
          }
          val candidates = dry.candidates
          if (candidates.isEmpty()) {
            notify(project, LocalGitMirrorBundle.message("prune.notify.noCandidates", repo), NotificationType.INFORMATION)
            return
          }

          // 2. Multi-select picker on EDT — all candidates pre-selected
          val toDelete = UIUtil.invokeAndWaitIfNeeded<List<String>> {
            val dialog = PruneBranchPickerDialog(project, candidates)
            if (!dialog.showAndGet()) return@invokeAndWaitIfNeeded emptyList()
            dialog.selectedBranches
          }
          if (toDelete.isEmpty()) return

          // 3. Confirm
          val confirmed = UIUtil.invokeAndWaitIfNeeded<Int> {
            Messages.showYesNoDialog(
              project,
              LocalGitMirrorBundle.message("prune.confirm.message", toDelete.size, toDelete.joinToString("\n")),
              LocalGitMirrorBundle.message("prune.confirm.title"),
              LocalGitMirrorBundle.message("prune.confirm.yes"),
              LocalGitMirrorBundle.message("prune.confirm.no"),
              null
            )
          }
          if (confirmed != Messages.YES) return

          // 4. Delete via the same deleteRef path as ManageMirrorBranchesAction
          val deleted = mutableListOf<String>()
          val failed = mutableListOf<String>()
          for (branch in toDelete) {
            indicator.text = LocalGitMirrorBundle.message("prune.progress.deleting", branch)
            val r = MirrorApi.deleteRef(settings.baseUrl, apiKey, repo, branch, SecretsStore.syncPassword, insecure)
            if (r.code in 200..299) deleted.add(branch)
            else failed.add("$branch (HTTP ${r.code}: ${r.body.take(120)})")
          }

          val msg = buildString {
            if (deleted.isNotEmpty()) append(LocalGitMirrorBundle.message("prune.notify.deleted", deleted.joinToString(", ")))
            if (failed.isNotEmpty()) {
              if (deleted.isNotEmpty()) append("\n")
              append(LocalGitMirrorBundle.message("prune.notify.failed", failed.joinToString("; ")))
            }
          }
          notify(project, msg.ifBlank { LocalGitMirrorBundle.message("prune.notify.done") },
            if (failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
          history.add(LocalGitMirrorBundle.message("history.op.pruneBranches"), failed.isEmpty(),
            "candidates=${candidates.size} deleted=${deleted.size} failed=${failed.size} " +
              "branches=${(deleted + toDelete).distinct()} repo='$repo'")
        }
      }
    )
  }

  private fun notify(project: Project, msg: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("DocCache")
      .createNotification(msg, type)
      .notify(project)
  }

  companion object {
    /** Base branches whose merged heads make a branch a prune candidate. */
    val BASES: List<String> = listOf("master", "develop", "plan_fix")
  }
}
