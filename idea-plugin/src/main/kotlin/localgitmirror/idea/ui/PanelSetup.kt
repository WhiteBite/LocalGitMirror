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
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorCrypto
import localgitmirror.idea.net.LanDiscovery
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.HandshakeCache
import localgitmirror.idea.workkit.BundleCrypto
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
      label(LocalGitMirrorBundle.message("setup.label.url"))
    }
    row {
      textField()
        .bindText(::setupUrl)
        .resizableColumn()
      button(LocalGitMirrorBundle.message("setup.label.discover")) { onDiscoverSetup() }
        .gap(RightGap.SMALL)
    }

    row {
      label(LocalGitMirrorBundle.message("setup.label.apiKey"))
    }
    row {
      passwordField()
        .bindText(::setupApiKey)
        .resizableColumn()
    }

    row {
      label(LocalGitMirrorBundle.message("setup.label.syncPassword"))
    }
    row {
      passwordField()
        .bindText(::setupSyncPassword)
        .resizableColumn()
    }

    row {
      button(LocalGitMirrorBundle.message("setup.label.connect")) { onConnectSetup() }
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
            LocalGitMirrorBundle.message("settings.discover.none"),
            LocalGitMirrorBundle.message("settings.discover.title")
          )
        }
        servers.size == 1 -> {
          setupUrl = servers.first().toUrl()
          setupFormPanel?.reset()
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
  val v3Pinned = MirrorCrypto.isV3Pinned()
  if (setupSyncPassword.isBlank() && !v3Pinned) {
    notify(LocalGitMirrorBundle.message("setup.notify.passwordMissing"), NotificationType.WARNING)
    return
  }

  val s = service<MirrorSettingsService>().state
  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, LocalGitMirrorBundle.message("setup.task.check"), true) {
    override fun run(indicator: ProgressIndicator) {
      currentIndicator = indicator
      try {
        indicator.text = LocalGitMirrorBundle.message("setup.progress.checking")
        if (setupSyncPassword.isBlank()) {
          // v3 pinned: no shared password to verify — reachability only
          val caps = HandshakeCache.capabilities(url, setupApiKey, "", s.mirrorInsecureTls)
          if (caps.code !in 200..299) {
            notify(connectErrorMessage(caps.code, caps.body), NotificationType.ERROR)
            return
          }
        } else {
          val probe = HandshakeCache.passwordProbe(
            baseUrl = url,
            apiKey = setupApiKey,
            syncPassword = setupSyncPassword,
            insecureTls = s.mirrorInsecureTls
          )
          if (probe.code !in 200..299 || probe.bytes == null) {
            notify(connectErrorMessage(probe.code, probe.message), NotificationType.ERROR)
            return
          }
          val plain = try {
            String(BundleCrypto.decryptDumpBytes(probe.bytes, setupSyncPassword)).trim()
          } catch (_: Throwable) {
            null
          }
          if (plain != "LGM-PROBE" && plain != "SYNC-PROBE") {
            notify(LocalGitMirrorBundle.message("setup.notify.passwordMismatch"), NotificationType.ERROR)
            return
          }
        }

        s.baseUrl = url
        SecretsStore.mirrorApiKey = setupApiKey
        SecretsStore.syncPassword = setupSyncPassword

        notify(LocalGitMirrorBundle.message("setup.notify.ok"), NotificationType.INFORMATION)

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

private fun connectErrorMessage(code: Int, message: String): String =
  if (code == 0)
    LocalGitMirrorBundle.message("setup.notify.unreachable", message)
  else
    LocalGitMirrorBundle.message("setup.notify.httpError", code, message.take(200))

internal fun LocalGitMirrorPanel.baseDir(): File? {
  val basePath = project.basePath ?: return null
  if (basePath.isBlank()) return null
  return File(basePath)
}

internal fun LocalGitMirrorPanel.ensureConfigured(settings: MirrorSettingsService.State): String? {
  val cfg = syncFacade.validateSettings(settings)
  return if (cfg.ok) null else cfg.message
}
