package localgitmirror.idea.ui.exchange

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.ui.LocalGitMirrorPanel
import java.awt.Image
import java.io.File
import javax.swing.ImageIcon

private const val THUMB_MAX_WIDTH = 240
private const val THUMB_MAX_HEIGHT = 320

/** Persistent home for received exchange files: <project>/.doccache/exchange, gitignored. */
internal fun LocalGitMirrorPanel.exchangeDir(): File? {
  val base = project.basePath ?: return null
  val dir = File(base, ".doccache/exchange")
  if (!dir.exists() && !dir.mkdirs()) return null
  val gitignore = File(base, ".gitignore")
  val entry = ".doccache/"
  try {
    if (!gitignore.isFile) {
      gitignore.writeText("$entry\n", Charsets.UTF_8)
    } else if (gitignore.readText(Charsets.UTF_8).lines().none { it.trim() == entry }) {
      gitignore.appendText("$entry\n")
    }
  } catch (_: Throwable) {
  }
  return dir
}

internal fun LocalGitMirrorPanel.safeExchangeName(item: ExchangeItem): String {
  val raw = item.displayPath.substringAfterLast('/').ifBlank { item.title }
  val safe = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
  return safe.ifBlank { "file.bin" }
}

/** Decode a postbox image in the background and drop the thumbnail into its bubble. */
internal fun LocalGitMirrorPanel.queueThumbPrefetch(item: ExchangeItem) {
  val key = item.id
  if (chatThumbCache.containsKey(key) || !thumbQueued.add(key)) return
  val s = service<MirrorSettingsService>().state
  if (s.baseUrl.isBlank()) {
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

internal fun LocalGitMirrorPanel.scaledThumb(bytes: ByteArray): ImageIcon? {
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

internal fun LocalGitMirrorPanel.publishThumb(localId: String, icon: ImageIcon) {
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
