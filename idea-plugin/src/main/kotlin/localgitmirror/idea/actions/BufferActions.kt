package localgitmirror.idea.actions

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBList
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.workkit.BundleCrypto
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList

/**
 * Cross-machine clipboard buffer actions. Data is encrypted client-side with
 * SYNC_PASSWORD before it ever touches the wire — the server only stores
 * ciphertext plus an unencrypted preview hint. End-to-end model matches the
 * existing sync bundles so no new secret surface is introduced.
 */

private fun notify(project: Project?, message: String, type: NotificationType) {
  NotificationGroupManager.getInstance()
    .getNotificationGroup("LocalGitMirror")
    .createNotification(message, type)
    .notify(project)
}

private fun preconditionsOk(project: Project?): Triple<MirrorSettingsService.State, String, String>? {
  val settings = service<MirrorSettingsService>().state
  if (settings.baseUrl.isBlank()) {
    notify(project, LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
    return null
  }
  val pwd = SecretsStore.syncPassword
  if (pwd.isBlank()) {
    notify(project, LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
    return null
  }
  return Triple(settings, pwd, SecretsStore.mirrorApiKey)
}

/**
 * Take the current editor selection if there is one; otherwise fall back to
 * the system clipboard. Returns null if neither has usable text.
 */
private fun grabPayload(e: AnActionEvent): String? {
  val editor = e.getData(CommonDataKeys.EDITOR)
  val sel = editor?.selectionModel?.selectedText
  if (!sel.isNullOrEmpty()) return sel
  return try {
    val data = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor)
    data?.toString()?.takeIf { it.isNotEmpty() }
  } catch (_: Throwable) {
    null
  }
}

private fun buildHint(text: String): String {
  val firstLine = text.lineSequence().firstOrNull()?.trim() ?: ""
  return firstLine.take(80)
}

private fun formatTs(epochSec: Double): String {
  val ms = (epochSec * 1000).toLong()
  return SimpleDateFormat("HH:mm:ss").format(Date(ms))
}

private fun setSystemClipboard(text: String) {
  CopyPasteManager.getInstance().setContents(StringSelection(text))
}


/** Send: encrypt the editor selection (or system clipboard) and push to /api/buffer. */
class SendToBufferAction : AnAction() {
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = true
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project
    val (settings, pwd, apiKey) = preconditionsOk(project) ?: return

    val payload = grabPayload(e)
    if (payload.isNullOrEmpty()) {
      notify(project, LocalGitMirrorBundle.message("notify.buffer.noText"), NotificationType.WARNING)
      return
    }
    if (payload.length > 1_000_000) {
      notify(project, LocalGitMirrorBundle.message("notify.buffer.tooLarge"), NotificationType.WARNING)
      return
    }

    val hint = buildHint(payload)
    val historyService = service<OperationsHistoryService>()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("buffer.task.send"), false) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        val ciphertext = try {
          BundleCrypto.encryptBundleBytes(payload.toByteArray(Charsets.UTF_8), pwd)
        } catch (t: Throwable) {
          notify(project, LocalGitMirrorBundle.message("notify.buffer.encryptFail", t.message ?: ""), NotificationType.ERROR)
          return
        }
        val res = MirrorApi.bufferPut(settings.baseUrl, apiKey, settings.mirrorInsecureTls, ciphertext, hint)
        if (res.code !in 200..299) {
          notify(project, LocalGitMirrorBundle.message("notify.buffer.sendFail", res.code.toString(), res.message.take(200)), NotificationType.ERROR)
          historyService.add("Buffer send", false, "HTTP ${res.code}: ${res.message.take(200)}")
          return
        }
        notify(project, LocalGitMirrorBundle.message("notify.buffer.sendOk", payload.length.toString()), NotificationType.INFORMATION)
        historyService.add("Buffer send", true, "size=${payload.length} hint='${hint.take(40)}'")
      }
    })
  }
}


/** Paste: fetch the newest entry from /api/buffer, decrypt, copy into the system clipboard. */
class PasteFromBufferAction : AnAction() {
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = true
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project
    val (settings, pwd, apiKey) = preconditionsOk(project) ?: return
    val historyService = service<OperationsHistoryService>()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("buffer.task.paste"), false) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        val list = MirrorApi.bufferList(settings.baseUrl, apiKey, settings.mirrorInsecureTls)
        if (list.code !in 200..299) {
          notify(project, LocalGitMirrorBundle.message("notify.buffer.listFail", list.code.toString(), list.message.take(200)), NotificationType.ERROR)
          return
        }
        val latest = list.items.firstOrNull()
        if (latest == null) {
          notify(project, LocalGitMirrorBundle.message("notify.buffer.empty"), NotificationType.INFORMATION)
          return
        }
        fetchAndCopy(project, settings, apiKey, pwd, latest.id, latest.ts, historyService)
      }
    })
  }
}


