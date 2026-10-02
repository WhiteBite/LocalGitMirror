package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.gitlab.MrReviewService
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.*
import localgitmirror.idea.sync.v2.SyncFacadeService
import localgitmirror.idea.ui.exchange.ExchangeItem
import localgitmirror.idea.ui.exchange.refreshExchangeInBackground
import localgitmirror.idea.ui.exchange.startExchangePolling
import localgitmirror.idea.ui.exchange.stopExchangePolling
import localgitmirror.idea.ui.exchange.uploadFilesToPostbox
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*

class LocalGitMirrorPanel(val project: Project) : JPanel(BorderLayout()), Disposable {

  // ── Quick Setup form state (local vars bound to DSL fields) ──
  internal var setupUrl: String = service<MirrorSettingsService>().state.baseUrl.let {
    if (it.isNotBlank() && it != "https://localhost") it else ""
  }
  internal var setupApiKey: String = SecretsStore.cached.mirrorApiKey
  internal var setupSyncPassword: String = SecretsStore.cached.syncPassword
  internal var setupFormPanel: com.intellij.openapi.ui.DialogPanel? = null

  internal val status = JBLabel("")
  internal val mirrorBadge = BadgeLabel("Cache: ?")
  internal val lastSyncBadge = BadgeLabel("Last sync: \u2014")

  internal val historyView = HistoryView(this)
  internal fun refreshHistoryLog() = historyView.refreshHistoryLog()

  // Plugin version string, surfaced in the gear menu / tooltip instead of a
  // competing status badge (keeps the header row compact in a narrow tool window).
  internal val pluginVersionText: String = run {
    // Read plugin version at runtime from the platform's plugin descriptor
    val pluginId = com.intellij.openapi.extensions.PluginId.getId("localgitmirror.idea.orchestrator")
    val descriptor = com.intellij.ide.plugins.PluginManagerCore.getPlugin(pluginId)
    if (descriptor != null) "v${descriptor.version}" else "v?"
  }

  internal val historyService = service<OperationsHistoryService>()
  internal val syncFacade = project.getService(SyncFacadeService::class.java)
  internal var historyListener: (() -> Unit)? = null

  private companion object {
    const val THUMB_CACHE_MAX = 64
  }

