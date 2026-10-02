package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.actions.GitLabMrSender
import localgitmirror.idea.gitlab.MrNotesWriter
import localgitmirror.idea.gitlab.MrReviewService
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel

internal fun LocalGitMirrorPanel.buildReviewTab(): JComponent {
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
    add(JBLabel(LocalGitMirrorBundle.message("review.legend.multi")).apply {
      font = JBUI.Fonts.smallFont()
      foreground = JBColor(0x6F7277, 0x6F7277)
    })
  }

  val north = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    add(statusRow)
    add(mrReviewErrorLabel)
    add(searchRow)
    add(headerRow)
  }

  val listScroll = JScrollPane(mrReviewList).apply {
    border = BorderFactory.createEmptyBorder()
    viewportBorder = BorderFactory.createEmptyBorder()
  }

  val isWorkRole = localgitmirror.idea.deps.RoleDetector.detect(service<MirrorSettingsService>().state) ==
    localgitmirror.idea.deps.MachineRole.WORK
  fun iconBtn(icon: javax.swing.Icon, tipKey: String, onClick: (javax.swing.JButton) -> Unit): JButton =
    JButton(icon).apply {
      margin = JBUI.insets(2, 4)
      isFocusPainted = false
      toolTipText = LocalGitMirrorBundle.message(tipKey)
      addActionListener { onClick(this) }
    }
  val openBtn = iconBtn(AllIcons.Actions.Show, "review.tip.open") {
    mrReviewList.selectedValue?.let { openMrDialog(it) }
  }
  val fetchBtn = iconBtn(AllIcons.Actions.Refresh, "review.tip.fetch") { reloadReview() }
  reviewFetchButton = fetchBtn
  val syncNotesBtn = iconBtn(AllIcons.Actions.Upload, "review.tip.syncNotes") { syncMrNotes() }
  syncNotesBtn.isVisible = isWorkRole
  val pushRepliesBtn = iconBtn(AllIcons.Actions.Download, "review.tip.pushReplies") {
    localgitmirror.idea.gitlab.MrReplyPushService(project).pushInBackground()
  }
  pushRepliesBtn.isVisible = isWorkRole
  val moreBtn = iconBtn(AllIcons.Actions.MoreHorizontal, "panel.toolbar.more.tooltip") {
    val popup = javax.swing.JPopupMenu()
    popup.add(javax.swing.JMenuItem(LocalGitMirrorBundle.message("review.sendSelected")).apply {
      addActionListener { sendSelectedMrs() }
    })
    popup.add(javax.swing.JMenuItem(LocalGitMirrorBundle.message("review.saveAll")).apply {
      addActionListener { saveAllUnresolved() }
    })
    popup.show(it, 0, it.height)
  }
  val selectionGate = object : javax.swing.event.ListSelectionListener {
    override fun valueChanged(e: javax.swing.event.ListSelectionEvent?) {
      openBtn.isEnabled = mrReviewList.selectedValue != null
    }
  }
  mrReviewList.addListSelectionListener(selectionGate)
  selectionGate.valueChanged(null)
  project.getService(MrReviewService::class.java).onCacheUpdated = { refreshReview() }

  val bottom = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
    isOpaque = false
    border = JBUI.Borders.empty(4, 0, 0, 0)
    add(openBtn)
    add(fetchBtn)
    add(syncNotesBtn)
    add(pushRepliesBtn)
    add(moreBtn)
  }

  return JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(4, 8)
    add(north, BorderLayout.NORTH)
    add(listScroll, BorderLayout.CENTER)
    add(bottom, BorderLayout.SOUTH)
  }
}

internal fun LocalGitMirrorPanel.openMrDialog(row: MrReviewService.MrRowItem) {
  localgitmirror.idea.gitlab.MrReviewDialog.openFor(project, row)
}

/** Upload mr-notes for the selected MRs (all open MRs when none is selected) when their rendered content changed. */
internal fun LocalGitMirrorPanel.syncMrNotes(onlyIids: Set<Int>? = null) {
  val only = onlyIids ?: mrReviewList.selectedValuesList.map { it.iid }.toSet().ifEmpty { null }
  localgitmirror.idea.gitlab.MrNotesSync.syncInBackground(project, only) { report ->
    if (project.isDisposed) return@syncInBackground
    if (report.failures.isEmpty()) {
      notify(
        LocalGitMirrorBundle.message("mrnotes.sync.ok", report.uploaded.size, report.unchanged),
        NotificationType.INFORMATION,
      )
    } else {
      notify(
        LocalGitMirrorBundle.message("mrnotes.sync.fail", report.failures.joinToString("; ").take(300)),
        NotificationType.WARNING,
      )
    }
    historyService.add(
      LocalGitMirrorBundle.message("mrnotes.history.send"),
      report.failures.isEmpty(),
      "uploaded=${report.uploaded} unchanged=${report.unchanged}",
    )
    reloadReview(notify = false)
  }
}

/** Send selected MRs' branches to Cache in one batch via GitLabMrSender.sendAll. */
internal fun LocalGitMirrorPanel.sendSelectedMrs() {
  val rows = mrReviewList.selectedValuesList
    .filter { it.source == MrReviewService.Source.GITLAB && it.sourceBranch.isNotBlank() }
  if (rows.isEmpty()) {
    notify(LocalGitMirrorBundle.message("review.sendSelected.none"), NotificationType.WARNING)
    return
  }
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: send ${rows.size} MR(s)", true) {
    override fun run(indicator: ProgressIndicator) {
      GitLabMrSender.sendAll(project, rows.map { it.sourceBranch to it.iid })
    }
  })
}

