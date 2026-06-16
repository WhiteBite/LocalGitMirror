package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
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

class LocalGitMirrorPanel(val project: Project) : JPanel(BorderLayout()) {
  internal val log = JTextArea()
  internal val status = JBLabel("")
  internal val mirrorBadge = BadgeLabel("Mirror: ?")
  internal val gitLabBadge = BadgeLabel("GitLab: ?")
  internal val lastSyncBadge = BadgeLabel("Last sync: \u2014")
  internal val progressBar = JProgressBar().apply { isVisible = false; isIndeterminate = true }

  internal lateinit var historyScroll: JScrollPane

  internal val historyService = service<OperationsHistoryService>()
  internal val syncFacade = project.getService(SyncFacadeService::class.java)

  internal val branchChipPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
  internal val selectedAdditionalBranches = mutableSetOf<String>()

  internal fun isWorkMode(): Boolean = service<MirrorSettingsService>().state.isWorkMode()

  internal var isSyncing = false
    set(value) {
      field = value
      updateUiState()
    }

  internal enum class SyncOutcome { OK, FAIL }

  private fun updateUiState() {
    UIUtil.invokeLaterIfNeeded {
      val enabled = !isSyncing
      // Disable all buttons in the top container during sync
      fun disableAll(container: java.awt.Container) {
        for (c in container.components) {
          if (c is JButton || c is JToggleButton) c.isEnabled = enabled
          if (c is java.awt.Container) disableAll(c)
        }
      }
      disableAll(this)
      progressBar.isVisible = isSyncing
    }
  }

  /** Standard action button — natural size, no stretching. */
  private fun btn(title: String, icon: Icon? = null, action: () -> Unit): JButton {
    val b = JButton(title, icon)
    b.margin = JBUI.insets(2, 8)
    b.font = b.font.deriveFont(JBUI.scale(12f).toFloat())
    b.isFocusPainted = false
    b.addActionListener { action() }
    return b
  }

  /** Primary action button — IntelliJ default style (accent). */
  private fun primaryBtn(title: String, icon: Icon? = null, action: () -> Unit): JButton {
    val b = JButton(title, icon)
    b.margin = JBUI.insets(2, 8)
    b.font = b.font.deriveFont(Font.BOLD)
    b.putClientProperty("JButton.buttonType", "default")
    b.addActionListener { action() }
    return b
  }

  private fun createBranchChip(branchName: String): JToggleButton {
    val chip = JToggleButton(LocalGitMirrorBundle.message("send.addBaseBranch", branchName))
    chip.font = chip.font.deriveFont(JBUI.scale(11f).toFloat())
    chip.margin = JBUI.insets(2, 8)
    chip.isFocusPainted = false
    chip.border = BorderFactory.createEmptyBorder(JBUI.scale(3), JBUI.scale(10), JBUI.scale(3), JBUI.scale(10))
    chip.isSelected = selectedAdditionalBranches.contains(branchName)
    chip.addActionListener {
      if (chip.isSelected) selectedAdditionalBranches.add(branchName)
      else selectedAdditionalBranches.remove(branchName)
      updateChipStyles()
    }
    styleChip(chip)
    return chip
  }

  private fun styleChip(chip: JToggleButton) {
    if (chip.isSelected) {
      chip.background = JBColor(Color(0x3574F0), Color(0x4A86E8))
      chip.foreground = JBColor(Color.WHITE, Color.WHITE)
    } else {
      chip.background = JBColor(Color(0xE0E0E0), Color(0x3C3C3C))
      chip.foreground = JBColor(Color(0x333333), Color(0xBBBBBB))
    }
  }

  private fun updateChipStyles() {
    branchChipPanel.components.filterIsInstance<JToggleButton>().forEach { styleChip(it) }
  }

  private fun refreshBranchChips() {
    branchChipPanel.removeAll()
    val dir = baseDir() ?: return
    val branches = GitLocal.listBranches(project, dir)
    val currentBranch = GitLocal.currentBranch(project, dir) ?: return

    val currentLbl = JBLabel(LocalGitMirrorBundle.message("send.currentBranch", currentBranch))
    currentLbl.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
    currentLbl.border = JBUI.Borders.emptyRight(4)
    branchChipPanel.add(currentLbl)

    val baseBranches = listOf("master", "main", "develop").filter { it in branches && it != currentBranch }
    for (br in baseBranches) {
      branchChipPanel.add(createBranchChip(br))
    }

    val moreBtn = JButton(LocalGitMirrorBundle.message("send.moreBranches"))
    moreBtn.font = moreBtn.font.deriveFont(JBUI.scale(11f).toFloat())
    moreBtn.margin = JBUI.insets(2, 6)
    moreBtn.addActionListener {
      val chosen = Messages.showEditableChooseDialog(
        LocalGitMirrorBundle.message("dialog.selectBranch.prompt"),
        LocalGitMirrorBundle.message("dialog.selectBranch.title"),
        null, branches.toTypedArray(), branches.firstOrNull { it != currentBranch }, null
      )
      if (chosen != null && chosen != currentBranch) {
        selectedAdditionalBranches.add(chosen)
        refreshBranchChips()
      }
    }
    branchChipPanel.add(moreBtn)
  }

