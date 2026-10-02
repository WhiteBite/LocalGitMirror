package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.gitlab.MrReviewService
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorSyncApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.ListCellRenderer

internal fun LocalGitMirrorPanel.buildBranchesTab(): JComponent {
  val legendInfo = JBLabel(AllIcons.General.Information).apply {
    toolTipText = LocalGitMirrorBundle.message("panel.branch.legend")
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    border = JBUI.Borders.emptyLeft(6)
  }

  val searchRow = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(4, 0, 4, 0)
    add(branchFilterField, BorderLayout.CENTER)
    add(legendInfo, BorderLayout.EAST)
  }

  val north = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    add(searchRow)
  }

  val listScroll = JScrollPane(branchList).apply {
    border = BorderFactory.createEmptyBorder()
    viewportBorder = BorderFactory.createEmptyBorder()
    background = UIUtil.getListBackground()
    viewport.background = UIUtil.getListBackground()
  }

  val detail = BranchDetail(this)
  branchDetail = detail
  branchList.addListSelectionListener { detail.refreshFor(branchList.selectedValue) }
  detail.refreshFor(branchList.selectedValue)

  return JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(4, 8)
    add(north, BorderLayout.NORTH)
    add(listScroll, BorderLayout.CENTER)
    add(detail, BorderLayout.SOUTH)
  }
}

/**
 * Refresh the selector from local Git immediately. When [withMirror] is true,
 * also append fresh refs from Mirror asynchronously. The mirror fetch is
 * gated off the init/show path so the panel never auto-fires network on
 * activation — only explicit user refresh and post-sync completions opt in.
 */
internal fun LocalGitMirrorPanel.refreshBranchCombo(userInitiated: Boolean = false, withMirror: Boolean = false) {
  val dir = baseDir()
  if (dir == null) {
    if (userInitiated) notify(LocalGitMirrorBundle.message("notify.projectDir.missing"), NotificationType.WARNING)
    return
  }

  val selectedName = selectedBranchChoice()?.name
  ApplicationManager.getApplication().executeOnPooledThread {
    val localBranches = GitLocal.listBranches(project, dir)
    val currentBranch = GitLocal.currentBranch(project, dir)
    val items = computeBranchItems(dir, localBranches, mirrorRefs, currentBranch)
    UIUtil.invokeLaterIfNeeded {
      if (project.isDisposed) return@invokeLaterIfNeeded
      applyBranchItems(items, selectedName, currentBranch)
      if (withMirror) {
        refreshMirrorBranches(dir, localBranches, currentBranch, userInitiated)
      }
    }
  }
}

private fun LocalGitMirrorPanel.refreshMirrorBranches(
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
  val selectedName = selectedBranchChoice()?.name
  setBranchRefreshInProgress(true)
  ApplicationManager.getApplication().executeOnPooledThread {
    val result = MirrorSyncApi.getRefs(
      baseUrl = settings.baseUrl,
      apiKey = SecretsStore.mirrorApiKey,
      repo = repo,
      syncPassword = SecretsStore.syncPassword,
      insecureTls = settings.mirrorInsecureTls
    )
    val newRefs = if (result.code in 200..299 && result.refs != null)
      result.refs.mapValues { it.value.sha } else null
    val items = newRefs?.let { computeBranchItems(dir, localBranches, it, currentBranch) }

    UIUtil.invokeLaterIfNeeded {
      if (project.isDisposed || requestGeneration != branchRefreshGeneration.get()) return@invokeLaterIfNeeded
      if (items != null && newRefs != null) {
        mirrorRefs = newRefs
        applyBranchItems(items, selectedName, currentBranch)
        finishBranchRefresh("Cache: ${mirrorRefs.size} веток")
      } else {
        val detail = "Сервер не ответил: ${result.message.take(120)}"
        finishBranchRefresh(detail)
        if (userInitiated) notify(detail, NotificationType.WARNING)
      }
    }
  }
}

private fun LocalGitMirrorPanel.setBranchRefreshInProgress(inProgress: Boolean) {
  branchRefreshInProgress = inProgress
  com.intellij.ide.ActivityTracker.getInstance().inc()
  if (inProgress) branchList.toolTipText = "Загружаем ветки с сервера…"
}

private fun LocalGitMirrorPanel.finishBranchRefresh(detail: String) {
  setBranchRefreshInProgress(false)
  branchList.toolTipText = "Ветка для Отправить / Подтянуть; ★ есть только на Cache. $detail"
}

