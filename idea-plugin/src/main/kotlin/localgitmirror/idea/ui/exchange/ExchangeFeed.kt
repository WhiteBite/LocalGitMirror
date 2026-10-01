package localgitmirror.idea.ui.exchange

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorBufferApi
import localgitmirror.idea.mirror.MirrorCrypto
import localgitmirror.idea.mirror.MirrorPostboxApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.ui.LocalGitMirrorPanel
import localgitmirror.idea.ui.baseDir
import localgitmirror.idea.ui.notify
import localgitmirror.idea.workkit.ExchangeCrypto
import localgitmirror.idea.workkit.ExchangeMeta
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File

private const val EXCHANGE_POLL_MS = 5_000

internal fun LocalGitMirrorPanel.startExchangePolling() {
  exchangePollAlarm.cancelAllRequests()
  exchangePollAlarm.addRequest({
    if (project.isDisposed) return@addRequest
    if (tabsPane?.selectedIndex == 3) {
      refreshExchangeInBackground()
      startExchangePolling()
    }
  }, EXCHANGE_POLL_MS)
}

internal fun LocalGitMirrorPanel.stopExchangePolling() = exchangePollAlarm.cancelAllRequests()

/** Fetch buffer entries + repo postbox files off the EDT into the chat timeline. */
internal fun LocalGitMirrorPanel.refreshExchangeInBackground() {
  if (project.isDisposed || ApplicationManager.getApplication().isDisposeInProgress) return
  val s = service<MirrorSettingsService>().state
  if (s.baseUrl.isBlank()) return
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: exchange", true) {
    override fun run(indicator: ProgressIndicator) {
      indicator.checkCanceled()
      val pwd = SecretsStore.syncPassword
      val buffer = MirrorBufferApi.bufferList(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls)
      val dir = baseDir()
      val repo = if (dir != null)
        try { syncFacade.resolveRepo(dir, s).sanitized } catch (_: Throwable) { "" }
      else ""
      indicator.checkCanceled()
      val files = if (repo.isNotBlank())
        MirrorPostboxApi.fileSyncList(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls)
      else null

      val items = mutableListOf<ExchangeItem>()
      if (buffer.code in 200..299) {
        for (b in buffer.items) {
          val plain = if (b.hintEnc.isNotBlank()) decryptExchangeHint(b.hintEnc, pwd, b.hint) else b.hint
          val meta = ExchangeMeta.parseHint(plain)
          val text = meta.text.ifBlank { b.hint }.ifBlank { EMPTY_HINT_MARK }
          items.add(
            ExchangeItem(
              ExchangeItem.Kind.BUFFER, b.id, b.ts, b.size, text, b.pinned, "", "",
              sideOf(meta.side)
            )
          )
        }
      }
      if (files != null && files.code in 200..299) {
        for (f in files.items) {
          val meta = ExchangeMeta.parseName(f.path)
          val display = meta.text.ifBlank { f.path }
          items.add(
            ExchangeItem(
              ExchangeItem.Kind.FILE, f.id, f.mtime.toDouble(), f.size,
              display.substringAfterLast('/').ifBlank { display }, false, display, repo,
              sideOf(meta.side)
            )
          )
        }
      }
      val bufferOk = buffer.code in 200..299
      val bufferCode = buffer.code

      UIUtil.invokeLaterIfNeeded {
        if (project.isDisposed) return@invokeLaterIfNeeded
        allServerItems = items.sortedBy { it.ts }
        chatEmptyMessage = if (bufferOk)
          LocalGitMirrorBundle.message("panel.exchange.empty")
        else
          LocalGitMirrorBundle.message("panel.exchange.buffer.listFail", bufferCode)
        renderChat()
        allServerItems.filter { it.isImage && !it.isEcho }.forEach { queueThumbPrefetch(it) }
      }
    }
  })
}

private fun LocalGitMirrorPanel.sideOf(marker: String?): ExchangeItem.Side = when (marker) {
  ExchangeMeta.SIDE_PLUGIN -> ExchangeItem.Side.WORK
  ExchangeMeta.SIDE_WEB -> ExchangeItem.Side.HOME
  else -> ExchangeItem.Side.UNKNOWN
}

