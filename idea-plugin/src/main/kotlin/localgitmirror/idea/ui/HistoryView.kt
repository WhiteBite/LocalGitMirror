package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.OperationsHistoryService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

internal class HistoryView(private val panel: LocalGitMirrorPanel) {

  // ── History list (one-line-per-entry, double-click for details) ──
  internal val historyListModel = DefaultListModel<OperationsHistoryService.Entry>()
  internal val historyList = JBList(historyListModel).apply {
    cellRenderer = HistoryCellRenderer()
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    fixedCellHeight = JBUI.scale(22)
    font = JBUI.Fonts.smallFont()
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount >= 2) {
          val idx = locationToIndex(e.point)
          if (idx >= 0 && idx < historyListModel.size()) {
            HistoryEntryDialog(historyListModel.getElementAt(idx)).show()
          }
        }
      }
    })
  }

  internal lateinit var historyScroll: JScrollPane
  private var historyExpanded = true

  internal fun refreshHistoryLog() {
    val entries = panel.historyService.latest(40)
    if (::historyScroll.isInitialized) {
      historyScroll.isVisible = historyExpanded && entries.isNotEmpty()
    }
    historyListModel.clear()
    entries.forEach { historyListModel.addElement(it) }
    if (::historyScroll.isInitialized) {
      historyScroll.parent?.revalidate()
      historyScroll.parent?.repaint()
      panel.revalidate()
      panel.repaint()
    }
  }

  internal fun buildHistoryPanel(): JComponent {
    historyScroll = JScrollPane(historyList).apply {
      preferredSize = Dimension(JBUI.scale(100), JBUI.scale(140))
      isVisible = false
      border = BorderFactory.createEmptyBorder()
      viewportBorder = BorderFactory.createEmptyBorder()
    }
    val toggleBtn = JButton(AllIcons.General.ChevronDown).apply {
      margin = JBUI.insets(1, 2)
      isFocusPainted = false
      isBorderPainted = false
      isContentAreaFilled = false
      toolTipText = LocalGitMirrorBundle.message("panel.history.toggle.tooltip")
    }
    val historyLabel = JBLabel(LocalGitMirrorBundle.message("panel.history.title")).apply {
      font = JBUI.Fonts.smallFont().asBold()
    }
    val clearBtn = JButton(AllIcons.Actions.GC).apply {
      margin = JBUI.insets(1, 2)
      isFocusPainted = false
      isBorderPainted = false
      isContentAreaFilled = false
      toolTipText = LocalGitMirrorBundle.message("toolwindow.history.clear")
      addActionListener {
        panel.historyService.clear()
        refreshHistoryLog()
      }
    }
    val header = JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.empty(2, 4)
      val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
        isOpaque = false
        add(toggleBtn)
        add(historyLabel)
      }
      add(left, BorderLayout.WEST)
      add(clearBtn, BorderLayout.EAST)
    }
    toggleBtn.addActionListener {
      historyExpanded = !historyExpanded
      toggleBtn.icon = if (historyExpanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
      refreshHistoryLog()
    }
    return JPanel(BorderLayout()).apply {
      isOpaque = true
      background = UIUtil.getPanelBackground()
      border = JBUI.Borders.customLine(JBColor.border(), 1)
      add(header, BorderLayout.NORTH)
      add(historyScroll, BorderLayout.CENTER)
    }
  }

  private inner class HistoryCellRenderer : ColoredListCellRenderer<OperationsHistoryService.Entry>() {
    override fun customizeCellRenderer(
      list: JList<out OperationsHistoryService.Entry>,
      value: OperationsHistoryService.Entry?,
      index: Int,
      selected: Boolean,
      hasFocus: Boolean
    ) {
      if (value == null) return
      val shortTime = if (value.timestamp.length >= 16) value.timestamp.substring(5, 16) else value.timestamp
      val ok = value.status == "OK"
      val mark = if (ok) "\u2713" else "\u2717"
      val markAttr = SimpleTextAttributes(
        SimpleTextAttributes.STYLE_PLAIN,
        if (ok) JBColor(0x2E7D32, 0x66BB6A) else JBColor(0xC62828, 0xEF5350)
      )
      append("$shortTime  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
      append(mark, markAttr)
      append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
      append(
        value.operation,
        SimpleTextAttributes.REGULAR_ATTRIBUTES
      )
      val shortDetails = (value.details.lineSequence().firstOrNull() ?: "")
        .replace(Regex("[0-9a-f]{16,}"), "…")
        .replace("id=…", "")
        .replace("'", "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .take(60)
      if (shortDetails.isNotEmpty()) {
        append("  $shortDetails", SimpleTextAttributes.GRAYED_ATTRIBUTES)
      }
      toolTipText = value.details.take(1000)
    }
  }

  /** Dialog showing full details of a history entry, with a Copy button. */
  private inner class HistoryEntryDialog(private val entry: OperationsHistoryService.Entry) : DialogWrapper(panel.project, false) {
    init {
      title = LocalGitMirrorBundle.message("history.dialog.title")
      init()
    }

    override fun createCenterPanel(): JComponent {
      val fullText = "${entry.timestamp} [${entry.status}] ${entry.operation}\n\n${entry.details}"
      val ta = JTextArea(fullText).apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
      }
      return JScrollPane(ta).apply {
        preferredSize = Dimension(JBUI.scale(500), JBUI.scale(300))
      }
    }

    override fun createActions(): Array<javax.swing.Action> {
      val copyAction = object : javax.swing.AbstractAction(LocalGitMirrorBundle.message("history.dialog.copy")) {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
          val fullText = "${entry.timestamp} [${entry.status}] ${entry.operation}\n\n${entry.details}"
          val clipboard = Toolkit.getDefaultToolkit().systemClipboard
          clipboard.setContents(StringSelection(fullText), null)
        }
      }
      val closeAction = object : javax.swing.AbstractAction(LocalGitMirrorBundle.message("history.dialog.close")) {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
          close(OK_EXIT_CODE)
        }
      }
      return arrayOf(copyAction, closeAction)
    }
  }
}
