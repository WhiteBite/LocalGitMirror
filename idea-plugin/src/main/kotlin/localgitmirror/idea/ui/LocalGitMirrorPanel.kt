package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.actions.PullFromMirrorAction
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.gitlab.MrNotesWriter
import localgitmirror.idea.gitlab.MrReviewService
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.net.LanDiscovery
import localgitmirror.idea.settings.*
import localgitmirror.idea.sync.HandshakeCache
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

  // ── Branch selector (JBList with status) ──
  // Shows local branches immediately and appends Mirror-only branches after a
  // background refs request. BranchListItem keeps the raw name + status for actions.
  internal val branchListModel = DefaultListModel<BranchListItem>()
  // All items before filtering — used by branchFilterField to re-apply the filter.
  private var allBranchItems: List<BranchListItem> = emptyList()
  private var respondButton: javax.swing.JButton? = null
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

  internal val filesListModel = DefaultListModel<String>()
  internal val filesList = JBList(filesListModel).apply {
    font = JBUI.Fonts.smallFont()
    fixedCellHeight = JBUI.scale(24)
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = IconTextCellRenderer(AllIcons.FileTypes.Any_type)
    emptyText.text = LocalGitMirrorBundle.message("panel.exchange.files.empty")
    emptyText.appendLine(
      LocalGitMirrorBundle.message("panel.exchange.files.refresh"),
      SimpleTextAttributes.LINK_ATTRIBUTES
    ) { refreshExchangeInBackground() }
  }

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
  internal val bufferListModel = DefaultListModel<MirrorApi.BufferItem>()
  internal val bufferEntriesList = JBList(bufferListModel).apply {
    font = JBUI.Fonts.smallFont()
    fixedCellHeight = JBUI.scale(24)
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = BufferCellRenderer()
    emptyText.text = LocalGitMirrorBundle.message("panel.exchange.buffer.empty")
    emptyText.appendLine(LocalGitMirrorBundle.message("panel.exchange.buffer.emptyNote"))
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount >= 2) pasteSelectedBufferEntry()
      }
    })
  }

  private inner class BufferCellRenderer : ColoredListCellRenderer<MirrorApi.BufferItem>() {
    override fun customizeCellRenderer(
      list: JList<out MirrorApi.BufferItem>,
      value: MirrorApi.BufferItem?,
      index: Int,
      selected: Boolean,
      hasFocus: Boolean
    ) {
      if (value == null) return
      icon = AllIcons.Actions.Copy
      iconTextGap = JBUI.scale(6)
      border = JBUI.Borders.empty(1, 6)
      val preview = value.hint.ifBlank { LocalGitMirrorBundle.message("buffer.history.emptyHint") }
      append(
        LocalGitMirrorBundle.message("buffer.history.row", formatBufferTs(value.ts), value.size.toString(), preview),
        SimpleTextAttributes.REGULAR_ATTRIBUTES
      )
    }
  }

  private fun resetBufferEmptyText() {
    bufferEntriesList.emptyText.clear()
    bufferEntriesList.emptyText.text = LocalGitMirrorBundle.message("panel.exchange.buffer.empty")
    bufferEntriesList.emptyText.appendLine(LocalGitMirrorBundle.message("panel.exchange.buffer.emptyNote"))
  }

  internal fun pasteSelectedBufferEntry() {
    val item = bufferEntriesList.selectedValue ?: return
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("buffer.task.paste"), true) {
      override fun run(indicator: ProgressIndicator) {
        localgitmirror.idea.actions.pasteBufferEntryById(project, item.id, item.ts)
      }
    })
  }
  internal val roleBadge = BadgeLabel("")
  private var tabsPane: JBTabbedPane? = null

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
    selectionMode = ListSelectionModel.SINGLE_SELECTION
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
      val amber = JBColor(0xE3AE4D, 0xE3AE4D)
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
      nameLabel.font = JBUI.Fonts.smallFont().let { if (value.isCurrent) it.asBold() else it }
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

      deltaLabel.text = when (value.status) {
        BranchStatus.AHEAD -> value.aheadCount?.let { "+$it" } ?: ""
        BranchStatus.BEHIND -> value.behindCount?.let { "\u2212$it" } ?: ""
        else -> ""
      }
      deltaLabel.foreground = if (isSelected) selFg else statusFg
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
      val shortDetails = value.details.lineSequence().firstOrNull()?.take(60) ?: ""
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

  /** Primary (accent) button. */
  private fun primaryBtn(title: String, icon: Icon? = null, action: () -> Unit): JButton {
    val b = JButton(title, icon)
    b.margin = JBUI.insets(2, 10)
    b.font = b.font.deriveFont(Font.BOLD)
    b.putClientProperty("JButton.buttonType", "default")
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
    tabs.setToolTipTextAt(0, LocalGitMirrorBundle.message("panel.branch.legend"))
    tabs.addChangeListener { onTabChanged(tabs.selectedIndex) }
    tabsPane = tabs

    val center = JPanel(BorderLayout()).apply { isOpaque = false }
    center.add(progressRow, BorderLayout.NORTH)
    center.add(tabs, BorderLayout.CENTER)
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

  private fun onTabChanged(index: Int) {
    if (project.isDisposed || ApplicationManager.getApplication().isDisposeInProgress) return
    when (index) {
      1 -> refreshReview()
      2 -> refreshDepsInBackground()
      3 -> refreshExchangeInBackground()
    }
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
    statusRow.add(status, BorderLayout.WEST)
    statusRow.add(roleBadge, BorderLayout.EAST)

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
    val respondBtn = primaryBtn(LocalGitMirrorBundle.message("deps.menu.respond")) {
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
    val bufferButtons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
      isOpaque = false
      border = JBUI.Borders.empty(4, 0, 0, 0)
      add(btn(LocalGitMirrorBundle.message("panel.exchange.buffer.paste"), AllIcons.Actions.Copy) {
        pasteSelectedBufferEntry()
      })
      add(btn(LocalGitMirrorBundle.message("toolwindow.menu.refresh"), AllIcons.Actions.Refresh) {
        refreshExchangeInBackground()
      })
      add(primaryBtn(LocalGitMirrorBundle.message("buffer.menu.send")) {
        triggerLgmAction("LocalGitMirror.BufferSend")
      })
    }

    val bufferSection = JPanel(BorderLayout()).apply {
      isOpaque = false
      add(sectionHeader(LocalGitMirrorBundle.message("panel.exchange.buffer")), BorderLayout.NORTH)
      add(JScrollPane(bufferEntriesList).apply {
        border = BorderFactory.createEmptyBorder()
        viewportBorder = BorderFactory.createEmptyBorder()
      }, BorderLayout.CENTER)
      add(bufferButtons, BorderLayout.SOUTH)
    }

    val filesSection = JPanel(BorderLayout()).apply {
      isOpaque = false
      add(sectionHeader(LocalGitMirrorBundle.message("panel.exchange.files")), BorderLayout.NORTH)
      add(JScrollPane(filesList).apply {
        border = BorderFactory.createEmptyBorder()
        viewportBorder = BorderFactory.createEmptyBorder()
      }, BorderLayout.CENTER)
    }

    val split = OnePixelSplitter(true, 0.45f)
    split.firstComponent = bufferSection
    split.secondComponent = filesSection

    val bottom = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
      isOpaque = false
      border = JBUI.Borders.empty(4, 0, 0, 0)
      add(btn(LocalGitMirrorBundle.message("action.LocalGitMirror.FileSendSelected.text")) {
        triggerLgmAction("LocalGitMirror.FileSendSelected")
      })
      add(btn(LocalGitMirrorBundle.message("action.LocalGitMirror.FileFetch.text")) {
        triggerLgmAction("LocalGitMirror.FileFetch")
      })
    }

    return JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.empty(4, 8)
      add(split, BorderLayout.CENTER)
      add(bottom, BorderLayout.SOUTH)
    }
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
      add(btn(LocalGitMirrorBundle.message("review.fetch"), AllIcons.Actions.Refresh) {
        reloadReview()
      })
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
  }

  private fun reloadReview() {
    project.getService(MrReviewService::class.java).refreshInBackground(notify = true) {
      if (project.isDisposed) return@refreshInBackground
      refreshReview()
    }
  }

  private fun updateReviewTabTitle() {
    val attn = project.getService(MrReviewService::class.java)
      .cachedRows().count { it.unresolved > 0 }
    tabsPane?.let { tabs ->
      if (tabs.tabCount > 1) {
        tabs.setTitleAt(1, if (attn > 0)
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
    val role = localgitmirror.idea.deps.RoleDetector.describe(s)
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
    roleBadge.text = role
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
        tabs.setTitleAt(0, LocalGitMirrorBundle.message("tab.branches.count", branchCount))
      }
      if (tabs.tabCount > 2) {
        tabs.setTitleAt(2, if (actionable > 0)
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

  /** Fetch buffer head entry + repo file list off the EDT for the Exchange tab. */
  internal fun refreshExchangeInBackground() {
    if (project.isDisposed || ApplicationManager.getApplication().isDisposeInProgress) return
    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) return
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: exchange", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.checkCanceled()
        val buffer = MirrorApi.bufferList(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls)
        val dir = baseDir()
        val repo = if (dir != null)
          try { syncFacade.resolveRepo(dir, s).sanitized } catch (_: Throwable) { "" }
        else ""
        indicator.checkCanceled()
        val files = if (repo.isNotBlank())
          MirrorApi.fileSyncList(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls)
        else null
        UIUtil.invokeLaterIfNeeded {
          if (project.isDisposed) return@invokeLaterIfNeeded
          val selectedId = bufferEntriesList.selectedValue?.id
          bufferListModel.clear()
          if (buffer.code in 200..299) {
            resetBufferEmptyText()
            buffer.items.forEach { bufferListModel.addElement(it) }
            val idx = (0 until bufferListModel.size()).firstOrNull { bufferListModel.getElementAt(it).id == selectedId }
            if (idx != null) bufferEntriesList.selectedIndex = idx
          } else {
            bufferEntriesList.emptyText.clear()
            bufferEntriesList.emptyText.text =
              LocalGitMirrorBundle.message("panel.exchange.buffer.listFail", buffer.code)
          }
          filesListModel.clear()
          if (files != null && files.code in 200..299) {
            files.items.forEach {
              filesListModel.addElement(LocalGitMirrorBundle.message("panel.exchange.files.row", it.path, it.size))
            }
          }
        }
      }
    })
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