/** Re-apply the branch filter text to the list model. */
internal fun LocalGitMirrorPanel.applyBranchFilter() {
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

/** Git-heavy part of the selector rebuild; safe off the EDT. */
private fun LocalGitMirrorPanel.computeBranchItems(
  dir: File,
  localBranches: List<String>,
  mirrorRefs: Map<String, String>,
  currentBranch: String?,
): List<BranchListItem> {
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
  return items.map { item ->
    val mr = mrByBranch[item.name]
    item.copy(mrIid = mr?.iid, mrUnresolved = mr?.unresolved ?: 0)
  }
}

/** Swing part of the selector rebuild; EDT only. */
private fun LocalGitMirrorPanel.applyBranchItems(items: List<BranchListItem>, selectedName: String?, currentBranch: String?) {
  allBranchItems = items

  val preferred = BranchSelectorModel.preferredSelection(selectedName, currentBranch,
    items.map { BranchChoice(it.name, it.localHash != null) })

  val filter = branchFilterField.text.trim().lowercase()
  val visibleItems = if (filter.isBlank()) items else items.filter { it.name.lowercase().contains(filter) }

  branchListModel.clear()
  visibleItems.forEach { branchListModel.addElement(it) }
  if (preferred != null) {
    val idx = visibleItems.indexOfFirst { it.name == preferred }
    if (idx >= 0) branchList.selectedIndex = idx
  }
  updateStatusStrip()
  branchDetail?.refreshFor(branchList.selectedValue)
}

internal fun LocalGitMirrorPanel.selectedBranchChoice(): BranchChoice? {
  val item = branchList.selectedValue ?: return null
  return BranchChoice(item.name, item.localHash != null)
}

/** Returns the raw branch name currently chosen in the selector. */
internal fun LocalGitMirrorPanel.selectedBranch(): String? {
  branchList.selectedValue?.name?.let { return it }
  val dir = baseDir() ?: return null
  return GitLocal.currentBranch(project, dir)
}

internal fun LocalGitMirrorPanel.showBranchContextMenu(e: MouseEvent) {
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
      addActionListener { syncMrNotes(setOf(iid)) }
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

/** Count local branches that are ahead of their tracking branch. */
internal fun LocalGitMirrorPanel.countDivergedBranches(): Int {
  val dir = baseDir() ?: return 0
  val res = GitLocal.run(project, dir, 10L, "for-each-ref", "--format=%(upstream:track)", "refs/heads")
  if (!res.ok()) return 0
  return res.stdout.lines().count { it.trimStart().startsWith("[ahead") }
}

internal class BranchListCellRenderer : JPanel(BorderLayout()), ListCellRenderer<BranchListItem> {
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
    toolTipText = statusTooltip(value.status)

    nameLabel.text = value.name
    nameLabel.font = Font("JetBrains Mono", if (value.isCurrent) Font.BOLD else Font.PLAIN, JBUI.scale(12))
    nameLabel.foreground = when {
      isSelected -> selFg
      value.isCurrent -> JBColor(0x2E7D32, 0x5FAD65)
      value.status == BranchStatus.MIRROR_ONLY -> UIUtil.getContextHelpForeground()
      else -> UIUtil.getListForeground()
    }

    badgeLabel.text = if (value.mrIid != null)
      "  !${value.mrIid}${if (value.mrUnresolved > 0) " \u26a0${value.mrUnresolved}" else ""}"
    else ""
    badgeLabel.foreground = when {
      isSelected -> selFg
      value.mrUnresolved > 0 -> JBColor(0xB8860B, 0xE3AE4D)
      else -> JBColor(0x2E7D32, 0x66BB6A)
    }

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

private fun statusTooltip(status: BranchStatus): String = when (status) {
  BranchStatus.SYNCED -> LocalGitMirrorBundle.message("panel.branch.status.synced")
  BranchStatus.AHEAD -> LocalGitMirrorBundle.message("panel.branch.status.ahead")
  BranchStatus.BEHIND -> LocalGitMirrorBundle.message("panel.branch.status.behind")
  BranchStatus.MIRROR_ONLY -> LocalGitMirrorBundle.message("panel.branch.status.mirrorOnly")
  BranchStatus.LOCAL_ONLY -> LocalGitMirrorBundle.message("panel.branch.status.localOnly")
}
