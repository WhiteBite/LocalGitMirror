package localgitmirror.idea.ui.exchange

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.ui.LocalGitMirrorPanel
import localgitmirror.idea.workkit.BubbleText
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.font.LineBreakMeasurer
import javax.swing.*

private const val BUBBLE_TEXT_MAX_WIDTH = 420
private const val COPY_FLASH_MS = 1500

internal fun LocalGitMirrorPanel.buildBubbleRow(item: ExchangeItem): JComponent {
  val row = WrapMaxPanel()
  row.layout = BorderLayout()
  row.isOpaque = false
  row.border = JBUI.Borders.empty(2, 2)
  val bubble = buildBubble(item)
  val own = item.side == localSide()
  row.add(bubble, if (own) BorderLayout.EAST else BorderLayout.WEST)
  row.alignmentX = Component.LEFT_ALIGNMENT
  installBubbleMouse(row, item)
  installBubbleMouse(bubble, item)
  return row
}

/**
 * Panel whose BoxLayout maximum height always tracks the current preferred height.
 * A frozen maximumSize captured before layout clips bubbles once the text area
 * rewraps at the real viewport width.
 */
private class WrapMaxPanel : JPanel() {
  override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

private fun LocalGitMirrorPanel.bubbleTint(item: ExchangeItem): JBColor = when {
  item.state == ExchangeItem.State.FAILED -> JBColor(0xF2DCDC, 0x563A3A)
  item.side == ExchangeItem.Side.WORK -> JBColor(0xDBEAF7, 0x2E4356)
  item.side == ExchangeItem.Side.HOME -> JBColor(0xF2F3F5, 0x434547)
  else -> JBColor(0xE8E9EB, 0x383A3C)
}

private class BubblePanel(private val tint: Color) : JPanel(BorderLayout()) {
  init {
    isOpaque = false
    border = JBUI.Borders.empty(6, 10)
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g2.color = tint
    g2.fillRoundRect(0, 0, width - 1, height - 1, JBUI.scale(14), JBUI.scale(14))
    g2.dispose()
    super.paintComponent(g)
  }
}

private fun LocalGitMirrorPanel.buildBubble(item: ExchangeItem): JComponent {
  val bubble = BubblePanel(bubbleTint(item))
  val content = WrapMaxPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
  }
  when {
    item.kind == ExchangeItem.Kind.BUFFER -> {
      bubble.add(bubbleCopyRow(item), BorderLayout.NORTH)
      content.add(bufferBody(item))
    }
    item.isMrNotes -> content.add(fileCard(item, AllIcons.Toolwindows.ToolWindowMessages,
      LocalGitMirrorBundle.message("panel.exchange.ctx.open")) { openExchangeItem(item) })
    item.isImage -> content.add(imageBody(item))
    item.isText -> content.add(fileCard(item,
      if (item.isLog) AllIcons.Debugger.Console else AllIcons.FileTypes.Text,
      LocalGitMirrorBundle.message("panel.exchange.ctx.open")) { openExchangeItem(item) })
    else -> content.add(fileCard(item, AllIcons.FileTypes.Any_type,
      LocalGitMirrorBundle.message("panel.exchange.chat.save")) { saveExchangeFileAs(item) })
  }
  content.add(bubbleFooter(item))
  bubble.add(content, BorderLayout.CENTER)
  return bubble
}

/** Always-visible copy button pinned to the bubble's top-right corner. */
private fun LocalGitMirrorPanel.bubbleCopyRow(item: ExchangeItem): JComponent {
  val button = JButton(AllIcons.Actions.Copy).apply {
    margin = JBUI.insets(0)
    isFocusPainted = false
    isContentAreaFilled = false
    isBorderPainted = false
    toolTipText = LocalGitMirrorBundle.message("panel.exchange.chat.copy")
    addActionListener { copyBubbleText(item, this) }
  }
  return JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
    isOpaque = false
    add(button)
  }
}

