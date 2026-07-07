package localgitmirror.idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.RepoResolver
import localgitmirror.idea.workkit.RepoFileSyncCrypto
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

private fun fileSyncNotify(project: Project?, message: String, type: NotificationType) {
  NotificationGroupManager.getInstance()
    .getNotificationGroup("LocalGitMirror")
    .createNotification(message, type)
    .notify(project)
}

private data class FileSyncContext(
  val settings: MirrorSettingsService.State,
  val apiKey: String,
  val password: String,
  val projectDir: File,
  val repo: String
)

private fun fileSyncContext(project: Project?): FileSyncContext? {
  if (project == null) return null
  val basePath = project.basePath ?: run {
    fileSyncNotify(project, LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.WARNING)
    return null
  }
  val settings = service<MirrorSettingsService>().state
  if (settings.baseUrl.isBlank()) {
    fileSyncNotify(project, LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
    return null
  }
  val password = SecretsStore.syncPassword
  if (password.isBlank()) {
    fileSyncNotify(project, LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
    return null
  }
  val dir = File(basePath).canonicalFile
  val repo = RepoResolver.resolve(project, dir, "").sanitized
  if (repo.isBlank()) {
    fileSyncNotify(project, LocalGitMirrorBundle.message("filesync.notify.repoMissing"), NotificationType.WARNING)
    return null
  }
  return FileSyncContext(settings, SecretsStore.mirrorApiKey, password, dir, repo)
}

private fun selectedProjectFile(e: AnActionEvent, projectDir: File): Pair<File, String>? {
  val vf = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
  if (vf.isDirectory) return null
  val file = File(vf.path).canonicalFile
  val root = projectDir.canonicalFile
  val rel = try {
    root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
  } catch (_: Throwable) {
    return null
  }
  if (rel.isBlank() || rel.startsWith("../") || rel == ".." || rel.contains("/../")) return null
  return file to rel
}

private fun formatFileSyncTs(epochSec: Long): String =
  SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(epochSec * 1000L))

class SendSelectedFileAction : AnAction() {
  override fun update(e: AnActionEvent) {
    val project = e.project
    val basePath = project?.basePath
    val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
    e.presentation.isEnabled = project != null && basePath != null && vf != null && !vf.isDirectory
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val ctx = fileSyncContext(project) ?: return
    val selected = selectedProjectFile(e, ctx.projectDir)
    if (selected == null) {
      fileSyncNotify(project, LocalGitMirrorBundle.message("filesync.notify.selectFile"), NotificationType.WARNING)
      return
    }
    val (file, relativePath) = selected
    val history = service<OperationsHistoryService>()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("filesync.task.send"), true) {
      override fun run(indicator: ProgressIndicator) {
        val encrypted = File.createTempFile("lgm-file-sync-", ".bin")
        try {
          indicator.text = LocalGitMirrorBundle.message("filesync.progress.encrypt")
          RepoFileSyncCrypto.encryptFile(file, encrypted, ctx.password) { done, total ->
            indicator.fraction = if (total > 0) done.toDouble() / total.toDouble() else 0.0
          }
          indicator.text = LocalGitMirrorBundle.message("filesync.progress.upload")
          val res = MirrorApi.fileSyncUpload(
            ctx.settings.baseUrl,
            ctx.apiKey,
            ctx.repo,
            ctx.settings.mirrorInsecureTls,
            relativePath,
            file.length(),
            encrypted
          ) { sent, total ->
            indicator.fraction = if (total > 0) sent.toDouble() / total.toDouble() else 0.0
          }
          if (res.code !in 200..299 || res.id.isNullOrBlank()) {
            fileSyncNotify(project, LocalGitMirrorBundle.message("filesync.notify.sendFail", res.code.toString(), res.message.take(200)), NotificationType.ERROR)
            history.add("File sync send", false, "HTTP ${res.code}: ${res.message.take(200)}")
            return
          }
          fileSyncNotify(project, LocalGitMirrorBundle.message("filesync.notify.sendOk", relativePath, res.id), NotificationType.INFORMATION)
          history.add("File sync send", true, "$relativePath id=${res.id} size=${file.length()}")
        } catch (t: Throwable) {
          fileSyncNotify(project, LocalGitMirrorBundle.message("filesync.notify.sendFail", "0", t.message ?: t::class.simpleName ?: ""), NotificationType.ERROR)
          history.add("File sync send", false, t.message ?: t::class.simpleName ?: "error")
        } finally {
          try { encrypted.delete() } catch (_: Throwable) {}
        }
      }
    })
  }
}

