package localgitmirror.idea.settings

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
import javax.swing.SwingUtilities

class MirrorSettingsConfigurable(private val project: Project) : Configurable {

  private val state: MirrorSettingsService.State get() = service<MirrorSettingsService>().state
  private val projectState: MirrorProjectSettingsService.State get() = project.service<MirrorProjectSettingsService>().state

  private var dialogPanel: DialogPanel? = null

  // Live component refs: test/discover buttons must read what is typed, not the
  // not-yet-applied persistent state.
  private var urlField: javax.swing.JTextField? = null
  private var apiKeyField: javax.swing.JPasswordField? = null

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
      group("Сервер") {
        row("URL") {
          textField()
            .bindText(state::baseUrl)
            .resizableColumn()
            .comment("e.g. https://192.168.1.50")
            .applyToComponent { urlField = this }
          button("Найти") { onDiscoverClicked() }
            .gap(RightGap.SMALL)
          button("Проверить") { onTestClicked() }
        }

        row("API Key") {
          passwordField()
            .bindText(::mirrorApiKeyLocal)
            .comment("из Plugin Connection Info на сервере")
            .applyToComponent { apiKeyField = this }
        }

        row("Пароль синхронизации") {
          passwordField()
            .bindText(::syncPasswordLocal)
            .comment("Пароль для шифрования данных при передаче")
        }
      }

      // Advanced settings (collapsed by default)
      collapsibleGroup("Дополнительно", false) {
        row("Repo Override") {
          textField()
            .bindText(projectState::repoOverride)
            .resizableColumn()
            .comment("Переопределение имени репозитория на сервере (если папка называется иначе)")
        }
      }

      // GitLab MR transfer: URL/project overrides + API token
      collapsibleGroup(LocalGitMirrorBundle.message("settings.gitlab.title"), false) {
        row(LocalGitMirrorBundle.message("settings.gitlab.url.label")) {
          textField()
            .bindText(state::gitlabUrl)
            .resizableColumn()
            .comment(LocalGitMirrorBundle.message("settings.gitlab.url.comment"))
        }

        row(LocalGitMirrorBundle.message("settings.gitlab.token.label")) {
          passwordField()
            .bindText(::gitlabTokenLocal)
            .comment(LocalGitMirrorBundle.message("settings.gitlab.token.comment"))
        }

        row {
          button(LocalGitMirrorBundle.message("settings.gitlab.test.label")) { onGitLabTestClicked() }
        }

        row {
          label(LocalGitMirrorBundle.message("settings.gitlab.autoHint"))
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

        row(LocalGitMirrorBundle.message("auto.pollSec")) {
          textField()
            .bindIntText(state::depsPollSec)
            .comment(LocalGitMirrorBundle.message("auto.pollSec.comment"))
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
              "Серверы не найдены в локальной сети",
              "Поиск сервера"
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
              "Найдено несколько серверов. Выберите:",
              "Поиск сервера",
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

  // ── Test Connection ──
  private fun onTestClicked() {
    val urlToTest = resolveUrl(urlField?.text ?: state.baseUrl)
    if (urlToTest.isBlank()) {
      Messages.showInfoMessage(dialogPanel, "Укажите URL сервера", "Проверка подключения")
      return
    }
    val apiKeyToTest = apiKeyField?.text?.takeIf { it.isNotBlank() } ?: mirrorApiKeyLocal

    Thread({
      val pingResult = runCatching { MirrorApi.ping(urlToTest, apiKeyToTest, state.mirrorInsecureTls) }
        .getOrElse { MirrorApi.HttpResult(0, it.message ?: "error") }

      SwingUtilities.invokeLater {
        if (pingResult.code !in 200..299) {
          Messages.showErrorDialog(
            dialogPanel,
            "Не удалось подключиться: HTTP ${pingResult.code}\n${pingResult.body.take(200)}",
            "Проверка подключения"
          )
        } else {
          Messages.showInfoMessage(dialogPanel, "Подключение успешно", "Проверка подключения")
        }
      }
    }, "Cache-Test").apply { isDaemon = true }.start()
  }

  private fun onGitLabTestClicked() {
    val resolved = localgitmirror.idea.gitlab.GitLabConfig.resolve(project)
    val conf = resolved.copy(token = gitlabTokenLocal.trim().ifBlank { resolved.token })
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
