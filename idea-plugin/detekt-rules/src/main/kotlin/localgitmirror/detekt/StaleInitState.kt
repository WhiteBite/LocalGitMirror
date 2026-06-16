package localgitmirror.detekt

import io.gitlab.arturbosch.detekt.api.*
import org.jetbrains.kotlin.psi.*

/**
 * Detects class-level `val` properties initialized from a service state
 * that are read once at construction and never refreshed.
 *
 * Bad pattern:
 *   class MyPanel {
 *     val workMode = service<MirrorSettingsService>().state.isWorkMode()
 *   }
 *
 * The value is frozen at construction time. If the user changes settings,
 * this property stays stale.
 *
 * Fix: use a function, computed property, or re-read in refresh methods.
 */
class StaleInitState(config: Config) : Rule(config) {

  override val issue = Issue(
    id = "StaleInitState",
    severity = Severity.Warning,
    description = "Class-level val initialized from service state. " +
      "This value is frozen at construction and won't reflect settings changes.",
    debt = Debt.TEN_MINS,
  )

  /** Patterns that indicate a service state read */
  private val statePatterns = listOf(
    "service<",
    "getService(",
    ".state.",
    ".state)",
  )

  override fun visitProperty(property: KtProperty) {
    super.visitProperty(property)

    // Only check class-level properties (not inside functions)
    val parent = property.parent
    if (parent !is KtClassBody) return

    // Only check val (immutable) properties — var properties can be updated
    if (!property.isVar && property.hasInitializer()) {
      val initializer = property.initializer?.text ?: return

      // Check if the initializer reads from a service state
      val readsState = statePatterns.any { initializer.contains(it) }
      if (!readsState) return

      // Check if it's a known safe pattern (e.g., service reference stored for later use)
      val isServiceRef = initializer.trim().startsWith("service<") &&
        !initializer.contains(".state")
      if (isServiceRef) return

      // Also skip if it's a project.getService() call (service reference, not state value)
      val isProjectServiceRef = initializer.trim().startsWith("project.getService(") &&
        !initializer.contains(".state")
      if (isProjectServiceRef) return

      report(
        CodeSmell(
          issue,
          Entity.from(property),
          message = "Property '${property.name}' reads service state at construction time. " +
            "This value will be stale if settings change. " +
            "Consider using a function or re-reading in refresh methods.",
        )
      )
    }
  }
}
