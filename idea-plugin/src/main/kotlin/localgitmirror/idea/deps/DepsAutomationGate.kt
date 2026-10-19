package localgitmirror.idea.deps

/**
 * Master gate for all deps automation (gradle-sync hook, poller, initial
 * missing check, vault cache sync). Pure function of primitives so it is
 * unit-testable without IntelliJ fixtures; the caller resolves the mode from
 * settings and the role via [RoleDetector] at read time. Manual UI actions
 * (Request/Respond/Apply buttons) are NOT gated.
 */
internal object DepsAutomationGate {

  /** "auto" = only on HOME; "on" = forced; "off"/unknown = disabled (fail-safe). */
  fun enabled(mode: String, roleIsHome: Boolean): Boolean = when (mode.trim().lowercase()) {
    "auto" -> roleIsHome
    "on" -> true
    else -> false
  }
}

/**
 * Poller-side gate state: the poller thread runs from project open even while
 * the gate is closed, and the one-time initialization (Gradle sync hook,
 * initial missing check, vault sync) runs exactly once — on the first pass
 * where the gate is open, possibly long after project open.
 */
internal class DepsAutomationGateTracker {
  enum class Action { IDLE, INITIALIZE, POLL }

  private var initialized = false

  fun pass(gateOpen: Boolean): Action = when {
    !gateOpen -> Action.IDLE
    !initialized -> {
      initialized = true
      Action.INITIALIZE
    }
    else -> Action.POLL
  }
}
