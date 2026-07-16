package localgitmirror.idea.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.*
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.net.LanDiscovery
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities

class MirrorSettingsConfigurable(private val project: Project) : Configurable {

  private val state: MirrorSettingsService.State get() = service<MirrorSettingsService>().state

  private val projectState: MirrorProjectSettingsService.State
    get() = project.service<MirrorProjectSettingsService>().state

  private var dialogPanel: DialogPanel? = null

  // SecretsStore-backed fields — managed manually (not in PersistentStateComponent)
  private var mirrorApiKeyLocal = ""
  private var syncPasswordLocal = ""

  // Label updated live when key is fetched / cleared
  private var fpLabel: JLabel? = null

  override fun getDisplayName(): String = "LocalGitMirror"

  override fun createComponent(): JComponent {
    mirrorApiKeyLocal = SecretsStore.mirrorApiKey
    syncPasswordLocal = SecretsStore.syncPassword

    val panel = panel {
      // ── Mirror Server (always visible — 3 essentials) ──
      group(LocalGitMirrorBundle.message("settings.mirror.title", "Mirror Server")) {

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
            .bindText(projectState::repoOverride)
            .comment(LocalGitMirrorBundle.message("settings.mirror.repo.comment"))
        }

        row {
          checkBox("Проверять изменения на Mirror при открытии проекта")
            .bindSelected(state::autoCheckPullOnStartup)
            .comment("Показывает уведомление, если на Mirror есть изменения для текущей ветки.")
        }
      }

      // ── Advanced (collapsed — rarely needed) ──
      collapsibleGroup(LocalGitMirrorBundle.message("settings.advanced.title"), false) {

        row(LocalGitMirrorBundle.message("settings.mirror.apiKey")) {
          passwordField()
            .bindText(::mirrorApiKeyLocal)
            .comment(LocalGitMirrorBundle.message("settings.mirror.apiKey.comment"))
        }

        row {
          checkBox(LocalGitMirrorBundle.message("settings.insecureTls"))
            .bindSelected(state::mirrorInsecureTls)
            .comment(LocalGitMirrorBundle.message("settings.insecureTls.comment"))
        }

        row {
          checkBox(LocalGitMirrorBundle.message("settings.sync.offlineMode"))
            .bindSelected(state::offlineGenerateOnly)
            .comment(LocalGitMirrorBundle.message("settings.sync.offlineMode.comment"))
        }

        row {
          checkBox("Deps diagnostics (debug)")
            .bindSelected(state::depsDiagnosticsEnabled)
            .comment("Write a log under the IDE log folder — never into the project. Names hidden unless verbose is also on.")
        }
        row {
          checkBox("Deps diagnostics verbose (include package names)")
            .bindSelected(state::depsDiagnosticsVerbose)
            .comment("Only effective when diagnostics is enabled.")
        }

        row("npm corporate scopes") {
          textField()
            .bindText(state::npmCorporateScopes)
            .comment(
              "Optional override, comma-separated (e.g. @krypto-ui,krypto-). " +
                "By default npm deps are classified by probing the public registry — " +
                "a package missing there is corporate. Use this only to force-include " +
                "scopes, or when this machine has no public npm access."
            )
        }

        // ── v3 key pinning ───────────────────────────────────────────────────
        group(LocalGitMirrorBundle.message("settings.v3.title")) {
          row(LocalGitMirrorBundle.message("settings.v3.fingerprint")) {
            val currentFp = state.serverPubKeyFp
              .ifBlank { LocalGitMirrorBundle.message("settings.v3.fingerprint.none") }
            val lbl = JLabel(currentFp)
            fpLabel = lbl
            cell(lbl)
              .comment(LocalGitMirrorBundle.message("settings.v3.fingerprint.comment"))
            button(LocalGitMirrorBundle.message("settings.v3.fetchPin")) { onFetchKeyClicked() }
              .gap(RightGap.SMALL)
            button(LocalGitMirrorBundle.message("settings.v3.clearPin")) { onClearKeyClicked() }
          }
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
    }, "LAN-Discovery").apply { isDaemon = true }.start()
  }

