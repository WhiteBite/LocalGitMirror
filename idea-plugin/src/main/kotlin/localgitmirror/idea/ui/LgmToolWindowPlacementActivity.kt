package localgitmirror.idea.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Existing projects remember the old bottom anchor in their workspace layout,
 * which overrides the plugin.xml anchor. Once per project, after the layout is
 * restored, move the tool window to the right side (stacks under Gradle).
 * Afterwards the placement is fully user-owned.
 */
class LgmToolWindowPlacementActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    val props = PropertiesComponent.getInstance(project)
    if (props.getBoolean(PLACED_FLAG)) return
    val tw = ToolWindowManager.getInstance(project).getToolWindow("LocalGitMirror") ?: return
    tw.setAnchor(ToolWindowAnchor.RIGHT) { }
    props.setValue(PLACED_FLAG, true)
  }

  private companion object {
    const val PLACED_FLAG = "lgm.toolwindow.right.v1"
  }
}
