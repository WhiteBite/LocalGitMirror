package localgitmirror.idea.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import localgitmirror.idea.gitlab.GitLabApi
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Multi-select chooser of open GitLab merge requests: any number of MR
 * checkboxes plus a free-text entry ("!N" or a plain branch name, which also
 * works without a token). OK hands the checked MRs and the typed text back to
 * the caller; an empty result means no-op.
 */
class MrMultiSelectDialog(private val mrs: List<GitLabApi.MrInfo>) : DialogWrapper(true) {

  private val checkBoxes = mrs.map { JBCheckBox(mrText(it)) }
  private val selectAll = JBCheckBox(LocalGitMirrorBundle.message("gitlab.chooser.selectAll"))
  private val freeText = JBTextField()

  init {
    title = LocalGitMirrorBundle.message("gitlab.chooser.title")
    setOKButtonText(LocalGitMirrorBundle.message("gitlab.mrlist.send"))
    init()
    freeText.emptyText.text = LocalGitMirrorBundle.message("gitlab.chooser.hint")
    selectAll.addActionListener {
      val selected = selectAll.isSelected
      checkBoxes.forEach { it.isSelected = selected }
      updateOkButton()
    }
    checkBoxes.forEach { box ->
      box.addActionListener {
        selectAll.isSelected = checkBoxes.all { it.isSelected }
        updateOkButton()
      }
    }
    freeText.document.addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updateOkButton()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updateOkButton()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updateOkButton()
    })
    updateOkButton()
  }

  private fun updateOkButton() {
    val n = checkBoxes.count { it.isSelected } + (if (typedText.isBlank()) 0 else 1)
    okAction.putValue(
      javax.swing.Action.NAME,
      if (n == 0) LocalGitMirrorBundle.message("gitlab.mrlist.send")
      else LocalGitMirrorBundle.message("gitlab.chooser.okCount", n)
    )
  }

  val selectedMrs: List<GitLabApi.MrInfo>
    get() = mrs.filterIndexed { index, _ -> checkBoxes[index].isSelected }

  val typedText: String
    get() = freeText.text.trim()

  override fun createCenterPanel(): JComponent {
    val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
    panel.border = JBUI.Borders.empty(8)
    panel.add(JLabel(LocalGitMirrorBundle.message("gitlab.chooser.prompt")), BorderLayout.NORTH)

    val fieldRow = JPanel(BorderLayout()).apply { add(freeText, BorderLayout.NORTH) }
    if (checkBoxes.isEmpty()) {
      panel.add(fieldRow, BorderLayout.CENTER)
      panel.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(100))
      return panel
    }

    val rows = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(selectAll)
      checkBoxes.forEach { add(it) }
      add(Box.createVerticalGlue())
    }
    panel.add(JBScrollPane(rows).apply {
      preferredSize = Dimension(JBUI.scale(560), JBUI.scale(320))
    }, BorderLayout.CENTER)
    panel.add(fieldRow, BorderLayout.SOUTH)
    return panel
  }

  private fun mrText(mr: GitLabApi.MrInfo): String =
    "!${mr.iid}  ${mr.title}  (${mr.sourceBranch})"
}
