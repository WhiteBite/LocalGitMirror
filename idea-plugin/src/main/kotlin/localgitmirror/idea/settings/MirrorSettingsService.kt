package localgitmirror.idea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "LocalGitMirrorSettings", storages = [Storage("localgitmirror.xml")])
class MirrorSettingsService : PersistentStateComponent<MirrorSettingsService.State> {

  data class State(
    var baseUrl: String = "https://localhost",

    // ui language: auto | en | ru
    var uiLanguage: String = "auto",

    var mirrorInsecureTls: Boolean = true,

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
    var npmCorporateScopes: String = "",

    // Protocol v3 (hybrid ECIES): the pinned long-term X25519 PUBLIC key of the
    // home server, base64. When set, sync uploads use ephemeral-key encryption
    // to this key (forward secrecy, no shared password needed on this machine)
    // instead of the SYNC_PASSWORD. A public key is not a secret, so storing it
    // in plain settings (not the credential store) is fine. `serverPubKeyFp` is
    // the fingerprint shown for out-of-band verification against the server
    // console. Empty = fall back to the legacy password envelope.
    var serverPubKeyB64: String = "",
    var serverPubKeyFp: String = ""
  )

  private var state = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    XmlSerializerUtil.copyBean(state, this.state)
  }
}
