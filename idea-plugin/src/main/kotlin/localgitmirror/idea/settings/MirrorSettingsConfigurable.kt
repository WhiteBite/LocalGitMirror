package localgitmirror.idea.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.*
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.net.LanDiscovery
import javax.swing.JComponent
import javax.swing.SwingUtilities

class MirrorSettingsConfigurable : Configurable {

  private val state: MirrorSettingsService.State get() = service<MirrorSettingsService>().state
  private var dialogPanel: DialogPanel? = null

  // Mutable copies for DSL binding (synced with SecretsStore on apply/reset)
  private var mirrorApiKeyLocal = ""
  private var syncPasswordLocal = ""
  private var gitLabTokenLocal = ""

  override fun getDisplayName(): String = "LocalGitMirror"

  override fun createComponent(): JComponent {
    // Seed local copies from SecretsStore
    mirrorApiKeyLocal = SecretsStore.mirrorApiKey
    syncPasswordLocal = SecretsStore.syncPassword
    gitLabTokenLocal = SecretsStore.gitLabToken

    val panel = panel {
      // ── Group 1: Mirror Server ──
      group(LocalGitMirrorBundle.message("settings.mirror.title", "Mirror Server")) {
        row(LocalGitMirrorBundle.message("settings.mirror.baseUrl")) {
          textField()
            .bindText(state::baseUrl)
            .gap(RightGap.SMALL)
            .comment("e.g. https://192.168.1.50")
          button(LocalGitMirrorBundle.message("settings.discover")) { onDiscoverClicked() }
            .gap(RightGap.SMALL)
            .comment(LocalGitMirrorBundle.message("settings.discover.tooltip"))
        }
        row(LocalGitMirrorBundle.message("settings.mirror.apiKey")) {
          passwordField()
            .bindText(::mirrorApiKeyLocal)
            .comment("Optional, for API authentication")
        }
        row(LocalGitMirrorBundle.message("settings.mirror.syncPassword")) {
          passwordField()
            .bindText(::syncPasswordLocal)
            .comment("Used to encrypt/decrypt sync bundles")
        }
        row(LocalGitMirrorBundle.message("settings.mirror.repo")) {
          textField()
            .bindText(state::repo)
            .comment("Auto by project name if empty")
        }
        row {
          checkBox(LocalGitMirrorBundle.message("settings.insecureTls"))
            .bindSelected(state::mirrorInsecureTls)
            .comment("For self-signed certificates")
        }
      }

      // ── Group 2: GitLab Integration (collapsible) ──
      collapsibleGroup(LocalGitMirrorBundle.message("settings.gitlab.title")) {
        row(LocalGitMirrorBundle.message("settings.gitlab.baseUrl")) {
          textField()
            .bindText(state::gitLabBaseUrl)
            .comment("e.g. https://gitlab.example.com")
        }
        row(LocalGitMirrorBundle.message("settings.gitlab.token")) {
          passwordField()
            .bindText(::gitLabTokenLocal)
            .comment("PRIVATE-TOKEN header value")
        }
        row(LocalGitMirrorBundle.message("settings.gitlab.project")) {
          textField()
            .bindText(state::gitLabProject)
            .comment("Project ID or group/name")
        }
        row(LocalGitMirrorBundle.message("settings.gitlab.defaultTarget")) {
          textField()
            .bindText(state::gitLabDefaultTargetBranch)
            .comment("Default: main")
        }
        row {
          checkBox(LocalGitMirrorBundle.message("settings.insecureTls"))
            .bindSelected(state::gitLabInsecureTls)
        }
      }

      // ── Group 3: Behavior & Git ──
      group(LocalGitMirrorBundle.message("settings.behavior.title", "Behavior")) {
        row(LocalGitMirrorBundle.message("settings.git.remote")) {
          textField()
            .bindText(state::gitRemoteName)
            .comment("Default: origin")
        }
        row(LocalGitMirrorBundle.message("settings.git.pullMode")) {
          comboBox(listOf("new-branch", "ff-only"))
            .bindItem(
              { state.pullBackDefaultMode },
              { state.pullBackDefaultMode = it ?: "new-branch" }
            )
        }
        row {
          checkBox(LocalGitMirrorBundle.message("settings.sync.autoCheck"))
            .bindSelected(state::autoCheckPullOnStartup)
        }
        row {
          checkBox(LocalGitMirrorBundle.message("settings.sync.offlineMode"))
            .bindSelected(state::offlineGenerateOnly)
            .comment("Generate bundle locally without uploading")
        }
      }

      // ── Group 4: User Interface ──
      group(LocalGitMirrorBundle.message("settings.ui.title", "Interface")) {
        row(LocalGitMirrorBundle.message("settings.ui.language")) {
          comboBox(listOf("auto", "en", "ru"))
            .bindItem(
              { state.uiLanguage },
              { state.uiLanguage = it ?: "auto" }
            )
        }
        row(LocalGitMirrorBundle.message("settings.workMode")) {
          comboBox(listOf("auto", "work", "home"))
            .bindItem(
              { state.workMode },
              { state.workMode = it ?: "auto" }
            )
            .comment("auto = work if GitLab configured, home otherwise")
        }
        row {
          checkBox(LocalGitMirrorBundle.message("settings.ui.simpleMode"))
            .bindSelected(state::simpleUiMode)
            .comment("Show only essential actions in tool window")
        }
      }
    }

    dialogPanel = panel
    return panel
  }

