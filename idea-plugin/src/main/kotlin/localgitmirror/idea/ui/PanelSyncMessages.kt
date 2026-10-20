package localgitmirror.idea.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import localgitmirror.idea.i18n.LocalGitMirrorBundle

/** Pure notification/decision seams for the panel sync actions, kept outside Swing so they can be unit tested. */
internal object PanelSyncMessages {

  fun sendBranchOk(branch: String, repo: String?): String =
    LocalGitMirrorBundle.message("notify.send.branch.ok.repo", branch, repo ?: "?")

  fun pushAsOk(branch: String, repo: String?): String =
    LocalGitMirrorBundle.message("notify.send.pushAs.ok", branch, repo ?: "?")

  /** [selected] is accepted on purpose and ignored: the action's label says "current". */
  @Suppress("UNUSED_PARAMETER")
  fun sendCurrentTarget(selected: BranchChoice?, currentBranch: String): String = currentBranch

  fun sendFailureText(repo: String?, message: String, details: String): String =
    workspaceDirtyText(details) ?: "repo='${repo ?: "?"}' $message. $details"

  // the refusal body is truncated to 500 chars upstream, so JSON parse falls back to a scan
  private fun workspaceDirtyText(details: String): String? {
    if (!DIRTY_CODE.containsMatchIn(details)) return null
    val paths = extractDirtyPaths(details)
    return LocalGitMirrorBundle.message("notify.send.workspaceDirty", paths.joinToString(", "))
  }

  private fun extractDirtyPaths(body: String): List<String> {
    val viaJson = runCatching {
      Json.parseToJsonElement(body).jsonObject["paths"]?.jsonArray
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
    }.getOrNull()
    if (viaJson != null) return viaJson
    val open = PATHS_ARRAY.find(body) ?: return emptyList()
    val start = open.range.last + 1
    val end = body.indexOf(']', start)
    val segment = if (end >= 0) body.substring(start, end) else body.substring(start)
    return QUOTED.findAll(segment).map { it.groupValues[1] }.toList()
  }

  private val DIRTY_CODE = Regex("\"code\"\\s*:\\s*\"workspace_dirty\"")
  private val PATHS_ARRAY = Regex("\"paths\"\\s*:\\s*\\[")
  private val QUOTED = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
}
