package localgitmirror.idea.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.*
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.net.LanDiscovery
import javax.swing.JComponent
import javax.swing.SwingUtilities

class MirrorSettingsConfigurable : Configurable {

  private val state: MirrorSettingsService.State get() = service<MirrorSettingsService>().state
  private var dialogPanel: DialogPanel? = null

  // SecretsStore-backed fields — managed manually (not in PersistentStateComponent)
  private var mirrorApiKeyLocal = ""
  private var syncPasswordLocal = ""

  override fun getDisplayName(): String = "LocalGitMirror"

  override fun createComponent(): JComponent {
    mirrorApiKeyLocal = SecretsStore.mirrorApiKey
    syncPasswordLocal = SecretsStore.syncPassword

    val panel = panel {
      // ── Group 1: Mirror Server (always visible — the essentials) ──
      group(LocalGitMirrorBundle.message("settings.mirror.title", "Mirror Server")) {

        // URL + Discover + Test on one row
        row(LocalGitMirrorBundle.message("settings.mirror.baseUrl")) {
          textField()
            .bindText(state::baseUrl)
            .resizableColumn()
            .comment("e.g. https://192.168.1.50")
          button(LocalGitMirrorBundle.message("settings.discover")) { onDiscoverClicked() }
            .gap(RightGap.SMALL)
          button(LocalGitMirrorBundle.message("settings.test")) { onTestClicked() }
        }

        row(LocalGitMirrorBundle.message("settings.mirror.syncPassword")) {
          passwordField()
            .bindText(::syncPasswordLocal)
            .comment(LocalGitMirrorBundle.message("settings.mirror.syncPassword.comment"))
        }

        row(LocalGitMirrorBundle.message("settings.mirror.repo")) {
          textField()
            .bindText(state::repo)
            .comment(LocalGitMirrorBundle.message("settings.mirror.repo.comment"))
        }

        row {
          checkBox(LocalGitMirrorBundle.message("settings.insecureTls"))
            .bindSelected(state::mirrorInsecureTls)
            .comment(LocalGitMirrorBundle.message("settings.insecureTls.comment"))
        }
      }

      // ── Language (visible) ──
      row(LocalGitMirrorBundle.message("settings.ui.language")) {
        comboBox(listOf("auto", "en", "ru"))
          .bindItem(
            { state.uiLanguage },
            { state.uiLanguage = it ?: "auto" }
          )
      }

      // ── Advanced (collapsed by default) ──
      collapsibleGroup(LocalGitMirrorBundle.message("settings.advanced.title"), false) {

        row(LocalGitMirrorBundle.message("settings.mirror.apiKey")) {
          passwordField()
            .bindText(::mirrorApiKeyLocal)
            .comment(LocalGitMirrorBundle.message("settings.mirror.apiKey.comment"))
        }

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
          checkBox(LocalGitMirrorBundle.message("settings.sync.offlineMode"))
            .bindSelected(state::offlineGenerateOnly)
            .comment(LocalGitMirrorBundle.message("settings.sync.offlineMode.comment"))
        }

        row(LocalGitMirrorBundle.message("settings.deps.internalRepos")) {
          textField()
            .bindText(state::internalRepos)
            .comment(LocalGitMirrorBundle.message("settings.deps.internalRepos.comment"))
        }
      }
    }

    dialogPanel = panel
    return panel
  }

  override fun isModified(): Boolean {
    val panel = dialogPanel ?: return false
    if (panel.isModified()) return true
    if (mirrorApiKeyLocal != SecretsStore.mirrorApiKey) return true
    if (syncPasswordLocal != SecretsStore.syncPassword) return true
    return false
  }

  override fun apply() {
    val panel = dialogPanel ?: return
    panel.apply()
    SecretsStore.mirrorApiKey = mirrorApiKeyLocal
    SecretsStore.syncPassword = syncPasswordLocal

    // Normalize URL: add https:// if no scheme, strip trailing slash
    val url = state.baseUrl.trim()
    state.baseUrl = when {
      url.isBlank() -> url
      url.startsWith("http://") || url.startsWith("https://") -> url.trimEnd('/')
      else -> "https://${url.trimEnd('/')}"
    }

    // Normalize defaults
    if (state.gitRemoteName.isBlank()) state.gitRemoteName = "origin"
    if (state.pullBackDefaultMode.isBlank()) state.pullBackDefaultMode = "new-branch"
  }

  override fun reset() {
    val panel = dialogPanel ?: return
    panel.reset()
    mirrorApiKeyLocal = SecretsStore.mirrorApiKey
    syncPasswordLocal = SecretsStore.syncPassword
  }

  override fun disposeUIResources() {
    dialogPanel = null
  }

  // ── LAN Discovery ──
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
              null, options, options.first(), null
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

  // ── Test Connection ──
  private fun onTestClicked() {
    val urlToTest = dialogPanel?.let {
      // Read the current field value before apply() is called
      state.baseUrl.trim().let { u ->
        when {
          u.isBlank() -> ""
          u.startsWith("http://") || u.startsWith("https://") -> u.trimEnd('/')
          else -> "https://${u.trimEnd('/')}"
        }
      }
    } ?: state.baseUrl.trim()

    if (urlToTest.isBlank()) {
      Messages.showInfoMessage(
        LocalGitMirrorBundle.message("settings.test.urlMissing"),
        LocalGitMirrorBundle.message("settings.test.title")
      )
      return
    }

    Thread({
      val apiKey = mirrorApiKeyLocal
      val insecureTls = state.mirrorInsecureTls
      val result = try {
        MirrorApi.ping(urlToTest, apiKey, insecureTls)
      } catch (t: Throwable) {
        MirrorApi.HttpResult(0, t.message ?: "error")
      }

      SwingUtilities.invokeLater {
        if (result.code in 200..299) {
          Messages.showInfoMessage(
            LocalGitMirrorBundle.message("settings.test.ok"),
            LocalGitMirrorBundle.message("settings.test.title")
          )
        } else {
          Messages.showErrorDialog(
            LocalGitMirrorBundle.message("settings.test.fail", result.code, result.body.take(200)),
            LocalGitMirrorBundle.message("settings.test.title")
          )
        }
      }
    }, "Mirror-Test").start()
  }
}
