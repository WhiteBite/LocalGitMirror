package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.actions.PullFromMirrorAction
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.net.LanDiscovery
import localgitmirror.idea.settings.*
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*

class LocalGitMirrorPanel(val project: Project) : JPanel(BorderLayout()) {

  // ── Quick Setup form state (local vars bound to DSL fields) ──
  private var setupUrl: String = service<MirrorSettingsService>().state.baseUrl.let {
    if (it.isNotBlank() && it != "https://localhost") it else ""
  }
  private var setupApiKey: String = SecretsStore.mirrorApiKey
  private var setupSyncPassword: String = SecretsStore.syncPassword
  private var setupFormPanel: com.intellij.openapi.ui.DialogPanel? = null

  internal val status = JBLabel("")
  internal val mirrorBadge = BadgeLabel("Mirror: ?")
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

  internal val historyService = service<OperationsHistoryService>()
  internal val syncFacade = project.getService(SyncFacadeService::class.java)

  // ── Branch selector (JBList with status) ──
  // Shows local branches immediately and appends Mirror-only branches after a
  // background refs request. BranchListItem keeps the raw name + status for actions.
  internal val branchListModel = DefaultListModel<BranchListItem>()
  // All items before filtering — used by branchFilterField to re-apply the filter.
  private var allBranchItems: List<BranchListItem> = emptyList()
  // Small filter field above the list (speed search fallback for this SDK).
  private val branchFilterField = JBTextField().apply {
    emptyText.text = "Фильтр веток…"
    font = JBUI.Fonts.smallFont()
    toolTipText = "Фильтр веток"
    document.addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = applyBranchFilter()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = applyBranchFilter()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = applyBranchFilter()
    })
  }
  internal val branchList = JBList(branchListModel).apply {
    font = JBUI.Fonts.smallFont()
    cellRenderer = BranchListCellRenderer()
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    toolTipText = "Ветка для Отправить / Подтянуть; ★ есть только на Mirror"
  }

  private inner class BranchListCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
      list: JList<*>?,
      value: Any?,
      index: Int,
      isSelected: Boolean,
      cellHasFocus: Boolean
    ): Component {
      val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
      if (value is BranchListItem) {
        val prefix = when (value.status) {
          BranchStatus.SYNCED -> "\u2713 "
          BranchStatus.AHEAD -> "\u2191 "
          BranchStatus.BEHIND -> "\u2193 "
          BranchStatus.MIRROR_ONLY -> "\u2605 "
          BranchStatus.LOCAL_ONLY -> "\u25CB "
        }
        text = "$prefix${value.name}"
        font = JBUI.Fonts.smallFont()
      }
      return c
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
      val shortDetails = value.details.lineSequence().firstOrNull()?.take(60) ?: ""
      if (shortDetails.isNotEmpty()) {
        append("  $shortDetails", SimpleTextAttributes.GRAYED_ATTRIBUTES)
      }
      toolTipText = value.details.take(1000)
    }
  }
  private val branchRefreshButton = JButton(AllIcons.Actions.Refresh).apply {
    margin = JBUI.insets(1)
    isFocusPainted = false
    toolTipText = LocalGitMirrorBundle.message("panel.branch.refresh.tooltip")
    addActionListener { refreshBranchCombo(userInitiated = true) }
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
  internal val moreMenu = JPopupMenu()

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
      progressBar.isVisible = isSyncing
      progressLabel.isVisible = isSyncing
      cancelButton.isVisible = isSyncing
      if (!isSyncing) {
        progressLabel.text = ""
        progressBar.isIndeterminate = true
        progressBar.value = 0
        currentIndicator = null
      }
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

  /** Primary (accent) button. */
  private fun primaryBtn(title: String, icon: Icon? = null, action: () -> Unit): JButton {
    val b = JButton(title, icon)
    b.margin = JBUI.insets(2, 10)
    b.font = b.font.deriveFont(Font.BOLD)
    b.putClientProperty("JButton.buttonType", "default")
    b.addActionListener { action() }
    return b
  }

  private fun gearMenuItem(title: String, icon: Icon? = null, action: () -> Unit): JMenuItem {
    val mi = JMenuItem(title, icon)
    mi.addActionListener { action() }
    return mi
  }

  /** Trigger an action registered in plugin.xml by id, in the panel's project context. */
  private fun runRegisteredAction(actionId: String) {
    val action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(actionId) ?: return
    val dataContext = com.intellij.openapi.actionSystem.DataContext { dataId ->
      if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(dataId)) project else null
    }
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
   * Refresh the selector from local Git immediately, then append fresh refs
   * from Mirror asynchronously. A branch received from another machine is
   * therefore visible without restarting the IDE or creating it locally first.
   */
  internal fun refreshBranchCombo(userInitiated: Boolean = false) {
    val dir = baseDir()
    if (dir == null) {
      if (userInitiated) notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.WARNING)
      return
    }

    val localBranches = GitLocal.listBranches(project, dir)
    val currentBranch = GitLocal.currentBranch(project, dir)
    replaceBranchItems(localBranches, mirrorRefs, currentBranch)
    refreshMirrorBranches(dir, localBranches, currentBranch, userInitiated)
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
      finishBranchRefresh("Mirror не настроен")
      if (userInitiated) notify("Укажите адрес Mirror в настройках плагина.", NotificationType.WARNING)
      return
    }

    val repo = try { syncFacade.resolveRepo(dir, settings).sanitized } catch (_: Throwable) { "" }
    if (repo.isBlank()) {
      finishBranchRefresh("Не удалось определить репозиторий Mirror")
      if (userInitiated) notify("Не удалось определить имя репозитория Mirror.", NotificationType.WARNING)
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
          finishBranchRefresh("Mirror: ${mirrorRefs.size} веток")
        } else {
          val detail = "Mirror не ответил для repo '$repo': ${result.message.take(120)}"
          finishBranchRefresh(detail)
          if (userInitiated) notify(detail, NotificationType.WARNING)
        }
      }
    }
  }

  private fun setBranchRefreshInProgress(inProgress: Boolean) {
    branchRefreshButton.isEnabled = !inProgress && !isSyncing
    branchRefreshButton.toolTipText = if (inProgress)
      LocalGitMirrorBundle.message("panel.branch.refresh.inprogress")
    else
      LocalGitMirrorBundle.message("panel.branch.refresh.tooltip")
    if (inProgress) branchList.toolTipText = "Загружаем ветки с Mirror…"
  }

  private fun finishBranchRefresh(detail: String) {
    setBranchRefreshInProgress(false)
    branchList.toolTipText = "Ветка для Отправить / Подтянуть; ★ есть только на Mirror. $detail"
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
      BranchListItem(name, status, localHash, mirrorHash)
    }

    allBranchItems = items

    val preferred = BranchSelectorModel.preferredSelection(selectedName, currentBranch,
      items.map { BranchChoice(it.name, it.localHash != null) })

    // Apply current filter (if any) before populating the model.
    val filter = branchFilterField.text.trim().lowercase()
    val visibleItems = if (filter.isBlank()) items else items.filter { it.name.lowercase().contains(filter) }

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

  /** Rebuild the concise gear menu; routine actions stay at the top, rare tools are grouped. */
  internal fun rebuildGearMenu() {
    moreMenu.removeAll()
    // Group 1: branch refresh + connection test
    moreMenu.add(gearMenuItem("Обновить ветки Mirror", AllIcons.Actions.Refresh) { refreshBranchCombo(userInitiated = true) })
    moreMenu.add(gearMenuItem("Проверить подключение", AllIcons.Actions.Checked) { testMirror() })
    moreMenu.addSeparator()
    // Group 2: deps
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("action.LocalGitMirror.DepsRequest.text"), AllIcons.Actions.Download) { triggerLgmAction("LocalGitMirror.DepsRequest") })
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("action.LocalGitMirror.DepsRespond.text"), AllIcons.Actions.Upload) { triggerLgmAction("LocalGitMirror.DepsRespond") })
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("action.LocalGitMirror.DepsApply.text"), AllIcons.Actions.CheckOut) { triggerLgmAction("LocalGitMirror.DepsApply") })
    moreMenu.addSeparator()
    // Group 3: service
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("panel.menu.exportBundle"), AllIcons.Actions.Upload) { exportBundle() })
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("panel.menu.importBundle"), AllIcons.Actions.Download) { importBundle() })
    moreMenu.add(gearMenuItem("Скачать плагин с Mirror", AllIcons.Actions.Download) { downloadLatestPlugin() })
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("action.LocalGitMirror.Preflight.text"), AllIcons.Actions.Preview) { triggerLgmAction("LocalGitMirror.Preflight") })
    moreMenu.addSeparator()
    // Group 4: settings
    moreMenu.add(gearMenuItem("Настройки", AllIcons.General.Settings) {
      ShowSettingsUtil.getInstance().showSettingsDialog(project, "localgitmirror.settings")
      refreshStatus()
    })
    // Role now shown in header status — removed disabled «Машина: …» item.
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

  /** Build the full main UI (header, badges, actions, log). Called on init and after successful setup. */
  private fun buildMainUi() {
    removeAll()
    
    // Configure branch list for multi-select
    branchList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    branchList.visibleRowCount = 6
    branchList.fixedCellHeight = JBUI.scale(26)
    
    val branchScroll = JScrollPane(branchList).apply {
      border = BorderFactory.createEmptyBorder()
      viewportBorder = BorderFactory.createEmptyBorder()
    }
    
    // Configure history list
    historyScroll = JScrollPane(historyList).apply {
      preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(120))
      isVisible = false
      border = BorderFactory.createEmptyBorder()
      viewportBorder = BorderFactory.createEmptyBorder()
    }
    
    rebuildGearMenu()
    
    val mainPanel = panel {
      // Status line with ⚙ settings + ⋮ more buttons
      row {
        status.font = JBUI.Fonts.smallFont()
        status.foreground = UIUtil.getContextHelpForeground()
        cell(status).resizableColumn()

        // ⚙ Settings button — opens settings dialog directly
        val settingsBtn = JButton(AllIcons.General.Settings).apply {
          margin = JBUI.insets(1)
          isFocusPainted = false
          isBorderPainted = false
          isContentAreaFilled = false
          toolTipText = LocalGitMirrorBundle.message("toolwindow.menu.settings")
          addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "localgitmirror.settings")
            refreshStatus()
          }
        }
        cell(settingsBtn)

        // ⋮ More button — shows the popup gear menu
        val moreBtn = JButton(AllIcons.Actions.More).apply {
          margin = JBUI.insets(1)
          isFocusPainted = false
          isBorderPainted = false
          isContentAreaFilled = false
          toolTipText = "LocalGitMirror $pluginVersionText"
          addActionListener { moreMenu.show(this, 0, height) }
        }
        cell(moreBtn)
      }
      
      // Branch filter (speed search fallback for this SDK)
      row {
        cell(branchFilterField).resizableColumn()
      }
      
      // Branch list (compact, no label)
      row {
        cell(branchScroll).resizableColumn()
        cell(branchRefreshButton)
      }
      
      // Action buttons row
      row {
        button("↓ Стянуть") { pullSelectedBranches() }
          .applyToComponent {
            putClientProperty("JButton.buttonType", "default")
            font = font.deriveFont(Font.BOLD)
          }
        button("↑ Отправить") { sendSelectedBranches() }
          .applyToComponent {
            putClientProperty("JButton.buttonType", "default")
            font = font.deriveFont(Font.BOLD)
          }
        button("🗑 Удалить") { deleteSelectedBranches() }
          .applyToComponent {
            font = font.deriveFont(Font.PLAIN)
            toolTipText = "Удалить выбранные ветки (локально и на Mirror)"
          }
      }
      
      // Progress row (hidden by default)
      row {
        cell(progressBar).resizableColumn()
        cell(cancelButton)
        cell(progressLabel)
      }
      
      // History: explicit header row (toggle + title + clear always visible)
      // plus a plain content row. collapsibleGroup hid the clear button when
      // expanded and broke re-expanding once children visibility was touched.
      row {
        val toggleBtn = JButton(AllIcons.General.ChevronDown).apply {
          margin = JBUI.insets(1, 2)
          isFocusPainted = false
          isBorderPainted = false
          isContentAreaFilled = false
          toolTipText = "Свернуть/развернуть историю"
        }
        cell(toggleBtn)
        cell(JLabel("История")).applyToComponent { font = JBUI.Fonts.smallFont() }
        val clearBtn = JButton(AllIcons.Actions.GC).apply {
          margin = JBUI.insets(1, 2)
          isFocusPainted = false
          isBorderPainted = false
          isContentAreaFilled = false
          toolTipText = "Очистить историю"
          addActionListener {
            historyService.clear()
            refreshHistoryLog()
          }
        }
        cell(clearBtn).align(AlignX.RIGHT)
        toggleBtn.addActionListener {
          historyExpanded = !historyExpanded
          toggleBtn.icon = if (historyExpanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
          refreshHistoryLog()
        }
      }
      row {
        cell(historyScroll).resizableColumn()
      }
    }
    
    mainPanel.border = JBUI.Borders.empty(4, 8)
    add(mainPanel, BorderLayout.NORTH)
    
    refreshBranchCombo()
    refreshStatus()
    refreshHistoryLog()
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
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Стягивание ${branches.size} веток", true) {
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
        refreshBranchCombo()
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
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Отправка $branchName", true) {
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
        refreshBranchCombo()
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
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Отправка ${branches.size} веток", true) {
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
        refreshBranchCombo()
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
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Экспорт bundle", true) {
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
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Импорт bundle", true) {
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
        refreshBranchCombo()
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
      "Удалить ${branchNames.size} веток локально и на Mirror?\n\n${branchNames.joinToString("\n")}",
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
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Удаление веток", true) {
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
            errors.add("$branch (Mirror): ${mirrorResult.body.take(100)}")
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
        refreshBranchCombo()
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
        label("🔗 LocalGitMirror").bold()
      }
      
      row {
        label("URL Mirror")
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
              "Серверы Mirror не найдены в локальной сети.\nПроверьте, что Mirror запущен и доступен.",
              "Поиск Mirror"
            )
          }
          servers.size == 1 -> {
            setupUrl = servers.first().toUrl()
            setupFormPanel?.reset()
          }
          else -> {
            val options = servers.map { "${it.toUrl()} (${it.ip})" }.toTypedArray()
            val chosen = Messages.showEditableChooseDialog(
              "Найдено несколько серверов Mirror. Выберите:",
              "Поиск Mirror",
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
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Проверка подключения", true) {
      override fun run(indicator: ProgressIndicator) {
        currentIndicator = indicator
        try {
          indicator.text = "Проверяем подключение к Mirror…"
          val probe = MirrorApi.passwordProbe(url, setupApiKey, s.mirrorInsecureTls)
          if (probe.code !in 200..299) {
            val msg = if (probe.code == 0)
              "Mirror недоступен: ${probe.message}"
            else
              "Ошибка подключения (HTTP ${probe.code}): ${probe.message.take(200)}"
            notify(msg, NotificationType.ERROR)
            return
          }

          // Save settings
          s.baseUrl = url
          SecretsStore.mirrorApiKey = setupApiKey
          SecretsStore.syncPassword = setupSyncPassword

          notify("Подключение к Mirror установлено успешно.", NotificationType.INFORMATION)

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
      .getNotificationGroup("LocalGitMirror")
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
    val role = localgitmirror.idea.deps.RoleDetector.describe(s)
    val divergedCount = countDivergedBranches()

    // Resolve Mirror repo for tooltip (single source of truth)
    val repoRes = try { syncFacade.resolveRepo(dir, s) } catch (_: Throwable) { null }

    if (connected) {
      val branchCount = GitLocal.listBranches(project, dir).size
      val branchesWord = LocalGitMirrorBundle.message("status.branches", branchCount)
      val arrow = if (divergedCount > 0) " \u2191" else ""
      status.text = LocalGitMirrorBundle.message("panel.status.connected") +
        " \u00b7 $branchCount $branchesWord$arrow \u00b7 " + role
    } else {
      status.text = LocalGitMirrorBundle.message("panel.status.disconnected") + " \u00b7 " + role
    }
    status.toolTipText = repoRes?.let {
      "Mirror repo '${it.sanitized}' \u00b7 source: ${it.source.name.lowercase().replace('_', ' ')}"
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

  internal fun refreshHistoryLog() {
    val entries = historyService.latest(40)
    if (::historyScroll.isInitialized) {
      historyScroll.isVisible = historyExpanded && entries.isNotEmpty()
    }
    historyListModel.clear()
    entries.forEach { historyListModel.addElement(it) }
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
