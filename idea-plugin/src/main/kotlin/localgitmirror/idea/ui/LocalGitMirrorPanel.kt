package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.*
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.awt.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*

class LocalGitMirrorPanel(val project: Project) : JPanel(BorderLayout()) {
  internal val log = JTextArea()
  internal val status = JBLabel("")
  internal val mirrorBadge = BadgeLabel("Mirror: ?")
  internal val lastSyncBadge = BadgeLabel("Last sync: \u2014")
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

  internal lateinit var historyScroll: JScrollPane

  internal val historyService = service<OperationsHistoryService>()
  internal val syncFacade = project.getService(SyncFacadeService::class.java)

  // ── Branch selector (JComboBox) ──
  // Shows local branches immediately and appends Mirror-only branches after a
  // background refs request. BranchChoice keeps the raw name for actions.
  internal val branchCombo = JComboBox<BranchChoice>().apply {
    font = JBUI.Fonts.smallFont()
    toolTipText = "Ветка для Отправить / Подтянуть; ★ есть только на Mirror"
  }
  private val branchRefreshButton = JButton(AllIcons.Actions.Refresh).apply {
    margin = JBUI.insets(1)
    isFocusPainted = false
    toolTipText = "Обновить ветки с Mirror"
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

  private fun gearSubmenu(title: String, icon: Icon? = null, build: JMenu.() -> Unit): JMenu =
    JMenu(title).apply {
      this.icon = icon
      build()
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
    replaceBranchChoices(localBranches, mirrorBranches, currentBranch)
    refreshMirrorBranches(dir, localBranches, currentBranch, userInitiated)
  }

  private var mirrorBranches: Set<String> = emptySet()

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
          mirrorBranches = result.refs.keys
          replaceBranchChoices(localBranches, mirrorBranches, currentBranch)
          finishBranchRefresh("Mirror: ${mirrorBranches.size} веток")
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
    branchRefreshButton.toolTipText = if (inProgress) "Обновляем ветки Mirror…" else "Обновить ветки с Mirror"
    if (inProgress) branchCombo.toolTipText = "Загружаем ветки с Mirror…"
  }

  private fun finishBranchRefresh(detail: String) {
    setBranchRefreshInProgress(false)
    branchCombo.toolTipText = "Ветка для Отправить / Подтянуть; ★ есть только на Mirror. $detail"
  }

  private fun replaceBranchChoices(
    localBranches: List<String>,
    mirrorBranches: Collection<String>,
    currentBranch: String?
  ) {
    val selectedName = selectedBranchChoice()?.name
    val choices = BranchSelectorModel.merge(localBranches, mirrorBranches)
    val preferred = BranchSelectorModel.preferredSelection(selectedName, currentBranch, choices)

    branchCombo.removeAllItems()
    choices.forEach(branchCombo::addItem)
    if (preferred != null) {
      branchCombo.selectedItem = choices.firstOrNull { it.name == preferred }
    }
  }

  internal fun selectedBranchChoice(): BranchChoice? = branchCombo.selectedItem as? BranchChoice

  /** Returns the raw branch name currently chosen in the selector. */
  internal fun selectedBranch(): String? {
    selectedBranchChoice()?.name?.let { return it }
    val dir = baseDir() ?: return null
    return GitLocal.currentBranch(project, dir)
  }