  init {
    layout = BorderLayout()

    val settingsState = service<MirrorSettingsService>().state
    val workMode = isWorkMode()

    // ── topContainer: packs all controls tightly to the top ──
    val topContainer = JPanel()
    topContainer.layout = BoxLayout(topContainer, BoxLayout.Y_AXIS)
    topContainer.border = JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(8))

    // Row 1: Header — badges LEFT, gear RIGHT (compact!)
    val headerRow = JPanel(BorderLayout(JBUI.scale(4), 0))
    headerRow.isOpaque = false
    headerRow.alignmentX = LEFT_ALIGNMENT

    val badges = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    badges.isOpaque = false
    val modeBadge = ModeBadge(
      if (workMode) LocalGitMirrorBundle.message("badge.mode.work") else LocalGitMirrorBundle.message("badge.mode.home"),
      workMode
    )
    badges.add(modeBadge)
    badges.add(mirrorBadge)
    if (workMode) badges.add(gitLabBadge)
    badges.add(lastSyncBadge)
    headerRow.add(badges, BorderLayout.CENTER)

    val moreMenu = JPopupMenu()
    fun menuItem(title: String, icon: Icon? = null, action: () -> Unit): JMenuItem {
      val mi = JMenuItem(title, icon); mi.addActionListener { action() }; return mi
    }
    moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.preflight"), AllIcons.General.InspectionsOK) { runPreflight() })
    moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.dryRunSend"), AllIcons.Actions.Preview) { runDryRun() })
    moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.dryRunPull"), AllIcons.Actions.Preview) { runPullDryRun() })
    moreMenu.addSeparator()
    moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.menu.applyLocalDump"), AllIcons.Actions.OpenNewTab) { applyLocalDump() })
    moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.menu.testMirror"), AllIcons.Actions.Checked) { testMirror() })
    if (workMode) moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.menu.testGitLab"), AllIcons.Actions.Checked) { testGitLab() })
    moreMenu.addSeparator()
    moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.menu.copyConfig"), AllIcons.Actions.Copy) { copyConfigLine() })
    moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.menu.pasteConfig"), AllIcons.Actions.Upload) { pasteConfigLine() })
    moreMenu.addSeparator()
    val autoPullItem = JCheckBoxMenuItem(LocalGitMirrorBundle.message("settings.sync.autoCheck"))
    autoPullItem.isSelected = settingsState.autoCheckPullOnStartup
    autoPullItem.addActionListener { settingsState.autoCheckPullOnStartup = autoPullItem.isSelected }
    moreMenu.add(autoPullItem)
    moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.menu.settings"), AllIcons.General.Settings) {
      ShowSettingsUtil.getInstance().showSettingsDialog(project, "LocalGitMirror"); refreshStatus()
    })

    val gearBtn = JButton(AllIcons.General.Settings)
    gearBtn.margin = JBUI.insets(1)
    gearBtn.isFocusPainted = false
    gearBtn.isBorderPainted = false
    gearBtn.isContentAreaFilled = false
    gearBtn.toolTipText = LocalGitMirrorBundle.message("toolwindow.menu.settings")
    gearBtn.addActionListener { moreMenu.show(gearBtn, 0, gearBtn.height) }
    headerRow.add(gearBtn, BorderLayout.EAST)
    topContainer.add(headerRow)

    // Status line (branch info, compact)
    status.font = JBUI.Fonts.smallFont()
    status.foreground = UIUtil.getContextHelpForeground()
    status.alignmentX = LEFT_ALIGNMENT
    status.border = JBUI.Borders.empty(2, 0)
    topContainer.add(status)

    topContainer.add(Box.createVerticalStrut(JBUI.scale(4)))

    // Row 2: Action buttons — two BoxLayout rows, buttons take natural size
    val actionsBox = JPanel()
    actionsBox.layout = BoxLayout(actionsBox, BoxLayout.Y_AXIS)
    actionsBox.isOpaque = false
    actionsBox.alignmentX = LEFT_ALIGNMENT
    fun actionRow(vararg buttons: JButton): JPanel {
      val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
      row.isOpaque = false
      row.alignmentX = LEFT_ALIGNMENT
      row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
      buttons.forEach { row.add(it) }
      return row
    }

    if (workMode) {
      actionsBox.add(actionRow(
        primaryBtn(LocalGitMirrorBundle.message("toolwindow.sendCurrent"), AllIcons.Actions.Upload) { syncCurrentBranch() },
        btn(LocalGitMirrorBundle.message("toolwindow.menu.sendBranch"), AllIcons.Vcs.Branch) { syncBranch() },
        btn(LocalGitMirrorBundle.message("toolwindow.menu.sendAs"), AllIcons.Actions.Copy) { pushAs() }
      ))
      actionsBox.add(Box.createVerticalStrut(JBUI.scale(2)))
      actionsBox.add(actionRow(
        btn(LocalGitMirrorBundle.message("toolwindow.sendCommits"), AllIcons.Vcs.History) { syncSelectedCommits() },
        btn(LocalGitMirrorBundle.message("toolwindow.menu.sendMr"), AllIcons.Vcs.Merge) { syncMr() },
        btn(LocalGitMirrorBundle.message("toolwindow.pullFromMirror"), AllIcons.Actions.Download) { pullFromMirror() },
        btn(LocalGitMirrorBundle.message("toolwindow.pullBack"), AllIcons.Actions.Diff) { pullBack() }
      ))
    } else {
      actionsBox.add(actionRow(
        primaryBtn(LocalGitMirrorBundle.message("toolwindow.pullFromMirror"), AllIcons.Actions.Download) { pullFromMirror() },
        btn(LocalGitMirrorBundle.message("toolwindow.sendCurrent"), AllIcons.Actions.Upload) { syncCurrentBranch() }
      ))
      actionsBox.add(Box.createVerticalStrut(JBUI.scale(2)))
      actionsBox.add(actionRow(
        btn(LocalGitMirrorBundle.message("toolwindow.sendCommits"), AllIcons.Vcs.History) { syncSelectedCommits() },
        btn(LocalGitMirrorBundle.message("toolwindow.pullBack"), AllIcons.Actions.Diff) { pullBack() }
      ))
    }
    topContainer.add(actionsBox)

    // Row 3: Branch chips
    branchChipPanel.isOpaque = false
    branchChipPanel.alignmentX = LEFT_ALIGNMENT
    refreshBranchChips()
    topContainer.add(branchChipPanel)

    topContainer.add(Box.createVerticalStrut(JBUI.scale(2)))

    // Row 4: History toggle + clear button (compact)
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

    // Pack topContainer to NORTH — no vertical gap
    add(topContainer, BorderLayout.NORTH)

    // Progress bar above history
    progressBar.alignmentX = LEFT_ALIGNMENT
    val bottomPanel = JPanel(BorderLayout())
    bottomPanel.add(progressBar, BorderLayout.NORTH)

    // History log fills CENTER (expands when toggled)
    log.isEditable = false; log.lineWrap = true; log.wrapStyleWord = true
    log.font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(11))
    historyScroll = JScrollPane(log); historyScroll.isVisible = false
    bottomPanel.add(historyScroll, BorderLayout.CENTER)
    add(bottomPanel, BorderLayout.CENTER)

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
    status.text = LocalGitMirrorBundle.message("toolwindow.status.branchClean", branch, clean.toString())

    val mirrorConfigured = s.baseUrl.isNotBlank() && SecretsStore.syncPassword.isNotBlank()
    mirrorBadge.text = if (mirrorConfigured) LocalGitMirrorBundle.message("toolwindow.badge.mirrorConnected") else LocalGitMirrorBundle.message("toolwindow.badge.mirrorNotConfigured")
    mirrorBadge.status = if (mirrorConfigured) BadgeLabel.Status.GOOD else BadgeLabel.Status.BAD

    val gitLabConfigured = s.gitLabBaseUrl.isNotBlank() && s.gitLabProject.isNotBlank() && SecretsStore.gitLabToken.isNotBlank()
    gitLabBadge.text = if (gitLabConfigured) LocalGitMirrorBundle.message("toolwindow.badge.gitlabConnected") else LocalGitMirrorBundle.message("toolwindow.badge.gitlabNotConfigured")
    gitLabBadge.status = if (gitLabConfigured) BadgeLabel.Status.GOOD else BadgeLabel.Status.BAD

    refreshBranchChips()
  }

  internal fun ensureConfigured(settings: MirrorSettingsService.State): String? {
    val cfg = syncFacade.validateSettings(settings)
    return if (cfg.ok) null else cfg.message
  }

  internal fun markLastSyncOk() {
    markLastSync(SyncOutcome.OK)
  }

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

    val snapshot = entries.joinToString("\n") { e ->
      "${e.timestamp} [${e.status}] ${e.operation}: ${e.details}"
    }

    log.text = snapshot
    log.caretPosition = 0
  }
}
