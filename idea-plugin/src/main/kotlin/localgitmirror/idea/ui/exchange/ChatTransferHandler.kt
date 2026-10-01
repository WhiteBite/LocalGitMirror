package localgitmirror.idea.ui.exchange

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.ui.LocalGitMirrorPanel
import localgitmirror.idea.workkit.BundleCrypto
import localgitmirror.idea.workkit.RepoFileSyncCrypto
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.TransferHandler

private const val EXPORT_FETCH_TIMEOUT_SEC = 120L

internal class ChatTransferHandler(
  private val panel: LocalGitMirrorPanel,
  private val toComposer: Boolean
) : TransferHandler() {
  override fun canImport(support: TransferSupport): Boolean {
    if (!support.isDrop) return false
    if (support.transferable is ExchangeTransferable) return false
    return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
      support.isDataFlavorSupported(DataFlavor.imageFlavor) ||
      support.isDataFlavorSupported(DataFlavor.stringFlavor)
  }

  override fun importData(support: TransferSupport): Boolean {
    val t = support.transferable ?: return false
    return try {
      when {
        t.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
          @Suppress("UNCHECKED_CAST")
          val files = t.getTransferData(DataFlavor.javaFileListFlavor) as? List<File> ?: return false
          panel.uploadFilesToPostbox(files.filter { it.isFile })
          true
        }
        t.isDataFlavorSupported(DataFlavor.imageFlavor) -> {
          val image = t.getTransferData(DataFlavor.imageFlavor) as? Image ?: return false
          panel.sendImageToPostbox(image)
          true
        }
        t.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
          val text = t.getTransferData(DataFlavor.stringFlavor)?.toString() ?: return false
          if (text.isEmpty()) return false
          if (toComposer) panel.composerField.replaceSelection(text) else panel.sendChatText(text)
          true
        }
        else -> false
      }
    } catch (_: Throwable) {
      false
    }
  }
}

/**
 * Export payload is fetched lazily: the network round-trip starts when the
 * drag begins and getTransferData only blocks if the drop beats the fetch.
 */
internal class ExchangeTransferable(
  private val panel: LocalGitMirrorPanel,
  private val item: ExchangeItem
) : Transferable {
  private val dataFuture = CompletableFuture<Any?>()

  init {
    ApplicationManager.getApplication().executeOnPooledThread {
      dataFuture.complete(runCatching { panel.fetchExportData(item) }.getOrNull())
    }
  }

  override fun getTransferDataFlavors(): Array<DataFlavor> =
    if (item.kind == ExchangeItem.Kind.BUFFER) arrayOf(DataFlavor.stringFlavor)
    else arrayOf(DataFlavor.javaFileListFlavor)

  override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
    getTransferDataFlavors().any { it.equals(flavor) }

  override fun getTransferData(flavor: DataFlavor): Any {
    if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
    return dataFuture.get(EXPORT_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS)
      ?: throw UnsupportedFlavorException(flavor)
  }
}

private fun LocalGitMirrorPanel.fetchExportData(item: ExchangeItem): Any? {
  val s = service<MirrorSettingsService>().state
  val pwd = SecretsStore.syncPassword
  if (s.baseUrl.isBlank() || pwd.isBlank()) return null
  if (item.kind == ExchangeItem.Kind.BUFFER) {
    val res = MirrorApi.bufferGet(s.baseUrl, SecretsStore.mirrorApiKey, s.mirrorInsecureTls, item.effectiveId)
    if (res.code !in 200..299 || res.file == null) return null
    return try {
      String(BundleCrypto.decryptDumpBytes(res.file.readBytes(), pwd), Charsets.UTF_8)
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { res.file.delete() }
    }
  }
  val enc = File.createTempFile("lgm-export-", ".bin")
  try {
    val repo = item.repo.ifBlank { resolveExchangeRepo(s) ?: return null }
    val dl = MirrorApi.fileSyncDownload(s.baseUrl, SecretsStore.mirrorApiKey, repo, s.mirrorInsecureTls, item.effectiveId, enc)
    if (dl.code !in 200..299) return null
    val dir = Files.createTempDirectory("lgm-export-").toFile()
    val out = File(dir, item.title.replace(Regex("[^A-Za-z0-9._\\-]"), "_").ifBlank { "file" })
    RepoFileSyncCrypto.decryptFile(enc, out, pwd, null)
    return listOf(out)
  } catch (_: Throwable) {
    return null
  } finally {
    runCatching { enc.delete() }
  }
}
