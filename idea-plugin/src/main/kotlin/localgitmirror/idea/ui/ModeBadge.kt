package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.JLabel

/**
 * Compact WORK/HOME mode indicator with an icon.
 */
class ModeBadge(text: String, isWork: Boolean) : JLabel(text) {

  var workMode: Boolean = isWork
    set(value) {
      field = value
      icon = if (value) AllIcons.Nodes.Deploy else AllIcons.Actions.ProjectWideAnalysisOff
      repaint()
    }

  init {
    isOpaque = false
    font = JBUI.Fonts.label(11f).deriveFont(java.awt.Font.BOLD)
    border = JBUI.Borders.empty(2, 6)
    icon = if (isWork) AllIcons.Nodes.Deploy else AllIcons.Actions.ProjectWideAnalysisOff
    iconTextGap = JBUI.scale(3)
    horizontalTextPosition = JLabel.RIGHT
  }

  private val accentColor: Color
    get() = if (workMode)
      JBColor(Color(0x3574F0), Color(0x589DF6))
    else
      JBColor(Color(0x307D4C), Color(0x4CAF50))

  override fun getForeground(): Color = accentColor
}
