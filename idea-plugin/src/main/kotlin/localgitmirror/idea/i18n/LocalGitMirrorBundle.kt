package localgitmirror.idea.i18n

import com.intellij.openapi.actionSystem.AnActionEvent
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

  /**
   * Localize an action's presentation (text + description) from the plugin's
   * own resource bundle, so menu items follow the system locale (like the
   * tool-window panel) rather than the IDE UI language. Safe to call every
   * update(): message() returns the key itself when missing.
   */
  fun localizePresentation(e: AnActionEvent, actionId: String) {
    e.presentation.text = message("action.$actionId.text")
    e.presentation.description = message("action.$actionId.description")
  }
}
