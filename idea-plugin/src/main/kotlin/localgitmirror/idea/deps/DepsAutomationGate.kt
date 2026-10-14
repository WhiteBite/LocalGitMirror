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
