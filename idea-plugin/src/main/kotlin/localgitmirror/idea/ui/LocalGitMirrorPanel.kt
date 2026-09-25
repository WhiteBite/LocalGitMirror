package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.*
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.actions.GitLabMrSender
import localgitmirror.idea.actions.PullFromMirrorAction
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.gitlab.GitLabConfig
import localgitmirror.idea.gitlab.MrNotesWriter
import localgitmirror.idea.gitlab.MrReviewService
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.net.LanDiscovery
import localgitmirror.idea.settings.*
import localgitmirror.idea.sync.HandshakeCache
import localgitmirror.idea.sync.v2.SyncFacadeService
import localgitmirror.idea.workkit.BubbleText
import localgitmirror.idea.workkit.BundleCrypto
import localgitmirror.idea.workkit.ExchangeCrypto
import localgitmirror.idea.workkit.ExchangeMeta
import localgitmirror.idea.workkit.RepoFileSyncCrypto
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.ActionListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.font.LineBreakMeasurer
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Date
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.swing.*

class LocalGitMirrorPanel(val project: Project) : JPanel(BorderLayout()), Disposable {

  // ── Quick Setup form state (local vars bound to DSL fields) ──
  private var setupUrl: String = service<MirrorSettingsService>().state.baseUrl.let {
    if (it.isNotBlank() && it != "https://localhost") it else ""
  }
  private var setupApiKey: String = SecretsStore.mirrorApiKey
  private var setupSyncPassword: String = SecretsStore.syncPassword
  private var setupFormPanel: com.intellij.openapi.ui.DialogPanel? = null

  internal val status = JBLabel("")
  internal val mirrorBadge = BadgeLabel("Cache: ?")
  internal val lastSyncBadge = BadgeLabel("Last sync: \u2014")

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
  // Plugin version string, surfaced in the gear menu / tooltip instead of a
  // competing status badge (keeps the header row compact in a narrow tool window).
  internal val pluginVersionText: String = run {
    // Read plugin version at runtime from the platform's plugin descriptor
    val pluginId = com.intellij.openapi.extensions.PluginId.getId("localgitmirror.idea.orchestrator")
    val descriptor = com.intellij.ide.plugins.PluginManagerCore.getPlugin(pluginId)
    if (descriptor != null) "v${descriptor.version}" else "v?"
  }

  // Progress bar + stage label shown during sync
  internal val progressBar = JProgressBar().apply {
    isVisible = false
    isIndeterminate = true
    minimum = 0; maximum = 100
  }
  internal val progressLabel = JBLabel("").apply {
    font = JBUI.Fonts.smallFont()
    foreground = UIUtil.getContextHelpForeground()
    isVisible = false
  }

  internal val cancelButton = JButton("Cancel").apply {
    isVisible = false
    margin = JBUI.insets(1, 8)
    font = JBUI.Fonts.smallFont()
    isFocusPainted = false
    addActionListener { cancelCurrentOperation() }
  }

  internal lateinit var historyScroll: JScrollPane
  private var historyExpanded = true
  private var progressRow: JPanel? = null

  internal val historyService = service<OperationsHistoryService>()
  internal val syncFacade = project.getService(SyncFacadeService::class.java)

  private companion object {
    const val EXCHANGE_POLL_MS = 5_000
    const val IDEA_LOG_TAIL_BYTES = 200 * 1024
    const val EXPORT_FETCH_TIMEOUT_SEC = 120L
    const val THUMB_CACHE_MAX = 64
    const val THUMB_MAX_WIDTH = 240
    const val THUMB_MAX_HEIGHT = 320
    const val BUBBLE_TEXT_MAX_WIDTH = 420
    const val COPY_FLASH_MS = 1500
    const val EMPTY_HINT_MARK = "\u2022\u2022\u2022"
    const val LOCAL_ID_PREFIX = "local-"
  }

