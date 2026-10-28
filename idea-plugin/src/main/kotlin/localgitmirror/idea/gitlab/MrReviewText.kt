package localgitmirror.idea.gitlab

import java.awt.Dimension
import java.awt.Font
import javax.swing.JTextArea
import javax.swing.JViewport
import javax.swing.SwingUtilities

internal class MrReviewText(text: String) : JTextArea(text) {
  init {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    isOpaque = false
    alignmentX = LEFT_ALIGNMENT
  }

  override fun getPreferredSize(): Dimension {
    val width = when {
      width > 0 -> this.width - insets.left - insets.right
      else -> viewportWrapWidth()
    } ?: return super.getPreferredSize()
    val measured = Measurer.height(text, font, width.coerceAtLeast(1))
    return Dimension(width + insets.left + insets.right, measured + insets.top + insets.bottom)
  }

  override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

  override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
    val resized = width != this.width
    super.setBounds(x, y, width, height)
    if (resized) parent?.revalidate()
  }

  private fun viewportWrapWidth(): Int? {
    val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport ?: return null
    if (viewport.extentSize.width <= 0) return null
    var available = viewport.extentSize.width
    var ancestor = parent
    while (ancestor != null && ancestor !== viewport) {
      available -= ancestor.insets.left + ancestor.insets.right
      ancestor = ancestor.parent
    }
    return available
  }

  private object Measurer {
    private val scratch = JTextArea().apply {
      lineWrap = true
      wrapStyleWord = true
    }

    fun height(text: String, font: Font, width: Int): Int {
      scratch.font = font
      scratch.text = text
      scratch.setSize(width, Int.MAX_VALUE)
      return scratch.preferredSize.height
    }
  }
}