private fun LocalGitMirrorPanel.decryptExchangeHint(enc: String, pwd: String, fallback: String): String =
  exchangeHintCache.getOrPut(enc) {
    runCatching { ExchangeCrypto.decryptHint(enc, pwd) }.getOrDefault(fallback)
  }

internal fun LocalGitMirrorPanel.resolveExchangeRepo(s: MirrorSettingsService.State): String? {
  val dir = baseDir()
  val repo = if (dir != null)
    try { syncFacade.resolveRepo(dir, s).sanitized } catch (_: Throwable) { "" }
  else ""
  if (repo.isBlank()) {
    notify(LocalGitMirrorBundle.message("filesync.notify.repoMissing"), NotificationType.WARNING)
    return null
  }
  return repo
}

internal fun LocalGitMirrorPanel.toggleExchangePin(item: ExchangeItem) {
  val s = service<MirrorSettingsService>().state
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: pin", true) {
    override fun run(indicator: ProgressIndicator) {
      val res = MirrorBufferApi.bufferPin(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls, item.effectiveId, !item.pinned)
      if (res.code !in 200..299) {
        notify(LocalGitMirrorBundle.message("panel.exchange.pin.fail", res.code), NotificationType.ERROR)
      }
    }
    override fun onSuccess() = refreshExchangeInBackground()
  })
}

internal fun LocalGitMirrorPanel.deleteExchangeItem(item: ExchangeItem) {
  if (item.isEcho && item.serverId == null) {
    discardEcho(item)
    return
  }
  val s = service<MirrorSettingsService>().state
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: delete", true) {
    private var ok = false
    override fun run(indicator: ProgressIndicator) {
      val repo = item.repo.ifBlank { resolveExchangeRepo(s) ?: "" }
      val res = if (item.kind == ExchangeItem.Kind.BUFFER)
        MirrorBufferApi.bufferDelete(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls, item.effectiveId)
      else
        MirrorPostboxApi.fileSyncAck(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls, item.effectiveId)
      if (res.code !in 200..299) {
        notify(LocalGitMirrorBundle.message("panel.exchange.delete.fail", res.code), NotificationType.ERROR)
      } else {
        ok = true
        historyService.add(
          LocalGitMirrorBundle.message("history.op.exchangeDelete"), true,
          "id=${item.effectiveId} ${item.displayPath}"
        )
      }
    }

    override fun onSuccess() {
      if (ok && item.isEcho) {
        chatEchoes.removeAll { it.id == item.id }
        item.serverId?.let { echoIdByServerId.remove(it) }
        if (item.localTemp && item.localFile != null) runCatching { File(item.localFile).delete() }
      }
      refreshExchangeInBackground()
    }
  })
}

internal fun LocalGitMirrorPanel.clearExchangeFeed() {
  val confirm = Messages.showYesNoDialog(
    project,
    LocalGitMirrorBundle.message("panel.exchange.clear.confirm"),
    LocalGitMirrorBundle.message("panel.exchange.clear.title"),
    LocalGitMirrorBundle.message("prune.confirm.yes"),
    LocalGitMirrorBundle.message("prune.confirm.no"),
    Messages.getWarningIcon()
  )
  if (confirm != Messages.YES) return
  val s = service<MirrorSettingsService>().state
  ProgressManager.getInstance().run(object :
    Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.clear"), true) {
    override fun run(indicator: ProgressIndicator) {
      val res = MirrorBufferApi.bufferClear(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls)
      if (res.code !in 200..299) {
        notify(LocalGitMirrorBundle.message("panel.exchange.delete.fail", res.code), NotificationType.ERROR)
      }
      // fresh listing: the cached one may miss items uploaded since the last poll
      val repo = resolveExchangeRepo(s)
      var acked = 0
      if (!repo.isNullOrBlank()) {
        val list = MirrorPostboxApi.fileSyncList(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls)
        if (list.code in 200..299) {
          for (f in list.items) {
            indicator.checkCanceled()
            if (MirrorPostboxApi.fileSyncAck(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls, f.id).code in 200..299) {
              acked++
            }
          }
        }
      }
      historyService.add(
        LocalGitMirrorBundle.message("history.op.exchangeClear"), true, "files=$acked"
      )
    }
    override fun onSuccess() {
      for (e in chatEchoes) {
        if (e.localTemp && e.localFile != null) runCatching { File(e.localFile).delete() }
      }
      chatEchoes.clear()
      echoIdByServerId.clear()
      chatBodyCache.clear()
      chatThumbCache.clear()
      bubbleThumbLabels.clear()
      expandedIds.clear()
      thumbQueued.clear()
      lastChatSignature = null
      project.basePath?.let { File(it, ".doccache/exchange") }
        ?.takeIf { it.exists() }
        ?.listFiles()
        ?.forEach { runCatching { it.delete() } }
      notify(LocalGitMirrorBundle.message("panel.exchange.clear.ok"), NotificationType.INFORMATION)
      renderChat()
      refreshExchangeInBackground()
    }
  })
}

