package localgitmirror.idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import localgitmirror.idea.workkit.ExchangeCrypto
import localgitmirror.idea.workkit.RepoFileSyncCrypto
import java.io.File

/**
 * Home machine: upload the agent's reply file `.mr-notes/replies-!N.md` to the
 * Cache postbox so the work machine can push it to GitLab.
 */
class UploadMrRepliesAction : AnAction() {

  override fun update(e: AnActionEvent) {
    LocalGitMirrorBundle.localizePresentation(e, "LocalGitMirror.UploadMrReplies")
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val base = project.basePath ?: return
    val files = File(base, ".mr-notes")
      .listFiles { f -> f.isFile && f.name.startsWith("replies-!") && f.name.endsWith(".md") }
      ?.sortedBy { it.name }
      .orEmpty()
    if (files.isEmpty()) {
      notify(project, LocalGitMirrorBundle.message("mrreplies.upload.none"), NotificationType.WARNING)
      return
    }
    val chosen: File = if (files.size == 1) {
      files[0]
    } else {
      val names = files.map { it.name }.toTypedArray()
      val picked = Messages.showEditableChooseDialog(
        LocalGitMirrorBundle.message("mrreplies.upload.choose"),
        LocalGitMirrorBundle.message("mrreplies.upload.chooseTitle"),
        null, names, names[0], null,
      ) ?: return
      files.firstOrNull { it.name == picked } ?: return
    }

    ApplicationManager.getApplication().executeOnPooledThread {
      val settings = service<MirrorSettingsService>().state
      val repo = runCatching {
        project.getService(SyncFacadeService::class.java).resolveRepo(File(base), settings).sanitized
      }.getOrDefault("")
      if (repo.isBlank()) {
        notify(project, LocalGitMirrorBundle.message("mrreplies.upload.fail", chosen.name, "repo not resolved"), NotificationType.ERROR)
        return@executeOnPooledThread
      }
      val plain = File.createTempFile("mr-replies-up-", ".md")
      val encrypted = File.createTempFile("mr-replies-up-", ".bin")
      try {
        chosen.copyTo(plain, overwrite = true)
        RepoFileSyncCrypto.encryptFile(plain, encrypted, SecretsStore.syncPassword, null)
        val pathEnc = ExchangeCrypto.encryptHint("mr-replies/${chosen.name}", SecretsStore.syncPassword)
        val up = MirrorApi.fileSyncUpload(
          settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls,
          "x/${java.util.UUID.randomUUID().toString().take(8)}", chosen.length(), encrypted, pathEnc, null,
        )
        if (up.code in 200..299) {
          notify(project, LocalGitMirrorBundle.message("mrreplies.upload.ok", chosen.name), NotificationType.INFORMATION)
        } else {
          notify(project, LocalGitMirrorBundle.message("mrreplies.upload.fail", chosen.name, "HTTP ${up.code} ${up.message}"), NotificationType.ERROR)
        }
      } catch (t: Throwable) {
        notify(project, LocalGitMirrorBundle.message("mrreplies.upload.fail", chosen.name, t.message ?: "error"), NotificationType.ERROR)
      } finally {
        runCatching { plain.delete() }
        runCatching { encrypted.delete() }
      }
    }
  }

  private fun notify(project: Project, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("DocCache")
      .createNotification(message, type)
      .notify(project)
  }
}
