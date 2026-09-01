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

  // SecretsStore-backed fields — managed manually (not in PersistentStateComponent)
  private var mirrorApiKeyLocal = ""
  private var syncPasswordLocal = ""
  private var gitlabTokenLocal = ""

  override fun getDisplayName(): String = "LocalGitMirror"

  override fun createComponent(): JComponent {
    mirrorApiKeyLocal = SecretsStore.mirrorApiKey
    syncPasswordLocal = SecretsStore.syncPassword
    gitlabTokenLocal = SecretsStore.gitlabToken

    val panel = panel {
      // Minimal settings: URL + API Key + Password
      group("Mirror Server") {
        row("URL") {
          textField()
            .bindText(state::baseUrl)
            .resizableColumn()
            .comment("e.g. https://192.168.1.50")
          button("Найти") { onDiscoverClicked() }
            .gap(RightGap.SMALL)
          button("Проверить") { onTestClicked() }
        }

        row("API Key") {
          passwordField()
            .bindText(::mirrorApiKeyLocal)
            .comment("Из Plugin Connection Info на Mirror-сервере")
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
            .comment("Переопределение имени репозитория на Mirror (если папка называется иначе)")
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

        row(LocalGitMirrorBundle.message("settings.gitlab.project.label")) {
          textField()
            .bindText(state::gitlabProject)
            .resizableColumn()
            .comment(LocalGitMirrorBundle.message("settings.gitlab.project.comment"))
        }

        row(LocalGitMirrorBundle.message("settings.gitlab.token.label")) {
          passwordField()
            .bindText(::gitlabTokenLocal)
            .comment(LocalGitMirrorBundle.message("settings.gitlab.token.comment"))
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
              "Серверы Mirror не найдены в локальной сети",
              "Поиск Mirror"
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
              "Найдено несколько серверов Mirror. Выберите:",
              "Поиск Mirror",
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
    val urlToTest = resolveUrl(state.baseUrl)
    if (urlToTest.isBlank()) {
      Messages.showInfoMessage("Укажите URL сервера", "Проверка подключения")
      return
    }

    Thread({
      val pingResult = runCatching { MirrorApi.ping(urlToTest, mirrorApiKeyLocal, state.mirrorInsecureTls) }
        .getOrElse { MirrorApi.HttpResult(0, it.message ?: "error") }

      SwingUtilities.invokeLater {
        if (pingResult.code !in 200..299) {
          Messages.showErrorDialog(
            "Не удалось подключиться: HTTP ${pingResult.code}\n${pingResult.body.take(200)}",
            "Проверка подключения"
          )
        } else {
          Messages.showInfoMessage("Подключение успешно", "Проверка подключения")
        }
      }
    }, "Mirror-Test").apply { isDaemon = true }.start()
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