/** History: show last N entries in a popup; clicking one copies it into the clipboard. */
class BufferHistoryAction : AnAction() {
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = true
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project
    val (settings, pwd, apiKey) = preconditionsOk(project) ?: return
    val historyService = service<OperationsHistoryService>()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("buffer.task.history"), false) {
      private var items: List<MirrorApi.BufferItem> = emptyList()
      private var errorMessage: String? = null

      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        val res = MirrorApi.bufferList(settings.baseUrl, apiKey, settings.mirrorInsecureTls)
        if (res.code !in 200..299) {
          errorMessage = "HTTP ${res.code}: ${res.message.take(200)}"
          return
        }
        items = res.items
      }

      override fun onSuccess() {
        val err = errorMessage
        if (err != null) {
          notify(project, LocalGitMirrorBundle.message("notify.buffer.listFail", "", err), NotificationType.ERROR)
          return
        }
        if (items.isEmpty()) {
          notify(project, LocalGitMirrorBundle.message("notify.buffer.empty"), NotificationType.INFORMATION)
          return
        }
        showPicker(project, items, settings, apiKey, pwd, historyService)
      }
    })
  }

  private fun showPicker(
    project: Project?,
    items: List<MirrorApi.BufferItem>,
    settings: MirrorSettingsService.State,
    apiKey: String,
    pwd: String,
    historyService: OperationsHistoryService
  ) {
    val popup = JBPopupFactory.getInstance()
      .createPopupChooserBuilder(items)
      .setTitle(LocalGitMirrorBundle.message("buffer.history.title"))
      .setRenderer(object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(l: JList<*>?, value: Any?, idx: Int, sel: Boolean, focus: Boolean): java.awt.Component {
          super.getListCellRendererComponent(l, value, idx, sel, focus)
          val it = value as? MirrorApi.BufferItem
          if (it != null) {
            val emptyHint = LocalGitMirrorBundle.message("buffer.history.emptyHint")
            val preview = if (it.hint.isNotBlank()) it.hint else emptyHint
            text = LocalGitMirrorBundle.message("buffer.history.row", formatTs(it.ts), it.size.toString(), preview)
          }
          return this
        }
      })
      .setItemChosenCallback { sel ->
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("buffer.task.paste"), false) {
          override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            fetchAndCopy(project, settings, apiKey, pwd, sel.id, sel.ts, historyService)
          }
        })
      }
      .createPopup()
    if (project != null) popup.showCenteredInCurrentWindow(project)
    else popup.showInFocusCenter()
  }
}


/**
 * Shared "fetch ciphertext → decrypt → put into system clipboard" tail.
 * Used by both PasteFromBuffer (latest) and BufferHistory (chosen entry).
 */
private fun fetchAndCopy(
  project: Project?,
  settings: MirrorSettingsService.State,
  apiKey: String,
  pwd: String,
  id: String,
  ts: Double,
  historyService: OperationsHistoryService
) {
  val res = MirrorApi.bufferGet(settings.baseUrl, apiKey, settings.mirrorInsecureTls, id)
  if (res.code !in 200..299 || res.file == null) {
    notify(project, LocalGitMirrorBundle.message("notify.buffer.getFail", res.code.toString(), res.message.take(200)), NotificationType.ERROR)
    historyService.add("Buffer paste", false, "HTTP ${res.code}: ${res.message.take(200)}")
    return
  }
  val ciphertext = try { res.file.readBytes() } catch (t: Throwable) {
    notify(project, LocalGitMirrorBundle.message("notify.buffer.getFail", "0", t.message ?: ""), NotificationType.ERROR)
    return
  } finally {
    try { res.file.delete() } catch (_: Throwable) {}
  }

  val plain = try {
    String(BundleCrypto.decryptDumpBytes(ciphertext, pwd), Charsets.UTF_8)
  } catch (t: Throwable) {
    notify(project, LocalGitMirrorBundle.message("notify.buffer.decryptFail", t.message ?: t::class.simpleName ?: ""), NotificationType.ERROR)
    historyService.add("Buffer paste", false, "decrypt failed: ${t::class.simpleName}")
    return
  }

  setSystemClipboard(plain)
  val previewLen = plain.length
  val notif = NotificationGroupManager.getInstance()
    .getNotificationGroup("LocalGitMirror")
    .createNotification(
      LocalGitMirrorBundle.message("notify.buffer.pasted", formatTs(ts), previewLen.toString()),
      NotificationType.INFORMATION
    )
  notif.addAction(NotificationAction.createSimpleExpiring(LocalGitMirrorBundle.message("buffer.action.showPreview")) {
    com.intellij.openapi.ui.Messages.showInfoMessage(project, plain.take(2000), LocalGitMirrorBundle.message("buffer.preview.title"))
  })
  notif.notify(project)
  historyService.add("Buffer paste", true, "id=$id size=$previewLen")
}
