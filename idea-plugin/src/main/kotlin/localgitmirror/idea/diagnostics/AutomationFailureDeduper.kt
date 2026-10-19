package localgitmirror.idea.diagnostics

/** Stable dedup signature of a recurring failure: exception class plus message, single line, bounded. */
fun failureSignature(t: Throwable): String =
  "${t.javaClass.simpleName}: ${t.message ?: "no message"}".replace('\n', ' ').take(200)

/**
 * Exactly-once gate for one recurring failure stream (a poll cycle, a startup
 * check, a state-based skip). [failure] returns the signature when it must
 * surface — first occurrence or a new signature after a different failure —
 * and null while the same failure repeats. [recovered] re-arms the active
 * signature so a recurrence after a healthy cycle surfaces again. Independent
 * failure streams need separate instances.
 */
class AutomationFailureDeduper {

  private var active: String? = null

  @Synchronized
  fun failure(signature: String): String? {
    if (signature == active) return null
    active = signature
    return signature
  }

  @Synchronized
  fun recovered() {
    active = null
  }
}
