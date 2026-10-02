package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.gitlab.MrReviewService
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

/** Selected-branch card under the branch list: sync summary, MR notes/replies, transfer actions. */
internal class BranchDetail(private val panel: LocalGitMirrorPanel) : JPanel(BorderLayout()) {

  private var current: BranchListItem? = null

  private val nameLabel = JBLabel().apply {
    font = JBUI.Fonts.smallFont().asBold()
    foreground = UIUtil.getLabelForeground()
  }
  private val codeValue = JBLabel().apply {
    font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(11))
    foreground = UIUtil.getLabelForeground()
  }
  private val notesValue = JBLabel().apply { font = JBUI.Fonts.smallFont() }
  private val repliesValue = JBLabel().apply { font = JBUI.Fonts.smallFont() }
  private val notesRow = keyValueRow("detail.row.notes", notesValue)
  private val repliesRow = keyValueRow("detail.row.replies", repliesValue)

  private val pullBtn = panel.primaryBtn(LocalGitMirrorBundle.message("detail.action.pull")) {
    panel.pullSelectedBranches()
  }
  private val sendBtn = panel.btn(LocalGitMirrorBundle.message("detail.action.send")) {
    panel.sendSelectedBranches()
  }
  private val openMrBtn = panel.btn(LocalGitMirrorBundle.message("detail.action.openMr")) {
    val iid = current?.mrIid ?: return@btn
    val row = mrRow(iid) ?: return@btn
    panel.openMrDialog(row)
  }
  private val moreBtn = JButton(AllIcons.Actions.MoreHorizontal).apply {
    margin = JBUI.insets(2, 4)
    isFocusPainted = false
    toolTipText = LocalGitMirrorBundle.message("panel.toolbar.more")
    addActionListener { showMorePopup(this) }
  }

  init {
    isOpaque = false
    border = JBUI.Borders.compound(
      JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
      JBUI.Borders.empty(8, 4, 4, 4)
    )
    val card = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
    }
    card.add(nameLabel)
    card.add(Box.createVerticalStrut(JBUI.scale(4)))
    card.add(keyValueRow("detail.row.code", codeValue))
    card.add(notesRow)
    card.add(repliesRow)
    card.add(Box.createVerticalStrut(JBUI.scale(6)))
    val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
      isOpaque = false
      add(pullBtn)
      add(sendBtn)
      add(openMrBtn)
      add(moreBtn)
    }
    actions.alignmentX = JComponent.LEFT_ALIGNMENT
    card.add(actions)
    add(card, BorderLayout.CENTER)
    refreshFor(null)
  }

  fun refreshFor(item: BranchListItem?) {
    current = item
    if (item == null) {
      nameLabel.text = LocalGitMirrorBundle.message("detail.empty")
      codeValue.text = ""
      notesRow.isVisible = false
      repliesRow.isVisible = false
      openMrBtn.isEnabled = false
      openMrBtn.toolTipText = null
      return
    }
    nameLabel.text = item.name
    codeValue.text = LocalGitMirrorBundle.message(
      "detail.code.line",
      shortHash(item.localHash), shortHash(item.mirrorHash), statusText(item)
    )
    val mr = item.mrIid?.let { mrRow(it) }
    notesRow.isVisible = mr != null
    repliesRow.isVisible = mr != null
    openMrBtn.isEnabled = mr != null
    openMrBtn.toolTipText =
      if (mr == null) LocalGitMirrorBundle.message("detail.action.openMr.noMr") else null
    if (mr != null) {
      notesValue.text = LocalGitMirrorBundle.message(
        "detail.notes.line", mr.iid, mr.unresolved,
        mr.notesSentAt
          ?.let { LocalGitMirrorBundle.message("detail.notes.read", it) }
          ?: LocalGitMirrorBundle.message("detail.notes.unread")
      )
      notesValue.foreground = if (mr.unresolved > 0)
        JBColor(0xB8860B, 0xE3AE4D) else UIUtil.getLabelForeground()
      repliesValue.text = repliesText(mr)
    }
  }

  private fun mrRow(iid: Int): MrReviewService.MrRowItem? =
    panel.project.getService(MrReviewService::class.java).cachedRows().firstOrNull { it.iid == iid }

  private fun statusText(item: BranchListItem): String = when {
    item.mrOnly -> LocalGitMirrorBundle.message("detail.code.mrOnly")
    item.status == BranchStatus.SYNCED -> LocalGitMirrorBundle.message("detail.code.synced")
    item.status == BranchStatus.AHEAD -> LocalGitMirrorBundle.message("detail.code.ahead", item.aheadCount ?: "?")
    item.status == BranchStatus.BEHIND -> LocalGitMirrorBundle.message("detail.code.behind", item.behindCount ?: "?")
    item.status == BranchStatus.MIRROR_ONLY -> LocalGitMirrorBundle.message("detail.code.mirrorOnly")
    else -> LocalGitMirrorBundle.message("detail.code.localOnly")
  }

  private fun repliesText(mr: MrReviewService.MrRowItem): String {
    if (mr.replyState.isBlank()) return LocalGitMirrorBundle.message("detail.replies.none")
    val count = if (mr.replyState == "failed") mr.replyFailed else mr.replyPosted
    val isWork = localgitmirror.idea.deps.RoleDetector.detect(service<MirrorSettingsService>().state) ==
      localgitmirror.idea.deps.MachineRole.WORK
    val key = if (mr.replyState == "pending" && isWork)
      "review.state.pending.work" else "review.state.${mr.replyState}"
    return LocalGitMirrorBundle.message(key, count)
  }

  private fun showMorePopup(anchor: JComponent) {
    val popup = JPopupMenu()
    popup.add(JMenuItem(LocalGitMirrorBundle.message("panel.branch.delete")).apply {
      addActionListener { panel.deleteSelectedBranches() }
    })
    val item = current
    val iid = item?.mrIid
    if (item != null && iid != null) {
      popup.addSeparator()
      popup.add(JMenuItem(LocalGitMirrorBundle.message("ctx.mr.save")).apply {
        addActionListener { panel.saveMrForBranch(item) }
      })
      popup.add(JMenuItem(LocalGitMirrorBundle.message("review.sendNotes")).apply {
        addActionListener { panel.syncMrNotes(setOf(iid)) }
      })
    }
    popup.show(anchor, 0, anchor.height)
  }

  private fun keyValueRow(keyText: String, value: JComponent): JPanel = JPanel(BorderLayout()).apply {
    isOpaque = false
    val key = JBLabel(LocalGitMirrorBundle.message(keyText)).apply {
      font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD, JBUI.scale(10f).toFloat())
      foreground = UIUtil.getContextHelpForeground()
      preferredSize = Dimension(JBUI.scale(70), JBUI.scale(18))
      border = JBUI.Borders.emptyRight(8)
    }
    add(key, BorderLayout.WEST)
    add(value, BorderLayout.CENTER)
  }

  private fun shortHash(hash: String?): String = hash?.take(7) ?: "\u2014"
}
