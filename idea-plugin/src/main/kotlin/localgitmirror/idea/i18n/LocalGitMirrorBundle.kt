package localgitmirror.idea.i18n

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

private const val BUNDLE = "messages.LocalGitMirrorBundle"

object LocalGitMirrorBundle {
  fun message(key: String, vararg params: Any): String {
    val text = try {
      ResourceBundle.getBundle(BUNDLE, Locale.getDefault()).getString(key)
    } catch (_: MissingResourceException) {
      key
    }
    return if (params.isEmpty()) text else MessageFormat.format(text, *params)
  }
}
