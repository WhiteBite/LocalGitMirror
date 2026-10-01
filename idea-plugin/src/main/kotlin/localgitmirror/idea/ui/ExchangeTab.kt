package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.ui.exchange.ChatTransferHandler
import localgitmirror.idea.ui.exchange.clearExchangeFeed
import localgitmirror.idea.ui.exchange.clipboardImage
import localgitmirror.idea.ui.exchange.pasteClipboardToExchange
import localgitmirror.idea.ui.exchange.refreshExchangeInBackground
import localgitmirror.idea.ui.exchange.sendClipboardScreenshot
import localgitmirror.idea.ui.exchange.sendComposerText
import localgitmirror.idea.ui.exchange.sendIdeaLogTail
import localgitmirror.idea.ui.exchange.sendImageToPostbox
import localgitmirror.idea.ui.exchange.uploadFilesToPostbox
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import javax.swing.*

internal fun LocalGitMirrorPanel.buildExchangeTab(): JComponent {
  chatPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
    KeyStroke.getKeyStroke("control V"), "lgm.exchange.paste"
  )
  chatPanel.actionMap.put("lgm.exchange.paste", object : AbstractAction() {
    override fun actionPerformed(e: java.awt.event.ActionEvent?) = pasteClipboardToExchange()
  })
  val dropHandler = ChatTransferHandler(this, toComposer = false)
  chatPanel.transferHandler = dropHandler
  chatScroll.transferHandler = dropHandler
  // bubble width is derived from the viewport width — rewrap on resize
  chatScroll.viewport.addComponentListener(object : ComponentAdapter() {
    override fun componentResized(e: ComponentEvent?) = chatPanel.revalidate()
  })

  composerField.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "lgm.chat.send")
  composerField.actionMap.put("lgm.chat.send", object : AbstractAction() {
    override fun actionPerformed(e: java.awt.event.ActionEvent?) = sendComposerText()
  })
  composerField.inputMap.put(KeyStroke.getKeyStroke("shift ENTER"), "lgm.chat.newline")
  composerField.actionMap.put("lgm.chat.newline", object : AbstractAction() {
    override fun actionPerformed(e: java.awt.event.ActionEvent?) {
      composerField.replaceSelection("\n")
    }
  })
  composerField.inputMap.put(KeyStroke.getKeyStroke("control V"), "lgm.chat.paste")
  composerField.actionMap.put("lgm.chat.paste", object : AbstractAction() {
    override fun actionPerformed(e: java.awt.event.ActionEvent?) {
      val image = clipboardImage()
      if (image != null) {
        sendImageToPostbox(image)
        return
      }
      composerField.paste()
    }
  })
  composerField.transferHandler = ChatTransferHandler(this, toComposer = true)

  val attachBtn = JButton(AllIcons.Actions.Upload).apply {
    margin = JBUI.insets(2, 4)
    isFocusPainted = false
    toolTipText = LocalGitMirrorBundle.message("panel.exchange.attach")
    addActionListener { chooseAndUploadFiles() }
  }
  val moreBtn = JButton(AllIcons.Actions.MoreHorizontal).apply {
    margin = JBUI.insets(2, 4)
    isFocusPainted = false
    toolTipText = LocalGitMirrorBundle.message("panel.toolbar.more")
    addActionListener { showExchangeOverflow(this) }
  }
  val composerScroll = JBScrollPane(composerField).apply {
    border = JBUI.Borders.empty()
    preferredSize = Dimension(JBUI.scale(200), JBUI.scale(56))
  }
  val east = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
    isOpaque = false
    add(primaryBtn(LocalGitMirrorBundle.message("panel.exchange.send"), AllIcons.Actions.Execute) {
      sendComposerText()
    })
    add(moreBtn)
  }
  val composer = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(6, 0, 2, 0)
    add(attachBtn, BorderLayout.WEST)
    add(composerScroll, BorderLayout.CENTER)
    add(east, BorderLayout.EAST)
  }

  return JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(4, 8)
    add(chatScroll, BorderLayout.CENTER)
    add(composer, BorderLayout.SOUTH)
  }
}

internal fun LocalGitMirrorPanel.showExchangeOverflow(anchor: JComponent) {
  val popup = JPopupMenu()
  popup.add(JMenuItem(LocalGitMirrorBundle.message("toolwindow.menu.refresh")).apply {
    addActionListener { refreshExchangeInBackground() }
  })
  popup.addSeparator()
  popup.add(JMenuItem(LocalGitMirrorBundle.message("panel.exchange.more.shot")).apply {
    addActionListener { sendClipboardScreenshot() }
  })
  popup.add(JMenuItem(LocalGitMirrorBundle.message("panel.exchange.more.log")).apply {
    addActionListener { sendIdeaLogTail() }
  })
  popup.addSeparator()
  popup.add(JMenuItem(LocalGitMirrorBundle.message("panel.exchange.more.clear")).apply {
    addActionListener { clearExchangeFeed() }
  })
  popup.show(anchor, 0, -popup.preferredSize.height)
}

internal fun LocalGitMirrorPanel.chooseAndUploadFiles() {
  val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
  val files = FileChooser.chooseFiles(descriptor, project, null)
  uploadFilesToPostbox(files.map { File(it.path) }.filter { it.isFile })
}
