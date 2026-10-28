package localgitmirror.idea.gitlab

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

class MrReviewTextTest {
  @Test
  fun `last line remains reachable when long finding is resized`() = SwingUtilities.invokeAndWait {
    val area = MrReviewText("A finding with a long explanation and `code()` in its body. ".repeat(40))
    val body = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(area)
      add(JButton("Edit"))
    }
    val scroll = JScrollPane(JPanel(BorderLayout()).apply { add(body, BorderLayout.NORTH) })

    for (width in listOf(500, 260, 600, 320)) {
      scroll.setSize(width, 400)
      repeat(4) { scroll.layoutTree() }

      val lastLine = requireNotNull(area.modelToView2D(area.document.length))
      assertTrue(lastLine.maxY <= area.height, "Last line is clipped at $width: ${lastLine.maxY} > ${area.height}")
    }
  }

  private fun Component.layoutTree() {
    if (this is Container) {
      doLayout()
      components.forEach { it.layoutTree() }
    }
  }
}