/**
 * Resolve a local echo: confirmed → SENT (deduped against the poll by server id),
 * confirmed without id (old server) → dropped, the poll will show the real entry,
 * failed → FAILED with a retry link. [tempFile] is a plugin-owned file deleted on success.
 */
internal fun LocalGitMirrorPanel.finishEcho(localId: String, ok: Boolean, serverId: String?, tempFile: String? = null) {
  if (project.isDisposed) return
  val i = chatEchoes.indexOfFirst { it.id == localId }
  if (i < 0) return
  val e = chatEchoes[i]
  when {
    ok && serverId != null -> {
      chatEchoes[i] = e.copy(
        state = ExchangeItem.State.SENT, serverId = serverId,
        localFile = tempFile ?: e.localFile
      )
      echoIdByServerId[serverId] = localId
      if (tempFile != null) runCatching { File(tempFile).delete() }
    }
    ok -> chatEchoes.removeAt(i)
    else -> chatEchoes[i] = e.copy(
      state = ExchangeItem.State.FAILED,
      localFile = tempFile ?: e.localFile
    )
  }
  renderChat()
}

private fun LocalGitMirrorPanel.discardEcho(item: ExchangeItem) {
  chatEchoes.removeAll { it.id == item.id }
  if (item.localTemp && item.localFile != null) runCatching { File(item.localFile).delete() }
  item.serverId?.let { echoIdByServerId.remove(it) }
  renderChat()
}

internal fun LocalGitMirrorPanel.retryEcho(item: ExchangeItem) {
  val s = service<MirrorSettingsService>().state
  val pwd = SecretsStore.syncPassword
  if (s.baseUrl.isBlank() || (pwd.isBlank() && !MirrorCrypto.isV3Pinned())) {
    notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
    return
  }
  val i = chatEchoes.indexOfFirst { it.id == item.id }
  if (i < 0) return
  when {
    item.kind == ExchangeItem.Kind.BUFFER && item.localText != null -> {
      chatEchoes[i] = item.copy(state = ExchangeItem.State.PENDING)
      renderChat()
      submitTextEcho(item.id, s, pwd, item.localText, null)
    }
    item.kind == ExchangeItem.Kind.BUFFER -> {
      chatEchoes[i] = item.copy(state = ExchangeItem.State.PENDING)
      renderChat()
      submitLogTailEcho(item.id, s, pwd)
    }
    item.kind == ExchangeItem.Kind.FILE && item.localFile != null -> {
      val f = File(item.localFile)
      if (!f.isFile) {
        notify(
          LocalGitMirrorBundle.message("panel.exchange.upload.fail", "0", item.localFile),
          NotificationType.ERROR
        )
        discardEcho(item)
        return
      }
      chatEchoes[i] = item.copy(state = ExchangeItem.State.PENDING)
      renderChat()
      submitFileEcho(item.id, s, f, item.title, item.localTemp)
    }
    else -> discardEcho(item)
  }
}

internal fun LocalGitMirrorPanel.pasteClipboardToExchange() {
  val image = clipboardImage()
  if (image != null) {
    sendImageToPostbox(image)
    return
  }
  try {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
      val text = clipboard.getData(DataFlavor.stringFlavor)?.toString() ?: ""
      if (text.isNotEmpty()) {
        sendChatText(text)
        return
      }
    }
  } catch (_: Throwable) {
  }
  notify(LocalGitMirrorBundle.message("panel.exchange.noClipboard"), NotificationType.WARNING)
}

internal fun LocalGitMirrorPanel.clipboardImage(): Image? = try {
  val clipboard = Toolkit.getDefaultToolkit().systemClipboard
  if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor))
    clipboard.getData(DataFlavor.imageFlavor) as? Image
  else null
} catch (_: Throwable) {
  null
}
