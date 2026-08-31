package localgitmirror.idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.ui.VaultDiagnosticsPanel
import javax.swing.JComponent

class ShowVaultDiagnosticsAction : AnAction() {
    override fun update(e: AnActionEvent) {
        LocalGitMirrorBundle.localizePresentation(e, "LocalGitMirror.VaultDiagnostics")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val settings = service<MirrorSettingsService>().state
        if (settings.baseUrl.isBlank()) {
            notify(project, "Mirror not configured. Set up Mirror URL in settings.", NotificationType.WARNING)
            return
        }

        val panel = VaultDiagnosticsPanel()

        val dialog = object : DialogWrapper(project, true) {
            init {
                title = "Vault Diagnostics"
                init()
            }

            override fun createCenterPanel(): JComponent = panel
        }

        panel.onRefresh = {
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = MirrorApi.mirrorStatus(
                    baseUrl = settings.baseUrl,
                    apiKey = SecretsStore.mirrorApiKey,
                    insecureTls = settings.mirrorInsecureTls
                )
                ApplicationManager.getApplication().invokeLater {
                    if (result.code in 200..299) {
                        parseAndUpdate(panel, result.body)
                    } else {
                        panel.updateStatusError("HTTP ${result.code}: ${result.body.take(200)}")
                    }
                }
            }
        }

        // Trigger initial fetch
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = MirrorApi.mirrorStatus(
                baseUrl = settings.baseUrl,
                apiKey = SecretsStore.mirrorApiKey,
                insecureTls = settings.mirrorInsecureTls
            )
            ApplicationManager.getApplication().invokeLater {
                if (result.code in 200..299) {
                    parseAndUpdate(panel, result.body)
                } else {
                    panel.updateStatusError("HTTP ${result.code}: ${result.body.take(200)}")
                }
            }
        }

        dialog.show()
    }

    private fun parseAndUpdate(panel: VaultDiagnosticsPanel, body: String) {
        try {
            val root = Json.parseToJsonElement(body).jsonObject
            val vaultPath = root["vault"]?.jsonPrimitive?.contentOrNull ?: "?"
            val stats = root["stats"]?.jsonObject
            val casSize = stats?.get("bytes")?.jsonPrimitive?.longOrNull ?: 0L
            val artifactCount = stats?.get("artifacts")?.jsonPrimitive?.intOrNull ?: 0
            val conflicts = root["conflicts"]?.jsonArray
            val conflictsCount = conflicts?.size ?: 0
            val wanted = root["wanted"]?.jsonArray
            val pendingWantedCount = wanted?.count {
                it.jsonObject["state"]?.jsonPrimitive?.contentOrNull == "PENDING"
            } ?: 0
            val lastBackup = root["last_backup"]?.jsonPrimitive?.contentOrNull
            panel.updateStatus(vaultPath, casSize, artifactCount, pendingWantedCount, conflictsCount, lastBackup)
        } catch (ex: Exception) {
            panel.updateStatusError("Parse error: ${ex.message}")
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("LocalGitMirror").createNotification(message, type).notify(project)
    }
}