  // ── Test Connection — проверяет коннект и детектирует смену ключа ──
  private fun onTestClicked() {
    val urlToTest = resolveUrl(state.baseUrl)
    if (urlToTest.isBlank()) {
      Messages.showInfoMessage(
        LocalGitMirrorBundle.message("settings.test.urlMissing"),
        LocalGitMirrorBundle.message("settings.test.title")
      )
      return
    }

    Thread({
      val apiKey     = mirrorApiKeyLocal
      val insecure   = state.mirrorInsecureTls
      val pingResult = runCatching { MirrorApi.ping(urlToTest, apiKey, insecure) }
        .getOrElse { MirrorApi.HttpResult(0, it.message ?: "error") }

      // Fetch server key for rotation detection (non-blocking, best-effort)
      val keyResult = runCatching { MirrorApi.fetchServerPubKey(urlToTest, apiKey, insecure) }
        .getOrNull()

      SwingUtilities.invokeLater {
        if (pingResult.code !in 200..299) {
          Messages.showErrorDialog(
            LocalGitMirrorBundle.message("settings.test.fail", pingResult.code, pingResult.body.take(200)),
            LocalGitMirrorBundle.message("settings.test.title")
          )
          return@invokeLater
        }

        // ── Детект смены ключа ─────────────────────────────────────────────
        val pinnedFp  = state.serverPubKeyFp
        val serverFp  = keyResult?.fp
        if (!pinnedFp.isNullOrBlank() && !serverFp.isNullOrBlank() && pinnedFp != serverFp) {
          val choice = Messages.showDialog(
            LocalGitMirrorBundle.message("settings.v3.keyChanged.message", pinnedFp, serverFp),
            LocalGitMirrorBundle.message("settings.v3.keyChanged.title"),
            arrayOf(
              LocalGitMirrorBundle.message("settings.v3.keyChanged.repin"),
              LocalGitMirrorBundle.message("settings.v3.keyChanged.keep")
            ),
            1, // default: Keep
            Messages.getWarningIcon()
          )
          if (choice == 0 && keyResult.pubB64 != null) {
            // Пользователь выбрал Re-pin
            state.serverPubKeyB64 = keyResult.pubB64
            state.serverPubKeyFp  = serverFp
            fpLabel?.text = serverFp
          }
          return@invokeLater
        }

        Messages.showInfoMessage(
          LocalGitMirrorBundle.message("settings.test.ok"),
          LocalGitMirrorBundle.message("settings.test.title")
        )
      }
    }, "Mirror-Test").apply { isDaemon = true }.start()
  }

  // ── Fetch & Pin server key ──────────────────────────────────────────────────
  private fun onFetchKeyClicked() {
    val url = resolveUrl(state.baseUrl)
    if (url.isBlank()) {
      Messages.showInfoMessage(
        LocalGitMirrorBundle.message("settings.v3.fetch.noUrl"),
        LocalGitMirrorBundle.message("settings.v3.title")
      )
      return
    }

    Thread({
      val res = runCatching {
        MirrorApi.fetchServerPubKey(url, mirrorApiKeyLocal, state.mirrorInsecureTls)
      }.getOrElse { MirrorApi.PubKeyResult(0, null, null, it.message ?: "error") }

      SwingUtilities.invokeLater {
        if (res.pubB64 == null || res.fp == null) {
          Messages.showErrorDialog(
            LocalGitMirrorBundle.message("settings.v3.fetch.fail", res.code, res.message.take(200)),
            LocalGitMirrorBundle.message("settings.v3.title")
          )
          return@invokeLater
        }
        state.serverPubKeyB64 = res.pubB64
        state.serverPubKeyFp  = res.fp
        fpLabel?.text = res.fp
        Messages.showInfoMessage(
          LocalGitMirrorBundle.message("settings.v3.fetch.ok", res.fp),
          LocalGitMirrorBundle.message("settings.v3.title")
        )
      }
    }, "Mirror-FetchKey").apply { isDaemon = true }.start()
  }

  // ── Clear Pin ───────────────────────────────────────────────────────────────
  private fun onClearKeyClicked() {
    state.serverPubKeyB64 = ""
    state.serverPubKeyFp  = ""
    fpLabel?.text = LocalGitMirrorBundle.message("settings.v3.fingerprint.none")
    Messages.showInfoMessage(
      LocalGitMirrorBundle.message("settings.v3.clear.ok"),
      LocalGitMirrorBundle.message("settings.v3.title")
    )
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────
  private fun resolveUrl(raw: String): String {
    val u = raw.trim()
    return when {
      u.isBlank() -> ""
      u.startsWith("http://") || u.startsWith("https://") -> u.trimEnd('/')
      else -> "https://${u.trimEnd('/')}"
    }
  }
}
