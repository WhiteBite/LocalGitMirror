package localgitmirror.idea.sync.v2

enum class Severity { INFO, WARN, ERROR }

data class Diagnostic(
  val severity: Severity,
  val code: String,
  val message: String,
  val hint: String? = null
)

data class PreflightReport(
  val ok: Boolean,
  val targetRepo: String?,
  val diagnostics: List<Diagnostic>
)

data class DryRunReport(
  val ok: Boolean,
  val targetRepo: String?,
  val branch: String?,
  val head: String?,
  val predictedMode: String,
  val commitRange: String,
  val commitCount: Int,
  val diagnostics: List<Diagnostic>
)

data class PullDryRunReport(
  val ok: Boolean,
  val targetRepo: String?,
  val localHead: String?,
  val remoteHead: String?,
  val hasUpdates: Boolean,
  val reason: String,
  val diagnostics: List<Diagnostic>
)

object SyncPlanning {
  data class Prediction(
    val mode: String,
    val range: String,
    val count: Int
  )

  fun predict(head: String, bestBase: String?, remoteHasHead: Boolean, rangeCount: Int?, fullCount: Int?): Prediction {
    if (remoteHasHead) {
      // Remote already has the commit object, so we can use pointer-only apply (no dump upload).
      return Prediction(mode = "pointer-only", range = "$head..$head", count = 0)
    }
    if (!bestBase.isNullOrBlank()) {
      return Prediction(
        mode = "incremental",
        range = "$bestBase..$head",
        count = rangeCount ?: -1
      )
    }
    return Prediction(
      mode = "full",
      range = "(full)",
      count = fullCount ?: -1
    )
  }
}
