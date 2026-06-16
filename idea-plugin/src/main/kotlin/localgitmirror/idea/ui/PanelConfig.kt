package localgitmirror.idea.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.ConfigLineCodec
import localgitmirror.idea.settings.ConfigSnapshot
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

// ── Config copy/paste extension functions for LocalGitMirrorPanel ──

internal fun LocalGitMirrorPanel.copyConfigLine() {
  val s = service<MirrorSettingsService>().state
  val line = ConfigLineCodec.encode(
    ConfigSnapshot(
      baseUrl = s.baseUrl,
      repo = s.repo,
      mirrorInsecureTls = s.mirrorInsecureTls,
      offlineGenerateOnly = s.offlineGenerateOnly,
      simpleUiMode = s.simpleUiMode,
      gitLabBaseUrl = s.gitLabBaseUrl,
      gitLabProject = s.gitLabProject,
      gitLabInsecureTls = s.gitLabInsecureTls,
      gitRemoteName = s.gitRemoteName,
      pullBackDefaultMode = s.pullBackDefaultMode,
      mirrorApiKey = SecretsStore.mirrorApiKey,
      syncPassword = SecretsStore.syncPassword,
      gitLabToken = SecretsStore.gitLabToken,
      workMode = s.workMode
    )
  )

  CopyPasteManager.getInstance().setContents(StringSelection(line))
  notify(LocalGitMirrorBundle.message("notify.config.copied"), NotificationType.INFORMATION)
}

internal fun LocalGitMirrorPanel.pasteConfigLine() {
  val clipboardText = try {
    val data = Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor)
    data?.toString().orEmpty()
  } catch (_: Exception) {
    ""
  }

  val fromClipboard = ConfigLineCodec.extractOrNull(clipboardText)

  val line = if (fromClipboard != null) {
    fromClipboard
  } else {
    val manual = Messages.showInputDialog(
      project, "Paste config line",
      LocalGitMirrorBundle.message("dialog.selectBranch.title"),
      null, "", null
    )?.trim().orEmpty()

    val extractedManual = ConfigLineCodec.extractOrNull(manual)
    if (extractedManual == null) {
      val direct = ConfigLineCodec.decode(manual)
      if (direct == null) {
        notify(LocalGitMirrorBundle.message("notify.config.notFound"), NotificationType.WARNING)
        return
      }
      applySnapshot(direct)
      notify(LocalGitMirrorBundle.message("notify.config.applied"), NotificationType.INFORMATION)
      return
    }
    extractedManual
  }

  val snapshot = ConfigLineCodec.decode(line)
  if (snapshot == null) {
    notify(LocalGitMirrorBundle.message("notify.config.invalid"), NotificationType.ERROR)
    return
  }

  applySnapshot(snapshot)
  notify(LocalGitMirrorBundle.message("notify.config.applied"), NotificationType.INFORMATION)
}

internal fun LocalGitMirrorPanel.applySnapshot(snapshot: ConfigSnapshot) {
  val s = service<MirrorSettingsService>().state
  s.baseUrl = snapshot.baseUrl
  s.repo = snapshot.repo
  s.mirrorInsecureTls = snapshot.mirrorInsecureTls
  s.offlineGenerateOnly = snapshot.offlineGenerateOnly
  s.simpleUiMode = snapshot.simpleUiMode
  s.gitLabBaseUrl = snapshot.gitLabBaseUrl
  s.gitLabProject = snapshot.gitLabProject
  s.gitLabInsecureTls = snapshot.gitLabInsecureTls
  s.gitRemoteName = snapshot.gitRemoteName
  s.pullBackDefaultMode = snapshot.pullBackDefaultMode
  s.workMode = snapshot.workMode

  SecretsStore.mirrorApiKey = snapshot.mirrorApiKey
  SecretsStore.syncPassword = snapshot.syncPassword
  SecretsStore.gitLabToken = snapshot.gitLabToken

  refreshStatus()
}
