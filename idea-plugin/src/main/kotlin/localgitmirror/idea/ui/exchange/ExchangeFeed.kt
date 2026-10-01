package localgitmirror.idea.ui.exchange

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.gitlab.MrReviewService
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.ui.LocalGitMirrorPanel
import localgitmirror.idea.ui.MrNotesDialog
import localgitmirror.idea.workkit.BundleCrypto
import localgitmirror.idea.workkit.ExchangeCrypto
import localgitmirror.idea.workkit.ExchangeMeta
import localgitmirror.idea.workkit.RepoFileSyncCrypto
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.*

private const val EXCHANGE_POLL_MS = 5_000
private const val IDEA_LOG_TAIL_BYTES = 200 * 1024
private const val THUMB_MAX_WIDTH = 240
private const val THUMB_MAX_HEIGHT = 320
private const val LOCAL_ID_PREFIX = "local-"

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
    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) return
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: exchange", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.checkCanceled()
        val pwd = SecretsStore.syncPassword
        val buffer = MirrorApi.bufferList(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls)
        val dir = baseDir()
        val repo = if (dir != null)
          try { syncFacade.resolveRepo(dir, s).sanitized } catch (_: Throwable) { "" }
        else ""
        indicator.checkCanceled()
        val files = if (repo.isNotBlank())
          MirrorApi.fileSyncList(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls)
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
            val plain = if (f.pathEnc.isNotBlank()) decryptExchangeHint(f.pathEnc, pwd, f.path) else f.path
            val meta = ExchangeMeta.parseName(plain)
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

  // ── Chat rendering ──

  private fun LocalGitMirrorPanel.chatMessages(): List<ExchangeItem> {
    val echoed = echoIdByServerId.keys
    return (allServerItems.filterNot { it.id in echoed } + chatEchoes).sortedBy { it.ts }
  }

  internal fun LocalGitMirrorPanel.renderChat(scrollToBottom: Boolean = false) {
    val messages = chatMessages()
    val signature = buildString {
      for (m in messages) {
        append(m.id).append('|').append(m.state).append('|').append(m.pinned)
          .append('|').append(m.serverId).append('|').append(m.title).append(';')
      }
      append("#e").append(expandedIds.joinToString(","))
      append("#b").append(chatBodyCache.keys.joinToString(","))
      append("#x").append(chatEmptyMessage)
    }
    if (signature == lastChatSignature && !scrollToBottom) return
    lastChatSignature = signature
    val stick = scrollToBottom || isChatAtBottom()
    bubbleThumbLabels.clear()
    chatPanel.removeAll()
    if (messages.isEmpty()) {
      chatPanel.add(JBLabel(chatEmptyMessage, AllIcons.Toolwindows.ToolWindowMessages, JBLabel.CENTER).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(24, 8)
        horizontalAlignment = JBLabel.CENTER
        alignmentX = Component.CENTER_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
      })
    }
    for (m in messages) chatPanel.add(buildBubbleRow(m))
    chatPanel.revalidate()
    chatPanel.repaint()
    if (stick) scrollChatToBottom()
  }

  private fun LocalGitMirrorPanel.isChatAtBottom(): Boolean {
    val vp = chatScroll.viewport ?: return true
    val extent = vp.extentSize.height
    val content = vp.viewSize.height
    return content <= extent || vp.viewPosition.y >= content - extent - JBUI.scale(48)
  }

  private fun LocalGitMirrorPanel.scrollChatToBottom() {
    SwingUtilities.invokeLater {
      if (project.isDisposed) return@invokeLater
      val bar = chatScroll.verticalScrollBar
      bar.value = bar.maximum
    }
  }

  /** Fetch the full buffer body off the EDT (lazy decrypt) and expand the bubble. */
  internal fun LocalGitMirrorPanel.fetchChatBody(item: ExchangeItem) {
    loadChatBody(item) {
      expandedIds.add(item.id)
      renderChat()
    }
  }

  /** Resolve the full buffer body (cache or bufferGet+decrypt); [onLoaded] runs on the EDT. */
  internal fun LocalGitMirrorPanel.loadChatBody(item: ExchangeItem, onLoaded: (String) -> Unit) {
    chatBodyCache[item.id]?.let {
      onLoaded(it)
      return
    }
    val s = service<MirrorSettingsService>().state
    val pwd = SecretsStore.syncPassword
    if (s.baseUrl.isBlank() || pwd.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.download"), true) {
      private var body: String? = null
      override fun run(indicator: ProgressIndicator) {
        val res = MirrorApi.bufferGet(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls, item.effectiveId)
        if (res.code !in 200..299 || res.file == null) return
        body = try {
          String(BundleCrypto.decryptDumpBytes(res.file.readBytes(), pwd), Charsets.UTF_8)
        } catch (_: Throwable) {
          null
        } finally {
          runCatching { res.file.delete() }
        }
      }

      override fun onSuccess() {
        val text = body ?: return
        chatBodyCache[item.id] = text
        onLoaded(text)
      }
    })
  }

  /** Decode a postbox image in the background and drop the thumbnail into its bubble. */
  private fun LocalGitMirrorPanel.queueThumbPrefetch(item: ExchangeItem) {
    val key = item.id
    if (chatThumbCache.containsKey(key) || !thumbQueued.add(key)) return
    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) {
      thumbQueued.remove(key)
      return
    }
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        val bytes = downloadDecrypted(item, s)
        val icon = bytes?.let { scaledThumb(it) }
        if (icon == null) {
          thumbQueued.remove(key)
          return@executeOnPooledThread
        }
        publishThumb(key, icon)
      } catch (_: Throwable) {
        thumbQueued.remove(key)
      }
    }
  }

  private fun LocalGitMirrorPanel.downloadDecrypted(item: ExchangeItem, s: MirrorSettingsService.State): ByteArray? {
    val enc = File.createTempFile("lgm-thumb-", ".bin")
    try {
      val dl = MirrorApi.fileSyncDownload(
        s.baseUrl, SecretsStore.mirrorApiKey, item.repo, s.mirrorInsecureTls, item.id, enc
      )
      if (dl.code !in 200..299) return null
      val out = File.createTempFile("lgm-thumb-", ".tmp")
      return try {
        RepoFileSyncCrypto.decryptFile(enc, out, SecretsStore.syncPassword, null)
        out.readBytes()
      } finally {
        runCatching { out.delete() }
      }
    } catch (_: Throwable) {
      return null
    } finally {
      runCatching { enc.delete() }
    }
  }

  private fun LocalGitMirrorPanel.scaledThumb(bytes: ByteArray): ImageIcon? {
    val base = ImageIcon(bytes)
    if (base.iconWidth <= 0) return null
    val maxW = JBUI.scale(THUMB_MAX_WIDTH)
    val maxH = JBUI.scale(THUMB_MAX_HEIGHT)
    if (base.iconWidth <= maxW && base.iconHeight <= maxH) return base
    val ratio = minOf(maxW.toDouble() / base.iconWidth, maxH.toDouble() / base.iconHeight)
    val w = (base.iconWidth * ratio).toInt().coerceAtLeast(1)
    val h = (base.iconHeight * ratio).toInt().coerceAtLeast(1)
    return ImageIcon(base.image.getScaledInstance(w, h, Image.SCALE_SMOOTH))
  }

  internal fun LocalGitMirrorPanel.openExchangeItem(item: ExchangeItem) {
    if (item.kind == ExchangeItem.Kind.BUFFER) {
      val local = item.localText
      if (local != null) {
        CopyPasteManager.getInstance().setContents(StringSelection(local))
        notify(LocalGitMirrorBundle.message("panel.exchange.chat.copied"), NotificationType.INFORMATION)
        return
      }
      ProgressManager.getInstance().run(object :
        Task.Backgroundable(project, LocalGitMirrorBundle.message("buffer.task.paste"), true) {
        override fun run(indicator: ProgressIndicator) {
          localgitmirror.idea.actions.pasteBufferEntryById(project, item.effectiveId, item.ts)
        }
      })
      return
    }
    when {
      item.isMrNotes -> withDecryptedFile(item) { plain -> openMrNotesFrom(plain, item) }
      item.isImage -> withDecryptedFile(item) { plain -> showImagePreview(plain, item) }
      item.isText -> withDecryptedFile(item) { plain -> openTextInEditor(plain, item) }
      else -> saveExchangeFileAs(item)
    }
  }

  /** Persistent home for received exchange files: <project>/.doccache/exchange, gitignored. */
  private fun LocalGitMirrorPanel.exchangeDir(): File? {
    val base = project.basePath ?: return null
    val dir = File(base, ".doccache/exchange")
    if (!dir.exists() && !dir.mkdirs()) return null
    val gitignore = File(base, ".gitignore")
    val entry = ".doccache/"
    try {
      if (!gitignore.isFile) {
        gitignore.writeText("$entry\n", Charsets.UTF_8)
      } else if (gitignore.readText(Charsets.UTF_8).lines().none { it.trim() == entry }) {
        gitignore.appendText("$entry\n", Charsets.UTF_8)
      }
    } catch (_: Throwable) {
    }
    return dir
  }

  /** Side marker stamped into exchange meta: the machine's detected role, not the client type. */
  private fun LocalGitMirrorPanel.localMetaSide(): String =
    ExchangeMeta.sideOfRole(localgitmirror.idea.deps.RoleDetector.detect(service<MirrorSettingsService>().state) == localgitmirror.idea.deps.MachineRole.WORK)

  private fun LocalGitMirrorPanel.safeExchangeName(item: ExchangeItem): String {
    val raw = item.displayPath.substringAfterLast('/').ifBlank { item.title }
    val safe = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    return safe.ifBlank { "file.bin" }
  }

  /** Absolute path of the received file: the materialized copy, downloading it first when absent. */
  internal fun LocalGitMirrorPanel.copyExchangePath(item: ExchangeItem) {
    val target = exchangeDir()?.let { File(it, safeExchangeName(item)) }
    if (target == null) {
      copyToClipboard(item.displayPath)
      return
    }
    if (target.isFile) {
      copyToClipboard(target.absolutePath)
      return
    }
    withDecryptedFile(item) { f -> copyToClipboard(f.absolutePath) }
  }

  private fun LocalGitMirrorPanel.copyToClipboard(text: String) {
    CopyPasteManager.getInstance().setContents(StringSelection(text))
    notify(LocalGitMirrorBundle.message("panel.exchange.chat.copied"), NotificationType.INFORMATION)
  }

  /** Ship a plugin zip to the other machine through the repo postbox (plugin/ namespace). */
  internal fun LocalGitMirrorPanel.sendPluginBuild() {
    val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
    descriptor.title = LocalGitMirrorBundle.message("plugin.send.title")
    val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
    val file = File(chosen.path)
    if (!file.name.endsWith(".zip")) {
      notify(LocalGitMirrorBundle.message("plugin.send.fail", file.name), NotificationType.WARNING)
      return
    }
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.upload"), true) {
      override fun run(indicator: ProgressIndicator) {
        val ok = localgitmirror.idea.gitlab.MrRepliesTransport.uploadFile(project, "plugin/${file.name}", file)
        notify(
          if (ok) LocalGitMirrorBundle.message("plugin.send.ok", file.name)
          else LocalGitMirrorBundle.message("plugin.send.fail", file.name),
          if (ok) NotificationType.INFORMATION else NotificationType.ERROR,
        )
      }
    })
  }

  /** Install a plugin zip shipped by the other machine from the postbox plugin/ namespace. */
  internal fun LocalGitMirrorPanel.installPluginFromCache() {
    val s = service<MirrorSettingsService>().state
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.download"), true) {
      private var target: File? = null
      override fun run(indicator: ProgressIndicator) {
        val repo = resolveExchangeRepo(s) ?: return
        val list = MirrorApi.fileSyncList(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls)
        if (list.code !in 200..299) return
        val items = list.items.mapNotNull { item ->
          displayPathOf(item).takeIf { it.startsWith("plugin/") && it.endsWith(".zip") }?.let { item to it }
        }
        if (items.isEmpty()) return
        val picked = if (items.size == 1) items[0] else {
          var result: Pair<MirrorApi.FileSyncItem, String>? = null
          SwingUtilities.invokeAndWait {
            val names = items.map { it.second }.toTypedArray()
            val name = Messages.showEditableChooseDialog(
              LocalGitMirrorBundle.message("plugin.install.choose"),
              LocalGitMirrorBundle.message("plugin.install.chooseTitle"),
              null, names, names.last(), null,
            )
            result = items.firstOrNull { it.second == name }
          }
          result ?: return
        }
        val enc = File.createTempFile("lgm-plugin-", ".bin")
        try {
          val dl = MirrorApi.fileSyncDownload(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls, picked.first.id, enc)
          if (dl.code !in 200..299 || dl.file == null) return
          val dir = File(com.intellij.openapi.application.PathManager.getSystemPath(), "doccache-plugin").apply { mkdirs() }
          val out = File(dir, picked.second.substringAfterLast('/'))
          RepoFileSyncCrypto.decryptFile(enc, out, SecretsStore.syncPassword, null)
          target = out
        } catch (_: Throwable) {
        } finally {
          runCatching { enc.delete() }
        }
      }

      override fun onSuccess() {
        val f = target
        if (f == null) {
          notify(LocalGitMirrorBundle.message("plugin.install.none"), NotificationType.WARNING)
          return
        }
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "preferences.plugins")
        notify(LocalGitMirrorBundle.message("plugin.install.manual", f.absolutePath), NotificationType.INFORMATION)
      }
    })
  }

  private fun LocalGitMirrorPanel.displayPathOf(item: MirrorApi.FileSyncItem): String {
    if (item.pathEnc.isBlank()) return item.path
    val plain = runCatching {
      localgitmirror.idea.workkit.ExchangeCrypto.decryptHint(item.pathEnc, SecretsStore.syncPassword)
    }.getOrDefault(item.path)
    return localgitmirror.idea.workkit.ExchangeMeta.parseName(plain).text.ifBlank { item.path }
  }

  /** Download + decrypt a postbox entry off the EDT; [consume] runs on the EDT and receives a persistent file in .doccache/exchange (or a temp file when the project dir is unavailable). */
  private fun LocalGitMirrorPanel.withDecryptedFile(item: ExchangeItem, consume: (File) -> Unit) {
    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.download"), true) {
      private var plain: File? = null
      private var plainIsTemp = false
      override fun run(indicator: ProgressIndicator) {
        val enc = File.createTempFile("lgm-dl-", ".bin")
        try {
          val repo = item.repo.ifBlank { resolveExchangeRepo(s) ?: return }
          val dl = MirrorApi.fileSyncDownload(
            s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls, item.effectiveId, enc
          )
          if (dl.code !in 200..299) {
            notify(
              LocalGitMirrorBundle.message("panel.exchange.download.fail", dl.code, dl.message.take(200)),
              NotificationType.ERROR
            )
            return
          }
          val out = File.createTempFile("lgm-plain-", ".tmp")
          RepoFileSyncCrypto.decryptFile(enc, out, SecretsStore.syncPassword, null)
          val target = exchangeDir()?.let { dir -> File(dir, safeExchangeName(item)) }
          val copied = target?.let { t ->
            runCatching { Files.copy(out.toPath(), t.toPath(), StandardCopyOption.REPLACE_EXISTING) }.isSuccess
          } ?: false
          if (copied && target != null) {
            runCatching { out.delete() }
            plain = target
          } else {
            plain = out
            plainIsTemp = true
          }
        } catch (t: Throwable) {
          notify(
            LocalGitMirrorBundle.message("panel.exchange.decrypt.fail", t.message ?: t::class.simpleName ?: ""),
            NotificationType.ERROR
          )
        } finally {
          runCatching { enc.delete() }
        }
      }

      override fun onSuccess() {
        val f = plain ?: return
        if (project.isDisposed) {
          runCatching { f.delete() }
          return
        }
        try {
          consume(f)
        } finally {
          if (plainIsTemp) runCatching { f.delete() }
        }
      }
    })
  }

  private fun LocalGitMirrorPanel.openMrNotesFrom(plain: File, item: ExchangeItem) {
    try {
      val markdown = plain.readText(Charsets.UTF_8)
      val iid = Regex("mr-!(\\d+)\\.md$").find(item.displayPath)?.groupValues?.getOrNull(1)?.toIntOrNull()
      if (iid == null) {
        notify(LocalGitMirrorBundle.message("panel.exchange.mrnotes.iidMissing"), NotificationType.WARNING)
        return
      }
      val row = project.getService(MrReviewService::class.java).parseMrMarkdown(iid, markdown)
      MrNotesDialog(project, row).show()
    } catch (t: Throwable) {
      notify(LocalGitMirrorBundle.message("panel.exchange.decrypt.fail", t.message ?: ""), NotificationType.ERROR)
    }
  }

  private fun LocalGitMirrorPanel.showImagePreview(plain: File, item: ExchangeItem) {
    try {
      val image = ImageIcon(plain.readBytes())
      object : DialogWrapper(project, false) {
        init {
          title = item.title
          init()
        }
        override fun createCenterPanel(): JComponent = JBScrollPane(JLabel(image)).apply {
          preferredSize = Dimension(JBUI.scale(720), JBUI.scale(480))
        }
      }.show()
    } catch (t: Throwable) {
      notify(LocalGitMirrorBundle.message("panel.exchange.decrypt.fail", t.message ?: ""), NotificationType.ERROR)
    }
  }

  private fun LocalGitMirrorPanel.openTextInEditor(plain: File, item: ExchangeItem) {
    try {
      val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(plain)
      if (vf != null) {
        FileEditorManager.getInstance(project).openFile(vf, true)
        return
      }
      val lvf = LightVirtualFile(item.title, plain.readText(Charsets.UTF_8))
      lvf.isWritable = false
      FileEditorManager.getInstance(project).openFile(lvf, true)
    } catch (t: Throwable) {
      notify(LocalGitMirrorBundle.message("panel.exchange.decrypt.fail", t.message ?: ""), NotificationType.ERROR)
    }
  }

  internal fun LocalGitMirrorPanel.saveExchangeFileAs(item: ExchangeItem) {
    val descriptor = FileSaverDescriptor(
      LocalGitMirrorBundle.message("panel.exchange.saveAs.title"),
      LocalGitMirrorBundle.message("panel.exchange.saveAs.desc")
    )
    val wrapper = FileChooserFactory.getInstance()
      .createSaveFileDialog(descriptor, project)
      .save(item.title) ?: return
    val target = wrapper.file
    withDecryptedFile(item) { plain ->
      try {
        Files.copy(plain.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        notify(LocalGitMirrorBundle.message("panel.exchange.saveAs.ok", target.absolutePath), NotificationType.INFORMATION)
        historyService.add(
          LocalGitMirrorBundle.message("history.op.exchangeSave"), true,
          "${item.displayPath} -> ${target.absolutePath}"
        )
      } catch (t: Throwable) {
        notify(LocalGitMirrorBundle.message("panel.exchange.saveAs.fail", t.message ?: ""), NotificationType.ERROR)
      }
    }
  }

  internal fun LocalGitMirrorPanel.toggleExchangePin(item: ExchangeItem) {
    val s = service<MirrorSettingsService>().state
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: pin", true) {
      override fun run(indicator: ProgressIndicator) {
        val res = MirrorApi.bufferPin(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls, item.effectiveId, !item.pinned)
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
          MirrorApi.bufferDelete(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls, item.effectiveId)
        else
          MirrorApi.fileSyncAck(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls, item.effectiveId)
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
        val res = MirrorApi.bufferClear(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls)
        if (res.code !in 200..299) {
          notify(LocalGitMirrorBundle.message("panel.exchange.delete.fail", res.code), NotificationType.ERROR)
        }
        // fresh listing: the cached one may miss items uploaded since the last poll
        val repo = resolveExchangeRepo(s)
        var acked = 0
        if (!repo.isNullOrBlank()) {
          val list = MirrorApi.fileSyncList(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls)
          if (list.code in 200..299) {
            for (f in list.items) {
              indicator.checkCanceled()
              if (MirrorApi.fileSyncAck(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls, f.id).code in 200..299) {
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

  // ── Exchange sends (optimistic chat echo) ──

  private fun LocalGitMirrorPanel.nowSec(): Double = System.currentTimeMillis() / 1000.0

  private fun LocalGitMirrorPanel.newLocalId(): String = LOCAL_ID_PREFIX + UUID.randomUUID().toString().take(8)

  internal fun LocalGitMirrorPanel.sendComposerText() {
    val text = composerField.text
    if (text.isBlank()) return
    if (sendChatText(text)) composerField.text = ""
  }

  /** Add the pending bubble instantly, then push in the background. Returns false if rejected upfront. */
  internal fun LocalGitMirrorPanel.sendChatText(text: String): Boolean {
    if (text.isEmpty()) {
      notify(LocalGitMirrorBundle.message("notify.buffer.noText"), NotificationType.WARNING)
      return false
    }
    if (text.length > 1_000_000) {
      notify(LocalGitMirrorBundle.message("notify.buffer.tooLarge"), NotificationType.WARNING)
      return false
    }
    val s = service<MirrorSettingsService>().state
    val pwd = SecretsStore.syncPassword
    if (s.baseUrl.isBlank() || pwd.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return false
    }
    val localId = newLocalId()
    val title = text.lineSequence().firstOrNull()?.trim()?.take(80)?.ifBlank { EMPTY_HINT_MARK } ?: EMPTY_HINT_MARK
    chatEchoes.add(
      ExchangeItem(
        ExchangeItem.Kind.BUFFER, localId, nowSec(), text.length.toLong(), title, false, "", "",
        ExchangeItem.Side.WORK, ExchangeItem.State.PENDING, localText = text
      )
    )
    renderChat(scrollToBottom = true)
    submitTextEcho(localId, s, pwd, text, null)
    return true
  }

  private fun LocalGitMirrorPanel.submitTextEcho(
    localId: String,
    s: MirrorSettingsService.State,
    pwd: String,
    text: String,
    hintOverride: String?
  ) {
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("buffer.task.send"), true) {
      private var result: Pair<Boolean, String?> = false to null
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        result = sendTextToBufferSync(s, pwd, text, hintOverride)
      }
      override fun onSuccess() = finishEcho(localId, result.first, result.second)
      override fun onThrowable(t: Throwable) = finishEcho(localId, false, null)
    })
  }

  /** Encrypt + push one buffer entry. Returns (httpOk, serverId). */
  private fun LocalGitMirrorPanel.sendTextToBufferSync(
    s: MirrorSettingsService.State,
    pwd: String,
    text: String,
    hintOverride: String?
  ): Pair<Boolean, String?> {
    return try {
      val ciphertext = BundleCrypto.encryptBundleBytes(text.toByteArray(Charsets.UTF_8), pwd)
      val hintEnc = ExchangeCrypto.encryptHint(ExchangeMeta.hintJson(text, hintOverride, localMetaSide()), pwd)
      val res = MirrorApi.bufferPut(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls, ciphertext, hintEnc)
      if (res.code !in 200..299) {
        historyService.add("Buffer send", false, "HTTP ${res.code}: ${res.message.take(200)}")
        false to null
      } else {
        historyService.add("Buffer send", true, "size=${text.length}")
        true to res.id
      }
    } catch (t: Throwable) {
      historyService.add("Buffer send", false, t.message ?: t::class.simpleName ?: "error")
      false to null
    }
  }

  /**
   * Resolve a local echo: confirmed → SENT (deduped against the poll by server id),
   * confirmed without id (old server) → dropped, the poll will show the real entry,
   * failed → FAILED with a retry link. [tempFile] is a plugin-owned file deleted on success.
   */
  private fun LocalGitMirrorPanel.finishEcho(localId: String, ok: Boolean, serverId: String?, tempFile: String? = null) {
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
    if (s.baseUrl.isBlank() || pwd.isBlank()) {
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

  internal fun LocalGitMirrorPanel.sendIdeaLogTail() {
    val s = service<MirrorSettingsService>().state
    val pwd = SecretsStore.syncPassword
    if (s.baseUrl.isBlank() || pwd.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val localId = newLocalId()
    chatEchoes.add(
      ExchangeItem(
        ExchangeItem.Kind.BUFFER, localId, nowSec(), IDEA_LOG_TAIL_BYTES.toLong(), "idea.log tail",
        false, "", "", ExchangeItem.Side.WORK, ExchangeItem.State.PENDING
      )
    )
    renderChat(scrollToBottom = true)
    submitLogTailEcho(localId, s, pwd)
  }

  private fun LocalGitMirrorPanel.submitLogTailEcho(localId: String, s: MirrorSettingsService.State, pwd: String) {
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("buffer.task.send"), true) {
      private var result: Pair<Boolean, String?> = false to null
      private var tail: String? = null
      override fun run(indicator: ProgressIndicator) {
        val logFile = File(PathManager.getLogPath())
        if (!logFile.isFile) return
        tail = try {
          RandomAccessFile(logFile, "r").use { raf ->
            val len = raf.length()
            raf.seek(maxOf(0L, len - IDEA_LOG_TAIL_BYTES))
            val bytes = ByteArray((len - maxOf(0L, len - IDEA_LOG_TAIL_BYTES)).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
          }
        } catch (t: Throwable) {
          null
        }
        val text = tail ?: return
        result = sendTextToBufferSync(s, pwd, text, "idea.log tail")
      }

      override fun onSuccess() {
        if (tail == null) {
          notify(LocalGitMirrorBundle.message("panel.exchange.log.missing"), NotificationType.WARNING)
          chatEchoes.removeAll { it.id == localId }
          renderChat()
          return
        }
        finishEcho(localId, result.first, result.second)
      }

      override fun onThrowable(t: Throwable) = finishEcho(localId, false, null)
    })
  }

  internal fun LocalGitMirrorPanel.sendClipboardScreenshot() {
    val image = clipboardImage()
    if (image == null) {
      notify(LocalGitMirrorBundle.message("panel.exchange.noClipboard"), NotificationType.WARNING)
      return
    }
    sendImageToPostbox(image)
  }

  internal fun LocalGitMirrorPanel.sendImageToPostbox(image: Image) {
    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val localId = newLocalId()
    val name = "shot-" + SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()) + ".png"
    chatEchoes.add(
      ExchangeItem(
        ExchangeItem.Kind.FILE, localId, nowSec(), 0L, name, false, name, "",
        ExchangeItem.Side.WORK, ExchangeItem.State.PENDING, localTemp = true
      )
    )
    renderChat(scrollToBottom = true)
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.upload"), true) {
      private var newId: String? = null
      private var tmpPath: String? = null
      override fun run(indicator: ProgressIndicator) {
        val tmp = File.createTempFile("lgm-shot-", ".png")
        try {
          ImageIO.write(toBufferedImage(image), "png", tmp)
          tmpPath = tmp.absolutePath
          runCatching { scaledThumb(tmp.readBytes())?.let { publishThumb(localId, it) } }
          newId = uploadFileToPostboxSync(s, tmp, name)
        } catch (t: Throwable) {
          historyService.add(
            LocalGitMirrorBundle.message("history.op.exchangeUpload"), false,
            "$name err=${t.message ?: t::class.simpleName}"
          )
        }
      }

      override fun onSuccess() = finishEcho(localId, newId != null, newId, tmpPath)
      override fun onThrowable(t: Throwable) = finishEcho(localId, false, null, tmpPath)
    })
  }

  private fun LocalGitMirrorPanel.toBufferedImage(img: Image): BufferedImage {
    if (img is BufferedImage) return img
    val bi = BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB)
    val g = bi.createGraphics()
    g.drawImage(img, 0, 0, null)
    g.dispose()
    return bi
  }

  internal fun LocalGitMirrorPanel.uploadFilesToPostbox(files: List<File>) {
    if (files.isEmpty()) return
    val s = service<MirrorSettingsService>().state
    if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) {
      notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val pending = files.map { f ->
      val localId = newLocalId()
      chatEchoes.add(
        ExchangeItem(
          ExchangeItem.Kind.FILE, localId, nowSec(), f.length(), f.name, false, f.name, "",
          ExchangeItem.Side.WORK, ExchangeItem.State.PENDING, localFile = f.absolutePath
        )
      )
      localId to f
    }
    renderChat(scrollToBottom = true)
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.upload"), true) {
      // "" is the failure sentinel — ConcurrentHashMap cannot hold null values.
      private val results = ConcurrentHashMap<String, String>()
      override fun run(indicator: ProgressIndicator) {
        for ((i, p) in pending.withIndex()) {
          indicator.checkCanceled()
          indicator.fraction = i.toDouble() / pending.size
          indicator.text = p.second.name
          if (ExchangeItem.isImageFileName(p.second.name)) {
            runCatching { scaledThumb(p.second.readBytes())?.let { publishThumb(p.first, it) } }
          }
          results[p.first] = uploadFileToPostboxSync(s, p.second) ?: ""
        }
      }

      override fun onSuccess() = applyUploadResults(pending, results)
      override fun onThrowable(t: Throwable) = applyUploadResults(pending, results)
    })
  }

  private fun LocalGitMirrorPanel.publishThumb(localId: String, icon: ImageIcon) {
    chatThumbCache[localId] = icon
    UIUtil.invokeLaterIfNeeded {
      if (project.isDisposed) return@invokeLaterIfNeeded
      bubbleThumbLabels[localId]?.let {
        it.icon = icon
        it.text = null
        it.revalidate()
        it.repaint()
      }
    }
  }

  private fun LocalGitMirrorPanel.applyUploadResults(
    pending: List<Pair<String, File>>,
    results: Map<String, String>
  ) {
    if (project.isDisposed) return
    for ((localId, _) in pending) {
      val id = results[localId]
      if (id != null) finishEcho(localId, id.isNotEmpty(), id.ifEmpty { null })
      else finishEcho(localId, false, null)
    }
  }

  private fun LocalGitMirrorPanel.submitFileEcho(
    localId: String,
    s: MirrorSettingsService.State,
    file: File,
    realName: String,
    isTemp: Boolean
  ) {
    ProgressManager.getInstance().run(object :
      Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.upload"), true) {
      private var newId: String? = null
      override fun run(indicator: ProgressIndicator) {
        newId = uploadFileToPostboxSync(s, file, realName)
      }

      override fun onSuccess() =
        finishEcho(localId, newId != null, newId, if (isTemp) file.absolutePath else null)

      override fun onThrowable(t: Throwable) =
        finishEcho(localId, false, null, if (isTemp) file.absolutePath else null)
    })
  }

  /** Encrypt + upload one file; returns the server id or null on failure. */
  private fun LocalGitMirrorPanel.uploadFileToPostboxSync(
    s: MirrorSettingsService.State,
    file: File,
    realName: String = file.name
  ): String? {
    val repo = resolveExchangeRepo(s) ?: return null
    val enc = File.createTempFile("lgm-up-", ".bin")
    return try {
      RepoFileSyncCrypto.encryptFile(file, enc, SecretsStore.syncPassword, null)
      finishPostboxUpload(s, repo, enc, realName, file.length())
    } catch (t: Throwable) {
      historyService.add(
        LocalGitMirrorBundle.message("history.op.exchangeUpload"), false,
        "$realName err=${t.message ?: t::class.simpleName}"
      )
      null
    } finally {
      runCatching { enc.delete() }
    }
  }

  private fun LocalGitMirrorPanel.finishPostboxUpload(
    s: MirrorSettingsService.State,
    repo: String,
    encrypted: File,
    realName: String,
    plainSize: Long
  ): String? {
    val pathEnc = ExchangeCrypto.encryptHint(ExchangeMeta.nameJson(realName, localMetaSide()), SecretsStore.syncPassword)
    val res = MirrorApi.fileSyncUpload(
      s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls,
      "x/${UUID.randomUUID().toString().take(8)}", plainSize, encrypted, pathEnc, null
    )
    if (res.code !in 200..299 || res.id.isNullOrBlank()) {
      historyService.add(
        LocalGitMirrorBundle.message("history.op.exchangeUpload"), false,
        "$realName HTTP ${res.code}: ${res.message.take(200)}"
      )
      return null
    }
    historyService.add(
      LocalGitMirrorBundle.message("history.op.exchangeUpload"), true,
      "$realName id=${res.id} size=$plainSize"
    )
    return res.id
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

