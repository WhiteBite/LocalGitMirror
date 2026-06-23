package localgitmirror.idea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * PER-PROJECT settings. The Mirror repo name lives here — NOT in the global
 * application settings — so one project's repo can never leak onto another
 * (the old global `repo` field caused exactly that: a value set for
 * onyx-platform routed every project's sync to onyx-platform).
 *
 * Stored in the project's workspace file (`.idea/workspace.xml`), which is
 * local to the machine and not shared via VCS. [repoOverride] is filled
 * automatically by [localgitmirror.idea.sync.v2.RepoResolver] from the git
 * remote when empty, and can be edited by the user in the settings page.
 */
@State(
  name = "LocalGitMirrorProjectSettings",
  storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class MirrorProjectSettingsService : PersistentStateComponent<MirrorProjectSettingsService.State> {

  data class State(
    // Per-project repo name. Empty = auto-resolve from git remote / folder.
    var repoOverride: String = ""
  )

  private var state = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    XmlSerializerUtil.copyBean(state, this.state)
  }
}
