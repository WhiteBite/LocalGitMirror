package localgitmirror.idea.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.net.LanDiscovery
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.HandshakeCache
import java.awt.BorderLayout
import java.awt.Font
import java.io.File
import javax.swing.SwingUtilities

/** Build the Quick Setup form shown when Mirror is not configured. */
internal fun LocalGitMirrorPanel.buildSetupUi() {
  val form = panel {
    row {
      label("🔗 DocCache").bold()
    }

    row {
      label("URL сервера")
    }
    row {
      textField()
        .bindText(::setupUrl)
        .resizableColumn()
      button("🔍 Найти") { onDiscoverSetup() }
        .gap(RightGap.SMALL)
    }

    row {
      label("API Key")
    }
    row {
      passwordField()
        .bindText(::setupApiKey)
        .resizableColumn()
    }

    row {
      label("Sync Password")
    }
    row {
      passwordField()
        .bindText(::setupSyncPassword)
        .resizableColumn()
    }

    row {
      button("Подключиться") { onConnectSetup() }
        .applyToComponent {
          putClientProperty("JButton.buttonType", "default")
          font = font.deriveFont(Font.BOLD)
        }
    }
  }
  form.border = JBUI.Borders.empty(JBUI.scale(12), JBUI.scale(16))
  setupFormPanel = form
  add(form, BorderLayout.NORTH)
}

internal fun LocalGitMirrorPanel.onDiscoverSetup() {
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
            "Серверы не найдены в локальной сети.\nПроверьте, что сервер запущен и доступен.",
            "Поиск сервера"
          )
        }
        servers.size == 1 -> {
          setupUrl = servers.first().toUrl()
          setupFormPanel?.reset()
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
              setupUrl = servers[idx].toUrl()
              setupFormPanel?.reset()
            }
          }
        }
      }
    }
  }, "LAN-Discovery").apply { isDaemon = true }.start()
}

internal fun LocalGitMirrorPanel.onConnectSetup() {
  val url = setupUrl.trim().let {
    if (it.isBlank()) return
    if (it.startsWith("http://") || it.startsWith("https://")) it.trimEnd('/')
    else "https://${it.trimEnd('/')}"
  }
  if (setupSyncPassword.isBlank()) {
    notify("Введите пароль синхронизации.", NotificationType.WARNING)
    return
  }

  val s = service<MirrorSettingsService>().state
  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Проверка подключения", true) {
    override fun run(indicator: ProgressIndicator) {
      currentIndicator = indicator
      try {
        indicator.text = "Проверяем подключение к серверу…"
        val probe = HandshakeCache.passwordProbe(
          baseUrl = url,
          apiKey = setupApiKey,
          syncPassword = setupSyncPassword,
          insecureTls = s.mirrorInsecureTls
        )
        if (probe.code !in 200..299) {
          val msg = if (probe.code == 0)
            "Сервер недоступен: ${probe.message}"
          else
            "Ошибка подключения (HTTP ${probe.code}): ${probe.message.take(200)}"
          notify(msg, NotificationType.ERROR)
          return
        }

        // Save settings
        s.baseUrl = url
        SecretsStore.mirrorApiKey = setupApiKey
        SecretsStore.syncPassword = setupSyncPassword

        notify("Подключение к серверу установлено.", NotificationType.INFORMATION)

        // Switch to main UI
        UIUtil.invokeLaterIfNeeded {
          removeAll()
          buildMainUi()
          revalidate()
          repaint()
        }
      } finally {
        isSyncing = false
      }
    }

    override fun onFinished() {
      isSyncing = false
    }

    override fun onCancel() {
      isSyncing = false
    }
  })
}

internal fun LocalGitMirrorPanel.baseDir(): File? {
  val basePath = project.basePath ?: return null
  if (basePath.isBlank()) return null
  return File(basePath)
}

internal fun LocalGitMirrorPanel.ensureConfigured(settings: MirrorSettingsService.State): String? {
  val cfg = syncFacade.validateSettings(settings)
  return if (cfg.ok) null else cfg.message
}