  // ── Branch selector (JBList with status) ──
  // Shows local branches immediately and appends Mirror-only branches after a
  // background refs request. BranchListItem keeps the raw name + status for actions.
  internal val branchListModel = DefaultListModel<BranchListItem>()
  // All items before filtering — used by branchFilterField to re-apply the filter.
  internal var allBranchItems: List<BranchListItem> = emptyList()
  // Chip filter mode (Все · С MR · Локальные); combines with the free-text filter.
  internal var branchFilterMode: BranchFilterMode = BranchFilterMode.ALL
  internal var respondButton: javax.swing.JButton? = null
  internal var reviewFetchButton: javax.swing.JButton? = null
  internal var reviewRowsSignature = ""
  internal val branchFilterField = SearchTextField(false).apply {
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
    toolTipText = LocalGitMirrorBundle.message("panel.branch.list.tooltip")
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
  internal var depsBanner: EditorNotificationPanel? = null
  internal var depsResponsesPanel: EditorNotificationPanel? = null
  internal var depsSectionLabel: JBLabel? = null
  internal var requestDepsButton: JButton? = null
  internal var applyDepsButton: JButton? = null

  // ── Exchange chat (unified buffer + postbox timeline) ──
  internal var allServerItems: List<ExchangeItem> = emptyList()
  internal var chatEmptyMessage: String = LocalGitMirrorBundle.message("panel.exchange.empty")
  // Local optimistic echoes: shown instantly, confirmed with the server id after HTTP 200.
  internal val chatEchoes = mutableListOf<ExchangeItem>()
  internal val echoIdByServerId = ConcurrentHashMap<String, String>()
  // hint_enc decryption is PBKDF2-heavy; cached so the 5 s poll doesn't re-derive keys.
  internal val exchangeHintCache = ConcurrentHashMap<String, String>()
  // Full buffer bodies fetched on "expand"; thumbnails decoded in background on arrival.
  internal val chatBodyCache = ConcurrentHashMap<String, String>()
  internal val chatThumbCache: MutableMap<String, ImageIcon> = Collections.synchronizedMap(
    object : LinkedHashMap<String, ImageIcon>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageIcon>) = size > THUMB_CACHE_MAX
    }
  )
  internal val thumbQueued: MutableSet<String> = ConcurrentHashMap.newKeySet()
  internal val bubbleThumbLabels = mutableMapOf<String, JBLabel>()
  internal val expandedIds = mutableSetOf<String>()
  internal var lastChatSignature: String? = null

  internal val chatPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    border = JBUI.Borders.empty(4, 0)
  }
  internal val chatScroll = JBScrollPane(chatPanel).apply {
    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    border = BorderFactory.createEmptyBorder()
    viewportBorder = BorderFactory.createEmptyBorder()
  }
  internal val composerField = JBTextArea(3, 30).apply {
    lineWrap = true
    wrapStyleWord = true
    font = JBUI.Fonts.smallFont()
    emptyText.text = LocalGitMirrorBundle.message("panel.exchange.composer.hint")
  }
  internal val exchangePollAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

  internal val statusDot = JBLabel("\u25CF")
  internal var stripConnected = false
  internal var stripRole: String = ""
  internal var stripRepo: String = ""
  internal var branchDetail: BranchDetail? = null
  internal var tabsPane: JBTabbedPane? = null
  internal val tabLabels = mutableMapOf<Int, JBLabel>()
  internal val tabPills = mutableMapOf<Int, JComponent>()

  internal val mrReviewListModel = DefaultListModel<MrReviewService.MrRowItem>()
  internal val mrReviewStatus = JBLabel("").apply {
    font = JBUI.Fonts.smallFont()
    foreground = UIUtil.getContextHelpForeground()
  }
  internal val mrReviewErrorLabel = JBLabel("").apply {
    font = JBUI.Fonts.smallFont()
    foreground = com.intellij.ui.JBColor(0xC62828, 0xEF5350)
    isVisible = false
  }
  internal val mrReviewFilterField = SearchTextField(false).apply {
    textEditor.emptyText.text = LocalGitMirrorBundle.message("review.filter")
    textEditor.font = JBUI.Fonts.smallFont()
    toolTipText = LocalGitMirrorBundle.message("review.filter")
    addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refreshReview()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refreshReview()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refreshReview()
    })
  }
  internal val mrReviewList = JBList(mrReviewListModel).apply {
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

  internal var branchRefreshInProgress = false

  internal val refreshBranchesAction = object : AnAction(
    LocalGitMirrorBundle.message("panel.branch.refresh.tooltip"),
    LocalGitMirrorBundle.message("panel.branch.refresh.tooltip"),
    AllIcons.Actions.Refresh
  ) {
    override fun actionPerformed(e: AnActionEvent) = refreshBranchCombo(userInitiated = true, withMirror = true)
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = !branchRefreshInProgress && !isSyncing
    }
  }

  internal val branchRefreshGeneration = AtomicLong()

  @Volatile
  internal var mirrorRefs: Map<String, String> = emptyMap()

  // Additional branches to include on send (legacy chip behaviour kept as internal set)
  internal val selectedAdditionalBranches = mutableSetOf<String>()

  // Dynamic UI containers
  internal val badgesPanel = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply { isOpaque = false }
  internal val actionsBox = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    alignmentX = LEFT_ALIGNMENT
  }
  /** Full action list — shown only in the overflow popup, organised into submenus. */
  internal val mainGroup = DefaultActionGroup()
  /** Send-side transport actions; rendered as the "↑ Send ▾" bar button popup. */
  internal val sendGroup = DefaultActionGroup(LocalGitMirrorBundle.message("panel.transport.send"), true)
  /** Pull-side transport actions; rendered as the "↓ Pull ▾" bar button popup. */
  internal val pullGroup = DefaultActionGroup(LocalGitMirrorBundle.message("panel.transport.pull"), true)

  internal val progress = ProgressController(this)

  internal var isSyncing: Boolean
    get() = progress.isSyncing
    set(value) { progress.isSyncing = value }

  internal var currentIndicator: ProgressIndicator?
    get() = progress.currentIndicator
    set(value) { progress.currentIndicator = value }

  internal fun setProgress(fraction: Double, text: String) = progress.setProgress(fraction, text)

  internal enum class SyncOutcome { OK, FAIL }

  init {
    layout = BorderLayout()

    // Files dropped anywhere in the tool window upload to the postbox; child
    // components with their own handlers (composer, chat feed) keep precedence.
    transferHandler = object : TransferHandler() {
      override fun canImport(support: TransferSupport): Boolean =
        support.isDrop && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

      override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        val files = (support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
          ?.filterIsInstance<File>()
          ?.filter { it.isFile }
          .orEmpty()
        if (files.isEmpty()) return false
        uploadFilesToPostbox(files)
        return true
      }
    }

    // Auto-refresh history on any new entry, regardless of which thread added it.
    // Registered once here so it survives rebuilds (setup → main UI transition).
    val listener: () -> Unit = {
      com.intellij.util.ui.UIUtil.invokeLaterIfNeeded { refreshHistoryLog() }
    }
    historyListener = listener
    historyService.addChangeListener(listener)

    val s = service<MirrorSettingsService>().state
    if (isMirrorConfigured(s)) {
      buildMainUi()
    } else {
      buildSetupUi()
      // a cold secret cache must not lock the user into the setup UI — re-check after the background warm
      SecretsStore.warmCacheAsync {
        com.intellij.util.ui.UIUtil.invokeLaterIfNeeded {
          if (project.isDisposed) return@invokeLaterIfNeeded
          if (isMirrorConfigured(service<MirrorSettingsService>().state)) {
            removeAll()
            buildMainUi()
            revalidate()
            repaint()
          } else {
            setupApiKey = SecretsStore.cached.mirrorApiKey
            setupSyncPassword = SecretsStore.cached.syncPassword
            setupFormPanel?.reset()
          }
        }
      }
    }
  }

  private fun isMirrorConfigured(s: MirrorSettingsService.State): Boolean =
    s.baseUrl.isNotBlank() &&
      (SecretsStore.cached.syncPassword.isNotBlank() || localgitmirror.idea.mirror.MirrorCrypto.isV3Pinned())

  /** Build the full main UI (toolbar + tabs + progress). Called on init and after successful setup. */
  internal fun buildMainUi() {
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
    project.getService(MrReviewService::class.java).onCacheUpdated = {
      refreshReview()
      branchDetail?.refreshFor(branchList.selectedValue)
    }

    val root = SimpleToolWindowPanel(true, true)
    root.setToolbar(buildTransportBar())

    val progressRow = progress.buildProgressRow()

    val tabs = JBTabbedPane()
    tabs.addTab(LocalGitMirrorBundle.message("tab.branches"), buildBranchesTab())
    tabs.addTab(LocalGitMirrorBundle.message("tab.exchange"), buildExchangeTab())
    tabs.addTab(LocalGitMirrorBundle.message("tab.deps"), buildDepsTab())
    for (i in 0 until tabs.tabCount) {
      tabs.setTabComponentAt(i, makeTabComponent(tabs, i))
    }
    tabs.addChangeListener { refreshTabStyles() }
    refreshTabStyles()
    tabs.addChangeListener { onTabChanged(tabs.selectedIndex) }
    tabsPane = tabs

    val north = JPanel(BorderLayout()).apply {
      isOpaque = false
      add(buildStatusStrip(), BorderLayout.NORTH)
      add(progressRow, BorderLayout.CENTER)
    }

    val center = JPanel(BorderLayout()).apply { isOpaque = false }
    center.add(north, BorderLayout.NORTH)
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

  private fun buildFooterRow(): JPanel = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(2, 8)
    add(JBLabel(LocalGitMirrorBundle.message("panel.footer.version", pluginVersionText)).apply {
      font = JBUI.Fonts.smallFont().deriveFont(Font.PLAIN, JBUI.scale(10f).toFloat())
      foreground = UIUtil.getContextHelpForeground()
    }, BorderLayout.WEST)
  }

  private fun onTabChanged(index: Int) {
    if (project.isDisposed || ApplicationManager.getApplication().isDisposeInProgress) return
    when (index) {
      1 -> {
        refreshExchangeInBackground()
        startExchangePolling()
      }
      2 -> refreshDepsInBackground()
    }
    if (index != 1) stopExchangePolling()
  }

  override fun dispose() {
    historyListener?.let { historyService.removeChangeListener(it) }
    historyListener = null
    exchangePollAlarm.cancelAllRequests()
  }
}
