package localgitmirror.idea.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

class LocalGitMirrorToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = try {
      LocalGitMirrorPanel(project)
    } catch (t: Throwable) {
      LOG.error("DocCache panel construction failed", t)
      errorPanel(t)
    }
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    toolWindow.contentManager.addContent(content)

    if (panel is LocalGitMirrorPanel) {
      // Refresh UI every time this tool window becomes visible (e.g. after Settings close)
      project.messageBus.connect(toolWindow.contentManager).subscribe(
        ToolWindowManagerListener.TOPIC,
        object : ToolWindowManagerListener {
          override fun toolWindowShown(shownToolWindow: ToolWindow) {
            if (shownToolWindow.id == toolWindow.id) {
              panel.refreshStatus()
            }
          }
        }
      )
    }
  }

  private fun errorPanel(t: Throwable): JPanel = JPanel(BorderLayout()).apply {
    val label = JBLabel("<html>DocCache UI failed to start: ${t.message ?: t::class.java.name}<br>" +
      "See idea.log for the stack trace.</html>")
    label.border = com.intellij.util.ui.JBUI.Borders.empty(12)
    add(label, BorderLayout.NORTH)
  }

  override fun shouldBeAvailable(project: Project): Boolean = true

  private companion object {
    val LOG = Logger.getInstance(LocalGitMirrorToolWindowFactory::class.java)
  }
}
