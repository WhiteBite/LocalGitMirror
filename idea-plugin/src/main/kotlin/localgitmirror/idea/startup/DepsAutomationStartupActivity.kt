package localgitmirror.idea.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import localgitmirror.idea.deps.DepsAutomationService

/**
 * Starts the [DepsAutomationService] on project open. The service is idempotent
 * (safe to call multiple times) and cancels itself when the project is disposed.
 *
 * Registered in plugin.xml alongside [PullCheckStartupActivity].
 */
class DepsAutomationStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    project.getService(DepsAutomationService::class.java).start()
  }
}
