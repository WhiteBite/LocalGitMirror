package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.ui.exchange.clearExchangeFeed
import localgitmirror.idea.ui.exchange.installPluginFromCache
import localgitmirror.idea.ui.exchange.sendPluginBuild
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*

internal fun LocalGitMirrorPanel.makeTabComponent(tabs: JBTabbedPane, index: Int): JComponent {
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

internal fun LocalGitMirrorPanel.refreshTabStyles() {
  val tabs = tabsPane ?: return
  for ((i, pill) in tabPills) {
    tabLabels[i]?.foreground = if (tabs.selectedIndex == i)
      JBColor(0x1F2328, 0xDFE1E5) else JBColor(0x6F7277, 0x8C8F94)
    pill.repaint()
  }
}

internal fun LocalGitMirrorPanel.setTabTitle(index: Int, text: String) {
  tabsPane?.setTitleAt(index, text)
  tabLabels[index]?.let { label ->
    label.text = text
    label.revalidate()
    tabPills[index]?.revalidate()
    tabsPane?.revalidate()
  }
}

/** Standard action button. */
internal fun LocalGitMirrorPanel.btn(title: String, icon: Icon? = null, action: () -> Unit): JButton {
  val b = JButton(title, icon)
  b.margin = JBUI.insets(2, 8)
  b.font = b.font.deriveFont(JBUI.scale(12f).toFloat())
  b.isFocusPainted = false
  b.addActionListener { action() }
  return b
}

/** Primary (accent) button — painted by the IDE theme, not by hand. */
internal fun LocalGitMirrorPanel.primaryBtn(title: String, icon: Icon? = null, action: () -> Unit): JButton =
  btn(title, icon, action).apply { putClientProperty("JButton.buttonType", "default") }

internal fun LocalGitMirrorPanel.greenBtn(title: String, icon: Icon? = null, action: () -> Unit): JButton =
  accentBtn(title, icon, JBColor(0x4A9D54, 0x4A9D54), action)

internal fun LocalGitMirrorPanel.accentBtn(title: String, icon: Icon?, bg: JBColor, action: () -> Unit): JButton {
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
internal fun LocalGitMirrorPanel.runRegisteredAction(actionId: String) {
  val action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(actionId) ?: return
  val dataContext = SimpleDataContext.getProjectContext(project)
  val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
    "LocalGitMirrorToolWindow", null, dataContext
  )
  action.actionPerformed(event)
}

internal fun LocalGitMirrorPanel.actionRow(vararg components: JComponent): JPanel {
  val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
  row.isOpaque = false
  row.alignmentX = Component.LEFT_ALIGNMENT
  row.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
  components.forEach { row.add(it) }
  return row
}

internal fun LocalGitMirrorPanel.rebuildGearMenu() {
  sendGroup.removeAll()
  sendGroup.add(panelAction(LocalGitMirrorBundle.message("panel.branch.send"), AllIcons.Actions.Upload) {
    sendSelectedBranches()
  })
  sendGroup.addSeparator()
  addRegisteredActionsTo(sendGroup,
    "LocalGitMirror.SyncCurrentBranch",
    "LocalGitMirror.SyncBranch",
    "LocalGitMirror.SendSelectedCommits",
    "LocalGitMirror.PushAs"
  )
  sendGroup.addSeparator()
  addRegisteredActionsTo(sendGroup, "LocalGitMirror.SendGitLabMr")
  sendGroup.add(panelAction(LocalGitMirrorBundle.message("review.sendNotes"), AllIcons.Actions.Upload) {
    syncMrNotes()
  })
  addRegisteredActionsTo(sendGroup, "LocalGitMirror.UploadMrReplies")
  sendGroup.addSeparator()
  addRegisteredActionsTo(sendGroup, "LocalGitMirror.FileSendSelected")

  pullGroup.removeAll()
  pullGroup.add(panelAction(LocalGitMirrorBundle.message("panel.branch.pull"), AllIcons.Actions.Download) {
    pullSelectedBranches()
  })
  pullGroup.addSeparator()
  addRegisteredActionsTo(pullGroup,
    "LocalGitMirror.SyncPull",
    "LocalGitMirror.PullBack",
    "LocalGitMirror.ApplyLocalSync"
  )
  pullGroup.addSeparator()
  addRegisteredActionsTo(pullGroup, "LocalGitMirror.PushMrReplies")
  addRegisteredActionsTo(pullGroup, "LocalGitMirror.FileFetch")

  mainGroup.removeAll()
  mainGroup.add(buildBranchesSubmenu())
  mainGroup.addSeparator()
  mainGroup.add(buildReviewSubmenu())
  mainGroup.addSeparator()
  mainGroup.add(buildDepsSubmenu())
  mainGroup.addSeparator()
  mainGroup.add(buildFilesSubmenu())
  mainGroup.addSeparator()
  mainGroup.add(buildServiceSubmenu())
  mainGroup.addSeparator()
  mainGroup.add(buildDiagnosticsSubmenu())
}

private fun LocalGitMirrorPanel.buildBranchesSubmenu(): DefaultActionGroup {
  val sub = DefaultActionGroup(LocalGitMirrorBundle.message("panel.menu.submenu.branches"), true)
  sub.add(refreshBranchesAction)
  sub.add(panelAction(LocalGitMirrorBundle.message("toolwindow.menu.testMirror"), AllIcons.Actions.Checked) {
    testMirror()
  })
  sub.addSeparator()
  addRegisteredActionsTo(sub,
    "LocalGitMirror.SyncCurrentBranch",
    "LocalGitMirror.SyncBranch",
    "LocalGitMirror.SendSelectedCommits",
    "LocalGitMirror.PushAs"
  )
  sub.addSeparator()
  addRegisteredActionsTo(sub, "LocalGitMirror.ManageBranches", "LocalGitMirror.PruneMirrorBranches")
  return sub
}

private fun LocalGitMirrorPanel.buildReviewSubmenu(): DefaultActionGroup {
  val sub = DefaultActionGroup(LocalGitMirrorBundle.message("panel.menu.submenu.review"), true)
  addRegisteredActionsTo(sub,
    "LocalGitMirror.SendGitLabMr",
    "LocalGitMirror.UploadMrReplies",
    "LocalGitMirror.PushMrReplies"
  )
  return sub
}

private fun LocalGitMirrorPanel.buildDepsSubmenu(): DefaultActionGroup {
  val sub = DefaultActionGroup(LocalGitMirrorBundle.message("panel.menu.submenu.deps"), true)
  addRegisteredActionsTo(sub,
    "LocalGitMirror.DepsRequest",
    "LocalGitMirror.DepsRespond",
    "LocalGitMirror.DepsApply",
    "LocalGitMirror.MirrorPublish"
  )
  return sub
}

private fun LocalGitMirrorPanel.buildFilesSubmenu(): DefaultActionGroup {
  val sub = DefaultActionGroup(LocalGitMirrorBundle.message("panel.menu.submenu.files"), true)
  addRegisteredActionsTo(sub, "LocalGitMirror.FileSendSelected", "LocalGitMirror.FileFetch")
  sub.addSeparator()
  addRegisteredActionsTo(sub, "LocalGitMirror.BufferSend", "LocalGitMirror.BufferPaste", "LocalGitMirror.BufferHistory")
  sub.add(panelAction(LocalGitMirrorBundle.message("panel.exchange.more.clear"), AllIcons.Actions.GC) {
    clearExchangeFeed()
  })
  return sub
}

private fun LocalGitMirrorPanel.buildServiceSubmenu(): DefaultActionGroup {
  val sub = DefaultActionGroup(LocalGitMirrorBundle.message("panel.menu.submenu.service"), true)
  sub.add(panelAction(LocalGitMirrorBundle.message("panel.menu.exportBundle"), AllIcons.Actions.Upload) { exportBundle() })
  sub.add(panelAction(LocalGitMirrorBundle.message("panel.menu.importBundle"), AllIcons.Actions.Download) { importBundle() })
  sub.addSeparator()
  sub.add(panelAction(LocalGitMirrorBundle.message("toolwindow.menu.copyConfig"), AllIcons.Actions.Copy) { copyConfigLine() })
  sub.add(panelAction(LocalGitMirrorBundle.message("toolwindow.menu.pasteConfig"), AllIcons.Actions.MenuPaste) { pasteConfigLine() })
  sub.add(panelAction(LocalGitMirrorBundle.message("panel.menu.vaultSync"), AllIcons.Actions.Download) {
    localgitmirror.idea.deps.VaultCacheSync.syncInBackground(project, "manual")
  })
  sub.addSeparator()
  sub.add(panelAction(LocalGitMirrorBundle.message("toolwindow.menu.downloadPlugin"), AllIcons.Actions.Download) { downloadLatestPlugin() })
  sub.add(panelAction(LocalGitMirrorBundle.message("panel.menu.sendPlugin"), AllIcons.Actions.Upload) { sendPluginBuild() })
  sub.add(panelAction(LocalGitMirrorBundle.message("panel.menu.installPlugin"), AllIcons.Actions.Download) { installPluginFromCache() })
  return sub
}

private fun LocalGitMirrorPanel.buildDiagnosticsSubmenu(): DefaultActionGroup {
  val sub = DefaultActionGroup(LocalGitMirrorBundle.message("panel.menu.submenu.diagnostics"), true)
  addRegisteredActionsTo(sub,
    "LocalGitMirror.Preflight",
    "LocalGitMirror.VaultDiagnostics",
    "LocalGitMirror.DryRun",
    "LocalGitMirror.DryRunPull"
  )
  return sub
}

internal fun LocalGitMirrorPanel.buildTransportBar(): JComponent {
  val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
    isOpaque = false
    border = JBUI.Borders.empty(2, 4)
  }
  bar.add(transportMenuButton(LocalGitMirrorBundle.message("panel.transport.send")) { showTransportPopup(sendGroup, it) })
  bar.add(transportMenuButton(LocalGitMirrorBundle.message("panel.transport.pull")) { showTransportPopup(pullGroup, it) })
  bar.add(verticalSeparator())
  bar.add(barIconButton(AllIcons.Actions.Refresh, LocalGitMirrorBundle.message("panel.branch.refresh.tooltip")) {
    refreshBranchCombo(userInitiated = true, withMirror = true)
  })
  bar.add(barIconButton(AllIcons.Actions.Checked, LocalGitMirrorBundle.message("toolwindow.menu.testMirror")) {
    testMirror()
  })
  bar.add(barIconButton(AllIcons.General.Settings, LocalGitMirrorBundle.message("toolwindow.menu.settings")) {
    ShowSettingsUtil.getInstance().showSettingsDialog(project, "localgitmirror.settings")
    refreshStatus()
  })
  bar.add(barIconButton(AllIcons.Actions.MoreHorizontal, LocalGitMirrorBundle.message("panel.toolbar.more.tooltip", pluginVersionText)) {
    showOverflowPopup(it)
  })
  return bar
}

