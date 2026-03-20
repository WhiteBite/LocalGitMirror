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
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File

class SendSelectedCommitsToMirrorAction : AnAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val baseDir = project.basePath
    if (baseDir.isNullOrBlank()) {
      notify(project, "Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val projectDir = File(baseDir)
    val settings = service<MirrorSettingsService>().state
    val syncFacade = project.getService(SyncFacadeService::class.java)

    val commits = GitLocal.recentCommits(project, projectDir, 30)
    if (commits.isEmpty()) {
      notify(project, "No recent commits found", NotificationType.WARNING)
      return
    }

    val selected = Messages.showEditableChooseDialog(
      "Enter commit hashes to send (space-separated) or pick one",
      "LocalGitMirror: Send commits",
      null,
      commits.map { it.display() }.toTypedArray(),
      commits.first().display(),
      null
    ) ?: return

    val hashes = parseHashes(selected)
    if (hashes.isEmpty()) {
      notify(project, "No commit hashes selected", NotificationType.WARNING)
      return
    }

    val repoInfo = syncFacade.describeRepoTarget(projectDir, settings)

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Send commits", false) {
      override fun run(indicator: ProgressIndicator) {
        notify(project, "Starting sync: $repoInfo", NotificationType.INFORMATION)
        val original = GitLocal.currentBranch(project, projectDir)
        val tempBranch = "lgm-send-commits-${System.currentTimeMillis()}"

        val create = GitLocal.checkoutNew(project, projectDir, tempBranch, "HEAD")
        if (!create.ok()) {
          notify(project, "Failed to create temp branch: ${create.stderr}", NotificationType.ERROR)
          return
        }

        try {
          for (hash in hashes) {
            val cp = GitLocal.cherryPick(project, projectDir, hash)
            if (!cp.ok()) {
              GitLocal.cherryPickAbort(project, projectDir)
              notify(project, "Cherry-pick failed for $hash: ${cp.stderr}", NotificationType.ERROR)
              return
            }
          }

          val syncRes = syncFacade.runFullSync(projectDir, settings)
          val res = syncRes.step
          if (!res.ok) {
            notify(project, "[trace=${syncRes.traceId}] repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}", NotificationType.ERROR)
            return
          }
          if (settings.offlineGenerateOnly) {
            notify(project, "[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: settings.repo}' at ${syncRes.dump?.absolutePath ?: res.details}", NotificationType.INFORMATION)
            return
          }
          notify(project, "[trace=${syncRes.traceId}] Sent selected commits to Mirror repo '${syncRes.repo ?: settings.repo}'. ${syncRes.http?.body?.take(500) ?: ""}", NotificationType.INFORMATION)
        } finally {
          if (!original.isNullOrBlank()) {
            GitLocal.checkout(project, projectDir, original)
          }
          GitLocal.deleteLocalBranch(project, projectDir, tempBranch, force = true)
        }
      }
    })
  }

  private fun parseHashes(input: String): List<String> {
    val hashRegex = Regex("^[0-9a-fA-F]{7,40}$")
    return input
      .trim()
      .split(Regex("\\s+"))
      .map { it.substringBefore(" ").trim() }
      .filter { it.isNotBlank() && hashRegex.matches(it) }
  }

  private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("LocalGitMirror")
      .createNotification(message, type)
      .notify(project)
  }
}
