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
      val iid = Regex("^replies-!(\\d+)\\.md$").find(chosen.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
      if (iid == null) {
        notify(project, LocalGitMirrorBundle.message("mrreplies.upload.fail", chosen.name, "bad file name"), NotificationType.ERROR)
        return@executeOnPooledThread
      }
      val ok = localgitmirror.idea.gitlab.MrRepliesTransport.uploadMarkdown(project, iid, chosen.readText(Charsets.UTF_8))
      if (ok) {
        notify(project, LocalGitMirrorBundle.message("mrreplies.upload.ok", chosen.name), NotificationType.INFORMATION)
      } else {
        notify(project, LocalGitMirrorBundle.message("mrreplies.upload.fail", chosen.name, "upload failed"), NotificationType.ERROR)
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
