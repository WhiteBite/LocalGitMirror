package localgitmirror.idea.ui

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JToggleButton

internal class ProgressController(private val panel: LocalGitMirrorPanel) {

  internal val progressBar = JProgressBar().apply {
    isVisible = false
    isIndeterminate = true
    minimum = 0; maximum = 100
  }
  internal val progressLabel = JBLabel("").apply {
    font = JBUI.Fonts.smallFont()
    foreground = UIUtil.getContextHelpForeground()
    isVisible = false
  }

  internal val cancelButton = JButton("Cancel").apply {
    isVisible = false
    margin = JBUI.insets(1, 8)
    font = JBUI.Fonts.smallFont()
    isFocusPainted = false
    addActionListener { cancelCurrentOperation() }
  }

  private var progressRow: JPanel? = null

  internal var isSyncing = false
    set(value) {
      field = value
      updateUiState()
    }

  /** Current progress indicator for cancellation support. Set by sync operations. */
  internal var currentIndicator: ProgressIndicator? = null

  /** Update progress bar + label from any thread. fraction in [0..1] or -1 for indeterminate. */
  internal fun setProgress(fraction: Double, text: String) {
    UIUtil.invokeLaterIfNeeded {
      progressLabel.text = text
      if (fraction < 0) {
        progressBar.isIndeterminate = true
      } else {
        progressBar.isIndeterminate = false
        progressBar.value = (fraction * 100).toInt().coerceIn(0, 100)
      }
    }
  }

  /** Cancel the currently running sync operation, if any. */
  internal fun cancelCurrentOperation() {
    currentIndicator?.cancel()
  }

  private fun updateUiState() {
    UIUtil.invokeLaterIfNeeded {
      val enabled = !isSyncing
      fun disableAll(container: java.awt.Container) {
        for (c in container.components) {
          if (c is JButton || c is JToggleButton || c is JComboBox<*> || c is JList<*>) c.isEnabled = enabled
          if (c is java.awt.Container) disableAll(c)
        }
      }
      disableAll(panel)
      cancelButton.isEnabled = true
      progressBar.isVisible = isSyncing
      progressLabel.isVisible = isSyncing
      cancelButton.isVisible = isSyncing
      progressRow?.isVisible = isSyncing
      if (!isSyncing) {
        progressLabel.text = ""
        progressBar.isIndeterminate = true
        progressBar.value = 0
        currentIndicator = null
      }
      panel.revalidate()
      panel.repaint()
    }
  }

  internal fun buildProgressRow(): JPanel {
    val row = JPanel(BorderLayout()).apply {
      isOpaque = false
      isVisible = false
      border = JBUI.Borders.empty(2, 8)
      add(progressBar, BorderLayout.CENTER)
      val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
        isOpaque = false
        add(cancelButton)
        add(progressLabel)
      }
      add(right, BorderLayout.EAST)
    }
    progressRow = row
    return row
  }
}
