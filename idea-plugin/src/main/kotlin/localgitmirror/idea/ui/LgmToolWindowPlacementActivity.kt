package localgitmirror.idea.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/**
 * Existing projects remember the old bottom anchor in their workspace layout,
 * which overrides the plugin.xml anchor. Once per project, after the layout is
 * restored, move the tool window to the bottom-right (split group of the right
 * stripe = below Gradle). The flag is set only after the anchor is verified;
 * otherwise the attempt is retried a few times with a delay. Afterwards the
 * placement is fully user-owned.
 */
class LgmToolWindowPlacementActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    val props = PropertiesComponent.getInstance(project)
    if (props.getBoolean(PLACED_FLAG)) return
    place(project, props, attempt = 1)
  }

  private fun place(project: Project, props: PropertiesComponent, attempt: Int) {
    // ToolWindowManager.invokeLater: documented EDT scheduling for tool window
    // ops, drops the callback if the project is disposed.
    ToolWindowManager.getInstance(project).invokeLater {
      if (project.isDisposed || props.getBoolean(PLACED_FLAG)) return@invokeLater
      val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
      if (tw == null) {
        LOG.info("[DocCache] placement attempt=$attempt: tool window not registered yet")
        retry(project, props, attempt)
        return@invokeLater
      }
      tw.setAnchor(ToolWindowAnchor.RIGHT, null)
      tw.setSplitMode(true, null)
      val ok = tw.anchor == ToolWindowAnchor.RIGHT
      LOG.info("[DocCache] placement attempt=$attempt anchor=${tw.anchor} ok=$ok")
      if (ok) {
        props.setValue(PLACED_FLAG, true)
      } else {
        retry(project, props, attempt)
      }
    }
  }

  private fun retry(project: Project, props: PropertiesComponent, attempt: Int) {
    if (attempt >= MAX_ATTEMPTS) return
    AppExecutorUtil.getAppScheduledExecutorService().schedule(
      { if (!project.isDisposed) place(project, props, attempt + 1) },
      RETRY_DELAY_SEC * attempt, TimeUnit.SECONDS
    )
  }

  private companion object {
    const val PLACED_FLAG = "doccache.toolwindow.right.v3"
    const val TOOL_WINDOW_ID = "DocCache"
    const val MAX_ATTEMPTS = 4
    const val RETRY_DELAY_SEC = 5L
    val LOG = Logger.getInstance(LgmToolWindowPlacementActivity::class.java)
  }
}
