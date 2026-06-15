package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.gitlab.GitLabApi
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.*
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.swing.*

class LocalGitMirrorPanel(private val project: Project) : JPanel(BorderLayout()) {
  private val log = JTextArea()
  private val status = JBLabel("Ready")
  private val mirrorBadge = BadgeLabel("Mirror: ?")
  private val gitLabBadge = BadgeLabel("GitLab: ?")
  private val lastSyncBadge = BadgeLabel("Last sync: —")
  private val progressBar = JProgressBar().apply { isVisible = false; isIndeterminate = true }
  
  private lateinit var historyScroll: JScrollPane
  private lateinit var centerHint: JLabel
  private val mainActionsPanel = JPanel()

  private val historyService = service<OperationsHistoryService>()
  private val syncFacade = project.getService(SyncFacadeService::class.java)

  private val includeBaseBranchCheck = JCheckBox("Include base branch:")
  private val baseBranchCombo = com.intellij.openapi.ui.ComboBox<String>()

  private var isSyncing = false
    set(value) {
      field = value
      updateUiState()
    }

  private enum class SyncOutcome { OK, FAIL }

  private fun updateUiState() {
    UIUtil.invokeLaterIfNeeded {
      val enabled = !isSyncing
      mainActionsPanel.components.forEach { section ->
        if (section is JPanel) {
          section.components.forEach { if (it is JButton || it is JToggleButton) it.isEnabled = enabled }
        }
      }
      progressBar.isVisible = isSyncing
    }
  }

  private fun btn(title: String, icon: Icon? = null, action: () -> Unit): JButton {
    val b = JButton(title, icon)
    b.margin = JBUI.insets(4, 8)
    b.horizontalAlignment = SwingConstants.LEFT
    b.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
    b.alignmentX = LEFT_ALIGNMENT
    b.addActionListener { action() }
    return b
  }