class FetchRepoFilesAction : AnAction() {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val ctx = fileSyncContext(project) ?: return
    val history = service<OperationsHistoryService>()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("filesync.task.list"), false) {
      private var items: List<MirrorApi.FileSyncItem> = emptyList()
      private var error: String? = null

      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        val res = MirrorApi.fileSyncList(ctx.settings.baseUrl, ctx.apiKey, ctx.repo, ctx.settings.mirrorInsecureTls)
        if (res.code !in 200..299) error = "HTTP ${res.code}: ${res.message.take(200)}" else items = res.items
      }

      override fun onSuccess() {
        val err = error
        if (err != null) {
          fileSyncNotify(project, LocalGitMirrorBundle.message("filesync.notify.listFail", err), NotificationType.ERROR)
          history.add("File sync fetch", false, err)
          return
        }
        if (items.isEmpty()) {
          fileSyncNotify(project, LocalGitMirrorBundle.message("filesync.notify.empty"), NotificationType.INFORMATION)
          return
        }
        showFilePicker(project, ctx, items, history)
      }
    })
  }

  private fun showFilePicker(project: Project, ctx: FileSyncContext, items: List<MirrorApi.FileSyncItem>, history: OperationsHistoryService) {
    val popup = JBPopupFactory.getInstance()
      .createPopupChooserBuilder(items)
      .setTitle(LocalGitMirrorBundle.message("filesync.picker.title"))
      .setRenderer(object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
          super.getListCellRendererComponent(list, value, index, selected, focus)
          val item = value as? MirrorApi.FileSyncItem
          if (item != null) {
            text = LocalGitMirrorBundle.message("filesync.picker.row", item.path, item.plainSize.toString(), formatFileSyncTs(item.mtime))
          }
          return this
        }
      })
      .setItemChosenCallback { item -> applyFile(project, ctx, item, history) }
      .createPopup()
    popup.showCenteredInCurrentWindow(project)
  }

  private fun applyFile(project: Project, ctx: FileSyncContext, item: MirrorApi.FileSyncItem, history: OperationsHistoryService) {
    val target = File(ctx.projectDir, item.path).canonicalFile
    val rootPath = ctx.projectDir.canonicalFile.toPath()
    if (!target.toPath().startsWith(rootPath)) {
      fileSyncNotify(project, LocalGitMirrorBundle.message("filesync.notify.badPath"), NotificationType.ERROR)
      return
    }
    if (target.exists()) {
      val choice = Messages.showYesNoDialog(
        project,
        LocalGitMirrorBundle.message("filesync.confirm.overwrite", item.path),
        LocalGitMirrorBundle.message("filesync.confirm.title"),
        Messages.getQuestionIcon()
      )
      if (choice != Messages.YES) return
    }

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("filesync.task.fetch"), true) {
      override fun run(indicator: ProgressIndicator) {
        val encrypted = File.createTempFile("lgm-file-sync-download-", ".bin")
        val targetParent = target.parentFile ?: ctx.projectDir
        targetParent.mkdirs()
        val plainTmp = File.createTempFile("lgm-file-sync-plain-", ".tmp", targetParent)
        try {
          indicator.text = LocalGitMirrorBundle.message("filesync.progress.download")
          val dl = MirrorApi.fileSyncDownload(ctx.settings.baseUrl, ctx.apiKey, ctx.repo, ctx.settings.mirrorInsecureTls, item.id, encrypted) { read, total ->
            indicator.fraction = if (total > 0) read.toDouble() / total.toDouble() else 0.0
          }
          if (dl.code !in 200..299) {
            fileSyncNotify(project, LocalGitMirrorBundle.message("filesync.notify.fetchFail", dl.code.toString(), dl.message.take(200)), NotificationType.ERROR)
            history.add("File sync fetch", false, "HTTP ${dl.code}: ${dl.message.take(200)}")
            return
          }
          indicator.text = LocalGitMirrorBundle.message("filesync.progress.decrypt")
          RepoFileSyncCrypto.decryptFile(encrypted, plainTmp, ctx.password) { done, total ->
            indicator.fraction = if (total > 0) done.toDouble() / total.toDouble() else 0.0
          }
          Files.move(plainTmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
          MirrorApi.fileSyncAck(ctx.settings.baseUrl, ctx.apiKey, ctx.repo, ctx.settings.mirrorInsecureTls, item.id)
          fileSyncNotify(project, LocalGitMirrorBundle.message("filesync.notify.fetchOk", item.path), NotificationType.INFORMATION)
          history.add("File sync fetch", true, "${item.path} id=${item.id}")
        } catch (t: Throwable) {
          fileSyncNotify(project, LocalGitMirrorBundle.message("filesync.notify.fetchFail", "0", t.message ?: t::class.simpleName ?: ""), NotificationType.ERROR)
          history.add("File sync fetch", false, t.message ?: t::class.simpleName ?: "error")
        } finally {
          try { encrypted.delete() } catch (_: Throwable) {}
          try { plainTmp.delete() } catch (_: Throwable) {}
        }
      }
    })
  }
}