private fun LocalGitMirrorPanel.transportMenuButton(label: String, onClick: (JButton) -> Unit): JButton =
  JButton("$label \u25BE").apply {
    font = JBUI.Fonts.smallFont()
    margin = JBUI.insets(2, 8)
    isFocusPainted = false
    addActionListener { onClick(this) }
  }

private fun LocalGitMirrorPanel.barIconButton(icon: Icon, tooltip: String, onClick: (JButton) -> Unit): JButton =
  JButton(icon).apply {
    margin = JBUI.insets(2, 4)
    isFocusPainted = false
    isBorderPainted = false
    isContentAreaFilled = false
    toolTipText = tooltip
    addActionListener { onClick(this) }
  }

private fun verticalSeparator(): JComponent = JSeparator(JSeparator.VERTICAL).apply {
  preferredSize = Dimension(JBUI.scale(2), JBUI.scale(22))
}

private fun LocalGitMirrorPanel.showTransportPopup(group: DefaultActionGroup, anchor: JComponent) {
  val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
    .createActionGroupPopup(
      null, group, SimpleDataContext.getProjectContext(project),
      com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false
    )
  popup.show(com.intellij.ui.awt.RelativePoint.getSouthWestOf(anchor))
}

internal fun LocalGitMirrorPanel.addRegisteredActionsTo(group: DefaultActionGroup, vararg ids: String) {
  val manager = ActionManager.getInstance()
  ids.forEach { id -> manager.getAction(id)?.let { group.add(it) } }
}