  private fun createSection(title: String): JPanel {
    val p = JPanel()
    p.layout = BoxLayout(p, BoxLayout.Y_AXIS)
    p.alignmentX = LEFT_ALIGNMENT
    p.border = BorderFactory.createCompoundBorder(
      BorderFactory.createTitledBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()), title),
      BorderFactory.createEmptyBorder(4, 4, 8, 4)
    )
    return p
  }

  init {
    layout = BorderLayout()
    val scrollRoot = JBPanel<JBPanel<*>>()
    scrollRoot.layout = BoxLayout(scrollRoot, BoxLayout.Y_AXIS)
    scrollRoot.border = JBUI.Borders.empty(8)

    // Status Row
    val badges = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
    badges.add(mirrorBadge)
    badges.add(gitLabBadge)
    badges.add(lastSyncBadge)
    badges.alignmentX = LEFT_ALIGNMENT
    scrollRoot.add(badges)
    scrollRoot.add(Box.createVerticalStrut(8))

    status.font = JBUI.Fonts.label(12f).deriveFont(Font.ITALIC)
    status.foreground = JBColor.GRAY
    status.alignmentX = LEFT_ALIGNMENT
    scrollRoot.add(status)
    scrollRoot.add(Box.createVerticalStrut(12))

    // Main Actions Grouping
    mainActionsPanel.layout = BoxLayout(mainActionsPanel, BoxLayout.Y_AXIS)
    mainActionsPanel.alignmentX = LEFT_ALIGNMENT

    val settingsState = service<MirrorSettingsService>().state
    val simpleUi = settingsState.simpleUiMode

    // --- SECTION: SYNC (SEND) ---
    val syncSection = createSection("Sync (Send)")
    
    val multiBranchPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
    multiBranchPanel.alignmentX = LEFT_ALIGNMENT
    baseBranchCombo.isEditable = true
    baseBranchCombo.prototypeDisplayValue = "a-very-long-branch-name-indeed"
    multiBranchPanel.add(includeBaseBranchCheck)
    multiBranchPanel.add(baseBranchCombo)
    syncSection.add(multiBranchPanel)
    syncSection.add(Box.createVerticalStrut(4))

    val sendCurrent = btn(LocalGitMirrorBundle.message("toolwindow.sendCurrent"), AllIcons.Actions.Upload) { syncCurrentBranch() }
    sendCurrent.font = sendCurrent.font.deriveFont(Font.BOLD)
    syncSection.add(sendCurrent)
    if (!simpleUi) {
      syncSection.add(Box.createVerticalStrut(4))
      syncSection.add(btn(LocalGitMirrorBundle.message("toolwindow.sendCommits"), AllIcons.Vcs.History) { syncSelectedCommits() })
      syncSection.add(Box.createVerticalStrut(4))
      syncSection.add(btn(LocalGitMirrorBundle.message("toolwindow.menu.sendBranch"), AllIcons.Vcs.Branch) { syncBranch() })
    }
    mainActionsPanel.add(syncSection)
    mainActionsPanel.add(Box.createVerticalStrut(12))

    // --- SECTION: PULL (RECEIVE) ---
    val pullSection = createSection("Pull (Receive)")
    pullSection.add(btn(LocalGitMirrorBundle.message("toolwindow.stealthPullBack"), AllIcons.Actions.Download) { stealthPullBack() })
    if (!simpleUi) {
      pullSection.add(Box.createVerticalStrut(4))
      pullSection.add(btn(LocalGitMirrorBundle.message("toolwindow.pullBack"), AllIcons.Actions.Diff) { pullBack() })
    }
    mainActionsPanel.add(pullSection)
    mainActionsPanel.add(Box.createVerticalStrut(12))

    // --- SECTION: TOOLS ---
    val toolsSection = createSection("Diagnostics & Tools")
    toolsSection.add(btn("Preflight Check", AllIcons.General.InspectionsOK) { runPreflight() })
    toolsSection.add(Box.createVerticalStrut(4))
    val dryRuns = JPanel(GridLayout(1, 2, 4, 0))
    dryRuns.alignmentX = LEFT_ALIGNMENT
    dryRuns.add(btn("Dry-run (Send)", AllIcons.Actions.Preview) { runDryRun() })
    dryRuns.add(btn("Dry-run (Pull)", AllIcons.Actions.Preview) { runPullDryRun() })
    toolsSection.add(dryRuns)
    mainActionsPanel.add(toolsSection)
    
    scrollRoot.add(mainActionsPanel)
    scrollRoot.add(Box.createVerticalStrut(12))

    // Footer
    val footer = JPanel(BorderLayout())
    footer.alignmentX = LEFT_ALIGNMENT
    val autoPullCheck = JCheckBox(LocalGitMirrorBundle.message("settings.sync.autoCheck"))
    autoPullCheck.isSelected = settingsState.autoCheckPullOnStartup
    autoPullCheck.addActionListener { settingsState.autoCheckPullOnStartup = autoPullCheck.isSelected }
    footer.add(autoPullCheck, BorderLayout.WEST)
    
    val moreToggle = JToggleButton(AllIcons.Actions.More)
    moreToggle.toolTipText = LocalGitMirrorBundle.message("toolwindow.moreActions")
    val moreMenu = JPopupMenu()
    fun menuItem(title: String, icon: Icon? = null, action: () -> Unit): JMenuItem {
      val mi = JMenuItem(title, icon)
      mi.addActionListener { action() }
      return mi
    }
    moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.menu.applyLocalDump"), AllIcons.Actions.OpenNewTab) { applyLocalDump() })
    moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.menu.testMirror"), AllIcons.Actions.Checked) { testMirror() })
    moreMenu.addSeparator()
    moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.menu.copyConfig"), AllIcons.Actions.Copy) { copyConfigLine() })
    moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.menu.pasteConfig"), AllIcons.Actions.Upload) { pasteConfigLine() })
    moreMenu.addSeparator()
    moreMenu.add(menuItem(LocalGitMirrorBundle.message("toolwindow.menu.settings"), AllIcons.General.Settings) {
      ShowSettingsUtil.getInstance().showSettingsDialog(project, "LocalGitMirror")
      refreshStatus()
    })
    moreToggle.addActionListener {
      moreMenu.show(moreToggle, 0, moreToggle.height)
      moreToggle.isSelected = false
    }
    footer.add(moreToggle, BorderLayout.EAST)
    scrollRoot.add(footer)
    scrollRoot.add(Box.createVerticalStrut(8))

    // --- History ---
    val historyPanel = JPanel(BorderLayout())
    historyPanel.alignmentX = LEFT_ALIGNMENT
    val historyToggle = JToggleButton("History ▾")
    historyToggle.margin = JBUI.insets(2, 4)
    
    log.isEditable = false
    log.lineWrap = true
    log.wrapStyleWord = true
    log.font = JBUI.Fonts.label(12f)
    historyScroll = JScrollPane(log)
    historyScroll.preferredSize = Dimension(100, 150)
    historyScroll.isVisible = false
    
    historyToggle.addActionListener {
      historyScroll.isVisible = historyToggle.isSelected
      historyToggle.text = if (historyToggle.isSelected) "History ▴" else "History ▾"
      revalidate()
      repaint()
    }
    
    historyPanel.add(historyToggle, BorderLayout.NORTH)
    historyPanel.add(historyScroll, BorderLayout.CENTER)
    scrollRoot.add(historyPanel)
    scrollRoot.add(Box.createVerticalStrut(8))

    scrollRoot.add(progressBar)

    val scrollView = JScrollPane(scrollRoot)
    scrollView.border = BorderFactory.createEmptyBorder()
    add(scrollView, BorderLayout.CENTER)

    centerHint = JBLabel("<html><body style='color:#999;text-align:center'>${LocalGitMirrorBundle.message("toolwindow.hint.quick")}</body></html>")
    centerHint.horizontalAlignment = SwingConstants.CENTER
    centerHint.border = JBUI.Borders.empty(12)
    add(centerHint, BorderLayout.SOUTH)

    refreshStatus()
    refreshHistoryLog()
  }

  private fun baseDir(): File? {
    val basePath = project.basePath ?: return null
    if (basePath.isBlank()) return null
    return File(basePath)
  }

  private fun append(line: String) {
    log.append(line)
    log.append("\n")
  }

  private fun notify(message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("LocalGitMirror")
      .createNotification(message, type)
      .notify(project)
  }

  private fun refreshStatus() {
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

    val localBranches = GitLocal.listBranches(project, dir)
    val currentCombo = baseBranchCombo.selectedItem as? String
    baseBranchCombo.removeAllItems()
    val preselect = currentCombo ?: "master"
    localBranches.forEach { baseBranchCombo.addItem(it) }
    if (localBranches.contains(preselect)) {
      baseBranchCombo.selectedItem = preselect
    } else if (localBranches.contains("main")) {
      baseBranchCombo.selectedItem = "main"
    }
  }

  private fun ensureConfigured(settings: MirrorSettingsService.State): String? {
    val cfg = syncFacade.validateSettings(settings)
    return if (cfg.ok) null else cfg.message
  }

  private fun syncCurrentBranch() {
    val dir = baseDir() ?: run {
      notify("Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val settings = service<MirrorSettingsService>().state
    val err = ensureConfigured(settings)
    if (err != null) {
      notify(err, NotificationType.WARNING)
      return
    }

    val branch = GitLocal.currentBranch(project, dir) ?: "(unknown)"

    val additionalBranches = if (includeBaseBranchCheck.isSelected) {
        val selected = baseBranchCombo.selectedItem as? String
        if (!selected.isNullOrBlank()) listOf(selected.trim()) else emptyList()
    } else emptyList()

    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Send current", false) {
      override fun run(indicator: ProgressIndicator) {
        try {
          append("Send current: $branch")
          if (additionalBranches.isNotEmpty()) append("Send additional: ${additionalBranches.joinToString()}")
          append("Target: ${syncFacade.describeRepoTarget(dir, settings)}")
          val syncRes = syncFacade.runFullSync(dir, settings, additionalBranches)
          val res = syncRes.step
          if (!res.ok) {
            append("Failed: ${res.message} ${res.details}")
            historyService.add("Send current", false, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}")
            notify("[trace=${syncRes.traceId}] repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}", NotificationType.ERROR)
            markLastSync(SyncOutcome.FAIL)
            refreshHistoryLog()
            return
          }
          if (settings.offlineGenerateOnly) {
            append("Offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
            historyService.add("Send current", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
            notify("[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: settings.repo}'", NotificationType.INFORMATION)
          } else {
            append("OK: ${syncRes.http?.code} ${syncRes.http?.body?.take(400) ?: ""}")
            historyService.add("Send current", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${syncRes.http?.body ?: "OK"}")
            notify("[trace=${syncRes.traceId}] Synced current branch to Mirror repo '${syncRes.repo ?: settings.repo}'", NotificationType.INFORMATION)
          }
          centerHint.text = "<html><body style='color:#8bc34a'>Last: Send current ✅</body></html>"
          markLastSyncOk()
          refreshHistoryLog()
        } finally {
          isSyncing = false
        }
      }

      override fun onFinished() {
        isSyncing = false
      }
    })
  }

  private fun syncBranch() {
    val dir = baseDir() ?: run {
      notify("Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val settings = service<MirrorSettingsService>().state
    val err = ensureConfigured(settings)
    if (err != null) {
      notify(err, NotificationType.WARNING)
      return
    }

    val branches = GitLocal.localBranches(project, dir)
    val current = GitLocal.currentBranch(project, dir)
    val chosen = Messages.showEditableChooseDialog(
      LocalGitMirrorBundle.message("dialog.selectBranch.prompt"),
      LocalGitMirrorBundle.message("dialog.selectBranch.title"),
      null,
      branches.toTypedArray(),
      current,
      null
    ) ?: return

    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Send branch", false) {
      override fun run(indicator: ProgressIndicator) {
        try {
          val selectTitle = LocalGitMirrorBundle.message("dialog.selectBranch.title")
          val selectPrompt = LocalGitMirrorBundle.message("dialog.selectBranch.prompt")
          val chosen = Messages.showEditableChooseDialog(selectPrompt, selectTitle, null, branches.toTypedArray(), current, null) ?: return

          val original = GitLocal.currentBranch(project, dir)
          append("Send branch: $chosen")
          append("Target: ${syncFacade.describeRepoTarget(dir, settings)}")

          val co = GitLocal.checkout(project, dir, chosen)
          if (!co.ok()) {
            append("Checkout failed: ${co.stderr}")
            notify("Checkout failed: ${co.stderr}", NotificationType.ERROR)
            return
          }

          val syncRes = try {
            syncFacade.runFullSync(dir, settings)
          } finally {
            if (!original.isNullOrBlank() && original != chosen) {
              val back = GitLocal.checkout(project, dir, original)
              if (!back.ok()) {
                notify("Sent, but failed to restore branch '$original': ${back.stderr}", NotificationType.WARNING)
              }
            }
          }

          val res = syncRes.step
          if (!res.ok) {
            append("Failed: ${res.message} ${res.details}")
            historyService.add("Send branch", false, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}")
            notify("[trace=${syncRes.traceId}] repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}", NotificationType.ERROR)
            markLastSync(SyncOutcome.FAIL)
            refreshHistoryLog()
            return
          }
          if (settings.offlineGenerateOnly) {
            append("Offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
            historyService.add("Send branch", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
            notify("[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: settings.repo}'", NotificationType.INFORMATION)
          } else {
            append("OK: ${syncRes.http?.code} ${syncRes.http?.body?.take(400) ?: ""}")
            historyService.add("Send branch", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${syncRes.http?.body ?: "OK"}")
            notify("[trace=${syncRes.traceId}] Synced branch '$chosen' to Mirror repo '${syncRes.repo ?: settings.repo}'", NotificationType.INFORMATION)
          }
          centerHint.text = "<html><body style='color:#8bc34a'>Last: Send branch ✅</body></html>"
          markLastSyncOk()
          refreshHistoryLog()
        } finally {
          isSyncing = false
        }
      }

      override fun onFinished() {
        isSyncing = false
      }
    })
  }

  private fun syncSelectedCommits() {
    val dir = baseDir() ?: run {
      notify("Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val settings = service<MirrorSettingsService>().state
    val err = ensureConfigured(settings)
    if (err != null) {
      notify(err, NotificationType.WARNING)
      return
    }

    val commits = GitLocal.recentCommits(project, dir, 30)
    if (commits.isEmpty()) {
      notify("No recent commits found", NotificationType.WARNING)
      return
    }
    val selectedHashes = pickCommitHashes(commits)
    if (selectedHashes.isEmpty()) {
      return
    }

    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Send commits", false) {
      override fun run(indicator: ProgressIndicator) {
        try {
          val original = GitLocal.currentBranch(project, dir)
          val tempBranch = "lgm-send-commits-${System.currentTimeMillis()}"
          append("Send commits: ${selectedHashes.joinToString(" ")}")
          append("Target: ${syncFacade.describeRepoTarget(dir, settings)}")

          val create = GitLocal.checkoutNew(project, dir, tempBranch, "HEAD")
          if (!create.ok()) {
            notify("Failed to create temp branch: ${create.stderr}", NotificationType.ERROR)
            return
          }

          var cherryFailed = false
          for (hash in selectedHashes) {
            val cp = GitLocal.cherryPick(project, dir, hash)
            if (!cp.ok()) {
              cherryFailed = true
              GitLocal.cherryPickAbort(project, dir)
              notify("Cherry-pick failed for $hash: ${cp.stderr}", NotificationType.ERROR)
              markLastSync(SyncOutcome.FAIL)
              historyService.add("Send commits", false, "Cherry-pick failed for $hash")
              break
            }
          }

          if (cherryFailed) {
            if (!original.isNullOrBlank()) {
              GitLocal.checkout(project, dir, original)
            }
            GitLocal.deleteLocalBranch(project, dir, tempBranch, force = true)
            return
          }

          val syncRes = try {
            syncFacade.runFullSync(dir, settings)
          } finally {
            if (!original.isNullOrBlank()) {
              GitLocal.checkout(project, dir, original)
            }
            GitLocal.deleteLocalBranch(project, dir, tempBranch, force = true)
          }

          val res = syncRes.step
          if (!res.ok) {
            append("Failed: ${res.message} ${res.details}")
            historyService.add("Send commits", false, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}")
            notify("[trace=${syncRes.traceId}] repo='${syncRes.repo ?: settings.repo}' ${res.message}. ${res.details}", NotificationType.ERROR)
            markLastSync(SyncOutcome.FAIL)
            refreshHistoryLog()
            return
          }

          if (settings.offlineGenerateOnly) {
            append("Offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
            historyService.add("Send commits", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
            notify("[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: settings.repo}'", NotificationType.INFORMATION)
          } else {
            append("OK: ${syncRes.http?.code} ${syncRes.http?.body?.take(400) ?: ""}")
            historyService.add("Send commits", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: settings.repo}' ${syncRes.http?.body ?: "OK"}")
            notify("[trace=${syncRes.traceId}] Sent selected commits to Mirror repo '${syncRes.repo ?: settings.repo}'", NotificationType.INFORMATION)
          }
          centerHint.text = "<html><body style='color:#8bc34a'>Last: Send commits ✅</body></html>"
          markLastSyncOk()
          refreshHistoryLog()
        } finally {
          isSyncing = false
        }
      }

      override fun onFinished() {
        isSyncing = false
      }
    })
  }

  private fun syncMr() {
    val dir = baseDir() ?: run {
      notify("Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val s = service<MirrorSettingsService>().state
    val err = ensureConfigured(s)
    if (err != null) {
      notify(err, NotificationType.WARNING)
      return
    }
    if (s.gitLabBaseUrl.isBlank() || SecretsStore.gitLabToken.isBlank() || s.gitLabProject.isBlank()) {
      notify("Configure GitLab Base URL/Token/Project in Settings", NotificationType.WARNING)
      return
    }

    val mrIid = pickMrIid(s) ?: return
    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Send MR/PR", false) {
      override fun run(indicator: ProgressIndicator) {
        try {
          val original = GitLocal.currentBranch(project, dir)
          var createdBranch: String? = null
          append("Send MR: !$mrIid")
          append("Target: ${syncFacade.describeRepoTarget(dir, s)}")

          val (mr, sourceBranch) = GitLabApi.getMergeRequestSourceBranch(
            s.gitLabBaseUrl,
            SecretsStore.gitLabToken,
            s.gitLabProject,
            mrIid,
            s.gitLabInsecureTls
          )
          if (mr.code !in 200..299 || sourceBranch.isNullOrBlank()) {
            notify("Failed to resolve MR source branch: HTTP ${mr.code}", NotificationType.ERROR)
            markLastSync(SyncOutcome.FAIL)
            historyService.add("Send MR/PR", false, "Failed to resolve source branch")
            refreshHistoryLog()
            return
          }

          val fetch = GitLocal.fetch(project, dir, s.gitRemoteName, sourceBranch)
          if (!fetch.ok()) {
            notify("git fetch failed: ${fetch.stderr}", NotificationType.ERROR)
            markLastSync(SyncOutcome.FAIL)
            historyService.add("Send MR/PR", false, "git fetch failed")
            refreshHistoryLog()
            return
          }

          val co = GitLocal.checkout(project, dir, sourceBranch)
          if (!co.ok()) {
            val localTemp = "lgm-mr-$mrIid-${System.currentTimeMillis()}"
            val co2 = GitLocal.checkoutNew(project, dir, localTemp, "FETCH_HEAD")
            if (!co2.ok()) {
              notify("git checkout failed: ${co.stderr}\n${co2.stderr}", NotificationType.ERROR)
              markLastSync(SyncOutcome.FAIL)
              historyService.add("Send MR/PR", false, "git checkout failed")
              refreshHistoryLog()
              return
            }
            createdBranch = localTemp
          }

          val syncRes = try {
            syncFacade.runFullSync(dir, s)
          } finally {
            if (!original.isNullOrBlank()) {
              GitLocal.checkout(project, dir, original)
            }
            if (!createdBranch.isNullOrBlank()) {
              GitLocal.deleteLocalBranch(project, dir, createdBranch, force = true)
            }
          }

          val res = syncRes.step
          if (!res.ok) {
            historyService.add("Send MR/PR", false, "trace=${syncRes.traceId} repo='${syncRes.repo ?: s.repo}' ${res.message}. ${res.details}")
            notify("[trace=${syncRes.traceId}] repo='${syncRes.repo ?: s.repo}' ${res.message}. ${res.details}", NotificationType.ERROR)
            markLastSync(SyncOutcome.FAIL)
            refreshHistoryLog()
            return
          }
          if (s.offlineGenerateOnly) {
            append("Offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
            historyService.add("Send MR/PR", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: s.repo}' offline dump: ${syncRes.dump?.absolutePath ?: res.details}")
            notify("[trace=${syncRes.traceId}] Offline mode: dump generated for repo '${syncRes.repo ?: s.repo}'", NotificationType.INFORMATION)
          } else {
            append("OK: ${syncRes.http?.code} ${syncRes.http?.body?.take(400) ?: ""}")
            historyService.add("Send MR/PR", true, "trace=${syncRes.traceId} repo='${syncRes.repo ?: s.repo}' ${syncRes.http?.body ?: "OK"}")
            notify("[trace=${syncRes.traceId}] Sent MR !$mrIid to Mirror repo '${syncRes.repo ?: s.repo}'", NotificationType.INFORMATION)
          }
          centerHint.text = "<html><body style='color:#8bc34a'>Last: Send MR/PR ✅</body></html>"
          markLastSyncOk()
          refreshHistoryLog()
        } finally {
          isSyncing = false
        }
      }

      override fun onFinished() {
        isSyncing = false
      }
    })
  }

  private fun pullBack() {
    val dir = baseDir() ?: run {
      notify("Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val s = service<MirrorSettingsService>().state
    val remote = s.gitRemoteName.ifBlank { "origin" }

    val branches = GitLocal.remoteBranches(project, dir, remote)
    if (branches.isEmpty()) {
      notify("No remote branches found for '$remote'", NotificationType.WARNING)
      return
    }

    val selectedRemoteRef = Messages.showEditableChooseDialog(
      LocalGitMirrorBundle.message("dialog.pullBack.selectRemote"),
      LocalGitMirrorBundle.message("dialog.pullBack.localBranchTitle"),
      null,
      branches.toTypedArray(),
      branches.firstOrNull(),
      null
    ) ?: return

    val mode = Messages.showEditableChooseDialog(
      LocalGitMirrorBundle.message("dialog.pullBack.mode"),
      LocalGitMirrorBundle.message("dialog.pullBack.localBranchTitle"),
      null,
      arrayOf("new-branch", "ff-only"),
      s.pullBackDefaultMode,
      null
    )?.trim()?.lowercase().orEmpty()

    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Pull back", false) {
      override fun run(indicator: ProgressIndicator) {
        try {
          val original = GitLocal.currentBranch(project, dir)
          append("Pull back from $selectedRemoteRef mode=$mode")

          val fetch = GitLocal.fetch(project, dir, remote)
          if (!fetch.ok()) {
            notify("git fetch failed: ${fetch.stderr}", NotificationType.ERROR)
            markLastSync(SyncOutcome.FAIL)
            historyService.add("Pull back", false, "git fetch failed")
            refreshHistoryLog()
            return
          }

          when (mode) {
            "ff-only" -> {
              val current = GitLocal.currentBranch(project, dir)
              if (current.isNullOrBlank()) {
                notify("Cannot determine current branch", NotificationType.ERROR)
                markLastSync(SyncOutcome.FAIL)
                historyService.add("Pull back", false, "Cannot determine current branch")
                refreshHistoryLog()
                return
              }
              val pull = GitLocal.pullFfOnly(project, dir, remote, current)
              if (!pull.ok()) {
                notify("git pull --ff-only failed: ${pull.stderr}", NotificationType.ERROR)
                markLastSync(SyncOutcome.FAIL)
                historyService.add("Pull back", false, "git pull --ff-only failed")
                refreshHistoryLog()
                return
              }
              notify("Pull back completed (ff-only)", NotificationType.INFORMATION)
              markLastSyncOk()
              historyService.add("Pull back", true, "ff-only")
              centerHint.text = "<html><body style='color:#8bc34a'>Last: Pull back (ff-only) ✅</body></html>"
              refreshHistoryLog()
            }

            else -> {
              val defaultName = "pullback-${System.currentTimeMillis()}"
              val localName = Messages.showInputDialog(
                project,
                LocalGitMirrorBundle.message("dialog.pullBack.localBranch"),
                LocalGitMirrorBundle.message("dialog.pullBack.localBranchTitle"),
                null,
                defaultName,
                null
              )?.trim().orEmpty()
              if (localName.isBlank()) return

              val co = GitLocal.checkoutNew(project, dir, localName, selectedRemoteRef)
              if (!co.ok()) {
                notify("Failed to create local branch: ${co.stderr}", NotificationType.ERROR)
                markLastSync(SyncOutcome.FAIL)
                historyService.add("Pull back", false, "Failed to create local branch")
                refreshHistoryLog()
                return
              }
              if (!original.isNullOrBlank()) {
                GitLocal.checkout(project, dir, original)
              }
              notify("Pull back completed into new branch '$localName'", NotificationType.INFORMATION)
              markLastSyncOk()
              historyService.add("Pull back", true, "new branch '$localName'")
              centerHint.text = "<html><body style='color:#8bc34a'>Last: Pull back (new branch) ✅</body></html>"
              refreshHistoryLog()
            }
          }
        } finally {
          isSyncing = false
        }
      }

      override fun onFinished() {
        isSyncing = false
      }
    })
  }

  private fun stealthPullBack() {
    isSyncing = true
    try {
      localgitmirror.idea.actions.StealthPullBackFromMirrorAction().actionPerformed(
        com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
          "LocalGitMirrorToolWindow",
          null,
          com.intellij.openapi.actionSystem.DataContext { dataId ->
            if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(dataId)) project else null
          }
        )
      )
    } finally {
      // NOTE: StealthPullBack is asynchronous but doesn't have a callback here.
      // For now we just reset it, but ideally we should listen to sync events.
      isSyncing = false
    }
  }

  private fun applyLocalDump() {
    localgitmirror.idea.actions.ApplyLocalDumpAction().actionPerformed(
      com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
        "LocalGitMirrorToolWindow",
        null,
        com.intellij.openapi.actionSystem.DataContext { dataId ->
          if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(dataId)) project else null
        }
      )
    )
  }

  private fun pushCurrent() {
    val dir = baseDir() ?: run {
      notify("Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val branch = GitLocal.currentBranch(project, dir)
    if (branch.isNullOrBlank()) {
      notify("Cannot determine current branch", NotificationType.ERROR)
      return
    }
    val s = service<MirrorSettingsService>().state
    val remote = Messages.showInputDialog(project, "Remote name", "git push", null, s.gitRemoteName, null) ?: return

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: git push", false) {
      override fun run(indicator: ProgressIndicator) {
        indicator.text = "Pushing $remote/$branch"
        append("git push $remote $branch")
        val res = GitLocal.push(project, dir, remote, branch, setUpstream = true)
        if (!res.ok()) {
          append("push failed: ${res.stderr}")
          notify("git push failed: ${res.stderr.take(500)}", NotificationType.ERROR)
          return
        }
        append("push ok: ${res.stdout}")
        notify("git push OK ($remote/$branch)", NotificationType.INFORMATION)
      }
    })
  }

  private fun testMirror() {
    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isBlank()) {
      notify(LocalGitMirrorBundle.message("toolwindow.badge.mirrorNotConfigured"), NotificationType.WARNING)
      return
    }

    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Test Mirror", false) {
      override fun run(indicator: ProgressIndicator) {
        try {
          val res = MirrorApi.ping(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls)
          append("Mirror test HTTP ${res.code}: ${res.body.take(300)}")
          if (res.code !in 200..299) {
            val msg = if (res.code == 0) {
              LocalGitMirrorBundle.message("notify.mirror.unreachable", res.body)
            } else {
              LocalGitMirrorBundle.message("notify.mirror.testFail", res.code.toString())
            }
            notify(msg, NotificationType.ERROR)
            return
          }
          notify(LocalGitMirrorBundle.message("notify.mirror.testOk"), NotificationType.INFORMATION)
        } finally {
          isSyncing = false
        }
      }

      override fun onFinished() {
        isSyncing = false
      }
    })
  }

  private fun runPreflight() {
    val dir = baseDir() ?: run {
      notify("Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val s = service<MirrorSettingsService>().state
    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Preflight", false) {
      override fun run(indicator: ProgressIndicator) {
        try {
          val r = syncFacade.runPreflight(dir, s)
          append("Preflight: target=${r.targetRepo ?: "(none)"} ok=${r.ok}")
          r.diagnostics.forEach { d ->
            append("[${d.severity}] ${d.code}: ${d.message}${if (d.hint.isNullOrBlank()) "" else " | hint: ${d.hint}"}")
          }
          if (r.ok) {
            notify("Preflight OK", NotificationType.INFORMATION)
          } else {
            notify("Preflight found issues. See history details.", NotificationType.WARNING)
          }
        } finally {
          isSyncing = false
        }
      }

      override fun onFinished() {
        isSyncing = false
      }
    })
  }

  private fun runDryRun() {
    val dir = baseDir() ?: run {
      notify("Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val s = service<MirrorSettingsService>().state
    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Dry-run (Send)", false) {
      override fun run(indicator: ProgressIndicator) {
        try {
          val r = syncFacade.runDryRun(dir, s)
          append("Dry-run (Send): ok=${r.ok} target=${r.targetRepo ?: "(none)"} branch=${r.branch ?: "(none)"}")
          append("Mode=${r.predictedMode} range=${r.commitRange} count=${r.commitCount}")
          r.diagnostics.forEach { d ->
            append("[${d.severity}] ${d.code}: ${d.message}${if (d.hint.isNullOrBlank()) "" else " | hint: ${d.hint}"}")
          }
          if (r.ok) {
            notify("Dry-run (Send): ${r.predictedMode}, ${r.commitCount} commits", NotificationType.INFORMATION)
          } else {
            notify("Dry-run (Send) failed. See history details.", NotificationType.WARNING)
          }
        } finally {
          isSyncing = false
        }
      }

      override fun onFinished() {
        isSyncing = false
      }
    })
  }

  private fun runPullDryRun() {
    val dir = baseDir() ?: run {
      notify("Cannot determine project directory", NotificationType.ERROR)
      return
    }
    val s = service<MirrorSettingsService>().state
    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Dry-run (Pull)", false) {
      override fun run(indicator: ProgressIndicator) {
        try {
          val r = syncFacade.runPullDryRun(dir, s)
          append("Dry-run (Pull): ok=${r.ok} target=${r.targetRepo ?: "(none)"}")
          append("hasUpdates=${r.hasUpdates} remoteHead=${r.remoteHead?.take(12) ?: "(empty)"} reason=${r.reason}")
          r.diagnostics.forEach { d ->
            append("[${d.severity}] ${d.code}: ${d.message}${if (d.hint.isNullOrBlank()) "" else " | hint: ${d.hint}"}")
          }
          if (r.ok) {
            notify("Dry-run (Pull): hasUpdates=${r.hasUpdates}, reason=${r.reason}", NotificationType.INFORMATION)
          } else {
            notify("Dry-run (Pull) failed. See history details.", NotificationType.WARNING)
          }
        } finally {
          isSyncing = false
        }
      }

      override fun onFinished() {
        isSyncing = false
      }
    })
  }

  private fun testGitLab() {
    val s = service<MirrorSettingsService>().state
    if (s.gitLabBaseUrl.isBlank() || SecretsStore.gitLabToken.isBlank() || s.gitLabProject.isBlank()) {
      notify(LocalGitMirrorBundle.message("toolwindow.badge.gitlabNotConfigured"), NotificationType.WARNING)
      return
    }

    isSyncing = true
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "LocalGitMirror: Test GitLab", false) {
      override fun run(indicator: ProgressIndicator) {
        try {
          val (res, mrs) = GitLabApi.listOpenMergeRequests(
            baseUrl = s.gitLabBaseUrl,
            token = SecretsStore.gitLabToken,
            projectIdOrPath = s.gitLabProject,
            insecureTls = s.gitLabInsecureTls,
            perPage = 5
          )
          append("GitLab test HTTP ${res.code}. Open MRs loaded: ${mrs.size}")
          if (res.code !in 200..299) {
            val msg = if (res.code == 0) {
              LocalGitMirrorBundle.message("notify.gitlab.unreachable", res.body)
            } else {
              LocalGitMirrorBundle.message("notify.gitlab.testFail", res.code.toString())
            }
            notify(msg, NotificationType.ERROR)
            return
          }
          notify(LocalGitMirrorBundle.message("notify.gitlab.testOk", mrs.size.toString()), NotificationType.INFORMATION)
        } finally {
          isSyncing = false
        }
      }

      override fun onFinished() {
        isSyncing = false
      }
    })
  }

  private fun pickMrIid(s: MirrorSettingsService.State): String? {
    val (listRes, mrs) = GitLabApi.listOpenMergeRequests(
      baseUrl = s.gitLabBaseUrl,
      token = SecretsStore.gitLabToken,
      projectIdOrPath = s.gitLabProject,
      insecureTls = s.gitLabInsecureTls,
      perPage = 20
    )

    if (listRes.code !in 200..299 || mrs.isEmpty()) {
      if (listRes.code !in 200..299) {
        append("MR list failed HTTP ${listRes.code}: ${listRes.body.take(300)}")
      }
      return Messages.showInputDialog(project, "Enter GitLab MR IID", "LocalGitMirror: Send MR/PR", null)
    }

    val items = mrs.map { it.display() }.toTypedArray()
    val selected = Messages.showEditableChooseDialog(
      "Select open Merge Request",
      "LocalGitMirror",
      null,
      items,
      items.firstOrNull(),
      null
    ) ?: return null

    val selectedMr = mrs.firstOrNull { it.display() == selected }
    if (selectedMr != null) return selectedMr.iid.toString()

    val typed = selected.trim()
    if (typed.isBlank()) return null
    return typed.removePrefix("!")
  }

  private fun pickCommitHashes(commits: List<GitLocal.CommitSummary>): List<String> {
    val dlg = CommitPickerDialog(project, commits)
    if (!dlg.showAndGet()) return emptyList()
    return dlg.selectedHashes
  }

  private fun markLastSyncOk() {
    markLastSync(SyncOutcome.OK)
  }

  private fun markLastSync(outcome: SyncOutcome) {
    val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    lastSyncBadge.status = when (outcome) {
      SyncOutcome.OK -> BadgeLabel.Status.GOOD
      SyncOutcome.FAIL -> BadgeLabel.Status.BAD
    }
    lastSyncBadge.text = when (outcome) {
      SyncOutcome.OK -> "Last sync: $ts ✅"
      SyncOutcome.FAIL -> "Last sync: $ts ❌"
    }
  }

  private fun refreshHistoryLog() {
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

  private fun copyConfigLine() {
    val s = service<MirrorSettingsService>().state
    val line = ConfigLineCodec.encode(
      ConfigSnapshot(
        baseUrl = s.baseUrl,
        repo = s.repo,
        mirrorInsecureTls = s.mirrorInsecureTls,
        offlineGenerateOnly = s.offlineGenerateOnly,
        simpleUiMode = s.simpleUiMode,
        gitLabBaseUrl = s.gitLabBaseUrl,
        gitLabProject = s.gitLabProject,
        gitLabInsecureTls = s.gitLabInsecureTls,
        gitRemoteName = s.gitRemoteName,
        pullBackDefaultMode = s.pullBackDefaultMode,
        mirrorApiKey = SecretsStore.mirrorApiKey,
        syncPassword = SecretsStore.syncPassword,
        gitLabToken = SecretsStore.gitLabToken
      )
    )

    CopyPasteManager.getInstance().setContents(StringSelection(line))
    notify(LocalGitMirrorBundle.message("notify.config.copied"), NotificationType.INFORMATION)
  }

  private fun pasteConfigLine() {
    val clipboardText = try {
      val data = Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor)
      data?.toString().orEmpty()
    } catch (_: Exception) {
      ""
    }

    // Try to find token in arbitrary clipboard text (with extra chars/newlines).
    val fromClipboard = ConfigLineCodec.extractOrNull(clipboardText)

    val line = if (fromClipboard != null) {
      fromClipboard
    } else {
      val manual = Messages.showInputDialog(
        project,
        "Paste LGM_CONFIG_V1 line",
        LocalGitMirrorBundle.message("dialog.selectBranch.title"),
        null,
        "",
        null
      )?.trim().orEmpty()

      val extractedManual = ConfigLineCodec.extractOrNull(manual)
      if (extractedManual == null) {
        // If token wasn't detected, allow direct payload paste fallback.
        val direct = ConfigLineCodec.decode(manual)
        if (direct == null) {
          notify(LocalGitMirrorBundle.message("notify.config.notFound"), NotificationType.WARNING)
          return
        }
        applySnapshot(direct)
        notify(LocalGitMirrorBundle.message("notify.config.applied"), NotificationType.INFORMATION)
        return
      }
      extractedManual
    }

    val snapshot = ConfigLineCodec.decode(line)
    if (snapshot == null) {
      notify(LocalGitMirrorBundle.message("notify.config.invalid"), NotificationType.ERROR)
      return
    }

    applySnapshot(snapshot)
    notify(LocalGitMirrorBundle.message("notify.config.applied"), NotificationType.INFORMATION)
  }

  private fun applySnapshot(snapshot: ConfigSnapshot) {
    val s = service<MirrorSettingsService>().state
    s.baseUrl = snapshot.baseUrl
    s.repo = snapshot.repo
    s.mirrorInsecureTls = snapshot.mirrorInsecureTls
    s.offlineGenerateOnly = snapshot.offlineGenerateOnly
    s.simpleUiMode = snapshot.simpleUiMode
    s.gitLabBaseUrl = snapshot.gitLabBaseUrl
    s.gitLabProject = snapshot.gitLabProject
    s.gitLabInsecureTls = snapshot.gitLabInsecureTls
    s.gitRemoteName = snapshot.gitRemoteName
    s.pullBackDefaultMode = snapshot.pullBackDefaultMode

    SecretsStore.mirrorApiKey = snapshot.mirrorApiKey
    SecretsStore.syncPassword = snapshot.syncPassword
    SecretsStore.gitLabToken = snapshot.gitLabToken

    refreshStatus()
  }
}
