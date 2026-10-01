package localgitmirror.idea.ui.exchange

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.gitlab.MrReviewService
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.ui.LocalGitMirrorPanel
import localgitmirror.idea.ui.MrNotesDialog
import localgitmirror.idea.ui.notify
import java.awt.Component
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities

// ── Chat rendering ──

private fun LocalGitMirrorPanel.chatMessages(): List<ExchangeItem> {
  val echoed = echoIdByServerId.keys
  return (allServerItems.filterNot { it.id in echoed } + chatEchoes).sortedBy { it.ts }
}

internal fun LocalGitMirrorPanel.renderChat(scrollToBottom: Boolean = false) {
  val messages = chatMessages()
  val signature = buildString {
    for (m in messages) {
      append(m.id).append('|').append(m.state).append('|').append(m.pinned)
        .append('|').append(m.serverId).append('|').append(m.title).append(';')
    }
    append("#e").append(expandedIds.joinToString(","))
    append("#b").append(chatBodyCache.keys.joinToString(","))
    append("#x").append(chatEmptyMessage)
  }
  if (signature == lastChatSignature && !scrollToBottom) return
  lastChatSignature = signature
  val stick = scrollToBottom || isChatAtBottom()
  bubbleThumbLabels.clear()
  chatPanel.removeAll()
  if (messages.isEmpty()) {
    chatPanel.add(JBLabel(chatEmptyMessage, AllIcons.Toolwindows.ToolWindowMessages, JBLabel.CENTER).apply {
      font = JBUI.Fonts.smallFont()
      foreground = UIUtil.getContextHelpForeground()
      border = JBUI.Borders.empty(24, 8)
      horizontalAlignment = JBLabel.CENTER
      alignmentX = Component.CENTER_ALIGNMENT
      maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    })
  }
  for (m in messages) chatPanel.add(buildBubbleRow(m))
  chatPanel.revalidate()
  chatPanel.repaint()
  if (stick) scrollChatToBottom()
}

private fun LocalGitMirrorPanel.isChatAtBottom(): Boolean {
  val vp = chatScroll.viewport ?: return true
  val extent = vp.extentSize.height
  val content = vp.viewSize.height
  return content <= extent || vp.viewPosition.y >= content - extent - JBUI.scale(48)
}

private fun LocalGitMirrorPanel.scrollChatToBottom() {
  SwingUtilities.invokeLater {
    if (project.isDisposed) return@invokeLater
    val bar = chatScroll.verticalScrollBar
    bar.value = bar.maximum
  }
}

/** Fetch the full buffer body off the EDT (lazy decrypt) and expand the bubble. */
internal fun LocalGitMirrorPanel.fetchChatBody(item: ExchangeItem) {
  loadChatBody(item) {
    expandedIds.add(item.id)
    renderChat()
  }
}

internal fun LocalGitMirrorPanel.openExchangeItem(item: ExchangeItem) {
  if (item.kind == ExchangeItem.Kind.BUFFER) {
    val local = item.localText
    if (local != null) {
      CopyPasteManager.getInstance().setContents(StringSelection(local))
      notify(LocalGitMirrorBundle.message("panel.exchange.chat.copied"), NotificationType.INFORMATION)
      return
    }
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("buffer.task.paste"), true) {
      override fun run(indicator: ProgressIndicator) {
        localgitmirror.idea.actions.pasteBufferEntryById(project, item.effectiveId, item.ts)
      }
    })
    return
  }
  when {
    item.isMrNotes -> withDecryptedFile(item) { plain -> openMrNotesFrom(plain, item) }
    item.isImage -> withDecryptedFile(item) { plain -> showImagePreview(plain, item) }
    item.isText -> withDecryptedFile(item) { plain -> openTextInEditor(plain, item) }
    else -> saveExchangeFileAs(item)
  }
}

private fun LocalGitMirrorPanel.openMrNotesFrom(plain: File, item: ExchangeItem) {
  try {
    val markdown = plain.readText(Charsets.UTF_8)
    val iid = Regex("mr-!(\\d+)\\.md$").find(item.displayPath)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (iid == null) {
      notify(LocalGitMirrorBundle.message("panel.exchange.mrnotes.iidMissing"), NotificationType.WARNING)
      return
    }
    val row = project.getService(MrReviewService::class.java).parseMrMarkdown(iid, markdown)
    MrNotesDialog(project, row).show()
  } catch (t: Throwable) {
    notify(LocalGitMirrorBundle.message("panel.exchange.decrypt.fail", t.message ?: ""), NotificationType.ERROR)
  }
}

private fun LocalGitMirrorPanel.showImagePreview(plain: File, item: ExchangeItem) {
  try {
    val image = ImageIcon(plain.readBytes())
    object : DialogWrapper(project, false) {
      init {
        title = item.title
        init()
      }
      override fun createCenterPanel(): JComponent = JBScrollPane(JLabel(image)).apply {
        preferredSize = Dimension(JBUI.scale(720), JBUI.scale(480))
      }
    }.show()
  } catch (t: Throwable) {
    notify(LocalGitMirrorBundle.message("panel.exchange.decrypt.fail", t.message ?: ""), NotificationType.ERROR)
  }
}

private fun LocalGitMirrorPanel.openTextInEditor(plain: File, item: ExchangeItem) {
  try {
    val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(plain)
    if (vf != null) {
      FileEditorManager.getInstance(project).openFile(vf, true)
      return
    }
    val lvf = LightVirtualFile(item.title, plain.readText(Charsets.UTF_8))
    lvf.isWritable = false
    FileEditorManager.getInstance(project).openFile(lvf, true)
  } catch (t: Throwable) {
    notify(LocalGitMirrorBundle.message("panel.exchange.decrypt.fail", t.message ?: ""), NotificationType.ERROR)
  }
}

internal fun LocalGitMirrorPanel.saveExchangeFileAs(item: ExchangeItem) {
  val descriptor = FileSaverDescriptor(
    LocalGitMirrorBundle.message("panel.exchange.saveAs.title"),
    LocalGitMirrorBundle.message("panel.exchange.saveAs.desc")
  )
  val wrapper = FileChooserFactory.getInstance()
    .createSaveFileDialog(descriptor, project)
    .save(item.title) ?: return
  val target = wrapper.file
  withDecryptedFile(item) { plain ->
    try {
      Files.copy(plain.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      notify(LocalGitMirrorBundle.message("panel.exchange.saveAs.ok", target.absolutePath), NotificationType.INFORMATION)
      historyService.add(
        LocalGitMirrorBundle.message("history.op.exchangeSave"), true,
        "${item.displayPath} -> ${target.absolutePath}"
      )
    } catch (t: Throwable) {
      notify(LocalGitMirrorBundle.message("panel.exchange.saveAs.fail", t.message ?: ""), NotificationType.ERROR)
    }
  }
}

/** Absolute path of the received file: the materialized copy, downloading it first when absent. */
internal fun LocalGitMirrorPanel.copyExchangePath(item: ExchangeItem) {
  val target = exchangeDir()?.let { File(it, safeExchangeName(item)) }
  if (target == null) {
    copyToClipboard(item.displayPath)
    return
  }
  if (target.isFile) {
    copyToClipboard(target.absolutePath)
    return
  }
  withDecryptedFile(item) { f -> copyToClipboard(f.absolutePath) }
}

private fun LocalGitMirrorPanel.copyToClipboard(text: String) {
  CopyPasteManager.getInstance().setContents(StringSelection(text))
  notify(LocalGitMirrorBundle.message("panel.exchange.chat.copied"), NotificationType.INFORMATION)
}
