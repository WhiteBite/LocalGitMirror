package localgitmirror.detekt

import io.gitlab.arturbosch.detekt.api.*
import org.jetbrains.kotlin.psi.*

/**
 * Detects hardcoded English string literals in AnAction subclasses
 * that should be using i18n (LocalGitMirrorBundle.message()).
 *
 * Filters out:
 * - Short strings (< 10 chars) — likely technical values
 * - Strings that look like paths, URLs, or identifiers
 * - Strings already wrapped in Bundle.message()
 * - Strings inside test code
 */
class HardcodedStringInAction(config: Config) : Rule(config) {

  override val issue = Issue(
    id = "HardcodedStringInAction",
    severity = Severity.Warning,
    description = "Hardcoded English string in AnAction subclass. " +
      "Use LocalGitMirrorBundle.message() for i18n support.",
    debt = Debt.FIVE_MINS,
  )

  /** Minimum length to be considered a user-visible string */
  private val minStringLength = 10

  /** Patterns that indicate non-user-facing strings */
  private val technicalPatterns = listOf(
    Regex("^https?://"),
    Regex("^/"),
    Regex("^\\w+\\.\\w+$"),
    Regex("^\\[trace="),
    Regex("^LocalGitMirror"),
    Regex("^git\\s"),
    Regex("^\\w+=\\w+"),
    Regex("^[a-z]+\\.[a-z]+\\.[a-z]"), // i18n keys like "action.mr.title"
    Regex("^FETCH_HEAD$"),
    Regex("^mr-tmp-"),
    Regex("^sync-tmp-"),
    Regex("^\\^"),  // regex patterns
  )

  /** Method names whose string arguments are developer-facing, not user-facing */
  private val developerFacingMethods = setOf(
    "append", "println", "log", "debug", "info", "warn", "error",
    "text", "contains", "matches", "split", "replace", "startsWith",
  )

  override fun visitClass(klass: KtClass) {
    super.visitClass(klass)

    // Only check AnAction subclasses
    val superTypes = klass.superTypeListEntries.map { it.text }
    val isAction = superTypes.any { it.contains("AnAction") }
    if (!isAction) return

    // Skip test classes
    if (klass.containingFile.name.contains("Test")) return

    // First pass: collect all string expressions that are inside Bundle.message() calls
    val bundleStrings = mutableSetOf<KtStringTemplateExpression>()
    klass.accept(object : DetektVisitor() {
      override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val calleeText = expression.calleeExpression?.text ?: ""
        if (calleeText.contains("Bundle") || calleeText.contains("message")) {
          // Collect all string arguments of this call
          expression.valueArguments.forEach { arg ->
            val strExpr = arg.getArgumentExpression() as? KtStringTemplateExpression
            if (strExpr != null) bundleStrings.add(strExpr)
          }
        }
      }
    })

    // Second pass: find hardcoded strings NOT in the bundle set
    klass.accept(object : DetektVisitor() {
      override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        val value = expression.text.removeSurrounding("\"")
        if (value.length < minStringLength) return

        // Skip strings used as Bundle.message() arguments
        if (expression in bundleStrings) return

        // Skip if inside a developer-facing method call
        if (isInsideDeveloperMethod(expression)) return

        // Skip if it looks technical
        if (technicalPatterns.any { it.containsMatchIn(value) }) return

        report(
          CodeSmell(
            issue,
            Entity.from(expression),
            message = "Hardcoded string '$value' in action class. " +
              "Consider using LocalGitMirrorBundle.message() for i18n.",
          )
        )
      }
    })
  }

  private fun isInsideDeveloperMethod(element: KtElement): Boolean {
    // Walk up to find the nearest call expression
    var current = element.parent
    while (current != null && current !is KtCallExpression && current !is KtFunction) {
      current = current.parent
    }
    if (current is KtCallExpression) {
      val callee = current.calleeExpression?.text ?: ""
      if (callee in developerFacingMethods) return true
    }
    // Also check for property assignment to indicator.text
    if (element.parent is KtBinaryExpression) {
      val binExpr = element.parent as KtBinaryExpression
      val left = binExpr.left?.text ?: ""
      if (left.endsWith(".text")) return true
    }
    return false
  }
}