  override fun isModified(): Boolean {
    val panel = dialogPanel ?: return false
    // Check if DSL panel fields changed
    if (panel.isModified()) return true
    // Check SecretsStore-backed fields
    if (mirrorApiKeyLocal != SecretsStore.mirrorApiKey) return true
    if (syncPasswordLocal != SecretsStore.syncPassword) return true
    if (gitLabTokenLocal != SecretsStore.gitLabToken) return true
    return false
  }

  override fun apply() {
    val panel = dialogPanel ?: return
    // Apply DSL-bound fields (state:: properties are updated automatically)
    panel.apply()
    // Apply SecretsStore-backed fields
    SecretsStore.mirrorApiKey = mirrorApiKeyLocal
    SecretsStore.syncPassword = syncPasswordLocal
    SecretsStore.gitLabToken = gitLabTokenLocal
    // Normalize defaults
    if (state.gitRemoteName.isBlank()) state.gitRemoteName = "origin"
    if (state.pullBackDefaultMode.isBlank()) state.pullBackDefaultMode = "new-branch"
    if (state.gitLabDefaultTargetBranch.isBlank()) state.gitLabDefaultTargetBranch = "main"
  }

  override fun reset() {
    val panel = dialogPanel ?: return
    // Reset DSL-bound fields
    panel.reset()
    // Reset SecretsStore-backed fields
    mirrorApiKeyLocal = SecretsStore.mirrorApiKey
    syncPasswordLocal = SecretsStore.syncPassword
    gitLabTokenLocal = SecretsStore.gitLabToken
  }

  override fun disposeUIResources() {
    dialogPanel = null
  }

  private fun onDiscoverClicked() {
    Thread({
      val servers = try {
        LanDiscovery.discover(timeoutMs = 6000)
      } catch (_: Exception) {
        emptyList()
      }

      SwingUtilities.invokeLater {
        when {
          servers.isEmpty() -> {
            Messages.showInfoMessage(
              LocalGitMirrorBundle.message("settings.discover.none"),
              LocalGitMirrorBundle.message("settings.discover.title")
            )
          }
          servers.size == 1 -> {
            val server = servers.first()
            state.baseUrl = server.toUrl()
            if (server.tls) state.mirrorInsecureTls = true
            dialogPanel?.reset()
          }
          else -> {
            val options = servers.map { "${it.toUrl()} (${it.ip})" }.toTypedArray()
            val chosen = Messages.showEditableChooseDialog(
              LocalGitMirrorBundle.message("settings.discover.multiple"),
              LocalGitMirrorBundle.message("settings.discover.title"),
              null,
              options,
              options.first(),
              null
            )
            if (chosen != null) {
              val idx = options.indexOf(chosen)
              if (idx >= 0) {
                state.baseUrl = servers[idx].toUrl()
                if (servers[idx].tls) state.mirrorInsecureTls = true
                dialogPanel?.reset()
              }
            }
          }
        }
      }
    }, "LAN-Discovery").start()
  }
}
