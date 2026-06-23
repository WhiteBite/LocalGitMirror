package localgitmirror.idea.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * A [FlowLayout] that wraps its components onto multiple rows AND reports a
 * correct preferred height for the wrapped layout.
 *
 * Plain FlowLayout always reports a single-row preferred size, so when it is
 * placed in a narrow container (like our tool-window header) the components
 * that don't fit get clipped / ellipsized instead of flowing to the next line.
 * This subclass measures against the target's current width, so the status
 * badges (Mirror / Last sync / version) wrap gracefully and stay fully
 * readable in a narrow tool window.
 *
 * Adapted from the well-known WrapLayout utility (Rob Camick).
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 5) : FlowLayout(align, hgap, vgap) {

  override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

  override fun minimumLayoutSize(target: Container): Dimension {
    val minimum = layoutSize(target, false)
    minimum.width -= (hgap + 1)
    return minimum
  }

  private fun layoutSize(target: Container, preferred: Boolean): Dimension {
    synchronized(target.treeLock) {
      // Width to wrap against: the target's own width, or its container's if
      // the target hasn't been sized yet. Fall back to "infinite" (single row).
      var targetWidth = target.size.width
      if (targetWidth == 0 && target.parent != null) targetWidth = target.parent.size.width
      if (targetWidth == 0) targetWidth = Int.MAX_VALUE

      val insets = target.insets
      val horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2)
      val maxWidth = targetWidth - horizontalInsetsAndGap

      val dim = Dimension(0, 0)
      var rowWidth = 0
      var rowHeight = 0

      for (i in 0 until target.componentCount) {
        val m = target.getComponent(i)
        if (!m.isVisible) continue
        val d = if (preferred) m.preferredSize else m.minimumSize
        if (rowWidth + d.width > maxWidth) {
          addRow(dim, rowWidth, rowHeight)
          rowWidth = 0
          rowHeight = 0
        }
        if (rowWidth != 0) rowWidth += hgap
        rowWidth += d.width
        rowHeight = maxOf(rowHeight, d.height)
      }
      addRow(dim, rowWidth, rowHeight)

      dim.width += horizontalInsetsAndGap
      dim.height += insets.top + insets.bottom + vgap * 2

      // When inside a scroll pane, account for the gap it may add.
      val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target)
      if (scrollPane != null && target.isValid) {
        dim.width -= (hgap + 1)
      }
      return dim
    }
  }

  private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
    dim.width = maxOf(dim.width, rowWidth)
    if (dim.height > 0) dim.height += vgap
    dim.height += rowHeight
  }
}
