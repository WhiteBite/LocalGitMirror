package localgitmirror.idea.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class LocalGitMirrorToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = LocalGitMirrorPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    toolWindow.contentManager.addContent(content)
  }

  override fun shouldBeAvailable(project: Project): Boolean = true
}
