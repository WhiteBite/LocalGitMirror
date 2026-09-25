package localgitmirror.idea.ui

import java.awt.Dimension
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BadgeLabelPaintTest {

  @Test
  fun `paint must not mutate foreground (mutating it schedules repaints and starves the EDT)`() {
    val label = BadgeLabel("ROLE")
    label.status = BadgeLabel.Status.GOOD
    val before = label.foreground
    assertNotNull(before)

    val img = BufferedImage(200, 40, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
      label.size = Dimension(200, 40)
      repeat(3) { label.paint(g) }
    } finally {
      g.dispose()
    }

    assertEquals(before, label.foreground, "paintComponent changed foreground — repaint loop regression")
  }

  @Test
  fun `status change recolors text via foreground`() {
    val label = BadgeLabel("ROLE")
    val neutral = label.foreground
    label.status = BadgeLabel.Status.BAD
    val bad = label.foreground
    kotlin.test.assertNotEquals(neutral, bad)
  }
}
