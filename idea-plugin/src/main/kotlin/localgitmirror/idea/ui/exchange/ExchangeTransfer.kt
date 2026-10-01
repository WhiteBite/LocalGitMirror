package localgitmirror.idea.ui.exchange

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorBufferApi
import localgitmirror.idea.mirror.MirrorCrypto
import localgitmirror.idea.mirror.MirrorPostboxApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.ui.LocalGitMirrorPanel
import localgitmirror.idea.ui.notify
import localgitmirror.idea.workkit.BundleCrypto
import localgitmirror.idea.workkit.ExchangeCrypto
import localgitmirror.idea.workkit.ExchangeMeta
import java.awt.Image
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
import javax.swing.SwingUtilities

private const val LOCAL_ID_PREFIX = "local-"
private const val IDEA_LOG_TAIL_BYTES = 200 * 1024

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
  if (s.baseUrl.isBlank() || (pwd.isBlank() && !MirrorCrypto.isV3Pinned())) {
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

internal fun LocalGitMirrorPanel.submitTextEcho(
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
    override fun onCancel() = finishEcho(localId, false, null)
  })
}

/** Seal + push one buffer entry. Returns (httpOk, serverId). */
private fun LocalGitMirrorPanel.sendTextToBufferSync(
  s: MirrorSettingsService.State,
  pwd: String,
  text: String,
  hintOverride: String?
): Pair<Boolean, String?> {
  return try {
    val sealed = MirrorCrypto.sealBufferPayload(text.toByteArray(Charsets.UTF_8), pwd)
    val meta = ExchangeMeta.hintJson(text, hintOverride, localMetaSide())
    val hintEnc = if (pwd.isBlank()) "" else ExchangeCrypto.encryptHint(meta, pwd)
    val res = MirrorBufferApi.bufferPut(
      s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls,
      sealed.bytes, hintEnc, false, sealed.epkB64, if (hintEnc.isBlank()) meta else ""
    )
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

internal fun LocalGitMirrorPanel.sendIdeaLogTail() {
  val s = service<MirrorSettingsService>().state
  val pwd = SecretsStore.syncPassword
  if (s.baseUrl.isBlank() || (pwd.isBlank() && !MirrorCrypto.isV3Pinned())) {
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

internal fun LocalGitMirrorPanel.submitLogTailEcho(localId: String, s: MirrorSettingsService.State, pwd: String) {
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

    override fun onCancel() = finishEcho(localId, false, null)
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
  if (s.baseUrl.isBlank()) {
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
    override fun onCancel() = finishEcho(localId, false, null, tmpPath)
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
  if (s.baseUrl.isBlank()) {
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
    override fun onCancel() = applyUploadResults(pending, results)
  })
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

internal fun LocalGitMirrorPanel.submitFileEcho(
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

    override fun onCancel() =
      finishEcho(localId, false, null, if (isTemp) file.absolutePath else null)
  })
}

/** Seal + upload one file; returns the server id or null on failure. */
private fun LocalGitMirrorPanel.uploadFileToPostboxSync(
  s: MirrorSettingsService.State,
  file: File,
  realName: String = file.name
): String? {
  val repo = resolveExchangeRepo(s) ?: return null
  val enc = File.createTempFile("lgm-up-", ".bin")
  return try {
    val displayPath = ExchangeMeta.nameJson(realName, localMetaSide())
    val sealed = MirrorCrypto.sealPostboxPayload(file.readBytes(), displayPath, file.length())
    enc.writeBytes(sealed.bytes)
    val res = MirrorPostboxApi.fileSyncUpload(
      s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls,
      sealed.epkB64, sealed.meta, enc
    )
    if (res.code !in 200..299 || res.id.isNullOrBlank()) {
      historyService.add(
        LocalGitMirrorBundle.message("history.op.exchangeUpload"), false,
        "$realName HTTP ${res.code}: ${res.message.take(200)}"
      )
      null
    } else {
      historyService.add(
        LocalGitMirrorBundle.message("history.op.exchangeUpload"), true,
        "$realName id=${res.id} size=${file.length()}"
      )
      res.id
    }
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

/** Resolve the full buffer body (cache or bufferGet+decrypt); [onLoaded] runs on the EDT. */
internal fun LocalGitMirrorPanel.loadChatBody(item: ExchangeItem, onLoaded: (String) -> Unit) {
  chatBodyCache[item.id]?.let {
    onLoaded(it)
    return
  }
  val s = service<MirrorSettingsService>().state
  val pwd = SecretsStore.syncPassword
  if (s.baseUrl.isBlank() || (pwd.isBlank() && !MirrorCrypto.isV3Pinned())) {
    notify(LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
    return
  }
  ProgressManager.getInstance().run(object :
    Task.Backgroundable(project, LocalGitMirrorBundle.message("panel.exchange.task.download"), true) {
    private var body: String? = null
    override fun run(indicator: ProgressIndicator) {
      val res = MirrorBufferApi.bufferGet(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls, item.effectiveId)
      if (res.code !in 200..299 || res.file == null) return
      body = try {
        val bytes = if (res.decrypted) res.file.readBytes()
        else BundleCrypto.decryptDumpBytes(res.file.readBytes(), pwd)
        String(bytes, Charsets.UTF_8)
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

internal fun LocalGitMirrorPanel.downloadDecrypted(item: ExchangeItem, s: MirrorSettingsService.State): ByteArray? {
  val enc = File.createTempFile("lgm-thumb-", ".bin")
  try {
    val dl = MirrorPostboxApi.fileSyncDownload(
      s.baseUrl, SecretsStore.mirrorApiKey, item.repo, s.mirrorInsecureTls, item.id, enc
    )
    if (dl.code !in 200..299) return null
    return enc.readBytes()
  } catch (_: Throwable) {
    return null
  } finally {
    runCatching { enc.delete() }
  }
}

/** Side marker stamped into exchange meta: the machine's detected role, not the client type. */
private fun LocalGitMirrorPanel.localMetaSide(): String =
  ExchangeMeta.sideOfRole(localgitmirror.idea.deps.RoleDetector.detect(service<MirrorSettingsService>().state) == localgitmirror.idea.deps.MachineRole.WORK)

/** Download + decrypt a postbox entry off the EDT; [consume] runs on the EDT and receives a persistent file in .doccache/exchange (or a temp file when the project dir is unavailable). */
internal fun LocalGitMirrorPanel.withDecryptedFile(item: ExchangeItem, consume: (File) -> Unit) {
  val s = service<MirrorSettingsService>().state
  if (s.baseUrl.isBlank()) {
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
        val dl = MirrorPostboxApi.fileSyncDownload(
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
        Files.copy(enc.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
      val list = MirrorPostboxApi.fileSyncList(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls)
      if (list.code !in 200..299) return
      val items = list.items.mapNotNull { item ->
        item.path.takeIf { it.startsWith("plugin/") && it.endsWith(".zip") }?.let { item to it }
      }
      if (items.isEmpty()) return
      val picked = if (items.size == 1) items[0] else {
        var result: Pair<MirrorPostboxApi.FileSyncItem, String>? = null
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
        val dl = MirrorPostboxApi.fileSyncDownload(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls, picked.first.id, enc)
        if (dl.code !in 200..299 || dl.file == null) return
        val dir = File(com.intellij.openapi.application.PathManager.getSystemPath(), "doccache-plugin").apply { mkdirs() }
        val out = File(dir, picked.second.substringAfterLast('/'))
        Files.copy(enc.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
