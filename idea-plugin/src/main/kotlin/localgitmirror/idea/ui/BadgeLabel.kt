package localgitmirror.idea.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
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
    border = JBUI.Borders.empty(2, 6)
    font = JBUI.Fonts.label(11f)
    iconTextGap = JBUI.scale(4)
  }

  private val dotColor: Color
    get() = when (status) {
      Status.GOOD -> JBColor(Color(0x307D4C), Color(0x4CAF50))
      Status.BAD -> JBColor(Color(0xC62828), Color(0xEF5350))
      Status.WARNING -> JBColor(Color(0xEF6C00), Color(0xFFB74D))
      Status.NEUTRAL -> JBColor(Color(0x616161), Color(0x9E9E9E))
    }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    // Draw small colored dot on the left
    val dotSize = JBUI.scale(6)
    val dotX = JBUI.scale(2)
    val dotY = (height - dotSize) / 2
    g2.color = dotColor
    g2.fillOval(dotX, dotY, dotSize, dotSize)

    g2.dispose()

    // Draw text with status color
    val oldFg = foreground
    foreground = dotColor
    // Shift text right to make room for the dot
    val savedBorder = border
    border = JBUI.Borders.empty(2, JBUI.scale(14), 2, 6)
    super.paintComponent(g)
    border = savedBorder
    foreground = oldFg
  }
}
