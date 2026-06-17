package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.*
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.awt.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

class LocalGitMirrorPanel(val project: Project) : JPanel(BorderLayout()) {
  internal val log = JTextArea()
  internal val status = JBLabel("")
  internal val mirrorBadge = BadgeLabel("Mirror: ?")
  internal val lastSyncBadge = BadgeLabel("Last sync: \u2014")
  internal val versionBadge = BadgeLabel("").apply {
    // Read plugin version at runtime from the platform's plugin descriptor
    val pluginId = com.intellij.openapi.extensions.PluginId.getId("localgitmirror.idea.orchestrator")
    val descriptor = com.intellij.ide.plugins.PluginManagerCore.getPlugin(pluginId)
    text = if (descriptor != null) "v${descriptor.version}" else "v?"
    status = BadgeLabel.Status.NEUTRAL
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

  internal lateinit var historyScroll: JScrollPane

  internal val historyService = service<OperationsHistoryService>()
  internal val syncFacade = project.getService(SyncFacadeService::class.java)

  // ── Branch selector (JComboBox) ──
  // Populated from local branches; enriched with Mirror branches during pull
  internal val branchCombo = JComboBox<String>().apply {
    font = JBUI.Fonts.smallFont()
    toolTipText = "Ветка для Отправить / Подтянуть"
  }

  // Additional branches to include on send (legacy chip behaviour kept as internal set)
  internal val selectedAdditionalBranches = mutableSetOf<String>()

  // Dynamic UI containers
  internal val badgesPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }
  internal val actionsBox = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    alignmentX = LEFT_ALIGNMENT
  }
  internal val moreMenu = JPopupMenu()
  internal val autoPullItem = JCheckBoxMenuItem(LocalGitMirrorBundle.message("settings.sync.autoCheck"))

  internal var isSyncing = false
    set(value) {
      field = value
      updateUiState()
    }

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

  private fun updateUiState() {
    UIUtil.invokeLaterIfNeeded {
      val enabled = !isSyncing
      fun disableAll(container: java.awt.Container) {
        for (c in container.components) {
          if (c is JButton || c is JToggleButton || c is JComboBox<*>) c.isEnabled = enabled
          if (c is java.awt.Container) disableAll(c)
        }
      }
      disableAll(this)
      progressBar.isVisible = isSyncing
      progressLabel.isVisible = isSyncing
      if (!isSyncing) {
        progressLabel.text = ""
        progressBar.isIndeterminate = true
        progressBar.value = 0
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

  private fun actionRow(vararg components: JComponent): JPanel {
    val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    row.isOpaque = false
    row.alignmentX = LEFT_ALIGNMENT
    row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
    components.forEach { row.add(it) }
    return row
  }

  /** Refresh branch combo from local branches. */
  internal fun refreshBranchCombo() {
    val dir = baseDir() ?: return
    val branches = GitLocal.listBranches(project, dir)
    val current = GitLocal.currentBranch(project, dir)

    val selected = (branchCombo.selectedItem as? String)?.takeIf { it in branches } ?: current

    branchCombo.removeAllItems()
    branches.forEach { branchCombo.addItem(it) }
    if (selected != null && branches.contains(selected)) {
      branchCombo.selectedItem = selected
    } else if (branches.isNotEmpty()) {
      branchCombo.selectedItem = branches.first()
    }
  }

  /** Returns the branch currently chosen in the selector, or current git branch as fallback. */
  internal fun selectedBranch(): String? {
    val combo = branchCombo.selectedItem as? String
    if (!combo.isNullOrBlank()) return combo
    val dir = baseDir() ?: return null
    return GitLocal.currentBranch(project, dir)
  }

  /** Rebuild gear menu. */
  internal fun rebuildGearMenu() {
    val settingsState = service<MirrorSettingsService>().state
    moreMenu.removeAll()

    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.preflight"), AllIcons.General.InspectionsOK) { runPreflight() })
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.dryRunSend"), AllIcons.Actions.Preview) { runDryRun() })
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.dryRunPull"), AllIcons.Actions.Preview) { runPullDryRun() })
    moreMenu.addSeparator()
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.menu.sendBranch"), AllIcons.Vcs.Branch) { syncBranch() })
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.menu.sendAs"), AllIcons.Actions.Copy) { pushAs() })
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.sendCommits"), AllIcons.Vcs.History) { syncSelectedCommits() })
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.pullBack"), AllIcons.Actions.Diff) { pullBack() })
    moreMenu.addSeparator()
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.menu.applyLocalDump"), AllIcons.Actions.OpenNewTab) { applyLocalDump() })
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.menu.testMirror"), AllIcons.Actions.Checked) { testMirror() })
    moreMenu.addSeparator()
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.menu.copyConfig"), AllIcons.Actions.Copy) { copyConfigLine() })
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.menu.pasteConfig"), AllIcons.Actions.Upload) { pasteConfigLine() })
    moreMenu.addSeparator()
    autoPullItem.isSelected = settingsState.autoCheckPullOnStartup
    moreMenu.add(autoPullItem)
    moreMenu.add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.menu.settings"), AllIcons.General.Settings) {
      ShowSettingsUtil.getInstance().showSettingsDialog(project, "localgitmirror.settings")
      refreshStatus()
    })
  }

  /** Rebuild action buttons. */
  internal fun rebuildActions() {
    actionsBox.removeAll()

    // Selector row: label + combobox
    val selectorRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    selectorRow.isOpaque = false
    selectorRow.alignmentX = LEFT_ALIGNMENT
    val branchLabel = JBLabel(LocalGitMirrorBundle.message("panel.branch.label"))
    branchLabel.font = JBUI.Fonts.smallFont()
    branchLabel.foreground = UIUtil.getContextHelpForeground()
    selectorRow.add(branchLabel)
    selectorRow.add(branchCombo)
    actionsBox.add(selectorRow)
    actionsBox.add(Box.createVerticalStrut(JBUI.scale(4)))

    // Primary action row: Pull + Send
    actionsBox.add(actionRow(
      primaryBtn(LocalGitMirrorBundle.message("toolwindow.pullFromMirror"), AllIcons.Actions.Download) { pullFromMirror() },
      primaryBtn(LocalGitMirrorBundle.message("toolwindow.sendCurrent"), AllIcons.Actions.Upload) { syncCurrentBranch() }
    ))

    rebuildGearMenu()
    revalidate()
    repaint()
  }

  init {
    layout = BorderLayout()

    val settingsState = service<MirrorSettingsService>().state

    val topContainer = JPanel()
    topContainer.layout = BoxLayout(topContainer, BoxLayout.Y_AXIS)
    topContainer.border = JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(8))

    // ── Header row: badges LEFT, gear RIGHT ──
    val headerRow = JPanel(BorderLayout(JBUI.scale(4), 0))
    headerRow.isOpaque = false
    headerRow.alignmentX = LEFT_ALIGNMENT

    badgesPanel.add(mirrorBadge)
    badgesPanel.add(lastSyncBadge)
    badgesPanel.add(versionBadge)
    headerRow.add(badgesPanel, BorderLayout.CENTER)

    autoPullItem.addActionListener { settingsState.autoCheckPullOnStartup = autoPullItem.isSelected }
    moreMenu.addPopupMenuListener(object : PopupMenuListener {
      override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
        autoPullItem.isSelected = service<MirrorSettingsService>().state.autoCheckPullOnStartup
      }
      override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}
      override fun popupMenuCanceled(e: PopupMenuEvent) {}
    })

    rebuildGearMenu()

    val gearBtn = JButton(AllIcons.General.Settings)
    gearBtn.margin = JBUI.insets(1)
    gearBtn.isFocusPainted = false
    gearBtn.isBorderPainted = false
    gearBtn.isContentAreaFilled = false
    gearBtn.toolTipText = LocalGitMirrorBundle.message("toolwindow.menu.settings")
    gearBtn.addActionListener { moreMenu.show(gearBtn, 0, gearBtn.height) }
    headerRow.add(gearBtn, BorderLayout.EAST)
    topContainer.add(headerRow)

    // ── Status line ──
    status.font = JBUI.Fonts.smallFont()
    status.foreground = UIUtil.getContextHelpForeground()
    status.alignmentX = LEFT_ALIGNMENT
    status.border = JBUI.Borders.empty(2, 0)
    topContainer.add(status)

    topContainer.add(Box.createVerticalStrut(JBUI.scale(4)))

    // ── Branch selector + action buttons ──
    refreshBranchCombo()
    rebuildActions()
    topContainer.add(actionsBox)

    topContainer.add(Box.createVerticalStrut(JBUI.scale(2)))

    // ── Progress row: bar + stage label ──
    val progressRow = JPanel(BorderLayout(JBUI.scale(4), 0))
    progressRow.isOpaque = false
    progressRow.alignmentX = LEFT_ALIGNMENT
    progressRow.add(progressBar, BorderLayout.CENTER)
    progressRow.add(progressLabel, BorderLayout.EAST)
    topContainer.add(progressRow)

    // ── History toggle + clear ──
    val historyToggleRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
    historyToggleRow.isOpaque = false
    historyToggleRow.alignmentX = LEFT_ALIGNMENT
    val historyToggle = JToggleButton(LocalGitMirrorBundle.message("toolwindow.history"))
    historyToggle.margin = JBUI.insets(2, 4)
    historyToggle.font = JBUI.Fonts.smallFont()
    historyToggle.isFocusPainted = false
    historyToggleRow.add(historyToggle)

    val clearBtn = JButton(AllIcons.Actions.GC)
    clearBtn.margin = JBUI.insets(1, 2)
    clearBtn.isFocusPainted = false
    clearBtn.isBorderPainted = false
    clearBtn.isContentAreaFilled = false
    clearBtn.toolTipText = LocalGitMirrorBundle.message("toolwindow.history.clear")
    clearBtn.addActionListener {
      historyService.clear()
      refreshHistoryLog()
      log.text = ""
    }
    historyToggleRow.add(clearBtn)
    topContainer.add(historyToggleRow)

    add(topContainer, BorderLayout.NORTH)

    log.isEditable = false; log.lineWrap = true; log.wrapStyleWord = true
    log.font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(11))
    historyScroll = JScrollPane(log); historyScroll.isVisible = false
    add(historyScroll, BorderLayout.CENTER)

    historyToggle.addActionListener {
      historyScroll.isVisible = historyToggle.isSelected
      historyToggle.text = if (historyToggle.isSelected)
        LocalGitMirrorBundle.message("toolwindow.history").replace("\u25be", "\u25b4")
      else LocalGitMirrorBundle.message("toolwindow.history")
      revalidate(); repaint()
    }

    refreshStatus()
    refreshHistoryLog()
  }

  internal fun baseDir(): File? {
    val basePath = project.basePath ?: return null
    if (basePath.isBlank()) return null
    return File(basePath)
  }

  internal fun append(line: String) {
    log.append(line)
    log.append("\n")
  }

  internal fun notify(message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("LocalGitMirror")
      .createNotification(message, type)
      .notify(project)
  }

  internal fun refreshStatus() {
    val dir = baseDir()
    if (dir == null) {
      status.text = LocalGitMirrorBundle.message("notify.projectDir.missing")
      return
    }
    val branch = GitLocal.currentBranch(project, dir) ?: "(unknown)"
    val clean = GitLocal.isCleanWorkTree(project, dir)
    val s = service<MirrorSettingsService>().state

    val cleanText = if (clean)
      LocalGitMirrorBundle.message("panel.status.clean")
    else
      LocalGitMirrorBundle.message("panel.status.dirty")
    status.text = "$branch · $cleanText"

    rebuildActions()
    refreshBranchCombo()

    val mirrorConfigured = s.baseUrl.isNotBlank() && SecretsStore.syncPassword.isNotBlank()
    mirrorBadge.text = if (mirrorConfigured)
      LocalGitMirrorBundle.message("toolwindow.badge.mirrorConnected")
    else
      LocalGitMirrorBundle.message("toolwindow.badge.mirrorNotConfigured")
    mirrorBadge.status = if (mirrorConfigured) BadgeLabel.Status.GOOD else BadgeLabel.Status.BAD
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
    val entries = historyService.latest(20)
    if (entries.isEmpty()) {
      log.text = "No operations yet"
      return
    }
    log.text = entries.joinToString("\n") { e ->
      "${e.timestamp} [${e.status}] ${e.operation}: ${e.details}"
    }
    log.caretPosition = 0
  }
}
