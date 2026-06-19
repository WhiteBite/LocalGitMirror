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

    // Deps-sync diagnostics. Off by default for stealth: nothing is written to
    // disk. When on, a single diag file is written under the IDE log dir (never
    // the project tree). "verbose" additionally allows coordinate names in it.
    var depsDiagnosticsEnabled: Boolean = false,
    var depsDiagnosticsVerbose: Boolean = false,

    // npm corporate-scope override (comma-separated, e.g. "@krypto-ui,krypto-").
    // The primary npm filter is a live probe of the public registry (a package
    // that 404s on registry.npmjs.org is corporate). This list is an OPTIONAL
    // override for when the dome has no public-npm access, or to force-include
    // packages by scope/prefix. Empty = rely on the registry probe.
    var npmCorporateScopes: String = ""
  )

  private var state = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    XmlSerializerUtil.copyBean(state, this.state)
  }
}
