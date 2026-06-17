package localgitmirror.idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.workkit.WorkKit
import java.io.File

class ApplyLocalDumpAction : AnAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val baseDir = project.basePath
    if (baseDir.isNullOrBlank()) {
      notify(project, LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
      return
    }
    val projectDir = File(baseDir)

    if (SecretsStore.syncPassword.isBlank()) {
      notify(project, LocalGitMirrorBundle.message("action.applyLocal.syncPasswordMissing"), NotificationType.WARNING)
      return
    }
    if (!GitLocal.isCleanWorkTree(project, projectDir)) {
      notify(project, LocalGitMirrorBundle.message("notify.worktree.dirty"), NotificationType.WARNING)
      return
    }

    val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
    descriptor.title = LocalGitMirrorBundle.message("action.applyLocal.selectFile.title")
    descriptor.description = LocalGitMirrorBundle.message("action.applyLocal.selectFile.description")
    descriptor.withFileFilter { vf -> vf.extension?.equals("bin", ignoreCase = true) == true || vf.extension?.equals("dmp", ignoreCase = true) == true }

    val baseVf = LocalFileSystem.getInstance().findFileByIoFile(projectDir)
    val chosen = FileChooser.chooseFile(descriptor, project, baseVf) ?: return
    val dumpFile = File(chosen.path)
    if (!dumpFile.exists() || !dumpFile.isFile) {
      notify(project, LocalGitMirrorBundle.message("action.applyLocal.fileNotExist"), NotificationType.ERROR)
      return
    }

    val mode = Messages.showEditableChooseDialog(
      LocalGitMirrorBundle.message("action.applyLocal.modePrompt"),
      LocalGitMirrorBundle.message("action.applyLocal.dialogTitle"),
      null,
      arrayOf("new-branch", "ff-only"),
      "new-branch",
      null
    )?.trim()?.lowercase().orEmpty()
    if (mode.isBlank()) return

    val branchName: String? = if (mode == "new-branch") {
      val defaultName = "local-import-${System.currentTimeMillis()}"
      Messages.showInputDialog(project, LocalGitMirrorBundle.message("action.applyLocal.newBranchPrompt"), LocalGitMirrorBundle.message("action.applyLocal.dialogTitleApply"), null, defaultName, null)?.trim()
    } else null
    if (mode == "new-branch" && branchName.isNullOrBlank()) return

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Apply sync package", false) {
      override fun run(indicator: ProgressIndicator) {
        indicator.text = LocalGitMirrorBundle.message("action.applyLocal.progress.preparing")
        indicator.text = LocalGitMirrorBundle.message("action.applyLocal.progress.applying")
        val res = WorkKit.applySyncPackage(
          workDir = projectDir,
          password = SecretsStore.syncPassword,
          dumpFile = dumpFile,
          mode = mode,
          newBranchName = branchName
        )
        if (!res.ok()) {
          notify(project, LocalGitMirrorBundle.message("action.applyLocal.failed", res.stderr.take(500)), NotificationType.ERROR)
          return
        }
        notify(project, LocalGitMirrorBundle.message("action.applyLocal.success"), NotificationType.INFORMATION)
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