  // ── Branch selector (JBList with status) ──
  // Shows local branches immediately and appends Mirror-only branches after a
  // background refs request. BranchListItem keeps the raw name + status for actions.
  internal val branchListModel = DefaultListModel<BranchListItem>()
  // All items before filtering — used by branchFilterField to re-apply the filter.
  private var allBranchItems: List<BranchListItem> = emptyList()
  private var respondButton: javax.swing.JButton? = null
  private var reviewFetchButton: javax.swing.JButton? = null
  private val branchFilterField = SearchTextField(false).apply {
    textEditor.emptyText.text = LocalGitMirrorBundle.message("panel.branch.filter")
    textEditor.font = JBUI.Fonts.smallFont()
    toolTipText = LocalGitMirrorBundle.message("panel.branch.filter")
    addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = applyBranchFilter()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = applyBranchFilter()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = applyBranchFilter()
    })
  }
  internal val branchList = JBList(branchListModel).apply {
    font = JBUI.Fonts.smallFont()
    cellRenderer = BranchListCellRenderer()
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    emptyText.text = LocalGitMirrorBundle.message("panel.branch.empty")
    emptyText.appendLine(
      LocalGitMirrorBundle.message("panel.branch.empty.refresh"),
      SimpleTextAttributes.LINK_ATTRIBUTES
    ) { refreshBranchCombo(userInitiated = true, withMirror = true) }
    toolTipText = "Ветка для Отправить / Подтянуть; ★ есть только на Cache"
  }

  internal val depsListModel = DefaultListModel<String>()
  internal val depsList = JBList(depsListModel).apply {
    font = JBUI.Fonts.smallFont()
    fixedCellHeight = JBUI.scale(24)
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = IconTextCellRenderer(AllIcons.Nodes.PpLib)
    emptyText.text = LocalGitMirrorBundle.message("panel.deps.empty")
    emptyText.appendLine(
      LocalGitMirrorBundle.message("panel.deps.empty.refresh"),
      SimpleTextAttributes.LINK_ATTRIBUTES
    ) { refreshDepsInBackground() }
  }
  private var depsBanner: EditorNotificationPanel? = null
  private var depsResponsesPanel: EditorNotificationPanel? = null
  private var depsSectionLabel: JBLabel? = null
  private var requestDepsButton: JButton? = null
  private var applyDepsButton: JButton? = null

  private inner class IconTextCellRenderer(private val rowIcon: Icon) :
    ColoredListCellRenderer<String>() {
    override fun customizeCellRenderer(
      list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean
    ) {
      icon = rowIcon
      iconTextGap = JBUI.scale(6)
      border = JBUI.Borders.empty(1, 6)
      append(value ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }
  }

  // ── Exchange chat (unified buffer + postbox timeline) ──
  private var allServerItems: List<ExchangeItem> = emptyList()
  private var chatEmptyMessage: String = LocalGitMirrorBundle.message("panel.exchange.empty")
  // Local optimistic echoes: shown instantly, confirmed with the server id after HTTP 200.
  private val chatEchoes = mutableListOf<ExchangeItem>()
  private val echoIdByServerId = ConcurrentHashMap<String, String>()
  // hint_enc/path_enc decryption is PBKDF2-heavy; cached so the 5 s poll doesn't re-derive keys.
  private val exchangeHintCache = ConcurrentHashMap<String, String>()
  // Full buffer bodies fetched on "expand"; thumbnails decoded in background on arrival.
  private val chatBodyCache = ConcurrentHashMap<String, String>()
  private val chatThumbCache: MutableMap<String, ImageIcon> = Collections.synchronizedMap(
    object : LinkedHashMap<String, ImageIcon>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageIcon>) = size > THUMB_CACHE_MAX
    }
  )
  private val thumbQueued: MutableSet<String> = ConcurrentHashMap.newKeySet()
  private val bubbleThumbLabels = mutableMapOf<String, JBLabel>()
  private val expandedIds = mutableSetOf<String>()
  private var lastChatSignature: String? = null

  private val chatPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    border = JBUI.Borders.empty(4, 0)
  }
  private val chatScroll = JBScrollPane(chatPanel).apply {
    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    border = BorderFactory.createEmptyBorder()
    viewportBorder = BorderFactory.createEmptyBorder()
  }
  private val composerField = JBTextArea(3, 30).apply {
    lineWrap = true
    wrapStyleWord = true
    font = JBUI.Fonts.smallFont()
    emptyText.text = LocalGitMirrorBundle.message("panel.exchange.composer.hint")
  }
  private val exchangePollAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

  internal val roleBadge = BadgeLabel("")
  internal val statusDot = JBLabel("\u25CF")
  private var tabsPane: JBTabbedPane? = null
  private val tabLabels = mutableMapOf<Int, JBLabel>()
  private val tabPills = mutableMapOf<Int, JComponent>()

  private fun makeTabComponent(tabs: JBTabbedPane, index: Int): JComponent {
    val label = JBLabel(tabs.getTitleAt(index)).apply {
      font = JBUI.Fonts.smallFont()
      border = JBUI.Borders.empty(4, 12, 4, 12)
      foreground = JBColor(0x6F7277, 0x8C8F94)
    }
    tabLabels[index] = label
    val pill = object : JPanel(BorderLayout()) {
      var hovered = false
      override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val bg = when {
          tabs.selectedIndex == index -> JBColor(0xE3E5E8, 0x333640)
          hovered -> JBColor(0xEBECEE, 0x313438)
          else -> null
        }
        if (bg != null) {
          g2.color = bg
          g2.fillRoundRect(0, 0, width, height, JBUI.scale(14), JBUI.scale(14))
        }
        g2.dispose()
        super.paintComponent(g)
      }
    }
    pill.isOpaque = false
    pill.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    pill.add(label, BorderLayout.CENTER)
    pill.addMouseListener(object : MouseAdapter() {
      override fun mouseEntered(e: MouseEvent) {
        pill.hovered = true
        pill.repaint()
      }
      override fun mouseExited(e: MouseEvent) {
        pill.hovered = false
        pill.repaint()
      }
      override fun mouseClicked(e: MouseEvent) {
        tabs.selectedIndex = index
      }
    })
    tabPills[index] = pill
    return pill
  }

  private fun refreshTabStyles() {
    val tabs = tabsPane ?: return
    for ((i, pill) in tabPills) {
      tabLabels[i]?.foreground = if (tabs.selectedIndex == i)
        JBColor(0x1F2328, 0xDFE1E5) else JBColor(0x6F7277, 0x8C8F94)
      pill.repaint()
    }
  }

  private fun setTabTitle(index: Int, text: String) {
    tabsPane?.setTitleAt(index, text)
    tabLabels[index]?.let { label ->
      label.text = text
      label.revalidate()
      tabPills[index]?.revalidate()
      tabsPane?.revalidate()
    }
  }

  private val mrReviewListModel = DefaultListModel<MrReviewService.MrRowItem>()
  private val mrReviewStatus = JBLabel("").apply {
    font = JBUI.Fonts.smallFont()
    foreground = UIUtil.getContextHelpForeground()
  }
  private val mrReviewFilterField = SearchTextField(false).apply {
    textEditor.emptyText.text = LocalGitMirrorBundle.message("review.filter")
    textEditor.font = JBUI.Fonts.smallFont()
    toolTipText = LocalGitMirrorBundle.message("review.filter")
    addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refreshReview()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refreshReview()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refreshReview()
    })
  }
  private val mrReviewList = JBList(mrReviewListModel).apply {
    font = JBUI.Fonts.smallFont()
    fixedCellHeight = JBUI.scale(24)
    cellRenderer = MrReviewListCellRenderer()
    selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    emptyText.text = LocalGitMirrorBundle.message("review.empty")
    emptyText.appendLine(
      LocalGitMirrorBundle.message("review.empty.hint"),
      SimpleTextAttributes.LINK_ATTRIBUTES
    ) { reloadReview() }
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount >= 2) {
          val row = selectedValue ?: return
          openMrDialog(row)
        }
      }
    })
  }

  private inner class MrReviewListCellRenderer : ColoredListCellRenderer<MrReviewService.MrRowItem>() {
    override fun customizeCellRenderer(
      list: JList<out MrReviewService.MrRowItem>,
      value: MrReviewService.MrRowItem?,
      index: Int,
      selected: Boolean,
      hasFocus: Boolean
    ) {
      if (value == null) return
      border = JBUI.Borders.empty(1, 6)
      val amber = JBColor(0xB8860B, 0xE3AE4D)
      val green = JBColor(0x2E7D32, 0x66BB6A)
      val badgeAttr = if (value.unresolved > 0)
        SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, amber)
      else
        SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.GRAY)
      append("!${value.iid}", badgeAttr)
      append("  ${value.title}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
      append("  ${value.sourceBranch}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
      val countAttr = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN,
        if (value.unresolved > 0) amber else green)
      val countText = if (value.unresolved > 0) "${value.unresolved}\u26a0"
                      else "\u2713 ${value.totalThreads}"
      append("  $countText", countAttr)
    }
  }

  private inner class BranchListCellRenderer : JPanel(BorderLayout()), ListCellRenderer<BranchListItem> {
    private val glyphLabel = JBLabel()
    private val nameLabel = JBLabel()
    private val badgeLabel = JBLabel()
    private val deltaLabel = JBLabel()

    init {
      isOpaque = true
      border = JBUI.Borders.empty(1, 6)
      val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(glyphLabel)
        add(nameLabel)
        add(badgeLabel)
      }
      add(left, BorderLayout.WEST)
      add(deltaLabel, BorderLayout.EAST)
      glyphLabel.font = JBUI.Fonts.smallFont().asBold()
      nameLabel.font = JBUI.Fonts.smallFont()
      badgeLabel.font = JBUI.Fonts.smallFont()
      deltaLabel.font = JBUI.Fonts.smallFont()
    }

    override fun getListCellRendererComponent(
      list: JList<out BranchListItem>,
      value: BranchListItem?,
      index: Int,
      isSelected: Boolean,
      cellHasFocus: Boolean
    ): Component {
      background = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
      if (value == null) return this
      val selFg = UIUtil.getListSelectionForeground(true)
      val statusFg = statusColor(value.status)

      glyphLabel.text = statusGlyph(value.status)
      glyphLabel.foreground = if (isSelected) selFg else statusFg

      nameLabel.text = value.name
      nameLabel.font = Font("JetBrains Mono", if (value.isCurrent) Font.BOLD else Font.PLAIN, JBUI.scale(12))
      nameLabel.foreground = when {
        isSelected -> selFg
        value.isCurrent -> JBColor(0x2E7D32, 0x5FAD65)
        value.status == BranchStatus.MIRROR_ONLY -> UIUtil.getContextHelpForeground()
        else -> UIUtil.getListForeground()
      }

      badgeLabel.text = if (value.mrIid != null)
        "  !${value.mrIid}${if (value.mrUnresolved > 0) " \u00b7 ${value.mrUnresolved}\u26a0" else ""}"
      else ""
      badgeLabel.foreground = if (isSelected) selFg else JBColor(0xB8860B, 0xE3AE4D)

      val delta = when (value.status) {
        BranchStatus.AHEAD -> value.aheadCount?.let { "+$it" } ?: ""
        BranchStatus.BEHIND -> value.behindCount?.let { "−$it" } ?: ""
        else -> ""
      }
      deltaLabel.text = delta.ifEmpty { if (value.isCurrent) "HEAD" else "" }
      deltaLabel.foreground = when {
        isSelected -> selFg
        delta.isEmpty() && value.isCurrent -> JBColor(0x6F7277, 0x6F7277)
        else -> statusFg
      }
      deltaLabel.border = JBUI.Borders.empty(0, 8, 0, 4)
      return this
    }
  }

  private fun statusGlyph(status: BranchStatus): String = when (status) {
    BranchStatus.SYNCED -> "\u2713"
    BranchStatus.AHEAD -> "\u2191"
    BranchStatus.BEHIND -> "\u2193"
    BranchStatus.MIRROR_ONLY -> "\u2605"
    BranchStatus.LOCAL_ONLY -> "\u25cb"
  }

  private fun statusColor(status: BranchStatus): JBColor = when (status) {
    BranchStatus.SYNCED -> JBColor(0x2E7D32, 0x5FAD65)
    BranchStatus.AHEAD -> JBColor(0xB8860B, 0xE3AE4D)
    BranchStatus.BEHIND -> JBColor(0x1565C0, 0x548AF7)
    BranchStatus.MIRROR_ONLY -> JBColor(0x9A7D0A, 0xD4A72C)
    BranchStatus.LOCAL_ONLY -> JBColor.GRAY
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
  private var branchRefreshInProgress = false

  private val refreshBranchesAction = object : AnAction(
    LocalGitMirrorBundle.message("panel.branch.refresh.tooltip"),
    LocalGitMirrorBundle.message("panel.branch.refresh.tooltip"),
    AllIcons.Actions.Refresh
  ) {
    override fun actionPerformed(e: AnActionEvent) = refreshBranchCombo(userInitiated = true, withMirror = true)
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = !branchRefreshInProgress && !isSyncing
    }
  }

  private val overflowAction = object : AnAction(
    LocalGitMirrorBundle.message("panel.toolbar.more"),
    LocalGitMirrorBundle.message("panel.toolbar.more.tooltip", pluginVersionText),
    AllIcons.Actions.MoreHorizontal
  ) {
    override fun actionPerformed(e: AnActionEvent) {
      val anchor = (e.inputEvent?.component as? JComponent) ?: this@LocalGitMirrorPanel
      showOverflowPopup(anchor)
    }
  }
  private val branchRefreshGeneration = AtomicLong()

  // Additional branches to include on send (legacy chip behaviour kept as internal set)
  internal val selectedAdditionalBranches = mutableSetOf<String>()

  // Dynamic UI containers
  internal val badgesPanel = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply { isOpaque = false }
  internal val actionsBox = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    alignmentX = LEFT_ALIGNMENT
  }
  /** Full action list — shown only in the overflow popup. */
  internal val mainGroup = DefaultActionGroup()
  /** Compact toolbar set — what actually renders in the panel's top toolbar. */
  internal val toolbarGroup = DefaultActionGroup()

  internal var isSyncing = false
    set(value) {
      field = value
      updateUiState()
    }

  /** Current progress indicator for cancellation support. Set by sync operations. */
  internal var currentIndicator: ProgressIndicator? = null

  internal enum class SyncOutcome { OK, FAIL }

  // ── Progress helpers ──

  /** Update progress bar + label from any thread. fraction in [0..1] or -1 for indeterminate. */
  internal fun setProgress(fraction: Double, text: String) {
    UIUtil.invokeLaterIfNeeded {
      progressLabel.text = text
      if (fraction < 0) {
        progressBar.isIndeterminate = true
      } else {
        progressBar.isIndeterminate = false
        progressBar.value = (fraction * 100).toInt().coerceIn(0, 100)
      }
    }
  }

  /** Cancel the currently running sync operation, if any. */
  internal fun cancelCurrentOperation() {
    currentIndicator?.cancel()
  }

  private fun updateUiState() {
    UIUtil.invokeLaterIfNeeded {
      val enabled = !isSyncing
      fun disableAll(container: java.awt.Container) {
        for (c in container.components) {
          if (c is JButton || c is JToggleButton || c is JComboBox<*> || c is JList<*>) c.isEnabled = enabled
          if (c is java.awt.Container) disableAll(c)
        }
      }
      disableAll(this)
      cancelButton.isEnabled = true
      progressBar.isVisible = isSyncing
      progressLabel.isVisible = isSyncing
      cancelButton.isVisible = isSyncing
      progressRow?.isVisible = isSyncing
      if (!isSyncing) {
        progressLabel.text = ""
        progressBar.isIndeterminate = true
        progressBar.value = 0
        currentIndicator = null
      }
      revalidate()
      repaint()
    }
  }

  /** Standard action button. */
  private fun btn(title: String, icon: Icon? = null, action: () -> Unit): JButton {
    val b = JButton(title, icon)
    b.margin = JBUI.insets(2, 8)
    b.font = b.font.deriveFont(JBUI.scale(12f).toFloat())
    b.isFocusPainted = false
    b.addActionListener { action() }
    return b
  }

  /** Primary (accent) button — painted by the IDE theme, not by hand. */
  private fun primaryBtn(title: String, icon: Icon? = null, action: () -> Unit): JButton =
    btn(title, icon, action).apply { putClientProperty("JButton.buttonType", "default") }

  private fun greenBtn(title: String, icon: Icon? = null, action: () -> Unit): JButton =
    accentBtn(title, icon, JBColor(0x4A9D54, 0x4A9D54), action)

  private fun accentBtn(title: String, icon: Icon?, bg: JBColor, action: () -> Unit): JButton {
    val b = JButton(title, icon)
    b.margin = JBUI.insets(2, 10)
    b.font = b.font.deriveFont(Font.BOLD)
    b.isOpaque = true
    b.background = bg
    b.foreground = JBColor.WHITE
    b.border = JBUI.Borders.empty(4, 12)
    b.isFocusPainted = false
    b.addActionListener { action() }
    return b
  }

  /** Trigger an action registered in plugin.xml by id, in the panel's project context. */
  private fun runRegisteredAction(actionId: String) {
    val action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(actionId) ?: return
    val dataContext = SimpleDataContext.getProjectContext(project)
    val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
      "LocalGitMirrorToolWindow", null, dataContext
    )
    action.actionPerformed(event)
  }

  private fun actionRow(vararg components: JComponent): JPanel {
    val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    row.isOpaque = false
    row.alignmentX = LEFT_ALIGNMENT
    row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
    components.forEach { row.add(it) }
    return row
  }

  /**
   * Refresh the selector from local Git immediately. When [withMirror] is true,
   * also append fresh refs from Mirror asynchronously. The mirror fetch is
   * gated off the init/show path so the panel never auto-fires network on
   * activation — only explicit user refresh and post-sync completions opt in.
   */
  internal fun refreshBranchCombo(userInitiated: Boolean = false, withMirror: Boolean = false) {
    val dir = baseDir()
    if (dir == null) {
      if (userInitiated) notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.WARNING)
      return
    }

    val localBranches = GitLocal.listBranches(project, dir)
    val currentBranch = GitLocal.currentBranch(project, dir)
    replaceBranchItems(localBranches, mirrorRefs, currentBranch)
    if (withMirror) {
      refreshMirrorBranches(dir, localBranches, currentBranch, userInitiated)
    }
  }

  private var mirrorRefs: Map<String, String> = emptyMap()

  private fun refreshMirrorBranches(
    dir: File,
    localBranches: List<String>,
    currentBranch: String?,
    userInitiated: Boolean
  ) {
    val settings = service<MirrorSettingsService>().state
    if (settings.baseUrl.isBlank()) {
      finishBranchRefresh("Cache не настроен")
      if (userInitiated) notify("Укажите адрес сервера в настройках плагина.", NotificationType.WARNING)
      return
    }

    val repo = try { syncFacade.resolveRepo(dir, settings).sanitized } catch (_: Throwable) { "" }
    if (repo.isBlank()) {
      finishBranchRefresh("Не удалось определить репозиторий сервера")
      if (userInitiated) notify("Не удалось определить имя репозитория сервера.", NotificationType.WARNING)
      return
    }

    val requestGeneration = branchRefreshGeneration.incrementAndGet()
    setBranchRefreshInProgress(true)
    ApplicationManager.getApplication().executeOnPooledThread {
      val result = MirrorApi.getRefs(
        baseUrl = settings.baseUrl,
        apiKey = SecretsStore.mirrorApiKey,
        repo = repo,
        syncPassword = SecretsStore.syncPassword,
        insecureTls = settings.mirrorInsecureTls
      )

      UIUtil.invokeLaterIfNeeded {
        if (project.isDisposed || requestGeneration != branchRefreshGeneration.get()) return@invokeLaterIfNeeded
        if (result.code in 200..299 && result.refs != null) {
          mirrorRefs = result.refs.mapValues { it.value.sha }
          replaceBranchItems(localBranches, mirrorRefs, currentBranch)
          finishBranchRefresh("Cache: ${mirrorRefs.size} веток")
        } else {
          val detail = "Сервер не ответил: ${result.message.take(120)}"
          finishBranchRefresh(detail)
          if (userInitiated) notify(detail, NotificationType.WARNING)
        }
      }
    }
  }

  private fun setBranchRefreshInProgress(inProgress: Boolean) {
    branchRefreshInProgress = inProgress
    com.intellij.ide.ActivityTracker.getInstance().inc()
    if (inProgress) branchList.toolTipText = "Загружаем ветки с сервера…"
  }

  private fun finishBranchRefresh(detail: String) {
    setBranchRefreshInProgress(false)
    branchList.toolTipText = "Ветка для Отправить / Подтянуть; ★ есть только на Cache. $detail"
  }

  /** Re-apply the branch filter text to the list model. */
  private fun applyBranchFilter() {
    val filter = branchFilterField.text.trim().lowercase()
    val selectedName = selectedBranchChoice()?.name
    branchListModel.clear()
    val visible = if (filter.isBlank()) allBranchItems
                  else allBranchItems.filter { it.name.lowercase().contains(filter) }
    visible.forEach { branchListModel.addElement(it) }
    if (selectedName != null) {
      val idx = (0 until branchListModel.size()).indexOfFirst { branchListModel.getElementAt(it).name == selectedName }
      if (idx >= 0) branchList.selectedIndex = idx
    }
  }

  private fun replaceBranchItems(
    localBranches: List<String>,
    mirrorRefs: Map<String, String>,
    currentBranch: String?
  ) {
    val dir = baseDir() ?: return
    val selectedName = selectedBranchChoice()?.name

    val allNames = (localBranches.toSet() + mirrorRefs.keys).toSortedSet()
    val items = allNames.map { name ->
      val localHash = if (name in localBranches) GitLocal.branchHash(project, dir, name) else null
      val mirrorHash = mirrorRefs[name]
      val status = when {
        localHash == null -> BranchStatus.MIRROR_ONLY
        mirrorHash == null -> BranchStatus.LOCAL_ONLY
        localHash == mirrorHash -> BranchStatus.SYNCED
        GitLocal.isAncestor(project, dir, mirrorHash, localHash) -> BranchStatus.AHEAD
        GitLocal.isAncestor(project, dir, localHash, mirrorHash) -> BranchStatus.BEHIND
        else -> BranchStatus.AHEAD // ponytail: divergent → show as ahead
      }
      val aheadCount = if (status == BranchStatus.AHEAD && localHash != null && mirrorHash != null)
        runCatching { GitLocal.commitCount(project, dir, "$mirrorHash..$localHash") }.getOrNull()
      else null
      val behindCount = if (status == BranchStatus.BEHIND && localHash != null && mirrorHash != null)
        runCatching { GitLocal.commitCount(project, dir, "$localHash..$mirrorHash") }.getOrNull()
      else null
      BranchListItem(
        name = name,
        status = status,
        localHash = localHash,
        mirrorHash = mirrorHash,
        aheadCount = aheadCount,
        behindCount = behindCount,
        isCurrent = name == currentBranch
      )
    }

    val mrByBranch = runCatching {
      project.getService(MrReviewService::class.java).rowsBySourceBranch()
    }.getOrDefault(emptyMap())
    val itemsWithMr = items.map { item ->
      val mr = mrByBranch[item.name]
      item.copy(mrIid = mr?.iid, mrUnresolved = mr?.unresolved ?: 0)
    }

    allBranchItems = itemsWithMr

    val preferred = BranchSelectorModel.preferredSelection(selectedName, currentBranch,
      itemsWithMr.map { BranchChoice(it.name, it.localHash != null) })

    // Apply current filter (if any) before populating the model.
    val filter = branchFilterField.text.trim().lowercase()
    val visibleItems = if (filter.isBlank()) itemsWithMr else itemsWithMr.filter { it.name.lowercase().contains(filter) }

    branchListModel.clear()
    visibleItems.forEach { branchListModel.addElement(it) }
    if (preferred != null) {
      val idx = visibleItems.indexOfFirst { it.name == preferred }
      if (idx >= 0) branchList.selectedIndex = idx
    }
  }

  internal fun selectedBranchChoice(): BranchChoice? {
    val item = branchList.selectedValue ?: return null
    return BranchChoice(item.name, item.localHash != null)
  }

  /** Returns the raw branch name currently chosen in the selector. */
  internal fun selectedBranch(): String? {
    branchList.selectedValue?.name?.let { return it }
    val dir = baseDir() ?: return null
    return GitLocal.currentBranch(project, dir)
  }

  /**
   * Rebuild the action groups that feed the panel. [toolbarGroup] is the small
   * icon set actually rendered in the top toolbar; [mainGroup] is the full
   * list shown only in the overflow popup. Registered plugin.xml actions are
   * referenced by id so their localized presentations stay the single source
   * of truth; panel-only operations get thin wrappers.
   */
  internal fun rebuildGearMenu() {
    toolbarGroup.removeAll()
    toolbarGroup.add(panelAction(LocalGitMirrorBundle.message("panel.branch.send"), AllIcons.Actions.Upload) {
      sendSelectedBranches()
    })
    toolbarGroup.add(panelAction(LocalGitMirrorBundle.message("panel.branch.pull"), AllIcons.Actions.Download) {
      pullSelectedBranches()
    })
    toolbarGroup.addSeparator()
    toolbarGroup.add(refreshBranchesAction)
    toolbarGroup.add(panelAction(LocalGitMirrorBundle.message("toolwindow.menu.testMirror"), AllIcons.Actions.Checked) {
      testMirror()
    })
    toolbarGroup.addSeparator()
    toolbarGroup.add(panelAction(LocalGitMirrorBundle.message("toolwindow.menu.settings"), AllIcons.General.Settings) {
      ShowSettingsUtil.getInstance().showSettingsDialog(project, "localgitmirror.settings")
      refreshStatus()
    })
    toolbarGroup.add(overflowAction)

    mainGroup.removeAll()
    mainGroup.add(refreshBranchesAction)
    mainGroup.add(panelAction(LocalGitMirrorBundle.message("toolwindow.menu.testMirror"), AllIcons.Actions.Checked) {
      testMirror()
    })
    mainGroup.addSeparator()
    addRegisteredActions(
      "LocalGitMirror.SyncCurrentBranch",
      "LocalGitMirror.SyncBranch",
      "LocalGitMirror.SendSelectedCommits",
      "LocalGitMirror.PushAs",
      "LocalGitMirror.SendGitLabMr",
      "LocalGitMirror.SyncPull",
      "LocalGitMirror.PullBack",
      "LocalGitMirror.ApplyLocalSync"
    )
    mainGroup.addSeparator()
    addRegisteredActions(
      "LocalGitMirror.ManageBranches",
      "LocalGitMirror.PruneMirrorBranches"
    )
    mainGroup.addSeparator()
    addRegisteredActions(
      "LocalGitMirror.DepsRequest",
      "LocalGitMirror.DepsRespond",
      "LocalGitMirror.DepsApply",
      "LocalGitMirror.MirrorPublish"
    )
    mainGroup.addSeparator()
    addRegisteredActions(
      "LocalGitMirror.FileSendSelected",
      "LocalGitMirror.FileFetch"
    )
    mainGroup.addSeparator()
    addRegisteredActions(
      "LocalGitMirror.BufferSend",
      "LocalGitMirror.BufferPaste",
      "LocalGitMirror.BufferHistory"
    )
    mainGroup.addSeparator()
    addRegisteredActions(
      "LocalGitMirror.Preflight",
      "LocalGitMirror.VaultDiagnostics",
      "LocalGitMirror.DryRun",
      "LocalGitMirror.DryRunPull"
    )
    mainGroup.addSeparator()
    mainGroup.add(panelAction(LocalGitMirrorBundle.message("review.tab.open"), AllIcons.Actions.Show) { tabsPane?.selectedIndex = 1 })
    mainGroup.add(panelAction(LocalGitMirrorBundle.message("panel.menu.exportBundle"), AllIcons.Actions.Upload) { exportBundle() })
    mainGroup.add(panelAction(LocalGitMirrorBundle.message("panel.menu.importBundle"), AllIcons.Actions.Download) { importBundle() })
    mainGroup.add(panelAction(LocalGitMirrorBundle.message("toolwindow.menu.copyConfig"), AllIcons.Actions.Copy) { copyConfigLine() })
    mainGroup.add(panelAction(LocalGitMirrorBundle.message("toolwindow.menu.pasteConfig"), AllIcons.Actions.MenuPaste) { pasteConfigLine() })
    mainGroup.add(panelAction(LocalGitMirrorBundle.message("panel.menu.vaultSync"), AllIcons.Actions.Download) {
      localgitmirror.idea.deps.VaultCacheSync.syncInBackground(project, "manual")
    })
    mainGroup.add(panelAction(LocalGitMirrorBundle.message("toolwindow.menu.downloadPlugin"), AllIcons.Actions.Download) { downloadLatestPlugin() })
    mainGroup.addSeparator()
    mainGroup.add(panelAction(LocalGitMirrorBundle.message("toolwindow.menu.settings"), AllIcons.General.Settings) {
      ShowSettingsUtil.getInstance().showSettingsDialog(project, "localgitmirror.settings")
      refreshStatus()
    })
  }

  private fun addRegisteredActions(vararg ids: String) {
    val manager = ActionManager.getInstance()
    ids.forEach { id -> manager.getAction(id)?.let { mainGroup.add(it) } }
  }

  private fun panelAction(title: String, icon: Icon, action: () -> Unit): AnAction =
    object : AnAction(title, title, icon) {
      override fun actionPerformed(e: AnActionEvent) = action()
    }

  /** Show the unified group as an overflow popup under [anchor]. */
  internal fun showOverflowPopup(anchor: JComponent) {
    val dataContext = SimpleDataContext.getProjectContext(project)
    val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
      .createActionGroupPopup(
        null, mainGroup, dataContext,
        com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true
      )
    popup.show(com.intellij.ui.awt.RelativePoint.getSouthWestOf(anchor))
  }

  /** Trigger a registered plugin action by id, with project context. Notifies on failure. */
  private fun triggerLgmAction(id: String) {
    runCatching { runRegisteredAction(id) }
      .onFailure { notify("Не удалось запустить действие: ${it.message ?: it::class.simpleName}", NotificationType.ERROR) }
  }

  /** Rebuild action buttons (now just refreshes gear menu). */
  internal fun rebuildActions() {
    rebuildGearMenu()
    revalidate()
    repaint()
  }

  init {
    layout = BorderLayout()

    // Auto-refresh history on any new entry, regardless of which thread added it.
    // Registered once here so it survives rebuilds (setup → main UI transition).
    val listener: () -> Unit = {
      com.intellij.util.ui.UIUtil.invokeLaterIfNeeded { refreshHistoryLog() }
    }
    historyService.addChangeListener(listener)

    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isNotBlank() && SecretsStore.syncPassword.isNotBlank()) {
      buildMainUi()
    } else {
      buildSetupUi()
    }
  }

  /** Build the full main UI (toolbar + tabs + progress). Called on init and after successful setup. */
  private fun buildMainUi() {
    removeAll()

    branchList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    branchList.addMouseListener(object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) {
        if (e.isPopupTrigger) showBranchContextMenu(e)
      }
      override fun mouseReleased(e: MouseEvent) {
        if (e.isPopupTrigger) showBranchContextMenu(e)
      }
      override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount >= 2) {
          val item = branchList.selectedValue ?: return
          val iid = item.mrIid ?: return
          val row = project.getService(MrReviewService::class.java)
            .cachedRows().firstOrNull { it.iid == iid } ?: return
          openMrDialog(row)
        }
      }
    })
    branchList.fixedCellHeight = JBUI.scale(26)
    ListSpeedSearch.installOn(branchList)

    rebuildGearMenu()

    val root = SimpleToolWindowPanel(true, true)
    val toolbar = ActionManager.getInstance()
      .createActionToolbar("LocalGitMirrorPanel", toolbarGroup, true)
    toolbar.targetComponent = this
    root.setToolbar(toolbar.component)

    progressRow = buildProgressRow()

    val tabs = JBTabbedPane()
    tabs.addTab(LocalGitMirrorBundle.message("tab.branches"), buildBranchesTab())
    tabs.addTab(LocalGitMirrorBundle.message("tab.review"), buildReviewTab())
    tabs.addTab(LocalGitMirrorBundle.message("tab.deps"), buildDepsTab())
    tabs.addTab(LocalGitMirrorBundle.message("tab.exchange"), buildExchangeTab())
    for (i in 0 until tabs.tabCount) {
      tabs.setTabComponentAt(i, makeTabComponent(tabs, i))
    }
    tabs.addChangeListener { refreshTabStyles() }
    refreshTabStyles()
    tabs.addChangeListener { onTabChanged(tabs.selectedIndex) }
    tabsPane = tabs

    val center = JPanel(BorderLayout()).apply { isOpaque = false }
    center.add(progressRow, BorderLayout.NORTH)
    center.add(tabs, BorderLayout.CENTER)
    center.add(buildFooterRow(), BorderLayout.SOUTH)
    root.setContent(center)

    add(root, BorderLayout.CENTER)

    // Populate local branches immediately, then fetch mirror refs once so the
    // status glyphs (synced/ahead/behind/mirror-only) are meaningful on first
    // open instead of every row showing as LOCAL_ONLY.
    refreshBranchCombo(userInitiated = false, withMirror = true)
    refreshStatus()
    refreshReview()
    refreshHistoryLog()
  }

  private fun buildProgressRow(): JPanel = JPanel(BorderLayout()).apply {
    isOpaque = false
    isVisible = false
    border = JBUI.Borders.empty(2, 8)
    add(progressBar, BorderLayout.CENTER)
    val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
      isOpaque = false
      add(cancelButton)
      add(progressLabel)
    }
    add(right, BorderLayout.EAST)
  }

  private fun buildFooterRow(): JPanel = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(2, 8)
    add(JBLabel(LocalGitMirrorBundle.message("panel.footer.version", pluginVersionText)).apply {
      font = JBUI.Fonts.smallFont().deriveFont(Font.PLAIN, JBUI.scale(10f).toFloat())
      foreground = UIUtil.getContextHelpForeground()
    }, BorderLayout.WEST)
    add(roleBadge, BorderLayout.EAST)
  }

  private fun onTabChanged(index: Int) {
    if (project.isDisposed || ApplicationManager.getApplication().isDisposeInProgress) return
    when (index) {
      1 -> reloadReview(notify = false)
      2 -> refreshDepsInBackground()
      3 -> {
        refreshExchangeInBackground()
        startExchangePolling()
      }
    }
    if (index != 3) stopExchangePolling()
  }

  private fun startExchangePolling() {
    exchangePollAlarm.cancelAllRequests()
    exchangePollAlarm.addRequest({
      if (project.isDisposed) return@addRequest
      if (tabsPane?.selectedIndex == 3) {
        refreshExchangeInBackground()
        startExchangePolling()
      }
    }, EXCHANGE_POLL_MS)
  }

  private fun stopExchangePolling() = exchangePollAlarm.cancelAllRequests()

  override fun dispose() {
    exchangePollAlarm.cancelAllRequests()
  }

  private fun sectionHeader(text: String): JBLabel = JBLabel(text).apply {
    font = JBUI.Fonts.smallFont().asBold()
    foreground = UIUtil.getLabelForeground()
    border = JBUI.Borders.empty(8, 0, 4, 0)
    alignmentX = LEFT_ALIGNMENT
  }

  private fun buildBranchesTab(): JComponent {
    val statusRow = JPanel(BorderLayout()).apply { isOpaque = false }
    status.font = JBUI.Fonts.smallFont()
    status.foreground = UIUtil.getContextHelpForeground()
    statusDot.font = JBUI.Fonts.smallFont()
    val statusLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
      isOpaque = false
      add(statusDot)
      add(status)
    }
    statusRow.add(statusLeft, BorderLayout.WEST)

    val sectionRow = JPanel(BorderLayout()).apply { isOpaque = false }
    sectionRow.add(JBLabel(LocalGitMirrorBundle.message("panel.branch.section")).apply {
      font = JBUI.Fonts.smallFont()
      foreground = UIUtil.getContextHelpForeground()
    }, BorderLayout.WEST)
    sectionRow.add(JBLabel(LocalGitMirrorBundle.message("panel.branch.legend")).apply {
      font = JBUI.Fonts.smallFont().deriveFont(Font.PLAIN, JBUI.scale(10f).toFloat())
      foreground = JBColor(0x5C5F64, 0x5C5F64)
    }, BorderLayout.EAST)

    val searchRow = JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.empty(4, 0, 4, 0)
      add(branchFilterField, BorderLayout.CENTER)
    }

    val north = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      add(statusRow)
      add(searchRow)
      add(sectionRow)
    }

    val listScroll = JScrollPane(branchList).apply {
      border = BorderFactory.createEmptyBorder()
      viewportBorder = BorderFactory.createEmptyBorder()
    }

    val bottom = JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.empty(4, 0, 0, 0)
      val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        isOpaque = false
        add(primaryBtn(LocalGitMirrorBundle.message("panel.branch.send"), AllIcons.Actions.Upload) {
          sendSelectedBranches()
        })
        add(btn(LocalGitMirrorBundle.message("panel.branch.pull"), AllIcons.Actions.Download) {
          pullSelectedBranches()
        })
      }
      add(left, BorderLayout.WEST)
      add(btn(LocalGitMirrorBundle.message("panel.branch.delete"), AllIcons.Actions.GC) {
        deleteSelectedBranches()
      }, BorderLayout.EAST)
    }

    val top = JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.empty(4, 8)
      add(north, BorderLayout.NORTH)
      add(listScroll, BorderLayout.CENTER)
      add(bottom, BorderLayout.SOUTH)
    }

    val split = OnePixelSplitter(true, 0.72f)
    split.firstComponent = top
    split.secondComponent = buildHistoryPanel()
    return split
  }

  private fun buildHistoryPanel(): JComponent {
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
        historyService.clear()
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
      isOpaque = false
      add(header, BorderLayout.NORTH)
      add(historyScroll, BorderLayout.CENTER)
    }
  }

  private fun buildDepsBanner(): EditorNotificationPanel =
    EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
      isVisible = false
      createActionLabel(LocalGitMirrorBundle.message("panel.deps.banner.all")) {
        triggerLgmAction("LocalGitMirror.DepsRespond")
      }
    }

  private fun buildDepsTab(): JComponent {
    val banner = buildDepsBanner()
    depsBanner = banner

    val responsesPanel = EditorNotificationPanel(EditorNotificationPanel.Status.Info).apply {
      isVisible = false
      createActionLabel(LocalGitMirrorBundle.message("panel.deps.responses.apply")) {
        triggerLgmAction("LocalGitMirror.DepsApply")
      }
    }
    depsResponsesPanel = responsesPanel

    val banners = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      add(banner)
      add(responsesPanel)
    }

    val label = sectionHeader(LocalGitMirrorBundle.message("panel.deps.requests"))
    depsSectionLabel = label

    val center = JPanel(BorderLayout()).apply {
      isOpaque = false
      add(label, BorderLayout.NORTH)
      add(JScrollPane(depsList).apply {
        border = BorderFactory.createEmptyBorder()
        viewportBorder = BorderFactory.createEmptyBorder()
      }, BorderLayout.CENTER)
    }

    val bottom = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
      isOpaque = false
      border = JBUI.Borders.empty(4, 0, 0, 0)
    }
    val respondBtn = greenBtn(LocalGitMirrorBundle.message("deps.menu.respond")) {
      triggerLgmAction("LocalGitMirror.DepsRespond")
    }
    respondButton = respondBtn
    bottom.add(respondBtn)
    val requestBtn = btn(LocalGitMirrorBundle.message("deps.menu.request")) {
      triggerLgmAction("LocalGitMirror.DepsRequest")
    }
    requestDepsButton = requestBtn
    bottom.add(requestBtn)
    val applyBtn = btn(LocalGitMirrorBundle.message("deps.menu.apply")) {
      triggerLgmAction("LocalGitMirror.DepsApply")
    }
    applyDepsButton = applyBtn
    bottom.add(applyBtn)

    return JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.empty(4, 8)
      add(banners, BorderLayout.NORTH)
      add(center, BorderLayout.CENTER)
      add(bottom, BorderLayout.SOUTH)
    }
  }

  private fun buildExchangeTab(): JComponent {
    chatPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
      KeyStroke.getKeyStroke("control V"), "lgm.exchange.paste"
    )
    chatPanel.actionMap.put("lgm.exchange.paste", object : AbstractAction() {
      override fun actionPerformed(e: java.awt.event.ActionEvent?) = pasteClipboardToExchange()
    })
    val dropHandler = ChatTransferHandler(toComposer = false)
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
    composerField.transferHandler = ChatTransferHandler(toComposer = true)

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

  private fun showExchangeOverflow(anchor: JComponent) {
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

  private fun chooseAndUploadFiles() {
    val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
    val files = FileChooser.chooseFiles(descriptor, project, null)
    uploadFilesToPostbox(files.map { File(it.path) }.filter { it.isFile })
  }

  private fun clipboardImage(): Image? = try {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor))
      clipboard.getData(DataFlavor.imageFlavor) as? Image
    else null
  } catch (_: Throwable) {
    null
  }

  private fun buildReviewTab(): JComponent {
    val statusRow = JPanel(BorderLayout()).apply { isOpaque = false }
    statusRow.add(mrReviewStatus, BorderLayout.WEST)

    val searchRow = JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.empty(4, 0, 4, 0)
      add(mrReviewFilterField, BorderLayout.CENTER)
    }

    val headerRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
      isOpaque = false
      add(sectionHeader(LocalGitMirrorBundle.message("review.section")))
      add(JBLabel(LocalGitMirrorBundle.message("review.legend.unres")).apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor(0xB8860B, 0xE3AE4D)
      })
      add(JBLabel(LocalGitMirrorBundle.message("review.legend.ok")).apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor(0x2E7D32, 0x66BB6A)
      })
      add(JBLabel(LocalGitMirrorBundle.message("review.legend.multi")).apply {
        font = JBUI.Fonts.smallFont()
        foreground = JBColor(0x6F7277, 0x6F7277)
      })
    }

    val north = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      add(statusRow)
      add(searchRow)
      add(headerRow)
    }

    val listScroll = JScrollPane(mrReviewList).apply {
      border = BorderFactory.createEmptyBorder()
      viewportBorder = BorderFactory.createEmptyBorder()
    }

    val bottom = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
      isOpaque = false
      border = JBUI.Borders.empty(4, 0, 0, 0)
      add(primaryBtn(LocalGitMirrorBundle.message("review.open")) {
        mrReviewList.selectedValue?.let { openMrDialog(it) }
      })
      val fetchBtn = btn(LocalGitMirrorBundle.message("review.fetch"), AllIcons.Actions.Refresh) {
        reloadReview()
      }
      reviewFetchButton = fetchBtn
      add(fetchBtn)
      add(btn(LocalGitMirrorBundle.message("review.sendNotes"), AllIcons.Actions.Upload) {
        mrReviewList.selectedValue?.let { sendMrNotesToCache(it) }
      })
      add(btn(LocalGitMirrorBundle.message("review.sendSelected")) { sendSelectedMrs() })
      add(JButton(AllIcons.Actions.MenuSaveall).apply {
        margin = JBUI.insets(2, 4)
        isFocusPainted = false
        isBorderPainted = false
        isContentAreaFilled = false
        toolTipText = LocalGitMirrorBundle.message("review.saveAll")
        addActionListener { saveAllUnresolved() }
      })
    }

    return JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.empty(4, 8)
      add(north, BorderLayout.NORTH)
      add(listScroll, BorderLayout.CENTER)
      add(bottom, BorderLayout.SOUTH)
    }
  }
  
  /** Pull selected branches (multi-select support). Falls back to single-branch picker if nothing selected. */
  private fun pullSelectedBranches() {
    val selected = branchList.selectedValuesList
    if (selected.isEmpty()) {
      // No selection — use existing single-branch dialog
      pullFromMirror()
      return
    }
    if (selected.size == 1) {
      pullFromMirror(selected.first().name)
      return
    }
    // Multiple branches — pull each in sequence
    pullMultipleBranches(selected.map { it.name })
  }
  
  /** Send selected branches (multi-select support). Falls back to current branch if nothing selected. */
  private fun sendSelectedBranches() {
    val selected = branchList.selectedValuesList
    if (selected.isEmpty()) {
      syncCurrentBranch()
      return
    }
    if (selected.size == 1) {
      syncBranch(selected.first().name)
      return
    }
    syncMultipleBranches(selected.map { it.name })
  }
  
  /** Pull multiple branches in sequence. */
  private fun pullMultipleBranches(branches: List<String>) {
    if (isSyncing) {
      notify("Операция уже выполняется", NotificationType.WARNING)
      return
    }
    
    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Стягивание ${branches.size} веток", true) {
      override fun run(indicator: ProgressIndicator) {
        for ((index, branch) in branches.withIndex()) {
          indicator.checkCanceled()
          indicator.fraction = index.toDouble() / branches.size
          indicator.text = "Стягивание $branch (${index + 1}/${branches.size})"
          
          try {
            PullFromMirrorAction(preselectedBranch = branch).actionPerformed(
              com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                "MultiPull", null,
                com.intellij.openapi.actionSystem.DataContext { dataId ->
                  if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(dataId)) project else null
                }
              )
            )
          } catch (e: Exception) {
            notify("Ошибка стягивания $branch: ${e.message}", NotificationType.ERROR)
          }
        }
        
        notify("Стянуто ${branches.size} веток: ${branches.joinToString(", ")}", NotificationType.INFORMATION)
      }
      
      override fun onSuccess() {
        isSyncing = false
        refreshBranchCombo(withMirror = true)
      }

      override fun onThrowable(error: Throwable) {
        isSyncing = false
        notify("Ошибка: ${error.message}", NotificationType.ERROR)
      }
    })
  }

  /** Sync a specific branch to Mirror. */
  private fun syncBranch(branchName: String) {
    if (isSyncing) {
      notify("Операция уже выполняется", NotificationType.WARNING)
      return
    }
    
    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Отправка $branchName", true) {
      override fun run(indicator: ProgressIndicator) {
        val dir = baseDir() ?: run {
          notify("Проект не найден", NotificationType.ERROR)
          return
        }
        val settings = service<MirrorSettingsService>().state
        
        indicator.text = "Отправка $branchName"
        try {
          val result = syncFacade.runFullSync(dir, settings, additionalBranches = listOf(branchName))
          if (!result.step.ok) {
            notify("Ошибка отправки $branchName: ${result.step.message}", NotificationType.ERROR)
          } else {
            notify("Ветка $branchName отправлена", NotificationType.INFORMATION)
          }
        } catch (e: Exception) {
          notify("Ошибка отправки $branchName: ${e.message}", NotificationType.ERROR)
        }
      }
      
      override fun onSuccess() {
        isSyncing = false
        refreshBranchCombo(withMirror = true)
      }

      override fun onThrowable(error: Throwable) {
        isSyncing = false
        notify("Ошибка: ${error.message}", NotificationType.ERROR)
      }
    })
  }

  /** Send all selected branches (multi-select support). */
  private fun syncSelectedBranches() {
    val selected = branchList.selectedValuesList
    if (selected.isEmpty()) {
      notify("Выберите ветки для отправки", NotificationType.WARNING)
      return
    }
    val branchNames = selected.map { it.name }
    if (branchNames.size == 1) {
      syncCurrentBranch()
    } else {
      // Send multiple branches
      syncMultipleBranches(branchNames)
    }
  }
  
  /** Send multiple branches in sequence. */
  private fun syncMultipleBranches(branches: List<String>) {
    if (isSyncing) {
      notify("Операция уже выполняется", NotificationType.WARNING)
      return
    }
    
    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Отправка ${branches.size} веток", true) {
      override fun run(indicator: ProgressIndicator) {
        val dir = baseDir() ?: run {
          notify("Проект не найден", NotificationType.ERROR)
          return
        }
        val settings = service<MirrorSettingsService>().state
        
        for ((index, branch) in branches.withIndex()) {
          indicator.checkCanceled()
          indicator.fraction = index.toDouble() / branches.size
          indicator.text = "Отправка $branch (${index + 1}/${branches.size})"
          
          try {
            val result = syncFacade.runFullSync(dir, settings, additionalBranches = listOf(branch))
            if (!result.step.ok) {
              notify("Ошибка отправки $branch: ${result.step.message}", NotificationType.ERROR)
            }
          } catch (e: Exception) {
            notify("Ошибка отправки $branch: ${e.message}", NotificationType.ERROR)
          }
        }
        
        notify("Отправлено ${branches.size} веток: ${branches.joinToString(", ")}", NotificationType.INFORMATION)
      }

      override fun onSuccess() {
        isSyncing = false
        refreshBranchCombo(withMirror = true)
      }
      
      override fun onThrowable(error: Throwable) {
        isSyncing = false
        notify("Ошибка: ${error.message}", NotificationType.ERROR)
      }
    })
  }
  
  /** Export bundle for offline transfer. */
  private fun exportBundle() {
    val dir = baseDir() ?: run {
      notify("Проект не найден", NotificationType.ERROR)
      return
    }
    
    val selected = branchList.selectedValuesList
    if (selected.isEmpty()) {
      notify("Выберите ветки для экспорта", NotificationType.WARNING)
      return
    }
    
    val branchNames = selected.map { it.name }
    
    // Ask user where to save
    val fileChooser = JFileChooser().apply {
      dialogTitle = "Сохранить bundle файл"
      selectedFile = File("${project.name}-${branchNames.joinToString("-")}.bundle")
      fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Git Bundle", "bundle")
    }
    
    if (fileChooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return
    }
    
    val targetFile = fileChooser.selectedFile
    
    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Экспорт bundle", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.text = "Создание bundle для ${branchNames.joinToString(", ")}"
        
        try {
          // Use git bundle create
          val refs = branchNames.map { "refs/heads/$it" }
          val cmd = mutableListOf("git", "bundle", "create", targetFile.absolutePath) + refs
          
          val proc = ProcessBuilder(cmd)
            .directory(dir)
            .redirectErrorStream(false)
            .start()
          
          val exitCode = proc.waitFor()
          val stderr = proc.errorStream.bufferedReader().readText()
          
          if (exitCode != 0) {
            notify("Ошибка экспорта: $stderr", NotificationType.ERROR)
            historyService.add(LocalGitMirrorBundle.message("history.op.exportBundle"), false,
              "branches=${branchNames.joinToString(",")} err=${stderr.take(300)}")
          } else {
            notify("Bundle сохранён: ${targetFile.absolutePath}\nРазмер: ${targetFile.length() / 1024} KB", NotificationType.INFORMATION)
            historyService.add(LocalGitMirrorBundle.message("history.op.exportBundle"), true,
              "branches=${branchNames.joinToString(",")} size=${targetFile.length() / 1024}KB -> ${targetFile.absolutePath}")
          }
        } catch (e: Exception) {
          notify("Ошибка экспорта: ${e.message}", NotificationType.ERROR)
          historyService.add(LocalGitMirrorBundle.message("history.op.exportBundle"), false,
            "branches=${branchNames.joinToString(",")} err=${e.message?.take(300)}")
        }
      }
      
      override fun onSuccess() {
        isSyncing = false
      }
      
      override fun onThrowable(error: Throwable) {
        isSyncing = false
        notify("Ошибка: ${error.message}", NotificationType.ERROR)
      }
    })
  }
  
  /** Import bundle file. */
  private fun importBundle() {
    val dir = baseDir() ?: run {
      notify("Проект не найден", NotificationType.ERROR)
      return
    }
    
    // Ask user to select bundle file
    val fileChooser = JFileChooser().apply {
      dialogTitle = "Выберите bundle файл"
      fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Git Bundle", "bundle")
    }
    
    if (fileChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return
    }
    
    val bundleFile = fileChooser.selectedFile
    
    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Импорт bundle", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.text = "Импорт ${bundleFile.name}"
        
        try {
          // Use git bundle verify first
          val verifyProc = ProcessBuilder("git", "bundle", "verify", bundleFile.absolutePath)
            .directory(dir)
            .redirectErrorStream(false)
            .start()
          
          val verifyExit = verifyProc.waitFor()
          val verifyOutput = verifyProc.inputStream.bufferedReader().readText()
          
          if (verifyExit != 0) {
            notify("Bundle невалиден: $verifyOutput", NotificationType.ERROR)
            historyService.add(LocalGitMirrorBundle.message("history.op.importBundle"), false,
              "file=${bundleFile.name} verify failed: ${verifyOutput.take(300)}")
            return
          }

          // Extract branch names from verify output
          val branches = verifyOutput.lines()
            .filter { it.contains("refs/heads/") }
            .map { it.substringAfter("refs/heads/").trim() }

          // Use git fetch to import
          val fetchProc = ProcessBuilder("git", "fetch", bundleFile.absolutePath)
            .directory(dir)
            .redirectErrorStream(false)
            .start()

          val fetchExit = fetchProc.waitFor()
          val fetchStderr = fetchProc.errorStream.bufferedReader().readText()

          if (fetchExit != 0) {
            notify("Ошибка импорта: $fetchStderr", NotificationType.ERROR)
            historyService.add(LocalGitMirrorBundle.message("history.op.importBundle"), false,
              "file=${bundleFile.name} fetch err=${fetchStderr.take(300)}")
          } else {
            notify("Импортировано ${branches.size} веток: ${branches.joinToString(", ")}", NotificationType.INFORMATION)
            historyService.add(LocalGitMirrorBundle.message("history.op.importBundle"), true,
              "file=${bundleFile.name} branches=${branches.joinToString(",")}")
          }
        } catch (e: Exception) {
          notify("Ошибка импорта: ${e.message}", NotificationType.ERROR)
          historyService.add(LocalGitMirrorBundle.message("history.op.importBundle"), false,
            "file=${bundleFile.name} err=${e.message?.take(300)}")
        }
      }

      override fun onSuccess() {
        isSyncing = false
        refreshBranchCombo(withMirror = true)
      }
      
      override fun onThrowable(error: Throwable) {
        isSyncing = false
        notify("Ошибка: ${error.message}", NotificationType.ERROR)
      }
    })
  }
  
  /** Delete selected branches (locally and on Mirror). */
  private fun deleteSelectedBranches() {
    val selected = branchList.selectedValuesList
    if (selected.isEmpty()) {
      notify("Выберите ветки для удаления", NotificationType.WARNING)
      return
    }
    
    val branchNames = selected.map { it.name }
    val currentBranch = GitLocal.currentBranch(project, baseDir() ?: return)
    
    // Don't allow deleting current branch
    if (branchNames.contains(currentBranch)) {
      notify("Нельзя удалить текущую ветку '$currentBranch'", NotificationType.WARNING)
      return
    }
    
    val confirm = Messages.showYesNoDialog(
      project,
      "Удалить ${branchNames.size} веток локально и на Cache?\n\n${branchNames.joinToString("\n")}",
      "Подтверждение удаления",
      "Удалить",
      "Отмена",
      Messages.getWarningIcon()
    )
    
    if (confirm != Messages.YES) return
    
    val dir = baseDir() ?: return
    val settings = service<MirrorSettingsService>().state
    val repo = syncFacade.resolveRepo(dir, settings).sanitized
    
    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Удаление веток", true) {
      override fun run(indicator: ProgressIndicator) {
        val deleted = mutableListOf<String>()
        val errors = mutableListOf<String>()
        
        for (branch in branchNames) {
          indicator.checkCanceled()
          indicator.text = "Удаление $branch"
          
          // Delete locally
          val localResult = GitLocal.deleteLocalBranch(project, dir, branch, force = true)
          if (!localResult.ok()) {
            errors.add("$branch (локально): ${localResult.stderr}")
          }
          
          // Delete on Mirror
          val mirrorResult = MirrorApi.deleteRef(
            baseUrl = settings.baseUrl,
            apiKey = SecretsStore.mirrorApiKey,
            repo = repo,
            branch = branch,
            syncPassword = SecretsStore.syncPassword,
            insecureTls = settings.mirrorInsecureTls
          )
          if (mirrorResult.code !in 200..299) {
            errors.add("$branch (Cache): ${mirrorResult.body.take(100)}")
          }
          
          if (localResult.ok() || mirrorResult.code in 200..299) {
            deleted.add(branch)
          }
        }
        
        if (deleted.isNotEmpty()) {
          notify("Удалено ${deleted.size} веток: ${deleted.joinToString(", ")}", NotificationType.INFORMATION)
        }
        if (errors.isNotEmpty()) {
          notify("Ошибки: ${errors.joinToString("; ")}", NotificationType.WARNING)
        }
      }

      override fun onSuccess() {
        isSyncing = false
        refreshBranchCombo(withMirror = true)
      }
      
      override fun onThrowable(error: Throwable) {
        isSyncing = false
        notify("Ошибка: ${error.message}", NotificationType.ERROR)
      }
    })
  }

  /** Build the Quick Setup form shown when Mirror is not configured. */
  private fun buildSetupUi() {
    val form = panel {
      row {
        label("🔗 DocCache").bold()
      }

      row {
        label("URL сервера")
      }
      row {
        textField()
          .bindText(::setupUrl)
          .resizableColumn()
        button("🔍 Найти") { onDiscoverSetup() }
          .gap(RightGap.SMALL)
      }
      
      row {
        label("API Key")
      }
      row {
        passwordField()
          .bindText(::setupApiKey)
          .resizableColumn()
      }
      
      row {
        label("Sync Password")
      }
      row {
        passwordField()
          .bindText(::setupSyncPassword)
          .resizableColumn()
      }
      
      row {
        button("Подключиться") { onConnectSetup() }
          .applyToComponent {
            putClientProperty("JButton.buttonType", "default")
            font = font.deriveFont(Font.BOLD)
          }
      }
    }
    form.border = JBUI.Borders.empty(JBUI.scale(12), JBUI.scale(16))
    setupFormPanel = form
    add(form, BorderLayout.NORTH)
  }

  // ── Setup form actions ──

  private fun onDiscoverSetup() {
    Thread({
      val servers = try {
        LanDiscovery.discover(timeoutMs = 6000, authPassword = SecretsStore.syncPassword)
      } catch (_: Exception) {
        emptyList()
      }
      SwingUtilities.invokeLater {
        when {
          servers.isEmpty() -> {
            Messages.showInfoMessage(
              "Серверы не найдены в локальной сети.\nПроверьте, что сервер запущен и доступен.",
              "Поиск сервера"
            )
          }
          servers.size == 1 -> {
            setupUrl = servers.first().toUrl()
            setupFormPanel?.reset()
          }
          else -> {
            val options = servers.map { "${it.toUrl()} (${it.ip})" }.toTypedArray()
            val chosen = Messages.showEditableChooseDialog(
              "Найдено несколько серверов. Выберите:",
              "Поиск сервера",
              null, options, options.first(), null
            )
            if (chosen != null) {
              val idx = options.indexOf(chosen)
              if (idx >= 0) {
                setupUrl = servers[idx].toUrl()
                setupFormPanel?.reset()
              }
            }
          }
        }
      }
    }, "LAN-Discovery").apply { isDaemon = true }.start()
  }

  private fun onConnectSetup() {
    val url = setupUrl.trim().let {
      if (it.isBlank()) return
      if (it.startsWith("http://") || it.startsWith("https://")) it.trimEnd('/')
      else "https://${it.trimEnd('/')}"
    }
    if (setupSyncPassword.isBlank()) {
      notify("Введите пароль синхронизации.", NotificationType.WARNING)
      return
    }

    val s = service<MirrorSettingsService>().state
    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Проверка подключения", true) {
      override fun run(indicator: ProgressIndicator) {
        currentIndicator = indicator
        try {
          indicator.text = "Проверяем подключение к серверу…"
          val probe = HandshakeCache.passwordProbe(
            baseUrl = url,
            apiKey = setupApiKey,
            syncPassword = setupSyncPassword,
            insecureTls = s.mirrorInsecureTls
          )
          if (probe.code !in 200..299) {
            val msg = if (probe.code == 0)
              "Сервер недоступен: ${probe.message}"
            else
              "Ошибка подключения (HTTP ${probe.code}): ${probe.message.take(200)}"
            notify(msg, NotificationType.ERROR)
            return
          }

          // Save settings
          s.baseUrl = url
          SecretsStore.mirrorApiKey = setupApiKey
          SecretsStore.syncPassword = setupSyncPassword

          notify("Подключение к серверу установлено.", NotificationType.INFORMATION)

          // Switch to main UI
          UIUtil.invokeLaterIfNeeded {
            removeAll()
            buildMainUi()
            revalidate()
            repaint()
          }
        } finally {
          isSyncing = false
        }
      }

      override fun onFinished() {
        isSyncing = false
      }

      override fun onCancel() {
        isSyncing = false
      }
    })
  }

  internal fun baseDir(): File? {
    val basePath = project.basePath ?: return null
    if (basePath.isBlank()) return null
    return File(basePath)
  }

  internal fun append(line: String) {
    // Diagnostic output is now captured via OperationsHistoryService entries.
  }

  private fun openMrDialog(row: MrReviewService.MrRowItem) {
    MrNotesDialog(project, row).show()
  }

  /** Send one MR's discussions to the Cache file postbox (no branch sync). */
  private fun sendMrNotesToCache(row: MrReviewService.MrRowItem) {
    val dir = baseDir() ?: run {
      notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.ERROR)
      return
    }
    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: send MR notes", true) {
      override fun run(indicator: ProgressIndicator) {
        val repo = try { syncFacade.resolveRepo(dir, s).sanitized } catch (_: Throwable) { "" }
        if (repo.isBlank()) {
          notify(LocalGitMirrorBundle.message("filesync.notify.repoMissing"), NotificationType.WARNING)
          return
        }
        val markdown = MrNotesWriter.renderMarkdown(
          project, row.iid, row.title, row.sourceBranch, row.updatedAt,
          row.unresolved, row.totalThreads, row.discussions
        )
        val plain = File.createTempFile("tmp-mrnotes-up-", ".md")
        val enc = File.createTempFile("tmp-mrnotes-enc-", ".bin")
        try {
          plain.writeText(markdown, Charsets.UTF_8)
          localgitmirror.idea.workkit.RepoFileSyncCrypto.encryptFile(plain, enc, SecretsStore.syncPassword, null)
          val pathEnc = localgitmirror.idea.workkit.ExchangeCrypto.encryptHint(
            "mr-notes/mr-!${row.iid}.md", SecretsStore.syncPassword
          )
          val up = MirrorApi.fileSyncUpload(
            s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls,
            "x/${java.util.UUID.randomUUID().toString().take(8)}", 0L, enc, pathEnc, null
          )
          if (up.code in 200..299) {
            notify(LocalGitMirrorBundle.message("review.sendNotes.ok", row.iid), NotificationType.INFORMATION)
            historyService.add(LocalGitMirrorBundle.message("mrnotes.history.send"), true, "mr=!${row.iid} repo=$repo")
          } else {
            notify(LocalGitMirrorBundle.message("review.sendNotes.fail", row.iid, "HTTP ${up.code}"), NotificationType.ERROR)
            historyService.add(LocalGitMirrorBundle.message("mrnotes.history.send"), false, "mr=!${row.iid} HTTP ${up.code}")
          }
        } finally {
          runCatching { plain.delete() }
          runCatching { enc.delete() }
        }
      }
    })
  }

  /** Send selected MRs (branches + notes) to Cache in one batch via GitLabMrSender.sendAll. */
  private fun sendSelectedMrs() {
    val rows = mrReviewList.selectedValuesList
      .filter { it.source == MrReviewService.Source.GITLAB && it.sourceBranch.isNotBlank() }
    if (rows.isEmpty()) {
      notify(LocalGitMirrorBundle.message("review.sendSelected.none"), NotificationType.WARNING)
      return
    }
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: send ${rows.size} MR(s)", true) {
      override fun run(indicator: ProgressIndicator) {
        val conf = GitLabConfig.resolve(project)
        GitLabMrSender.sendAll(project, conf, rows.map { it.sourceBranch to it.iid })
      }
    })
  }

  private fun refreshReview() {
    val service = project.getService(MrReviewService::class.java)
    val rows = service.cachedRows()
    val sorted = rows.sortedWith(
      compareByDescending<MrReviewService.MrRowItem> { it.unresolved }
        .thenByDescending { it.updatedAt }
    )
    val filter = mrReviewFilterField.text.trim().lowercase()
    val visible = if (filter.isBlank()) sorted
                  else sorted.filter {
                    it.title.lowercase().contains(filter) ||
                    it.sourceBranch.lowercase().contains(filter)
                  }
    mrReviewListModel.clear()
    visible.forEach { mrReviewListModel.addElement(it) }

    val attention = rows.count { it.unresolved > 0 }
    mrReviewStatus.text = if (attention > 0)
      LocalGitMirrorBundle.message("review.status.attention", attention, rows.size)
    else
      LocalGitMirrorBundle.message("review.status.all", rows.size)

    updateReviewTabTitle()

    val hasGitlab = runCatching {
      val conf = localgitmirror.idea.gitlab.GitLabConfig.resolve(project)
      conf.url.isNotBlank() && conf.project.isNotBlank() &&
        localgitmirror.idea.gitlab.GitLabConfig.hasApi(conf)
    }.getOrDefault(false)
    reviewFetchButton?.text = LocalGitMirrorBundle.message(
      if (hasGitlab) "review.fetch" else "review.fetch.cache"
    )
  }

  private fun reloadReview(notify: Boolean = true) {
    project.getService(MrReviewService::class.java).refreshInBackground(notify = notify) {
      if (project.isDisposed) return@refreshInBackground
      refreshReview()
    }
  }

  private fun updateReviewTabTitle() {
    val attn = project.getService(MrReviewService::class.java)
      .cachedRows().count { it.unresolved > 0 }
    tabsPane?.let { tabs ->
      if (tabs.tabCount > 1) {
        setTabTitle(1, if (attn > 0)
          LocalGitMirrorBundle.message("tab.review.count", attn)
        else
          LocalGitMirrorBundle.message("tab.review"))
      }
    }
  }

  private fun saveAllUnresolved() {
    val service = project.getService(MrReviewService::class.java)
    val rows = service.cachedRows().filter { it.unresolved > 0 }
    if (rows.isEmpty()) {
      notify(LocalGitMirrorBundle.message("review.save.ok", 0), NotificationType.INFORMATION)
      return
    }
    rows.forEach { MrNotesWriter.writeForAgent(project, it) }
    MrNotesWriter.writeIndex(project, service.cachedRows())
    notify(LocalGitMirrorBundle.message("review.save.ok", rows.size), NotificationType.INFORMATION)
  }

  private fun saveMrForBranch(sel: BranchListItem) {
    val iid = sel.mrIid ?: return
    val service = project.getService(MrReviewService::class.java)
    val row = service.cachedRows().firstOrNull { it.iid == iid } ?: return
    MrNotesWriter.writeForAgent(project, row)
    MrNotesWriter.writeIndex(project, service.cachedRows())
    notify(LocalGitMirrorBundle.message("review.save.ok", 1), NotificationType.INFORMATION)
  }

  private fun showBranchContextMenu(e: MouseEvent) {
    val idx = branchList.locationToIndex(e.point)
    if (idx < 0 || idx >= branchListModel.size()) return
    if (!branchList.isSelectedIndex(idx)) {
      branchList.selectedIndex = idx
    }
    val sel = branchListModel.getElementAt(idx)
    val popup = JPopupMenu()
    popup.add(JMenuItem(LocalGitMirrorBundle.message("panel.branch.send")).apply {
      addActionListener { sendSelectedBranches() }
    })
    popup.add(JMenuItem(LocalGitMirrorBundle.message("panel.branch.pull")).apply {
      addActionListener { pullSelectedBranches() }
    })
    popup.addSeparator()
    val iid = sel.mrIid
    if (iid != null) {
      popup.add(JMenuItem(LocalGitMirrorBundle.message("ctx.mr.caption", iid)).apply {
        isEnabled = false
      })
      popup.add(JMenuItem(LocalGitMirrorBundle.message("ctx.mr.open")).apply {
        addActionListener {
          val row = project.getService(MrReviewService::class.java)
            .cachedRows().firstOrNull { it.iid == iid } ?: return@addActionListener
          openMrDialog(row)
        }
      })
      popup.add(JMenuItem(LocalGitMirrorBundle.message("ctx.mr.fetch")).apply {
        addActionListener { reloadReview() }
      })
      popup.add(JMenuItem(LocalGitMirrorBundle.message("ctx.mr.save")).apply {
        addActionListener { saveMrForBranch(sel) }
      })
      popup.add(JMenuItem(LocalGitMirrorBundle.message("review.sendNotes")).apply {
        addActionListener {
          val row = project.getService(MrReviewService::class.java)
            .cachedRows().firstOrNull { it.iid == iid } ?: return@addActionListener
          sendMrNotesToCache(row)
        }
      })
    } else {
      popup.add(JMenuItem(LocalGitMirrorBundle.message("ctx.mr.none")).apply {
        isEnabled = false
      })
    }
    popup.addSeparator()
    popup.add(JMenuItem(LocalGitMirrorBundle.message("panel.branch.delete")).apply {
      addActionListener { deleteSelectedBranches() }
    })
    popup.show(branchList, e.x, e.y)
  }

  internal fun notify(message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("DocCache")
      .createNotification(message, type)
      .notify(project)
  }

  /** Count local branches that are ahead of their tracking branch. */
  private fun countDivergedBranches(): Int {
    val dir = baseDir() ?: return 0
    val res = GitLocal.run(project, dir, 10L, "for-each-ref", "--format=%(upstream:track)", "refs/heads")
    if (!res.ok()) return 0
    return res.stdout.lines().count { it.trimStart().startsWith("[ahead") }
  }

  internal fun refreshStatus() {
    val dir = baseDir()
    if (dir == null) {
      status.text = LocalGitMirrorBundle.message("notify.projectDir.missing")
      return
    }
    val s = service<MirrorSettingsService>().state
    val connected = s.baseUrl.isNotBlank() && SecretsStore.syncPassword.isNotBlank()
    val machineRole = localgitmirror.idea.deps.RoleDetector.detect(s)
    val divergedCount = countDivergedBranches()

    // Resolve Mirror repo for tooltip (single source of truth)
    val repoRes = try { syncFacade.resolveRepo(dir, s) } catch (_: Throwable) { null }

    var branchCount = 0
    if (connected) {
      branchCount = GitLocal.listBranches(project, dir).size
      val branchesWord = LocalGitMirrorBundle.message("status.branches", branchCount)
      val arrow = if (divergedCount > 0) " \u2191" else ""
      status.text = LocalGitMirrorBundle.message("panel.status.connected") +
        " \u00b7 $branchCount $branchesWord$arrow"
    } else {
      status.text = LocalGitMirrorBundle.message("panel.status.disconnected")
    }
    roleBadge.text = if (machineRole == localgitmirror.idea.deps.MachineRole.WORK)
      LocalGitMirrorBundle.message("panel.role.work")
    else
      LocalGitMirrorBundle.message("panel.role.home")
    statusDot.foreground = if (connected) JBColor(0x5FAD65, 0x5FAD65) else JBColor.GRAY
    roleBadge.status = if (machineRole == localgitmirror.idea.deps.MachineRole.WORK)
      BadgeLabel.Status.WARNING else BadgeLabel.Status.GOOD

    val pending = localgitmirror.idea.deps.RespondDepsAction.lastKnownPendingCount.get()
    val responses = localgitmirror.idea.deps.ApplyDepsAction.lastKnownResponseCount.get()
    val isWork = machineRole == localgitmirror.idea.deps.MachineRole.WORK
    respondButton?.text = if (pending > 0)
      LocalGitMirrorBundle.message("panel.deps.respond.count", pending)
    else
      LocalGitMirrorBundle.message("panel.deps.respond")
    respondButton?.isVisible = isWork
    requestDepsButton?.isVisible = !isWork
    applyDepsButton?.isVisible = !isWork
    depsSectionLabel?.text = if (isWork)
      LocalGitMirrorBundle.message("panel.deps.requests")
    else
      LocalGitMirrorBundle.message("panel.deps.requests.home")
    depsList.emptyText.clear()
    depsList.emptyText.text = if (isWork)
      LocalGitMirrorBundle.message("panel.deps.empty")
    else
      LocalGitMirrorBundle.message("panel.deps.empty.home")
    depsList.emptyText.appendLine(
      LocalGitMirrorBundle.message("panel.deps.empty.refresh"),
      SimpleTextAttributes.LINK_ATTRIBUTES
    ) { refreshDepsInBackground() }
    val showBanner = connected && isWork && pending > 0
    depsBanner?.isVisible = showBanner
    if (showBanner) {
      depsBanner?.text = LocalGitMirrorBundle.message("panel.deps.banner", pending)
    }
    val showResponses = connected && !isWork && responses > 0
    depsResponsesPanel?.isVisible = showResponses
    if (showResponses) {
      depsResponsesPanel?.text = LocalGitMirrorBundle.message("panel.deps.responses", responses)
    }

    val actionable = if (isWork) pending else responses
    tabsPane?.let { tabs ->
      if (tabs.tabCount > 0) {
        setTabTitle(0, LocalGitMirrorBundle.message("tab.branches.count", branchCount))
      }
      if (tabs.tabCount > 2) {
        setTabTitle(2, if (actionable > 0)
          LocalGitMirrorBundle.message("tab.deps.count", actionable)
        else
          LocalGitMirrorBundle.message("tab.deps"))
      }
    }
    updateReviewTabTitle()

    status.toolTipText = repoRes?.let {
      "Repo \u00b7 source: ${it.source.name.lowercase().replace('_', ' ')}"
    }

    rebuildActions()
    refreshBranchCombo()
    mirrorBadge.isVisible = false
    lastSyncBadge.isVisible = false
  }

  /** Fetch pending deps requests (WORK role) off the EDT and refresh the banner/counters. */
  internal fun refreshDepsInBackground() {
    if (project.isDisposed || ApplicationManager.getApplication().isDisposeInProgress) return
    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) return
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: deps", true) {
      override fun run(indicator: ProgressIndicator) {
        val dir = baseDir() ?: return
        val repo = try { syncFacade.resolveRepo(dir, s).sanitized } catch (_: Throwable) { "" }
        if (repo.isBlank()) return
        indicator.checkCanceled()
        val pending = MirrorApi.depsPending(
          baseUrl = s.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repo,
          insecureTls = s.mirrorInsecureTls
        )
        val role = localgitmirror.idea.deps.RoleDetector.detect(s)
        indicator.checkCanceled()
        val responses = if (role == localgitmirror.idea.deps.MachineRole.HOME)
          MirrorApi.depsResponses(
            baseUrl = s.baseUrl,
            apiKey = SecretsStore.mirrorApiKey,
            repo = repo,
            insecureTls = s.mirrorInsecureTls
          )
        else null
        UIUtil.invokeLaterIfNeeded {
          if (project.isDisposed) return@invokeLaterIfNeeded
          if (pending.code in 200..299) {
            localgitmirror.idea.deps.RespondDepsAction.lastKnownPendingCount.set(pending.items.size)
            depsListModel.clear()
            pending.items.forEachIndexed { idx, item ->
              depsListModel.addElement(
                LocalGitMirrorBundle.message(
                  "panel.deps.row",
                  idx + 1,
                  formatSize(item.size),
                  formatBufferTs(item.mtime.toDouble())
                )
              )
            }
          }
          if (responses != null && responses.code in 200..299) {
            localgitmirror.idea.deps.ApplyDepsAction.lastKnownResponseCount.set(responses.items.size)
          }
          refreshStatus()
        }
      }
    })
  }

  /** Fetch buffer entries + repo postbox files off the EDT into the chat timeline. */
  internal fun refreshExchangeInBackground() {
    if (project.isDisposed || ApplicationManager.getApplication().isDisposeInProgress) return
    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) return
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: exchange", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.checkCanceled()
        val pwd = SecretsStore.syncPassword
        val buffer = MirrorApi.bufferList(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls)
        val dir = baseDir()
        val repo = if (dir != null)
          try { syncFacade.resolveRepo(dir, s).sanitized } catch (_: Throwable) { "" }
        else ""
        indicator.checkCanceled()
        val files = if (repo.isNotBlank())
          MirrorApi.fileSyncList(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls)
        else null

        val items = mutableListOf<ExchangeItem>()
        if (buffer.code in 200..299) {
          for (b in buffer.items) {
            val plain = if (b.hintEnc.isNotBlank()) decryptExchangeHint(b.hintEnc, pwd, b.hint) else b.hint
            val meta = ExchangeMeta.parseHint(plain)
            val text = meta.text.ifBlank { b.hint }.ifBlank { EMPTY_HINT_MARK }
            items.add(
              ExchangeItem(
                ExchangeItem.Kind.BUFFER, b.id, b.ts, b.size, text, b.pinned, "", "",
                sideOf(meta.side)
              )
            )
          }
        }
        if (files != null && files.code in 200..299) {
          for (f in files.items) {
            val plain = if (f.pathEnc.isNotBlank()) decryptExchangeHint(f.pathEnc, pwd, f.path) else f.path
            val meta = ExchangeMeta.parseName(plain)
            val display = meta.text.ifBlank { f.path }
            items.add(
              ExchangeItem(
                ExchangeItem.Kind.FILE, f.id, f.mtime.toDouble(), f.size,
                display.substringAfterLast('/').ifBlank { display }, false, display, repo,
                sideOf(meta.side)
              )
            )
          }
        }
        val bufferOk = buffer.code in 200..299
        val bufferCode = buffer.code

        UIUtil.invokeLaterIfNeeded {
          if (project.isDisposed) return@invokeLaterIfNeeded
          allServerItems = items.sortedBy { it.ts }
          chatEmptyMessage = if (bufferOk)
            LocalGitMirrorBundle.message("panel.exchange.empty")
          else
            LocalGitMirrorBundle.message("panel.exchange.buffer.listFail", bufferCode)
          renderChat()
          allServerItems.filter { it.isImage && !it.isEcho }.forEach { queueThumbPrefetch(it) }
        }
      }
    })
  }

  private fun sideOf(marker: String?): ExchangeItem.Side = when (marker) {
    ExchangeMeta.SIDE_PLUGIN -> ExchangeItem.Side.WORK
    ExchangeMeta.SIDE_WEB -> ExchangeItem.Side.HOME
    else -> ExchangeItem.Side.UNKNOWN
  }

  private fun decryptExchangeHint(enc: String, pwd: String, fallback: String): String =
    exchangeHintCache.getOrPut(enc) {
      runCatching { ExchangeCrypto.decryptHint(enc, pwd) }.getOrDefault(fallback)
    }

  // ── Chat rendering ──

  private fun chatMessages(): List<ExchangeItem> {
    val echoed = echoIdByServerId.keys
    return (allServerItems.filterNot { it.id in echoed } + chatEchoes).sortedBy { it.ts }
  }

  private fun renderChat(scrollToBottom: Boolean = false) {
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
        alignmentX = CENTER_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
      })
    }
    for (m in messages) chatPanel.add(buildBubbleRow(m))
    chatPanel.revalidate()
    chatPanel.repaint()
    if (stick) scrollChatToBottom()
  }

  private fun isChatAtBottom(): Boolean {
    val vp = chatScroll.viewport ?: return true
    val extent = vp.extentSize.height
    val content = vp.viewSize.height
    return content <= extent || vp.viewPosition.y >= content - extent - JBUI.scale(48)
  }

  private fun scrollChatToBottom() {
    SwingUtilities.invokeLater {
      if (project.isDisposed) return@invokeLater
      val bar = chatScroll.verticalScrollBar
      bar.value = bar.maximum
    }
  }

  private fun buildBubbleRow(item: ExchangeItem): JComponent {
    val row = WrapMaxPanel()
    row.layout = BorderLayout()
    row.isOpaque = false
    row.border = JBUI.Borders.empty(2, 2)
    val bubble = buildBubble(item)
    val own = item.side == ExchangeItem.Side.WORK
    row.add(bubble, if (own) BorderLayout.EAST else BorderLayout.WEST)
    row.alignmentX = LEFT_ALIGNMENT
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

  private fun bubbleTint(item: ExchangeItem): JBColor = when {
    item.state == ExchangeItem.State.FAILED -> JBColor(0xF2DCDC, 0x563A3A)
    item.side == ExchangeItem.Side.WORK -> JBColor(0xDBEAF7, 0x2E4356)
    item.side == ExchangeItem.Side.HOME -> JBColor(0xF2F3F5, 0x434547)
    else -> JBColor(0xE8E9EB, 0x383A3C)
  }

  private inner class BubblePanel(private val tint: Color) : JPanel(BorderLayout()) {
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

  private fun buildBubble(item: ExchangeItem): JComponent {
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
  private fun bubbleCopyRow(item: ExchangeItem): JComponent {
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
  private fun copyBubbleText(item: ExchangeItem, button: JButton?) {
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

  private fun flashCopied(button: JButton) {
    button.icon = AllIcons.Actions.Checked
    Timer(COPY_FLASH_MS, ActionListener { button.icon = AllIcons.Actions.Copy }).apply {
      isRepeats = false
      start()
    }
  }

  private fun bufferBody(item: ExchangeItem): JComponent {
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

  private fun imageBody(item: ExchangeItem): JComponent {
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

  private fun fileCard(item: ExchangeItem, icon: Icon, actionText: String, action: () -> Unit): JComponent {
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
    card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
    return card
  }

  private fun bubbleFooter(item: ExchangeItem): JComponent {
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

  private fun chatLink(text: String, onClick: () -> Unit): JBLabel = JBLabel(text).apply {
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

  private fun looksLikeCode(text: String): Boolean {
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

  private fun installBubbleMouse(comp: JComponent, item: ExchangeItem) {
    comp.transferHandler = object : TransferHandler() {
      override fun getSourceActions(c: JComponent): Int = COPY
      override fun createTransferable(c: JComponent): Transferable? =
        if (item.canOpen) ExchangeTransferable(item) else null
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

  private fun showBubbleContextMenu(item: ExchangeItem, comp: JComponent, x: Int, y: Int) {
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
        addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(item.displayPath)) }
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

  /** Fetch the full buffer body off the EDT (lazy decrypt) and expand the bubble. */
  private fun fetchChatBody(item: ExchangeItem) {
    loadChatBody(item) {
      expandedIds.add(item.id)
      renderChat()
    }
  }

  /** Resolve the full buffer body (cache or bufferGet+decrypt); [onLoaded] runs on the EDT. */
  private fun loadChatBody(item: ExchangeItem, onLoaded: (String) -> Unit) {
    chatBodyCache[item.id]?.let {
      onLoaded(it)
      return
    }
    val s = service<MirrorSettingsService>().state
    val pwd = SecretsStore.syncPassword
    if (s.baseUrl.isBlank() || pwd.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.download"), true) {
      private var body: String? = null
      override fun run(indicator: ProgressIndicator) {
        val res = MirrorApi.bufferGet(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls, item.effectiveId)
        if (res.code !in 200..299 || res.file == null) return
        body = try {
          String(BundleCrypto.decryptDumpBytes(res.file.readBytes(), pwd), Charsets.UTF_8)
        } catch (_: Throwable) {
          null
        } finally {
          runCatching { res.file.delete() }
        }
      }

      override fun onSuccess() {
        val text = body ?: return
        chatBodyCache[item.id] = text
        onLoaded(text)
      }
    })
  }

  /** Decode a postbox image in the background and drop the thumbnail into its bubble. */
  private fun queueThumbPrefetch(item: ExchangeItem) {
    val key = item.id
    if (chatThumbCache.containsKey(key) || !thumbQueued.add(key)) return
    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) {
      thumbQueued.remove(key)
      return
    }
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        val bytes = downloadDecrypted(item, s)
        val icon = bytes?.let { scaledThumb(it) }
        if (icon == null) {
          thumbQueued.remove(key)
          return@executeOnPooledThread
        }
        publishThumb(key, icon)
      } catch (_: Throwable) {
        thumbQueued.remove(key)
      }
    }
  }

  private fun downloadDecrypted(item: ExchangeItem, s: MirrorSettingsService.State): ByteArray? {
    val enc = File.createTempFile("lgm-thumb-", ".bin")
    try {
      val dl = MirrorApi.fileSyncDownload(
        s.baseUrl, SecretsStore.mirrorApiKey, item.repo, s.mirrorInsecureTls, item.id, enc
      )
      if (dl.code !in 200..299) return null
      val out = File.createTempFile("lgm-thumb-", ".tmp")
      return try {
        RepoFileSyncCrypto.decryptFile(enc, out, SecretsStore.syncPassword, null)
        out.readBytes()
      } finally {
        runCatching { out.delete() }
      }
    } catch (_: Throwable) {
      return null
    } finally {
      runCatching { enc.delete() }
    }
  }

  private fun scaledThumb(bytes: ByteArray): ImageIcon? {
    val base = ImageIcon(bytes)
    if (base.iconWidth <= 0) return null
    val maxW = JBUI.scale(THUMB_MAX_WIDTH)
    val maxH = JBUI.scale(THUMB_MAX_HEIGHT)
    if (base.iconWidth <= maxW && base.iconHeight <= maxH) return base
    val ratio = minOf(maxW.toDouble() / base.iconWidth, maxH.toDouble() / base.iconHeight)
    val w = (base.iconWidth * ratio).toInt().coerceAtLeast(1)
    val h = (base.iconHeight * ratio).toInt().coerceAtLeast(1)
    return ImageIcon(base.image.getScaledInstance(w, h, Image.SCALE_SMOOTH))
  }

  private fun openExchangeItem(item: ExchangeItem) {
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

  /** Download + decrypt a postbox entry off the EDT; [consume] runs on the EDT and owns the temp file. */
  private fun withDecryptedFile(item: ExchangeItem, consume: (File) -> Unit) {
    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.download"), true) {
      private var plain: File? = null
      override fun run(indicator: ProgressIndicator) {
        val enc = File.createTempFile("lgm-dl-", ".bin")
        try {
          val repo = item.repo.ifBlank { resolveExchangeRepo(s) ?: return }
          val dl = MirrorApi.fileSyncDownload(
            s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls, item.effectiveId, enc
          )
          if (dl.code !in 200..299) {
            notify(
              LocalGitMirrorBundle.message("panel.exchange.download.fail", dl.code, dl.message.take(200)),
              NotificationType.ERROR
            )
            return
          }
          val out = File.createTempFile("lgm-plain-", ".tmp")
          RepoFileSyncCrypto.decryptFile(enc, out, SecretsStore.syncPassword, null)
          plain = out
        } catch (t: Throwable) {
          notify(
            LocalGitMirrorBundle.message("panel.exchange.decrypt.fail", t.message ?: t::class.simpleName ?: ""),
            NotificationType.ERROR
          )
        } finally {
          runCatching { enc.delete() }
        }
      }

      override fun onSuccess() {
        val f = plain ?: return
        if (project.isDisposed) {
          runCatching { f.delete() }
          return
        }
        consume(f)
      }
    })
  }

  private fun openMrNotesFrom(plain: File, item: ExchangeItem) {
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
    } finally {
      runCatching { plain.delete() }
    }
  }

  private fun showImagePreview(plain: File, item: ExchangeItem) {
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
    } finally {
      runCatching { plain.delete() }
    }
  }

  private fun openTextInEditor(plain: File, item: ExchangeItem) {
    try {
      val vf = LightVirtualFile(item.title, plain.readText(Charsets.UTF_8))
      vf.isWritable = false
      FileEditorManager.getInstance(project).openFile(vf, true)
    } catch (t: Throwable) {
      notify(LocalGitMirrorBundle.message("panel.exchange.decrypt.fail", t.message ?: ""), NotificationType.ERROR)
    } finally {
      runCatching { plain.delete() }
    }
  }

  private fun saveExchangeFileAs(item: ExchangeItem) {
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
      } finally {
        runCatching { plain.delete() }
      }
    }
  }

  private fun toggleExchangePin(item: ExchangeItem) {
    val s = service<MirrorSettingsService>().state
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: pin", true) {
      override fun run(indicator: ProgressIndicator) {
        val res = MirrorApi.bufferPin(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls, item.effectiveId, !item.pinned)
        if (res.code !in 200..299) {
          notify(LocalGitMirrorBundle.message("panel.exchange.pin.fail", res.code), NotificationType.ERROR)
        }
      }
      override fun onSuccess() = refreshExchangeInBackground()
    })
  }

  private fun deleteExchangeItem(item: ExchangeItem) {
    if (item.isEcho && item.serverId == null) {
      discardEcho(item)
      return
    }
    val s = service<MirrorSettingsService>().state
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: delete", true) {
      private var ok = false
      override fun run(indicator: ProgressIndicator) {
        val repo = item.repo.ifBlank { resolveExchangeRepo(s) ?: "" }
        val res = if (item.kind == ExchangeItem.Kind.BUFFER)
          MirrorApi.bufferDelete(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls, item.effectiveId)
        else
          MirrorApi.fileSyncAck(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls, item.effectiveId)
        if (res.code !in 200..299) {
          notify(LocalGitMirrorBundle.message("panel.exchange.delete.fail", res.code), NotificationType.ERROR)
        } else {
          ok = true
          historyService.add(
            LocalGitMirrorBundle.message("history.op.exchangeDelete"), true,
            "id=${item.effectiveId} ${item.displayPath}"
          )
        }
      }

      override fun onSuccess() {
        if (ok && item.isEcho) {
          chatEchoes.removeAll { it.id == item.id }
          item.serverId?.let { echoIdByServerId.remove(it) }
          if (item.localTemp && item.localFile != null) runCatching { File(item.localFile).delete() }
        }
        refreshExchangeInBackground()
      }
    })
  }

  private fun clearExchangeFeed() {
    val confirm = Messages.showYesNoDialog(
      project,
      LocalGitMirrorBundle.message("panel.exchange.clear.confirm"),
      LocalGitMirrorBundle.message("panel.exchange.clear.title"),
      LocalGitMirrorBundle.message("prune.confirm.yes"),
      LocalGitMirrorBundle.message("prune.confirm.no"),
      Messages.getWarningIcon()
    )
    if (confirm != Messages.YES) return
    val s = service<MirrorSettingsService>().state
    val fileItems = allServerItems.filter { it.kind == ExchangeItem.Kind.FILE }
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.clear"), true) {
      override fun run(indicator: ProgressIndicator) {
        val res = MirrorApi.bufferClear(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls)
        if (res.code !in 200..299) {
          notify(LocalGitMirrorBundle.message("panel.exchange.delete.fail", res.code), NotificationType.ERROR)
        }
        for (f in fileItems) {
          indicator.checkCanceled()
          MirrorApi.fileSyncAck(s.baseUrl, SecretsStore.mirrorApiKey, f.repo, s.mirrorInsecureTls, f.id)
        }
        historyService.add(
          LocalGitMirrorBundle.message("history.op.exchangeClear"), true, "files=${fileItems.size}"
        )
      }
      override fun onSuccess() {
        for (e in chatEchoes) {
          if (e.localTemp && e.localFile != null) runCatching { File(e.localFile).delete() }
        }
        chatEchoes.clear()
        echoIdByServerId.clear()
        notify(LocalGitMirrorBundle.message("panel.exchange.clear.ok"), NotificationType.INFORMATION)
        refreshExchangeInBackground()
      }
    })
  }

  // ── Exchange sends (optimistic chat echo) ──

  private fun nowSec(): Double = System.currentTimeMillis() / 1000.0

  private fun newLocalId(): String = LOCAL_ID_PREFIX + UUID.randomUUID().toString().take(8)

  private fun sendComposerText() {
    val text = composerField.text
    if (text.isBlank()) return
    if (sendChatText(text)) composerField.text = ""
  }

  /** Add the pending bubble instantly, then push in the background. Returns false if rejected upfront. */
  private fun sendChatText(text: String): Boolean {
    if (text.isEmpty()) {
      notify(LocalGitMirrorBundle.message("notify.buffer.noText"), NotificationType.WARNING)
      return false
    }
    if (text.length > 1_000_000) {
      notify(LocalGitMirrorBundle.message("notify.buffer.tooLarge"), NotificationType.WARNING)
      return false
    }
    val s = service<MirrorSettingsService>().state
    val pwd = SecretsStore.syncPassword
    if (s.baseUrl.isBlank() || pwd.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return false
    }
    val localId = newLocalId()
    val title = text.lineSequence().firstOrNull()?.trim()?.take(80)?.ifBlank { EMPTY_HINT_MARK } ?: EMPTY_HINT_MARK
    chatEchoes.add(
      ExchangeItem(
        ExchangeItem.Kind.BUFFER, localId, nowSec(), text.length.toLong(), title, false, "", "",
        ExchangeItem.Side.WORK, ExchangeItem.State.PENDING, localText = text
      )
    )
    renderChat(scrollToBottom = true)
    submitTextEcho(localId, s, pwd, text, null)
    return true
  }

  private fun submitTextEcho(
    localId: String,
    s: MirrorSettingsService.State,
    pwd: String,
    text: String,
    hintOverride: String?
  ) {
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("buffer.task.send"), true) {
      private var result: Pair<Boolean, String?> = false to null
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        result = sendTextToBufferSync(s, pwd, text, hintOverride)
      }
      override fun onSuccess() = finishEcho(localId, result.first, result.second)
      override fun onThrowable(t: Throwable) = finishEcho(localId, false, null)
    })
  }

  /** Encrypt + push one buffer entry. Returns (httpOk, serverId). */
  private fun sendTextToBufferSync(
    s: MirrorSettingsService.State,
    pwd: String,
    text: String,
    hintOverride: String?
  ): Pair<Boolean, String?> {
    return try {
      val ciphertext = BundleCrypto.encryptBundleBytes(text.toByteArray(Charsets.UTF_8), pwd)
      val hintEnc = ExchangeCrypto.encryptHint(ExchangeMeta.hintJson(text, hintOverride), pwd)
      val res = MirrorApi.bufferPut(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls, ciphertext, hintEnc)
      if (res.code !in 200..299) {
        historyService.add("Buffer send", false, "HTTP ${res.code}: ${res.message.take(200)}")
        false to null
      } else {
        historyService.add("Buffer send", true, "size=${text.length}")
        true to res.id
      }
    } catch (t: Throwable) {
      historyService.add("Buffer send", false, t.message ?: t::class.simpleName ?: "error")
      false to null
    }
  }

  /**
   * Resolve a local echo: confirmed → SENT (deduped against the poll by server id),
   * confirmed without id (old server) → dropped, the poll will show the real entry,
   * failed → FAILED with a retry link. [tempFile] is a plugin-owned file deleted on success.
   */
  private fun finishEcho(localId: String, ok: Boolean, serverId: String?, tempFile: String? = null) {
    if (project.isDisposed) return
    val i = chatEchoes.indexOfFirst { it.id == localId }
    if (i < 0) return
    val e = chatEchoes[i]
    when {
      ok && serverId != null -> {
        chatEchoes[i] = e.copy(
          state = ExchangeItem.State.SENT, serverId = serverId,
          localFile = tempFile ?: e.localFile
        )
        echoIdByServerId[serverId] = localId
        if (tempFile != null) runCatching { File(tempFile).delete() }
      }
      ok -> chatEchoes.removeAt(i)
      else -> chatEchoes[i] = e.copy(
        state = ExchangeItem.State.FAILED,
        localFile = tempFile ?: e.localFile
      )
    }
    renderChat()
  }

  private fun discardEcho(item: ExchangeItem) {
    chatEchoes.removeAll { it.id == item.id }
    if (item.localTemp && item.localFile != null) runCatching { File(item.localFile).delete() }
    item.serverId?.let { echoIdByServerId.remove(it) }
    renderChat()
  }

  private fun retryEcho(item: ExchangeItem) {
    val s = service<MirrorSettingsService>().state
    val pwd = SecretsStore.syncPassword
    if (s.baseUrl.isBlank() || pwd.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val i = chatEchoes.indexOfFirst { it.id == item.id }
    if (i < 0) return
    when {
      item.kind == ExchangeItem.Kind.BUFFER && item.localText != null -> {
        chatEchoes[i] = item.copy(state = ExchangeItem.State.PENDING)
        renderChat()
        submitTextEcho(item.id, s, pwd, item.localText, null)
      }
      item.kind == ExchangeItem.Kind.BUFFER -> {
        chatEchoes[i] = item.copy(state = ExchangeItem.State.PENDING)
        renderChat()
        submitLogTailEcho(item.id, s, pwd)
      }
      item.kind == ExchangeItem.Kind.FILE && item.localFile != null -> {
        val f = File(item.localFile)
        if (!f.isFile) {
          notify(
            LocalGitMirrorBundle.message("panel.exchange.upload.fail", "0", item.localFile),
            NotificationType.ERROR
          )
          discardEcho(item)
          return
        }
        chatEchoes[i] = item.copy(state = ExchangeItem.State.PENDING)
        renderChat()
        submitFileEcho(item.id, s, f, item.title, item.localTemp)
      }
      else -> discardEcho(item)
    }
  }

  private fun sendIdeaLogTail() {
    val s = service<MirrorSettingsService>().state
    val pwd = SecretsStore.syncPassword
    if (s.baseUrl.isBlank() || pwd.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val localId = newLocalId()
    chatEchoes.add(
      ExchangeItem(
        ExchangeItem.Kind.BUFFER, localId, nowSec(), IDEA_LOG_TAIL_BYTES.toLong(), "idea.log tail",
        false, "", "", ExchangeItem.Side.WORK, ExchangeItem.State.PENDING
      )
    )
    renderChat(scrollToBottom = true)
    submitLogTailEcho(localId, s, pwd)
  }

  private fun submitLogTailEcho(localId: String, s: MirrorSettingsService.State, pwd: String) {
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("buffer.task.send"), true) {
      private var result: Pair<Boolean, String?> = false to null
      private var tail: String? = null
      override fun run(indicator: ProgressIndicator) {
        val logFile = File(PathManager.getLogPath())
        if (!logFile.isFile) return
        tail = try {
          RandomAccessFile(logFile, "r").use { raf ->
            val len = raf.length()
            raf.seek(maxOf(0L, len - IDEA_LOG_TAIL_BYTES))
            val bytes = ByteArray((len - maxOf(0L, len - IDEA_LOG_TAIL_BYTES)).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
          }
        } catch (t: Throwable) {
          null
        }
        val text = tail ?: return
        result = sendTextToBufferSync(s, pwd, text, "idea.log tail")
      }

      override fun onSuccess() {
        if (tail == null) {
          notify(LocalGitMirrorBundle.message("panel.exchange.log.missing"), NotificationType.WARNING)
          chatEchoes.removeAll { it.id == localId }
          renderChat()
          return
        }
        finishEcho(localId, result.first, result.second)
      }

      override fun onThrowable(t: Throwable) = finishEcho(localId, false, null)
    })
  }

  private fun sendClipboardScreenshot() {
    val image = clipboardImage()
    if (image == null) {
      notify(LocalGitMirrorBundle.message("panel.exchange.noClipboard"), NotificationType.WARNING)
      return
    }
    sendImageToPostbox(image)
  }

  private fun sendImageToPostbox(image: Image) {
    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val localId = newLocalId()
    val name = "shot-" + SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()) + ".png"
    chatEchoes.add(
      ExchangeItem(
        ExchangeItem.Kind.FILE, localId, nowSec(), 0L, name, false, name, "",
        ExchangeItem.Side.WORK, ExchangeItem.State.PENDING, localTemp = true
      )
    )
    renderChat(scrollToBottom = true)
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.upload"), true) {
      private var newId: String? = null
      private var tmpPath: String? = null
      override fun run(indicator: ProgressIndicator) {
        val tmp = File.createTempFile("lgm-shot-", ".png")
        try {
          ImageIO.write(toBufferedImage(image), "png", tmp)
          tmpPath = tmp.absolutePath
          runCatching { scaledThumb(tmp.readBytes())?.let { publishThumb(localId, it) } }
          newId = uploadFileToPostboxSync(s, tmp, name)
        } catch (t: Throwable) {
          historyService.add(
            LocalGitMirrorBundle.message("history.op.exchangeUpload"), false,
            "$name err=${t.message ?: t::class.simpleName}"
          )
        }
      }

      override fun onSuccess() = finishEcho(localId, newId != null, newId, tmpPath)
      override fun onThrowable(t: Throwable) = finishEcho(localId, false, null, tmpPath)
    })
  }

  private fun toBufferedImage(img: Image): BufferedImage {
    if (img is BufferedImage) return img
    val bi = BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB)
    val g = bi.createGraphics()
    g.drawImage(img, 0, 0, null)
    g.dispose()
    return bi
  }

  private fun uploadFilesToPostbox(files: List<File>) {
    if (files.isEmpty()) return
    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val pending = files.map { f ->
      val localId = newLocalId()
      chatEchoes.add(
        ExchangeItem(
          ExchangeItem.Kind.FILE, localId, nowSec(), f.length(), f.name, false, f.name, "",
          ExchangeItem.Side.WORK, ExchangeItem.State.PENDING, localFile = f.absolutePath
        )
      )
      localId to f
    }
    renderChat(scrollToBottom = true)
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.upload"), true) {
      // "" is the failure sentinel — ConcurrentHashMap cannot hold null values.
      private val results = ConcurrentHashMap<String, String>()
      override fun run(indicator: ProgressIndicator) {
        for ((i, p) in pending.withIndex()) {
          indicator.checkCanceled()
          indicator.fraction = i.toDouble() / pending.size
          indicator.text = p.second.name
          if (ExchangeItem.isImageFileName(p.second.name)) {
            runCatching { scaledThumb(p.second.readBytes())?.let { publishThumb(p.first, it) } }
          }
          results[p.first] = uploadFileToPostboxSync(s, p.second) ?: ""
        }
      }

      override fun onSuccess() = applyUploadResults(pending, results)
      override fun onThrowable(t: Throwable) = applyUploadResults(pending, results)
    })
  }

  private fun publishThumb(localId: String, icon: ImageIcon) {
    chatThumbCache[localId] = icon
    UIUtil.invokeLaterIfNeeded {
      if (project.isDisposed) return@invokeLaterIfNeeded
      bubbleThumbLabels[localId]?.let {
        it.icon = icon
        it.text = null
        it.revalidate()
        it.repaint()
      }
    }
  }

  private fun applyUploadResults(
    pending: List<Pair<String, File>>,
    results: Map<String, String>
  ) {
    if (project.isDisposed) return
    for ((localId, _) in pending) {
      val id = results[localId]
      if (id != null) finishEcho(localId, id.isNotEmpty(), id.ifEmpty { null })
      else finishEcho(localId, false, null)
    }
  }

  private fun submitFileEcho(
    localId: String,
    s: MirrorSettingsService.State,
    file: File,
    realName: String,
    isTemp: Boolean
  ) {
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.upload"), true) {
      private var newId: String? = null
      override fun run(indicator: ProgressIndicator) {
        newId = uploadFileToPostboxSync(s, file, realName)
      }

      override fun onSuccess() =
        finishEcho(localId, newId != null, newId, if (isTemp) file.absolutePath else null)

      override fun onThrowable(t: Throwable) =
        finishEcho(localId, false, null, if (isTemp) file.absolutePath else null)
    })
  }

  /** Encrypt + upload one file; returns the server id or null on failure. */
  private fun uploadFileToPostboxSync(
    s: MirrorSettingsService.State,
    file: File,
    realName: String = file.name
  ): String? {
    val repo = resolveExchangeRepo(s) ?: return null
    val enc = File.createTempFile("lgm-up-", ".bin")
    return try {
      RepoFileSyncCrypto.encryptFile(file, enc, SecretsStore.syncPassword, null)
      finishPostboxUpload(s, repo, enc, realName, file.length())
    } catch (t: Throwable) {
      historyService.add(
        LocalGitMirrorBundle.message("history.op.exchangeUpload"), false,
        "$realName err=${t.message ?: t::class.simpleName}"
      )
      null
    } finally {
      runCatching { enc.delete() }
    }
  }

  private fun finishPostboxUpload(
    s: MirrorSettingsService.State,
    repo: String,
    encrypted: File,
    realName: String,
    plainSize: Long
  ): String? {
    val pathEnc = ExchangeCrypto.encryptHint(ExchangeMeta.nameJson(realName), SecretsStore.syncPassword)
    val res = MirrorApi.fileSyncUpload(
      s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls,
      "x/${UUID.randomUUID().toString().take(8)}", plainSize, encrypted, pathEnc, null
    )
    if (res.code !in 200..299 || res.id.isNullOrBlank()) {
      historyService.add(
        LocalGitMirrorBundle.message("history.op.exchangeUpload"), false,
        "$realName HTTP ${res.code}: ${res.message.take(200)}"
      )
      return null
    }
    historyService.add(
      LocalGitMirrorBundle.message("history.op.exchangeUpload"), true,
      "$realName id=${res.id} size=$plainSize"
    )
    return res.id
  }

  private fun resolveExchangeRepo(s: MirrorSettingsService.State): String? {
    val dir = baseDir()
    val repo = if (dir != null)
      try { syncFacade.resolveRepo(dir, s).sanitized } catch (_: Throwable) { "" }
    else ""
    if (repo.isBlank()) {
      notify(LocalGitMirrorBundle.message("filesync.notify.repoMissing"), NotificationType.WARNING)
      return null
    }
    return repo
  }

  private fun pasteClipboardToExchange() {
    val image = clipboardImage()
    if (image != null) {
      sendImageToPostbox(image)
      return
    }
    try {
      val clipboard = Toolkit.getDefaultToolkit().systemClipboard
      if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
        val text = clipboard.getData(DataFlavor.stringFlavor)?.toString() ?: ""
        if (text.isNotEmpty()) {
          sendChatText(text)
          return
        }
      }
    } catch (_: Throwable) {
    }
    notify(LocalGitMirrorBundle.message("panel.exchange.noClipboard"), NotificationType.WARNING)
  }

  // ── Exchange drag & drop ──

  private inner class ChatTransferHandler(private val toComposer: Boolean) : TransferHandler() {
    override fun canImport(support: TransferSupport): Boolean {
      if (!support.isDrop) return false
      if (support.transferable is ExchangeTransferable) return false
      return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
        support.isDataFlavorSupported(DataFlavor.imageFlavor) ||
        support.isDataFlavorSupported(DataFlavor.stringFlavor)
    }

    override fun importData(support: TransferSupport): Boolean {
      val t = support.transferable ?: return false
      return try {
        when {
          t.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
            @Suppress("UNCHECKED_CAST")
            val files = t.getTransferData(DataFlavor.javaFileListFlavor) as? List<File> ?: return false
            uploadFilesToPostbox(files.filter { it.isFile })
            true
          }
          t.isDataFlavorSupported(DataFlavor.imageFlavor) -> {
            val image = t.getTransferData(DataFlavor.imageFlavor) as? Image ?: return false
            sendImageToPostbox(image)
            true
          }
          t.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
            val text = t.getTransferData(DataFlavor.stringFlavor)?.toString() ?: return false
            if (text.isEmpty()) return false
            if (toComposer) composerField.replaceSelection(text) else sendChatText(text)
            true
          }
          else -> false
        }
      } catch (_: Throwable) {
        false
      }
    }
  }

  /**
   * Export payload is fetched lazily: the network round-trip starts when the
   * drag begins and getTransferData only blocks if the drop beats the fetch.
   */
  private inner class ExchangeTransferable(private val item: ExchangeItem) : Transferable {
    private val dataFuture = CompletableFuture<Any?>()

    init {
      ApplicationManager.getApplication().executeOnPooledThread {
        dataFuture.complete(runCatching { fetchExportData(item) }.getOrNull())
      }
    }

    override fun getTransferDataFlavors(): Array<DataFlavor> =
      if (item.kind == ExchangeItem.Kind.BUFFER) arrayOf(DataFlavor.stringFlavor)
      else arrayOf(DataFlavor.javaFileListFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
      getTransferDataFlavors().any { it.equals(flavor) }

    override fun getTransferData(flavor: DataFlavor): Any {
      if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
      return dataFuture.get(EXPORT_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS)
        ?: throw UnsupportedFlavorException(flavor)
    }
  }

  private fun fetchExportData(item: ExchangeItem): Any? {
    val s = service<MirrorSettingsService>().state
    val pwd = SecretsStore.syncPassword
    if (s.baseUrl.isBlank() || pwd.isBlank()) return null
    if (item.kind == ExchangeItem.Kind.BUFFER) {
      val res = MirrorApi.bufferGet(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls, item.effectiveId)
      if (res.code !in 200..299 || res.file == null) return null
      return try {
        String(BundleCrypto.decryptDumpBytes(res.file.readBytes(), pwd), Charsets.UTF_8)
      } catch (_: Throwable) {
        null
      } finally {
        runCatching { res.file.delete() }
      }
    }
    val enc = File.createTempFile("lgm-export-", ".bin")
    try {
      val repo = item.repo.ifBlank { resolveExchangeRepo(s) ?: return null }
      val dl = MirrorApi.fileSyncDownload(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls, item.effectiveId, enc)
      if (dl.code !in 200..299) return null
      val dir = Files.createTempDirectory("lgm-export-").toFile()
      val out = File(dir, item.title.replace(Regex("[^A-Za-z0-9._\\-]"), "_").ifBlank { "file" })
      RepoFileSyncCrypto.decryptFile(enc, out, pwd, null)
      return listOf(out)
    } catch (_: Throwable) {
      return null
    } finally {
      runCatching { enc.delete() }
    }
  }

  private fun formatBufferTs(epochSec: Double): String {
    val ms = (epochSec * 1000).toLong()
    return java.text.SimpleDateFormat("HH:mm").format(java.util.Date(ms))
  }

  private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
  }

  internal fun ensureConfigured(settings: MirrorSettingsService.State): String? {
    val cfg = syncFacade.validateSettings(settings)
    return if (cfg.ok) null else cfg.message
  }

  internal fun markLastSyncOk() = markLastSync(SyncOutcome.OK)

  internal fun markLastSync(outcome: SyncOutcome) {
    val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    lastSyncBadge.status = when (outcome) {
      SyncOutcome.OK -> BadgeLabel.Status.GOOD
      SyncOutcome.FAIL -> BadgeLabel.Status.BAD
    }
    lastSyncBadge.text = when (outcome) {
      SyncOutcome.OK -> "Last sync: $ts \u2705"
      SyncOutcome.FAIL -> "Last sync: $ts \u274c"
    }
  }

  internal fun refreshHistoryLog() {
    val entries = historyService.latest(40)
    if (::historyScroll.isInitialized) {
      historyScroll.isVisible = historyExpanded && entries.isNotEmpty()
    }
    historyListModel.clear()
    entries.forEach { historyListModel.addElement(it) }
    if (::historyScroll.isInitialized) {
      historyScroll.parent?.revalidate()
      historyScroll.parent?.repaint()
      revalidate()
      repaint()
    }
  }

  /** Dialog showing full details of a history entry, with a Copy button. */
  private inner class HistoryEntryDialog(private val entry: OperationsHistoryService.Entry) : DialogWrapper(project, false) {
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

/** One message of the Exchange chat: either a buffer entry or a repo postbox file. */
internal data class ExchangeItem(
  val kind: Kind,
  val id: String,
  val ts: Double,
  val size: Long,
  val title: String,
  val pinned: Boolean,
  val displayPath: String,
  val repo: String,
  val side: Side = Side.UNKNOWN,
  val state: State = State.SENT,
  val serverId: String? = null,
  val localText: String? = null,
  val localFile: String? = null,
  val localTemp: Boolean = false,
) {
  enum class Kind { BUFFER, FILE }
  enum class Side { WORK, HOME, UNKNOWN }
  enum class State { PENDING, SENT, FAILED }

  val isEcho: Boolean get() = id.startsWith("local-")
  val effectiveId: String get() = serverId ?: id
  val canOpen: Boolean get() = !isEcho || serverId != null

  private val ext: String get() = displayPath.substringAfterLast('.', "").lowercase()
  val isMrNotes: Boolean get() = displayPath.startsWith("mr-notes/") && ext == "md"
  val isImage: Boolean get() = ext in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
  val isLog: Boolean get() = ext == "log"
  val isText: Boolean
    get() = isLog || ext in setOf(
      "txt", "md", "json", "xml", "yaml", "yml", "properties", "csv", "kt", "java",
      "py", "ts", "js", "html", "css", "sh", "bat", "ini", "toml", "sql", "gradle", "kts"
    )

  companion object {
    fun isImageFileName(name: String): Boolean =
      name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
  }
}