/** Copy the FULL message text; fetches the server body first when only a preview is known. */
private fun LocalGitMirrorPanel.copyBubbleText(item: ExchangeItem, button: JButton?) {
  val known = item.localText ?: chatBodyCache[item.id]
  if (known != null) {
    CopyPasteManager.getInstance().setContents(StringSelection(known))
    button?.let { flashCopied(it) }
    return
  }
  if (!item.canOpen) return
  loadChatBody(item) { full ->
    CopyPasteManager.getInstance().setContents(StringSelection(full))
    button?.let { flashCopied(it) }
  }
}

private fun LocalGitMirrorPanel.flashCopied(button: JButton) {
  button.icon = AllIcons.Actions.Checked
  Timer(COPY_FLASH_MS, ActionListener { button.icon = AllIcons.Actions.Copy }).apply {
    isRepeats = false
    start()
  }
}

private fun LocalGitMirrorPanel.bufferBody(item: ExchangeItem): JComponent {
  val known = item.localText ?: chatBodyCache[item.id]
  val body = WrapMaxPanel()
  body.layout = BorderLayout()
  body.isOpaque = false
  val text = known ?: item.title
  val expanded = item.id in expandedIds
  val clamped = BubbleText.clamp(text)
  val shown = if (clamped.truncated && !expanded) clamped.text else text
  body.add(BubbleTextArea(shown, looksLikeCode(shown)), BorderLayout.CENTER)

  val needsFetch = known == null && item.canOpen &&
    (item.title == EMPTY_HINT_MARK || item.size > item.title.length)
  val link = when {
    needsFetch -> chatLink(LocalGitMirrorBundle.message("panel.exchange.chat.expand")) {
      fetchChatBody(item)
    }
    clamped.truncated && !expanded -> chatLink(LocalGitMirrorBundle.message("panel.exchange.chat.expand")) {
      expandedIds.add(item.id)
      renderChat()
    }
    clamped.truncated -> chatLink(LocalGitMirrorBundle.message("panel.exchange.chat.collapse")) {
      expandedIds.remove(item.id)
      renderChat()
    }
    else -> null
  }
  if (link != null) {
    val linkRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
      isOpaque = false
      add(link)
    }
    body.add(linkRow, BorderLayout.SOUTH)
  }
  return body
}

private fun LocalGitMirrorPanel.imageBody(item: ExchangeItem): JComponent {
  val label = JBLabel()
  val thumb = chatThumbCache[item.id]
  if (thumb != null) label.icon = thumb
  else {
    label.icon = AllIcons.FileTypes.Image
    label.text = EMPTY_HINT_MARK
  }
  label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
  label.border = JBUI.Borders.empty(2, 0)
  label.addMouseListener(object : MouseAdapter() {
    override fun mouseClicked(e: MouseEvent) {
      if (SwingUtilities.isLeftMouseButton(e) && item.canOpen) openExchangeItem(item)
    }
  })
  bubbleThumbLabels[item.id] = label
  return label
}

private fun LocalGitMirrorPanel.fileCard(item: ExchangeItem, icon: Icon, actionText: String, action: () -> Unit): JComponent {
  val card = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
  card.isOpaque = false
  card.add(JBLabel(icon))
  card.add(JBLabel(item.title.take(48)).apply {
    font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(11))
  })
  if (item.size > 0) {
    card.add(JBLabel(formatSize(item.size)).apply {
      font = JBUI.Fonts.smallFont()
      foreground = JBColor(0x6F7277, 0x8C8F94)
    })
  }
  if (item.canOpen) {
    card.add(chatLink(actionText) { action() })
  }
  if (item.displayPath.isNotBlank()) {
    card.add(chatLink(LocalGitMirrorBundle.message("panel.exchange.chat.copyPath")) { copyExchangePath(item) })
  }
  card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
  return card
}

