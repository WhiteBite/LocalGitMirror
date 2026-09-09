package localgitmirror.idea.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JTextArea

/** Read-only viewer for transferred GitLab MR discussions (markdown text). */
class MrNotesDialog(private val path: String, private val text: String) : DialogWrapper(true) {

  private val area = JTextArea(text).apply {
    isEditable = false
    lineWrap = false
    font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))
    caretPosition = 0
  }

  init {
    title = path
    init()
  }

  override fun createCenterPanel(): JComponent = JBScrollPane(area).apply {
    preferredSize = Dimension(JBUI.scale(760), JBUI.scale(500))
  }
}
