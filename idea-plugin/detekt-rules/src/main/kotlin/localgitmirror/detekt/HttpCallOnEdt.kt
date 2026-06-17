package localgitmirror.detekt

import io.gitlab.arturbosch.detekt.api.*
import org.jetbrains.kotlin.psi.*

/**
 * Detects HTTP / network API calls that are NOT inside a background task.
 *
 * In IntelliJ plugins, network calls on the EDT freeze the IDE.
 * This rule flags calls to known HTTP/API helper classes/methods
 * unless they appear inside a `Task.Backgroundable.run()` override
 * or a `ProgressManager.getInstance().run(...)` lambda.
 */
class HttpCallOnEdt(config: Config) : Rule(config) {

  override val issue = Issue(
    id = "HttpCallOnEdt",
    severity = Severity.Warning,
    description = "HTTP/network call detected outside a background task. " +
      "Network calls on the EDT freeze the IDE. Wrap in Task.Backgroundable.",
    debt = Debt.TEN_MINS,
  )

  /** Class/object names that indicate HTTP calls */
  private val httpClassNames = setOf(
    "MirrorApi", "GitLabApi", "LanDiscovery",
    "HttpClient", "HttpURLConnection", "OkHttpClient",
  )

  /** Method names commonly used for HTTP operations */
  private val httpMethodNames = setOf(
    "ping", "upload", "download", "fetch", "getRefs",
    "listOpenMergeRequests", "getMergeRequestSourceBranch",
  )

  override fun visitCallExpression(expression: KtCallExpression) {
    super.visitCallExpression(expression)

    val calleeText = expression.calleeExpression?.text ?: return

    // Check if this looks like an HTTP call
    val isHttpCall = httpMethodNames.any { calleeText.contains(it) } ||
      httpClassNames.any { calleeText.contains(it) }

    if (!isHttpCall) return

    // Walk up to check if inside a background task
    if (isInsideBackgroundTask(expression)) return

    report(
      CodeSmell(
        issue,
        Entity.from(expression),
        message = "HTTP call '$calleeText' appears to be on the EDT. " +
          "Wrap in Task.Backgroundable or ProgressManager.run().",
      )
    )
  }

  private fun isInsideBackgroundTask(element: KtElement): Boolean {
    var parent = element.parent
    while (parent != null) {
      // Check for `object : Task.Backgroundable(...)` anonymous class
      if (parent is KtObjectDeclaration) {
        val superTypes = parent.superTypeListEntries.map { it.text }
        if (superTypes.any { it.contains("Task.Backgroundable") || it.contains("Task.Modal") }) {
          return true
        }
      }
      // Check for suspend function declaration
      if (parent is KtNamedFunction) {
        if (parent.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SUSPEND_KEYWORD)) {
          return true
        }
      }
      // Check for lambda passed to ProgressManager.run()
      if (parent is KtCallExpression) {
        val callText = parent.calleeExpression?.text ?: ""
        if (callText.contains("run") || callText.contains("runInBackground")) {
          return true
        }
      }
      // Check for Thread {} wrapper (including Thread({...}).start())
      if (parent is KtCallExpression) {
        val callText = parent.calleeExpression?.text ?: ""
        if (callText == "Thread" || callText == "thread") {
          return true
        }
      }
      // Walk through Thread({...}).start() — parent is KtDotQualifiedExpression
      if (parent is KtDotQualifiedExpression) {
        val receiver = parent.receiverExpression
        if (receiver is KtCallExpression) {
          val receiverText = receiver.calleeExpression?.text ?: ""
          if (receiverText == "Thread" || receiverText == "thread") {
            return true
          }
        }
      }
      // Check for SwingUtilities.invokeLater
      if (parent is KtCallExpression) {
        val callText = parent.calleeExpression?.text ?: ""
        if (callText.contains("invokeLater")) {
          return true
        }
      }
      parent = parent.parent
    }
    return false
  }
}
