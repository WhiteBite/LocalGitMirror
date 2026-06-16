package localgitmirror.detekt

import io.gitlab.arturbosch.detekt.api.*
import org.jetbrains.kotlin.psi.*

/**
 * Detects `ShowSettingsUtil.showSettingsDialog()` calls that use
 * a display name string instead of the configurable ID.
 *
 * Bad:  showSettingsDialog(project, "LocalGitMirror")
 * Good: showSettingsDialog(project, "localgitmirror.settings")
 *
 * Using the display name is fragile — if the name changes in plugin.xml,
 * the lookup silently fails.
 */
class SettingsLookupByDisplayName(config: Config) : Rule(config) {

  override val issue = Issue(
    id = "SettingsLookupByDisplayName",
    severity = Severity.Warning,
    description = "showSettingsDialog() should use configurable ID (with dots), not display name.",
    debt = Debt.FIVE_MINS,
  )

  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)

    val calleeText = expression.calleeExpression?.text ?: return
    if (!calleeText.contains("showSettingsDialog")) return

    // The second argument is the configurable identifier
    val args = expression.valueArguments
    if (args.size < 2) return

    val secondArg = args[1]
    val argText = secondArg.text.removeSurrounding("\"")

    // If the argument doesn't contain a dot, it's likely a display name, not an ID
    if (!argText.contains(".") && argText.isNotBlank() && !argText.contains("$")) {
      report(
        CodeSmell(
          issue,
          Entity.from(expression),
          message = "showSettingsDialog() uses '$argText' which looks like a display name. " +
            "Use the configurable ID from plugin.xml (e.g. 'localgitmirror.settings').",
        )
      )
    }
  }
}
