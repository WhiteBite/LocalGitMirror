package localgitmirror.idea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "LocalGitMirrorSettings", storages = [Storage("localgitmirror.xml")])
class MirrorSettingsService : PersistentStateComponent<MirrorSettingsService.State> {

  data class State(
    var baseUrl: String = "https://localhost",
    var repo: String = "",

    // ui language: auto | en | ru
    var uiLanguage: String = "auto",

    var mirrorInsecureTls: Boolean = true,

    // GitLab integration (MR fetch/create)
    var gitLabBaseUrl: String = "",
    var gitLabProject: String = "",
    var gitLabDefaultTargetBranch: String = "main",
    var gitLabInsecureTls: Boolean = false,

    var gitRemoteName: String = "origin",
    var pullBackDefaultMode: String = "new-branch",

    // If true, "Send" operations only generate encrypted dump locally.
    var offlineGenerateOnly: Boolean = false,

    // If true, show only essential actions in ToolWindow.
    var simpleUiMode: Boolean = false,

    // If true, check for incoming changes on project open and show balloon if available.
    var autoCheckPullOnStartup: Boolean = false,

    // Work/Home mode: "work", "home", or "auto" (auto-detect from GitLab config).
    var workMode: String = "auto"
  ) {
    /**
     * Resolves the effective mode based on [workMode] setting.
     * - "work" -> always work
     * - "home" -> always home
     * - "auto" -> work if GitLab is configured, home otherwise
     */
    fun resolveMode(): String {
      return when (workMode.lowercase()) {
        "work" -> "work"
        "home" -> "home"
        else -> {
          val gitLabConfigured = gitLabBaseUrl.isNotBlank() && gitLabProject.isNotBlank()
          if (gitLabConfigured) "work" else "home"
        }
      }
    }

    fun isWorkMode(): Boolean = resolveMode() == "work"
    fun isHomeMode(): Boolean = resolveMode() == "home"
  }

  private var state = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    XmlSerializerUtil.copyBean(state, this.state)
  }
}