  /** Rebuild the concise gear menu; routine actions stay at the top, rare tools are grouped. */
  internal fun rebuildGearMenu() {
    moreMenu.removeAll()

    val versionItem = JMenuItem("LocalGitMirror $pluginVersionText")
    versionItem.isEnabled = false
    moreMenu.add(versionItem)
    moreMenu.add(gearMenuItem("Обновить ветки Mirror", AllIcons.Actions.Refresh) { refreshBranchCombo(userInitiated = true) })
    moreMenu.add(gearMenuItem("Управление ветками Mirror…", AllIcons.Vcs.Branch) {
      runRegisteredAction("LocalGitMirror.ManageBranches")
    })
    moreMenu.add(gearMenuItem("Проверить подключение", AllIcons.Actions.Checked) { testMirror() })
    moreMenu.addSeparator()

    moreMenu.add(gearSubmenu("Другие операции с Git", AllIcons.Vcs.Branch) {
      add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.menu.sendBranch"), AllIcons.Vcs.Branch) { syncBranch() })
      add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.menu.sendAs"), AllIcons.Actions.Copy) { pushAs() })
      add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.sendCommits"), AllIcons.Vcs.History) { syncSelectedCommits() })
      addSeparator()
      add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.pullBack"), AllIcons.Actions.Diff) { pullBack() })
      add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.menu.applyLocalDump"), AllIcons.Actions.OpenNewTab) { applyLocalDump() })
    })
    moreMenu.add(gearSubmenu("Файлы между компьютерами", AllIcons.Actions.Upload) {
      add(gearMenuItem("Отправить выбранный файл…", AllIcons.Actions.Upload) {
        runRegisteredAction("LocalGitMirror.FileSendSelected")
      })
      add(gearMenuItem("Получить файл…", AllIcons.Actions.Download) {
        runRegisteredAction("LocalGitMirror.FileFetch")
      })
    })
    moreMenu.add(gearSubmenu("Корпоративные зависимости", AllIcons.Actions.Download) {
      add(gearMenuItem(LocalGitMirrorBundle.message("deps.menu.request"), AllIcons.Actions.Download) {
        runRegisteredAction("LocalGitMirror.DepsRequest")
      })
      add(gearMenuItem(LocalGitMirrorBundle.message("deps.menu.respond"), AllIcons.Actions.Upload) {
        runRegisteredAction("LocalGitMirror.DepsRespond")
      })
      add(gearMenuItem(LocalGitMirrorBundle.message("deps.menu.apply"), AllIcons.Actions.OpenNewTab) {
        runRegisteredAction("LocalGitMirror.DepsApply")
      })
    })
    moreMenu.add(gearSubmenu("Общий буфер", AllIcons.Actions.Copy) {
      add(gearMenuItem(LocalGitMirrorBundle.message("buffer.menu.send"), AllIcons.Actions.Upload) {
        runRegisteredAction("LocalGitMirror.BufferSend")
      })
      add(gearMenuItem(LocalGitMirrorBundle.message("buffer.menu.paste"), AllIcons.Actions.Download) {
        runRegisteredAction("LocalGitMirror.BufferPaste")
      })
      add(gearMenuItem(LocalGitMirrorBundle.message("buffer.menu.history"), AllIcons.Vcs.History) {
        runRegisteredAction("LocalGitMirror.BufferHistory")
      })
    })
    moreMenu.add(gearSubmenu("Диагностика и конфигурация", AllIcons.General.InspectionsOK) {
      add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.preflight"), AllIcons.General.InspectionsOK) { runPreflight() })
      add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.dryRunSend"), AllIcons.Actions.Preview) { runDryRun() })
      add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.dryRunPull"), AllIcons.Actions.Preview) { runPullDryRun() })
      addSeparator()
      add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.menu.copyConfig"), AllIcons.Actions.Copy) { copyConfigLine() })
      add(gearMenuItem(LocalGitMirrorBundle.message("toolwindow.menu.pasteConfig"), AllIcons.Actions.Upload) { pasteConfigLine() })
    })
    moreMenu.addSeparator()
    moreMenu.add(gearMenuItem("Скачать обновление плагина…", AllIcons.Actions.Download) { downloadLatestPlugin() })
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
    selectorRow.add(branchRefreshButton)
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

    val topContainer = JPanel()
    topContainer.layout = BoxLayout(topContainer, BoxLayout.Y_AXIS)
    topContainer.border = JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(8))

    // ── Header row: badges LEFT, gear RIGHT ──
    val headerRow = JPanel(BorderLayout(JBUI.scale(4), 0))
    headerRow.isOpaque = false
    headerRow.alignmentX = LEFT_ALIGNMENT

    badgesPanel.add(mirrorBadge)
    badgesPanel.add(lastSyncBadge)
    badgesPanel.alignmentX = LEFT_ALIGNMENT

    rebuildGearMenu()

    val gearBtn = JButton(AllIcons.General.Settings)
    gearBtn.margin = JBUI.insets(1)
    gearBtn.isFocusPainted = false
    gearBtn.isBorderPainted = false
    gearBtn.isContentAreaFilled = false
    gearBtn.toolTipText = "LocalGitMirror $pluginVersionText \u00b7 ${LocalGitMirrorBundle.message("toolwindow.menu.settings")}"
    gearBtn.addActionListener { moreMenu.show(gearBtn, 0, gearBtn.height) }
    headerRow.add(gearBtn, BorderLayout.EAST)
    topContainer.add(headerRow)
    // Badges live on their own full-width row so the WrapLayout can wrap them
    // onto a second line in a narrow tool window instead of truncating ("…").
    topContainer.add(badgesPanel)

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

    // Auto-refresh history on any new entry, regardless of which thread added it.
    val listener: () -> Unit = {
      com.intellij.util.ui.UIUtil.invokeLaterIfNeeded { refreshHistoryLog() }
    }
    historyService.addChangeListener(listener)
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

    // Show the RESOLVED Mirror repo (single source of truth) so it's always
    // visible where a sync will go — and from which source it was derived.
    val repoRes = try { syncFacade.resolveRepo(dir, s) } catch (_: Throwable) { null }
    val repoName = repoRes?.sanitized?.takeIf { it.isNotBlank() } ?: "?"
    status.text = "$repoName · $branch · $cleanText"
    status.toolTipText = repoRes?.let {
      "Mirror repo '${it.sanitized}' · source: ${it.source.name.lowercase().replace('_', ' ')}"
    }

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
