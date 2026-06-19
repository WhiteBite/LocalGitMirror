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

    var gitRemoteName: String = "origin",
    var pullBackDefaultMode: String = "new-branch",

    // If true, "Send" operations only generate encrypted dump locally.
    var offlineGenerateOnly: Boolean = false,

    // If true, check for incoming changes on project open and show balloon if available.
    var autoCheckPullOnStartup: Boolean = false,

    // Substrings (comma-separated, plain text) used to detect "internal" repos
    // (e.g. nexus.kryptonit) for Gradle deps sync. Only artifacts originating
    // from these are shipped from work to dome — the rest are reachable from
    // public repos on dome anyway. Empty = ship everything missing.
    var internalRepos: String = ""
  )

  private var state = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    XmlSerializerUtil.copyBean(state, this.state)
  }
}
