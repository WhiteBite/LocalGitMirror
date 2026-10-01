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
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
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
import localgitmirror.idea.actions.PullFromMirrorAction
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.gitlab.MrReviewService
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.net.LanDiscovery
import localgitmirror.idea.settings.*
import localgitmirror.idea.sync.HandshakeCache
import localgitmirror.idea.sync.v2.SyncFacadeService
import localgitmirror.idea.ui.exchange.ExchangeItem
import localgitmirror.idea.ui.exchange.clearExchangeFeed
import localgitmirror.idea.ui.exchange.formatBufferTs
import localgitmirror.idea.ui.exchange.formatSize
import localgitmirror.idea.ui.exchange.installPluginFromCache
import localgitmirror.idea.ui.exchange.refreshExchangeInBackground
import localgitmirror.idea.ui.exchange.sendPluginBuild
import localgitmirror.idea.ui.exchange.startExchangePolling
import localgitmirror.idea.ui.exchange.stopExchangePolling
import localgitmirror.idea.ui.exchange.uploadFilesToPostbox
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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

  private companion object {
    const val THUMB_CACHE_MAX = 64
  }

  // ── Branch selector (JBList with status) ──
  // Shows local branches immediately and appends Mirror-only branches after a
  // background refs request. BranchListItem keeps the raw name + status for actions.
  internal val branchListModel = DefaultListModel<BranchListItem>()
  // All items before filtering — used by branchFilterField to re-apply the filter.
  internal var allBranchItems: List<BranchListItem> = emptyList()
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
  // hint_enc/path_enc decryption is PBKDF2-heavy; cached so the 5 s poll doesn't re-derive keys.
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

  internal val roleBadge = BadgeLabel("")
  internal val statusDot = JBLabel("\u25CF")
  internal var tabsPane: JBTabbedPane? = null
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

  internal fun setTabTitle(index: Int, text: String) {
    tabsPane?.setTitleAt(index, text)
    tabLabels[index]?.let { label ->
      label.text = text
      label.revalidate()
      tabPills[index]?.revalidate()
      tabsPane?.revalidate()
    }
  }

  internal val mrReviewListModel = DefaultListModel<MrReviewService.MrRowItem>()
  internal val mrReviewStatus = JBLabel("").apply {
    font = JBUI.Fonts.smallFont()
    foreground = UIUtil.getContextHelpForeground()
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
  /** Full action list — shown only in the overflow popup. */
  internal val mainGroup = DefaultActionGroup()
  /** Compact toolbar set — what actually renders in the panel's top toolbar. */
  internal val toolbarGroup = DefaultActionGroup()

  internal val progress = ProgressController(this)

  internal var isSyncing: Boolean
    get() = progress.isSyncing
    set(value) { progress.isSyncing = value }

  internal var currentIndicator: ProgressIndicator?
    get() = progress.currentIndicator
    set(value) { progress.currentIndicator = value }

  internal fun setProgress(fraction: Double, text: String) = progress.setProgress(fraction, text)

  internal enum class SyncOutcome { OK, FAIL }

  /** Standard action button. */
  internal fun btn(title: String, icon: Icon? = null, action: () -> Unit): JButton {
    val b = JButton(title, icon)
    b.margin = JBUI.insets(2, 8)
    b.font = b.font.deriveFont(JBUI.scale(12f).toFloat())
    b.isFocusPainted = false
    b.addActionListener { action() }
    return b
  }

  /** Primary (accent) button — painted by the IDE theme, not by hand. */
  internal fun primaryBtn(title: String, icon: Icon? = null, action: () -> Unit): JButton =
    btn(title, icon, action).apply { putClientProperty("JButton.buttonType", "default") }

  internal fun greenBtn(title: String, icon: Icon? = null, action: () -> Unit): JButton =
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
    mainGroup.add(panelAction(LocalGitMirrorBundle.message("panel.exchange.more.clear"), AllIcons.Actions.GC) {
      clearExchangeFeed()
    })
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
    mainGroup.add(panelAction(LocalGitMirrorBundle.message("panel.menu.sendPlugin"), AllIcons.Actions.Upload) { sendPluginBuild() })
    mainGroup.add(panelAction(LocalGitMirrorBundle.message("panel.menu.installPlugin"), AllIcons.Actions.Download) { installPluginFromCache() })
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
  internal fun triggerLgmAction(id: String) {
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

    val progressRow = progress.buildProgressRow()

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

  override fun dispose() {
    exchangePollAlarm.cancelAllRequests()
  }

  internal fun sectionHeader(text: String): JBLabel = JBLabel(text).apply {
    font = JBUI.Fonts.smallFont().asBold()
    foreground = UIUtil.getLabelForeground()
    border = JBUI.Borders.empty(8, 0, 4, 0)
    alignmentX = LEFT_ALIGNMENT
  }

  /** Pull selected branches (multi-select support). Falls back to single-branch picker if nothing selected. */
  internal fun pullSelectedBranches() {
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
  internal fun sendSelectedBranches() {
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
  internal fun deleteSelectedBranches() {
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

  internal fun notify(message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("DocCache")
      .createNotification(message, type)
      .notify(project)
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
}