internal fun LocalGitMirrorPanel.refreshReview() {
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
  val signature = visible.joinToString(";") {
    "${it.iid}:${it.unresolved}:${it.replyState}:${it.replyPosted}:${it.replyFailed}:${it.notesSentAt}:${it.title}:${it.source}"
  }
  if (signature != reviewRowsSignature) {
    reviewRowsSignature = signature
    val selectedIids = mrReviewList.selectedValuesList.map { it.iid }.toSet()
    val viewport = mrReviewList.parent as? javax.swing.JViewport
    val viewPos = viewport?.viewPosition
    mrReviewListModel.clear()
    visible.forEach { mrReviewListModel.addElement(it) }
    val indices = (0 until mrReviewListModel.size())
      .filter { mrReviewListModel.getElementAt(it).iid in selectedIids }
      .toIntArray()
    if (indices.isNotEmpty()) mrReviewList.selectedIndices = indices
    if (viewPos != null) viewport?.viewPosition = viewPos
  }

  val err = service.cachedError()
  mrReviewErrorLabel.text = err?.let { LocalGitMirrorBundle.message("review.error.cache", it) } ?: ""
  mrReviewErrorLabel.isVisible = err != null

  val attention = rows.count { it.unresolved > 0 }
  mrReviewStatus.text = if (attention > 0)
    LocalGitMirrorBundle.message("review.status.attention", attention, rows.size)
  else
    LocalGitMirrorBundle.message("review.status.all", rows.size)

  updateReviewTabTitle()

  val hasGitlab = runCatching {
    val conf = localgitmirror.idea.gitlab.GitLabConfig.resolve(project)
    conf.url.isNotBlank() && conf.project.isNotBlank() &&
      localgitmirror.idea.gitlab.GitLabConfig.hasApi(conf)
  }.getOrDefault(false)
  reviewFetchButton?.text = LocalGitMirrorBundle.message(
    if (hasGitlab) "review.fetch" else "review.fetch.cache"
  )
}

internal fun LocalGitMirrorPanel.reloadReview(notify: Boolean = true) {
  project.getService(MrReviewService::class.java).refreshInBackground(notify = notify) {
    if (project.isDisposed) return@refreshInBackground
    refreshReview()
  }
}

internal fun LocalGitMirrorPanel.updateReviewTabTitle() {
  // Review tab is not rendered (Phase 1): tab index 1 is Chat, so no title update here.
}

internal fun LocalGitMirrorPanel.saveAllUnresolved() {
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

internal fun LocalGitMirrorPanel.saveMrForBranch(sel: BranchListItem) {
  val iid = sel.mrIid ?: return
  val service = project.getService(MrReviewService::class.java)
  val row = service.cachedRows().firstOrNull { it.iid == iid } ?: return
  MrNotesWriter.writeForAgent(project, row)
  MrNotesWriter.writeIndex(project, service.cachedRows())
  notify(LocalGitMirrorBundle.message("review.save.ok", 1), NotificationType.INFORMATION)
}

internal class MrReviewListCellRenderer : ColoredListCellRenderer<MrReviewService.MrRowItem>() {
  override fun customizeCellRenderer(
    list: JList<out MrReviewService.MrRowItem>,
    value: MrReviewService.MrRowItem?,
    index: Int,
    selected: Boolean,
    hasFocus: Boolean
  ) {
    if (value == null) return
    border = JBUI.Borders.empty(1, 6)
    val amber = JBColor(0xB8860B, 0xE3AE4D)
    val green = JBColor(0x2E7D32, 0x66BB6A)
    val badgeAttr = if (value.unresolved > 0)
      SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, amber)
    else
      SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.GRAY)
    append("!${value.iid}", badgeAttr)
    val countAttr = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN,
      if (value.unresolved > 0) amber else green)
    val countText = if (value.unresolved > 0) "${value.unresolved}\u26a0"
                    else "\u2713 ${value.totalThreads}"
    append("  $countText", countAttr)
    val notesText = value.notesSentAt
      ?.let { LocalGitMirrorBundle.message("review.notes.sent", it) }
      ?: LocalGitMirrorBundle.message("review.notes.notsent")
    append("  \u00b7 $notesText", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    if (value.replyState.isNotBlank()) {
      val count = if (value.replyState == "failed") value.replyFailed else value.replyPosted
      val stateColor = when (value.replyState) {
        "failed", "error" -> JBColor(0xC62828, 0xEF5350)
        "posted" -> green
        "local" -> JBColor(0x6F7277, 0x8C8F94)
        else -> amber
      }
      val stateKey = if (value.replyState == "pending" &&
        localgitmirror.idea.deps.RoleDetector.detect(service<MirrorSettingsService>().state) ==
        localgitmirror.idea.deps.MachineRole.WORK
      ) "review.state.pending.work" else "review.state.${value.replyState}"
      val stateText = LocalGitMirrorBundle.message(stateKey, count)
      append("  \u00b7 $stateText", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, stateColor))
    }
    append("  ${value.title}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
    if (value.source == MrReviewService.Source.CACHE) {
      append("  [${LocalGitMirrorBundle.message("review.row.cache")}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
    append("  ${value.sourceBranch}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
  }
}
