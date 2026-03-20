package localgitmirror.idea.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.FontMetrics
import java.awt.Graphics
import javax.swing.JLabel

class BadgeLabel(text: String) : JLabel(text) {
  enum class Status {
    GOOD,
    BAD,
    WARNING,
    NEUTRAL
  }

  var status: Status = Status.NEUTRAL
    set(value) {
      field = value
      repaint()
    }

  init {
    isOpaque = false
    border = JBUI.Borders.empty(2, 8)
    font = JBUI.Fonts.label(11f)
  }

  override fun paintComponent(g: Graphics) {
    val arc = JBUI.scale(10)
    val bg: Color = when (status) {
      Status.GOOD -> JBColor(Color(0x307D4C), Color(0x307D4C))
      Status.BAD -> JBColor(Color(0x8C3737), Color(0x8C3737))
      Status.WARNING -> JBColor(Color(0xD19A66), Color(0xD19A66))
      Status.NEUTRAL -> JBColor(Color(0x505050), Color(0x505050))
    }
    g.color = bg
    g.fillRoundRect(0, 0, width, height, arc, arc)

    g.color = Color.WHITE
    val fm: FontMetrics = g.getFontMetrics(font)
    val textWidth = fm.stringWidth(text)
    val x = (width - textWidth) / 2
    val y = (height - fm.height) / 2 + fm.ascent
    g.drawString(text, x, y)
  }
}