private fun LocalGitMirrorPanel.bubbleFooter(item: ExchangeItem): JComponent {
  val footer = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
  footer.isOpaque = false
  footer.border = JBUI.Borders.empty(3, 0, 0, 0)
  val parts = mutableListOf(formatBufferTs(item.ts))
  if (item.size > 0) parts.add(formatSize(item.size))
  when (item.side) {
    ExchangeItem.Side.WORK -> parts.add(LocalGitMirrorBundle.message("panel.exchange.side.work"))
    ExchangeItem.Side.HOME -> parts.add(LocalGitMirrorBundle.message("panel.exchange.side.home"))
    ExchangeItem.Side.UNKNOWN -> Unit
  }
  if (item.pinned) parts.add("\u2605")
  val metaLabel = JBLabel(parts.joinToString(" \u00b7 ")).apply {
    font = JBUI.Fonts.smallFont().deriveFont(Font.PLAIN, JBUI.scale(10f).toFloat())
    foreground = JBColor(0x6F7277, 0x8C8F94)
  }
  footer.add(metaLabel)
  when (item.state) {
    ExchangeItem.State.PENDING -> footer.add(JBLabel(LocalGitMirrorBundle.message("panel.exchange.chat.sending")).apply {
      font = JBUI.Fonts.smallFont().deriveFont(Font.PLAIN, JBUI.scale(10f).toFloat())
      foreground = JBColor(0x6F7277, 0x8C8F94)
    })
    ExchangeItem.State.FAILED -> {
      footer.add(JBLabel(LocalGitMirrorBundle.message("panel.exchange.chat.failed")).apply {
        font = JBUI.Fonts.smallFont().deriveFont(Font.PLAIN, JBUI.scale(10f).toFloat())
        foreground = JBColor(0xC7222D, 0xE08A8A)
      })
      footer.add(chatLink(LocalGitMirrorBundle.message("panel.exchange.chat.retry")) { retryEcho(item) })
    }
    ExchangeItem.State.SENT -> Unit
  }
  footer.maximumSize = Dimension(Int.MAX_VALUE, footer.preferredSize.height)
  return footer
}

private fun LocalGitMirrorPanel.chatLink(text: String, onClick: () -> Unit): JBLabel = JBLabel(text).apply {
  font = JBUI.Fonts.smallFont().deriveFont(Font.PLAIN, JBUI.scale(10f).toFloat())
  foreground = SimpleTextAttributes.LINK_ATTRIBUTES.fgColor
  cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
  addMouseListener(object : MouseAdapter() {
    override fun mouseClicked(e: MouseEvent) {
      if (SwingUtilities.isLeftMouseButton(e)) onClick()
    }
  })
}

/** Text bubble body: width = min(natural text width, ~75% of viewport), height measured per hard line via LineBreakMeasurer. */
private class BubbleTextArea(raw: String, mono: Boolean) :
  JTextArea(raw.replace("\r\n", "\n").replace('\r', '\n')) {
  init {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    isOpaque = false
    font = if (mono) Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12)) else JBUI.Fonts.smallFont()
    border = JBUI.Borders.empty()
  }

  private fun viewportWidth(): Int {
    var c: Container? = parent
    while (c != null) {
      if (c is JViewport && c.width > 0) return c.width
      c = c.parent
    }
    return 0
  }

  override fun getPreferredSize(): Dimension {
    val fm = getFontMetrics(font)
    val ins = insets
    val lines = text.split('\n').map { it.replace("\t", "        ") }
    val natural = lines.maxOf { fm.stringWidth(it) } + ins.left + ins.right + JBUI.scale(4)
    val vp = viewportWidth()
    val target = if (vp > 0) vp * 3 / 4 else JBUI.scale(BUBBLE_TEXT_MAX_WIDTH)
    val width = minOf(natural, target).coerceAtLeast(JBUI.scale(120))
    val wrapWidth = (width - ins.left - ins.right).coerceAtLeast(1)
    var h = ins.top + ins.bottom
    for (line in lines) {
      if (line.isEmpty()) {
        h += fm.height
        continue
      }
      val measurer = LineBreakMeasurer(
        java.text.AttributedString(line).getIterator(), fm.fontRenderContext
      )
      while (measurer.position < line.length) {
        measurer.nextLayout(wrapWidth.toFloat())
        h += fm.height
      }
    }
    return Dimension(width, h)
  }
}

