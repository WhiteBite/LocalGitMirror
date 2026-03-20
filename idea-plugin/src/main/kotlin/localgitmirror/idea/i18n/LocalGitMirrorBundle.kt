package localgitmirror.idea.i18n

import com.intellij.openapi.application.ApplicationManager
import localgitmirror.idea.settings.MirrorSettingsService
import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

private const val BUNDLE = "messages.LocalGitMirrorBundle"

object LocalGitMirrorBundle {
  fun message(key: String, vararg params: Any): String {
    val locale = preferredLocale()
    val text = try {
      ResourceBundle.getBundle(BUNDLE, locale).getString(key)
    } catch (_: MissingResourceException) {
      key
    }
    return if (params.isEmpty()) text else MessageFormat.format(text, *params)
  }

  private fun preferredLocale(): Locale {
    val app = ApplicationManager.getApplication() ?: return Locale.getDefault()
    val state = app.getService(MirrorSettingsService::class.java)?.state ?: return Locale.getDefault()
    return when (state.uiLanguage.lowercase()) {
      "ru" -> Locale("ru")
      "en" -> Locale.ENGLISH
      else -> Locale.getDefault()
    }
  }
}
