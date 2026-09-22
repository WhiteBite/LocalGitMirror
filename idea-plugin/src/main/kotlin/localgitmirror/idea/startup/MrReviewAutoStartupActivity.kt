package localgitmirror.idea.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import localgitmirror.idea.gitlab.MrReviewAutoService

/**
 * Starts the [MrReviewAutoService] poller on project open (auto MR review:
 * postbox → GitLab at work, postbox → .mr-notes/ at home).
 */
class MrReviewAutoStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    project.getService(MrReviewAutoService::class.java).start()
  }
}
