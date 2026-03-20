package localgitmirror.idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.gitlab.GitLabApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File

class SyncGitLabMrToMirrorAction : AnAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val baseDir = project.basePath
    if (baseDir.isNullOrBlank()) {
      notify(project, "Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val projectDir = File(baseDir)
    val s = service<MirrorSettingsService>().state
    val syncFacade = project.getService(SyncFacadeService::class.java)

    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) {
      notify(project, "Configure Mirror URL/password in settings", NotificationType.WARNING)
      return
    }

    val repoName = syncFacade.inferRepoName(projectDir, s)
    if (repoName.isBlank()) {
      notify(project, "Configured Mirror Repo is invalid after sanitization", NotificationType.ERROR)
      return
    }
    val repoInfo = syncFacade.describeRepoTarget(projectDir, s)

    val ensureRepo = syncFacade.ensureRemoteRepo(s.baseUrl, SecretsStore.mirrorApiKey, repoName, s.mirrorInsecureTls)
    if (!ensureRepo.ok) {
      notify(project, "${ensureRepo.message}. ${ensureRepo.details}", NotificationType.ERROR)
      return
    }
    if (s.gitLabBaseUrl.isBlank() || SecretsStore.gitLabToken.isBlank() || s.gitLabProject.isBlank()) {
      notify(project, "Configure GitLab Base URL/Token/Project in settings", NotificationType.WARNING)
      return
    }
    if (!GitLocal.isCleanWorkTree(project, projectDir)) {
      notify(project, "Working tree has uncommitted changes. Commit/stash before syncing.", NotificationType.WARNING)
      return
    }

    val mrIidRaw = Messages.showInputDialog(project, "Enter GitLab MR IID", "LocalGitMirror: Sync MR", null)
    val mrIid = mrIidRaw?.trim()?.removePrefix("!")
    if (mrIid.isNullOrBlank()) return

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Sync GitLab MR !${mrIid}", false) {
      override fun run(indicator: ProgressIndicator) {
        notify(project, "Starting sync: $repoInfo", NotificationType.INFORMATION)
        val originalBranch = GitLocal.currentBranch(project, projectDir)
        var createdBranch: String? = null

        indicator.text = "Fetching MR metadata"
        val (mr, sourceBranch) = GitLabApi.getMergeRequestSourceBranch(
          s.gitLabBaseUrl,
          SecretsStore.gitLabToken,
          s.gitLabProject,
          mrIid,
          s.gitLabInsecureTls
        )
        if (mr.code !in 200..299) {
          notify(project, "GitLab error HTTP ${mr.code}: ${mr.body.take(500)}", NotificationType.ERROR)
          return
        }

        if (sourceBranch.isNullOrBlank()) {
          notify(project, "Failed to parse source_branch from GitLab response", NotificationType.ERROR)
          return
        }

        indicator.text = "Fetching source branch '$sourceBranch'"
        val fetch = GitLocal.fetch(project, projectDir, s.gitRemoteName, sourceBranch)
        if (!fetch.ok()) {
          notify(project, "git fetch failed: ${fetch.stderr}", NotificationType.ERROR)
          return
        }

        indicator.text = "Checking out '$sourceBranch'"
        val co = GitLocal.checkout(project, projectDir, sourceBranch)
        if (!co.ok()) {
          // If checkout fails because branch does not exist locally, create tracking branch.
          val localTemp = "lgm-mr-${mrIid}-${System.currentTimeMillis()}"
          val co2 = GitLocal.checkoutNew(project, projectDir, localTemp, "FETCH_HEAD")
          if (!co2.ok()) {
            notify(project, "git checkout failed: ${co.stderr}\n${co2.stderr}", NotificationType.ERROR)
            return
          }
          createdBranch = localTemp
        }

        val syncRes = try {
          syncFacade.runFullSync(projectDir, s)
        } finally {
          if (!originalBranch.isNullOrBlank()) {
            val restore = GitLocal.checkout(project, projectDir, originalBranch)
            if (!restore.ok()) {
              notify(project, "Synced, but failed to restore branch '$originalBranch': ${restore.stderr}", NotificationType.WARNING)
            }
          }
          if (!createdBranch.isNullOrBlank()) {
            GitLocal.deleteLocalBranch(project, projectDir, createdBranch, force = true)
          }
        }

        val result = syncRes.step
        if (!result.ok) {
          notify(project, "[trace=${syncRes.traceId}] repo='${syncRes.repo ?: repoName}' ${result.message}. ${result.details}", NotificationType.ERROR)
          return
        }
        if (s.offlineGenerateOnly) {
          notify(project, "[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: repoName}' at ${syncRes.dump?.absolutePath ?: result.details}", NotificationType.INFORMATION)
          return
        }
        notify(project, "[trace=${syncRes.traceId}] Synced GitLab MR !${mrIid} (branch '$sourceBranch') to Mirror repo '${syncRes.repo ?: repoName}'. ${syncRes.http?.body?.take(500) ?: ""}", NotificationType.INFORMATION)
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
