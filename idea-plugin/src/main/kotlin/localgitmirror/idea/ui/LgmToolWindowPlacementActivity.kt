package localgitmirror.idea.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Existing projects remember the old bottom anchor in their workspace layout,
 * which overrides the plugin.xml anchor. Once per project, after the layout is
 * restored, move the tool window to the bottom-right (split group of the right
 * stripe = below Gradle). Afterwards the placement is fully user-owned.
 */
class LgmToolWindowPlacementActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    val props = PropertiesComponent.getInstance(project)
    if (props.getBoolean(PLACED_FLAG)) return
    // ToolWindowManager.invokeLater: documented EDT scheduling for tool window ops,
    // drops the callback if the project is disposed.
    ToolWindowManager.getInstance(project).invokeLater {
      if (props.getBoolean(PLACED_FLAG)) return@invokeLater
      val tw = ToolWindowManager.getInstance(project).getToolWindow("DocCache") ?: return@invokeLater
      tw.setAnchor(ToolWindowAnchor.RIGHT, null)
      // split mode on RIGHT anchor = bottom half of the right pane, i.e. under Gradle
      tw.setSplitMode(true, null)
      props.setValue(PLACED_FLAG, true)
    }
  }

  private companion object {
    const val PLACED_FLAG = "doccache.toolwindow.right.v2"
  }
}