internal fun LocalGitMirrorPanel.panelAction(title: String, icon: Icon, action: () -> Unit): AnAction =
  object : AnAction(title, title, icon) {
    override fun actionPerformed(e: AnActionEvent) = action()
  }

/** Show the unified group as an overflow popup under [anchor]. */
internal fun LocalGitMirrorPanel.showOverflowPopup(anchor: JComponent) {
  val dataContext = SimpleDataContext.getProjectContext(project)
  val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
    .createActionGroupPopup(
      null, mainGroup, dataContext,
      com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true
    )
  popup.show(com.intellij.ui.awt.RelativePoint.getSouthWestOf(anchor))
}

/** Compact strip under the transport: connection dot + status line, history popup link on the right. */
internal fun LocalGitMirrorPanel.buildStatusStrip(): JComponent {
  status.font = JBUI.Fonts.smallFont()
  status.foreground = UIUtil.getContextHelpForeground()
  statusDot.font = JBUI.Fonts.smallFont()
  val historyLink = JButton(LocalGitMirrorBundle.message("toolwindow.history")).apply {
    font = JBUI.Fonts.smallFont()
    isBorderPainted = false
    isContentAreaFilled = false
    isFocusPainted = false
    foreground = JBColor(0x2470B3, 0x548AF7)
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    margin = JBUI.insets(0, 4)
    addActionListener { showHistoryPopup(this) }
  }
  val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
    isOpaque = false
    add(statusDot)
    add(status)
  }
  return JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(2, 8)
    add(left, BorderLayout.WEST)
    add(historyLink, BorderLayout.EAST)
  }
}