private fun LocalGitMirrorPanel.looksLikeCode(text: String): Boolean {
  val lines = text.lines().filter { it.isNotBlank() }.take(20)
  if (lines.size < 2) return false
  val codey = lines.count { l ->
    val t = l.trimEnd()
    t.endsWith(";") || t.endsWith("{") || t.endsWith("}") || t.endsWith(")") ||
      l.startsWith("  ") || l.startsWith("\t") ||
      t.contains("fun ") || t.contains("def ") || t.contains("class ") || t.contains(" = ")
  }
  return codey * 2 >= lines.size
}

private fun LocalGitMirrorPanel.installBubbleMouse(comp: JComponent, item: ExchangeItem) {
  comp.transferHandler = object : TransferHandler() {
    override fun getSourceActions(c: JComponent): Int = COPY
    override fun createTransferable(c: JComponent): Transferable? =
      if (item.canOpen) ExchangeTransferable(this@installBubbleMouse, item) else null
  }
  var dragExported = false
  comp.addMouseListener(object : MouseAdapter() {
    override fun mousePressed(e: MouseEvent) {
      dragExported = false
      if (e.isPopupTrigger) showBubbleContextMenu(item, comp, e.x, e.y)
    }
    override fun mouseReleased(e: MouseEvent) {
      if (e.isPopupTrigger) showBubbleContextMenu(item, comp, e.x, e.y)
    }
  })
  comp.addMouseMotionListener(object : MouseMotionAdapter() {
    override fun mouseDragged(e: MouseEvent) {
      if (dragExported || !item.canOpen || !SwingUtilities.isLeftMouseButton(e)) return
      dragExported = true
      comp.transferHandler?.exportAsDrag(comp, e, TransferHandler.COPY)
    }
  })
}

private fun LocalGitMirrorPanel.showBubbleContextMenu(item: ExchangeItem, comp: JComponent, x: Int, y: Int) {
  val popup = JPopupMenu()
  if (item.kind == ExchangeItem.Kind.BUFFER) {
    popup.add(JMenuItem(LocalGitMirrorBundle.message("panel.exchange.chat.copy")).apply {
      addActionListener { copyBubbleText(item, null) }
    })
  } else {
    popup.add(JMenuItem(LocalGitMirrorBundle.message("panel.exchange.ctx.open")).apply {
      addActionListener { openExchangeItem(item) }
    })
  }
  if (item.kind != ExchangeItem.Kind.BUFFER || item.displayPath.isNotBlank()) {
    popup.add(JMenuItem(LocalGitMirrorBundle.message("panel.exchange.chat.copyPath")).apply {
      addActionListener { copyExchangePath(item) }
    })
  }
  if (item.kind == ExchangeItem.Kind.BUFFER && !item.isEcho) {
    val pinKey = if (item.pinned) "panel.exchange.ctx.unpin" else "panel.exchange.ctx.pin"
    popup.add(JMenuItem(LocalGitMirrorBundle.message(pinKey)).apply {
      addActionListener { toggleExchangePin(item) }
    })
  }
  popup.add(JMenuItem(LocalGitMirrorBundle.message("panel.exchange.ctx.delete")).apply {
    addActionListener { deleteExchangeItem(item) }
  })
  popup.show(comp, x, y)
}

private fun localSide(): ExchangeItem.Side =
  if (localgitmirror.idea.deps.RoleDetector.detect(service<MirrorSettingsService>().state) == localgitmirror.idea.deps.MachineRole.WORK)
    ExchangeItem.Side.WORK else ExchangeItem.Side.HOME

internal fun formatBufferTs(epochSec: Double): String {
  val ms = (epochSec * 1000).toLong()
  return java.text.SimpleDateFormat("HH:mm").format(java.util.Date(ms))
}

internal fun formatSize(bytes: Long): String = when {
  bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
  bytes >= 1024L -> "${bytes / 1024} KB"
  else -> "$bytes B"
}
