package localgitmirror.idea.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.*
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.HttpResult
import localgitmirror.idea.mirror.MirrorAuthApi
import localgitmirror.idea.net.LanDiscovery
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.SwingUtilities

class MirrorSettingsConfigurable(private val project: Project) : Configurable {

  private val state: MirrorSettingsService.State get() = service<MirrorSettingsService>().state
  private val projectState: MirrorProjectSettingsService.State get() = project.service<MirrorProjectSettingsService>().state

  private var dialogPanel: DialogPanel? = null

  // Live component refs: test/discover buttons must read what is typed, not the
  // not-yet-applied persistent state.
  private var urlField: JTextField? = null
  private var apiKeyField: javax.swing.JPasswordField? = null
  private var syncPasswordField: javax.swing.JPasswordField? = null
  private var gitlabUrlField: JTextField? = null
  private var gitlabTokenField: javax.swing.JPasswordField? = null
  private var pinField: JTextField? = null
  private var pollSecField: JTextField? = null
  private var syncPwdEcho: Char = 0.toChar()

  // SecretsStore-backed fields — managed manually (not in PersistentStateComponent)
  private var mirrorApiKeyLocal = ""
  private var syncPasswordLocal = ""
  private var gitlabTokenLocal = ""

  override fun getDisplayName(): String = "DocCache"