internal fun LocalGitMirrorPanel.updateStatusStrip() {
  val parts = mutableListOf(
    LocalGitMirrorBundle.message(
      if (stripConnected) "panel.status.connected" else "panel.status.disconnected"
    )
  )
  if (stripConnected) {
    if (stripRole.isNotBlank()) parts += stripRole
    if (stripRepo.isNotBlank()) parts += LocalGitMirrorBundle.message("panel.status.repo", stripRepo)
    val behind = allBranchItems.count { it.status == BranchStatus.BEHIND }
    val ahead = allBranchItems.count { it.status == BranchStatus.AHEAD }
    if (behind > 0 || ahead > 0) {
      parts += LocalGitMirrorBundle.message("panel.status.waiting", behind, ahead)
    }
  }
  status.text = parts.joinToString(" \u00b7 ")
}

internal fun LocalGitMirrorPanel.showHistoryPopup(anchor: JComponent) {
  val content = historyView.buildHistoryPanel()
  historyView.refreshHistoryLog()
  val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
    .createComponentPopupBuilder(content, null)
    .setRequestFocus(true)
    .setFocusable(true)
    .setResizable(true)
    .setMovable(true)
    .setMinSize(Dimension(JBUI.scale(360), JBUI.scale(220)))
    .createPopup()
  popup.show(com.intellij.ui.awt.RelativePoint.getSouthWestOf(anchor))
}

/** Trigger a registered plugin action by id, with project context. Notifies on failure. */
internal fun LocalGitMirrorPanel.triggerLgmAction(id: String) {
  runCatching { runRegisteredAction(id) }
    .onFailure { notify("Не удалось запустить действие: ${it.message ?: it::class.simpleName}", NotificationType.ERROR) }
}

/** Rebuild action buttons (now just refreshes gear menu). */
internal fun LocalGitMirrorPanel.rebuildActions() {
  rebuildGearMenu()
  revalidate()
  repaint()
}

internal fun LocalGitMirrorPanel.sectionHeader(text: String): JBLabel = JBLabel(text).apply {
  font = JBUI.Fonts.smallFont().asBold()
  foreground = UIUtil.getLabelForeground()
  border = JBUI.Borders.empty(8, 0, 4, 0)
  alignmentX = Component.LEFT_ALIGNMENT
}

internal fun LocalGitMirrorPanel.append(line: String) {
  // Diagnostic output is now captured via OperationsHistoryService entries.
}

internal fun LocalGitMirrorPanel.notify(message: String, type: NotificationType) {
  NotificationGroupManager.getInstance()
    .getNotificationGroup("DocCache")
    .createNotification(message, type)
    .notify(project)
}

internal fun LocalGitMirrorPanel.refreshStatus() {
  val dir = baseDir()
  if (dir == null) {
    status.text = LocalGitMirrorBundle.message("notify.projectDir.missing")
    return
  }
  val s = service<MirrorSettingsService>().state
  val connected = s.baseUrl.isNotBlank() &&
    (SecretsStore.cached.syncPassword.isNotBlank() || localgitmirror.idea.mirror.MirrorCrypto.isV3Pinned())
  val machineRole = localgitmirror.idea.deps.RoleDetector.detect(s)

  // Resolve Mirror repo for tooltip (single source of truth)
  val repoRes = try { syncFacade.resolveRepo(dir, s) } catch (_: Throwable) { null }

  var branchCount = 0
  if (connected) {
    branchCount = GitLocal.listBranches(project, dir).size
  }
  stripConnected = connected
  stripRole = LocalGitMirrorBundle.message(
    if (machineRole == localgitmirror.idea.deps.MachineRole.WORK)
      "panel.status.role.work" else "panel.status.role.home"
  )
  stripRepo = repoRes?.sanitized ?: ""
  updateStatusStrip()
  statusDot.foreground = if (connected) JBColor(0x5FAD65, 0x5FAD65) else JBColor.GRAY

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

internal fun LocalGitMirrorPanel.markLastSyncOk() = markLastSync(LocalGitMirrorPanel.SyncOutcome.OK)

internal fun LocalGitMirrorPanel.markLastSync(outcome: LocalGitMirrorPanel.SyncOutcome) {
  val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
  lastSyncBadge.status = when (outcome) {
    LocalGitMirrorPanel.SyncOutcome.OK -> BadgeLabel.Status.GOOD
    LocalGitMirrorPanel.SyncOutcome.FAIL -> BadgeLabel.Status.BAD
  }
  lastSyncBadge.text = when (outcome) {
    LocalGitMirrorPanel.SyncOutcome.OK -> "Last sync: $ts \u2705"
    LocalGitMirrorPanel.SyncOutcome.FAIL -> "Last sync: $ts \u274c"
  }
}
