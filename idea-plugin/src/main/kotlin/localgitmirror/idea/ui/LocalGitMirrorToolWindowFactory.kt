package localgitmirror.idea.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory

class LocalGitMirrorToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = LocalGitMirrorPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    toolWindow.contentManager.addContent(content)

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

  override fun shouldBeAvailable(project: Project): Boolean = true
}