  override fun createComponent(): JComponent {
    mirrorApiKeyLocal = SecretsStore.mirrorApiKey
    syncPasswordLocal = SecretsStore.syncPassword
    gitlabTokenLocal = SecretsStore.gitlabToken

    val panel = panel {
      // Minimal settings: URL + API Key + Password
      group(LocalGitMirrorBundle.message("settings.mirror.title")) {
        row(LocalGitMirrorBundle.message("settings.mirror.baseUrl")) {
          textField()
            .bindText(state::baseUrl)
            .resizableColumn()
            .comment(LocalGitMirrorBundle.message("settings.mirror.baseUrl.comment"))
            .applyToComponent { urlField = this }
          button(LocalGitMirrorBundle.message("settings.discover")) { onDiscoverClicked() }
            .gap(RightGap.SMALL)
          button(LocalGitMirrorBundle.message("settings.test")) { onTestClicked() }
        }

        row(LocalGitMirrorBundle.message("settings.mirror.apiKey")) {
          passwordField()
            .bindText(::mirrorApiKeyLocal)
            .comment(LocalGitMirrorBundle.message("settings.mirror.apiKey.hint"))
            .applyToComponent { apiKeyField = this }
        }

        row(LocalGitMirrorBundle.message("settings.mirror.syncPassword")) {
          passwordField()
            .bindText(::syncPasswordLocal)
            .comment(LocalGitMirrorBundle.message("settings.mirror.syncPassword.comment"))
            .applyToComponent { syncPasswordField = this }
          if (localgitmirror.idea.deps.RoleDetector.detect(state) == localgitmirror.idea.deps.MachineRole.HOME) {
            val eye = javax.swing.JButton(com.intellij.icons.AllIcons.Actions.Show).apply {
              toolTipText = LocalGitMirrorBundle.message("settings.mirror.syncPassword.show")
              isFocusPainted = false
              isBorderPainted = false
              isContentAreaFilled = false
            }
            eye.addActionListener {
              val f = syncPasswordField ?: return@addActionListener
              if (f.echoChar.code == 0) {
                f.echoChar = syncPwdEcho
                eye.toolTipText = LocalGitMirrorBundle.message("settings.mirror.syncPassword.show")
              } else {
                syncPwdEcho = f.echoChar
                f.echoChar = 0.toChar()
                eye.toolTipText = LocalGitMirrorBundle.message("settings.mirror.syncPassword.hide")
              }
            }
            cell(eye)
          }
        }

        row {
          checkBox(LocalGitMirrorBundle.message("settings.tlsPin"))
            .bindSelected(state::tlsPinEnabled)
        }

        row(LocalGitMirrorBundle.message("settings.tlsPin.fingerprint")) {
          textField()
            .bindText(state::serverCertSha256)
            .resizableColumn()
            .comment(LocalGitMirrorBundle.message("settings.tlsPin.fingerprint.comment"))
            .applyToComponent {
              isEditable = false
              pinField = this
            }
          button(LocalGitMirrorBundle.message("settings.tlsPin.clear")) {
            state.serverCertSha256 = ""
            pinField?.text = ""
          }
        }

        row {
          checkBox(LocalGitMirrorBundle.message("settings.insecureTls"))
            .bindSelected(state::mirrorInsecureTls)
            .comment(LocalGitMirrorBundle.message("settings.insecureTls.comment"))
        }

        row {
          checkBox(LocalGitMirrorBundle.message("settings.gitlabInsecureTls"))
            .bindSelected(state::gitlabInsecureTls)
            .comment(LocalGitMirrorBundle.message("settings.gitlabInsecureTls.comment"))
        }
      }

      // Advanced settings (collapsed by default)
      collapsibleGroup(LocalGitMirrorBundle.message("settings.advanced.title"), false) {
        row(LocalGitMirrorBundle.message("settings.repoOverride.label")) {
          textField()
            .bindText(projectState::repoOverride)
            .resizableColumn()
            .comment(LocalGitMirrorBundle.message("settings.repoOverride.comment"))
        }
      }

      // GitLab MR transfer: URL/project overrides + API token
      collapsibleGroup(LocalGitMirrorBundle.message("settings.gitlab.title"), false) {
        row(LocalGitMirrorBundle.message("settings.gitlab.url.label")) {
          textField()
            .bindText(state::gitlabUrl)
            .resizableColumn()
            .comment(LocalGitMirrorBundle.message("settings.gitlab.url.comment"))
            .applyToComponent { gitlabUrlField = this }
        }

        row(LocalGitMirrorBundle.message("settings.gitlab.token.label")) {
          passwordField()
            .bindText(::gitlabTokenLocal)
            .comment(LocalGitMirrorBundle.message("settings.gitlab.token.comment"))
            .applyToComponent { gitlabTokenField = this }
        }

        row {
          button(LocalGitMirrorBundle.message("settings.gitlab.test.label")) { onGitLabTestClicked() }
        }

        row {
          comment(LocalGitMirrorBundle.message("settings.gitlab.autoHint.1"))
        }
        row {
          comment(LocalGitMirrorBundle.message("settings.gitlab.autoHint.2"))
        }
        row {
          comment(LocalGitMirrorBundle.message("settings.gitlab.autoHint.3"))
        }
      }

      // Auto dependency synchronization settings
      collapsibleGroup(LocalGitMirrorBundle.message("auto.section.title"), false) {
        row(LocalGitMirrorBundle.message("auto.role.label")) {
          comboBox(listOf(
            LocalGitMirrorBundle.message("auto.role.auto"),
            LocalGitMirrorBundle.message("auto.role.home"),
            LocalGitMirrorBundle.message("auto.role.work")
          )).bindItem(
            { state.machineRole.let { role ->
              when (role.trim().lowercase()) {
                "home" -> LocalGitMirrorBundle.message("auto.role.home")
                "work" -> LocalGitMirrorBundle.message("auto.role.work")
                else -> LocalGitMirrorBundle.message("auto.role.auto")
              }
            } },
            { selected -> state.machineRole = when (selected) {
              LocalGitMirrorBundle.message("auto.role.home") -> "home"
              LocalGitMirrorBundle.message("auto.role.work") -> "work"
              else -> "auto"
            } }
          )
        }

        row(LocalGitMirrorBundle.message("auto.depsAutomation")) {
          comboBox(listOf("auto", "on", "off"))
            .bindItem(
              { state.depsAutomationMode },
              { selected -> state.depsAutomationMode = selected ?: "auto" }
            )
            .comment(LocalGitMirrorBundle.message("auto.depsAutomation.comment"))
        }

        row {
          checkBox(LocalGitMirrorBundle.message("auto.requestDeps"))
            .bindSelected(state::autoRequestDeps)
        }
        row {
          checkBox(LocalGitMirrorBundle.message("auto.respondDeps"))
            .bindSelected(state::autoRespondDeps)
        }
        row {
          checkBox(LocalGitMirrorBundle.message("auto.applyDeps"))
            .bindSelected(state::autoApplyDeps)
        }

        row {
          checkBox(LocalGitMirrorBundle.message("auto.mrReview"))
            .bindSelected(state::autoMrReview)
        }

        row {
          checkBox(LocalGitMirrorBundle.message("auto.mrBranches"))
            .bindSelected(state::autoMrBranches)
        }

        row {
          checkBox(LocalGitMirrorBundle.message("auto.autoPushReplies"))
            .bindSelected(state::autoPushReplies)
        }

        row(LocalGitMirrorBundle.message("auto.pollSec")) {
          textField()
            .bindIntText(state::depsPollSec)
            .comment(LocalGitMirrorBundle.message("auto.pollSec.comment"))
            .applyToComponent { pollSecField = this }
        }
      }

      // Dependency transfer: Nexus fallback + diagnostics
      collapsibleGroup(LocalGitMirrorBundle.message("settings.deps.title"), false) {
        row(LocalGitMirrorBundle.message("settings.deps.nexusUrl")) {
          textField()
            .bindText(state::nexusBaseUrl)
            .resizableColumn()
            .comment(LocalGitMirrorBundle.message("settings.deps.nexusUrl.comment"))
        }

        row {
          checkBox(LocalGitMirrorBundle.message("settings.deps.diagnostics"))
            .bindSelected(state::depsDiagnosticsEnabled)
        }

        row {
          checkBox(LocalGitMirrorBundle.message("settings.deps.diagnostics.verbose"))
            .bindSelected(state::depsDiagnosticsVerbose)
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
    if (gitlabTokenLocal != SecretsStore.gitlabToken) return true
    return false
  }

  override fun apply() {
    val panel = dialogPanel ?: return
    val errors = validateMirrorSettings(
      urlField?.text ?: state.baseUrl,
      pollSecField?.text ?: state.depsPollSec.toString()
    )
    if (errors.isNotEmpty()) {
      val text = errors.joinToString("\n") {
        LocalGitMirrorBundle.message(it.messageKey, *it.args.toTypedArray())
      }
      throw ConfigurationException(text)
    }
    val previousUrl = state.baseUrl
    panel.apply()
    SecretsStore.mirrorApiKey = mirrorApiKeyLocal
    SecretsStore.syncPassword = syncPasswordLocal
    SecretsStore.gitlabToken = gitlabTokenLocal

    // Normalize URL: add https:// if no scheme, strip trailing slash
    val url = state.baseUrl.trim()
    state.baseUrl = when {
      url.isBlank() -> url
      url.startsWith("http://") || url.startsWith("https://") -> url.trimEnd('/')
      else -> "https://${url.trimEnd('/')}"
    }
    if (state.baseUrl != previousUrl) {
      // pin belongs to the old server; a stale pin guarantees mismatch
      state.serverCertSha256 = ""
    }
  }

  override fun reset() {
    val panel = dialogPanel ?: return
    panel.reset()
    mirrorApiKeyLocal = SecretsStore.mirrorApiKey
    syncPasswordLocal = SecretsStore.syncPassword
    gitlabTokenLocal = SecretsStore.gitlabToken
  }

  override fun disposeUIResources() {
    dialogPanel = null
  }

  // ── LAN Discovery ──
  private fun onDiscoverClicked() {
    Thread({
      val servers = try {
        LanDiscovery.discover(timeoutMs = 6000, authPassword = SecretsStore.syncPassword)
      } catch (_: Exception) {
        emptyList()
      }

      SwingUtilities.invokeLater {
        when {
          servers.isEmpty() -> {
            Messages.showInfoMessage(
              dialogPanel,
              LocalGitMirrorBundle.message("settings.discover.none"),
              LocalGitMirrorBundle.message("settings.discover.title")
            )
          }
          servers.size == 1 -> {
            state.baseUrl = servers.first().toUrl()
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
                dialogPanel?.reset()
              }
            }
          }
        }
      }
    }, "LAN-Discovery").apply { isDaemon = true }.start()
  }

  // ── Test Connection ──
  private fun onTestClicked() {
    val urlToTest = resolveUrl(urlField?.text ?: state.baseUrl)
    if (urlToTest.isBlank()) {
      Messages.showInfoMessage(
        dialogPanel,
        LocalGitMirrorBundle.message("settings.test.urlMissing"),
        LocalGitMirrorBundle.message("settings.test.title")
      )
      return
    }
    val apiKeyToTest = apiKeyField?.text?.takeIf { it.isNotBlank() } ?: mirrorApiKeyLocal

    Thread({
      val pingResult = runCatching { MirrorAuthApi.ping(urlToTest, apiKeyToTest, state.mirrorInsecureTls) }
        .getOrElse { HttpResult(0, it.message ?: "error") }

      SwingUtilities.invokeLater {
        if (pingResult.code !in 200..299) {
          Messages.showErrorDialog(
            dialogPanel,
            LocalGitMirrorBundle.message("settings.test.fail", pingResult.code, testFailReason(pingResult.code)),
            LocalGitMirrorBundle.message("settings.test.title")
          )
        } else {
          Messages.showInfoMessage(
            dialogPanel,
            LocalGitMirrorBundle.message("settings.test.ok"),
            LocalGitMirrorBundle.message("settings.test.title")
          )
        }
      }
    }, "Cache-Test").apply { isDaemon = true }.start()
  }

  private fun testFailReason(code: Int): String = when (code) {
    0 -> LocalGitMirrorBundle.message("settings.test.reason.network")
    401, 403 -> LocalGitMirrorBundle.message("settings.test.reason.auth")
    404 -> LocalGitMirrorBundle.message("settings.test.reason.notFound")
    in 500..599 -> LocalGitMirrorBundle.message("settings.test.reason.server")
    else -> LocalGitMirrorBundle.message("settings.test.reason.other")
  }

  private fun onGitLabTestClicked() {
    val resolved = localgitmirror.idea.gitlab.GitLabConfig.resolve(project)
    val typedUrl = resolveUrl(gitlabUrlField?.text ?: state.gitlabUrl)
    val typedToken = gitlabTokenField?.text?.trim().orEmpty()
    val conf = resolved.copy(
      url = typedUrl.ifBlank { resolved.url },
      token = typedToken.ifBlank { resolved.token }
    )
    Thread({
      val result = runCatching { localgitmirror.idea.gitlab.GitLabApi.verify(conf) }
        .getOrElse { localgitmirror.idea.gitlab.GitLabApi.VerifyResult(0, null, it.message ?: "error") }
      SwingUtilities.invokeLater {
        val title = LocalGitMirrorBundle.message("settings.gitlab.test.title")
        val text = when {
          result.message == "not-detected" -> LocalGitMirrorBundle.message("settings.gitlab.test.noConf")
          result.message == "no-token" -> LocalGitMirrorBundle.message("settings.gitlab.test.noToken")
          result.code in 200..299 -> LocalGitMirrorBundle.message(
            "settings.gitlab.test.ok", conf.url, conf.project, result.projectName ?: ""
          )
          result.code == 401 -> LocalGitMirrorBundle.message("settings.gitlab.test.badToken", conf.url)
          result.code == 404 -> LocalGitMirrorBundle.message("settings.gitlab.test.badProject", conf.url, conf.project)
          else -> LocalGitMirrorBundle.message("settings.gitlab.test.fail", result.code, result.message)
        }
        if (result.code in 200..299) Messages.showInfoMessage(dialogPanel, text, title)
        else Messages.showErrorDialog(dialogPanel, text, title)
      }
    }, "GitLab-Test").apply { isDaemon = true }.start()
  }

  private fun resolveUrl(raw: String): String {
    val u = raw.trim()
    return when {
      u.isBlank() -> ""
      u.startsWith("http://") || u.startsWith("https://") -> u.trimEnd('/')
      else -> "https://${u.trimEnd('/')}"
    }
  }
}
