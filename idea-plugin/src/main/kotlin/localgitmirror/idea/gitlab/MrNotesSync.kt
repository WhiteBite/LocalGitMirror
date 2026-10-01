package localgitmirror.idea.gitlab

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.settings.MirrorProjectSettingsService
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Work-side notes transfer, independent of branch sync: renders every open MR's discussions and uploads `mr-notes/mr-!N.md` only when the rendered content hash changed since the last upload (per-iid hash in project state), re-uploading unchanged notes once the last upload is older than six days because the postbox drops items after seven. */
object MrNotesSync {

  data class Report(val uploaded: List<Int>, val unchanged: Int, val failures: List<String>)

  fun syncInBackground(project: Project, onlyIids: Set<Int>? = null, onDone: ((Report) -> Unit)? = null) {
    ApplicationManager.getApplication().executeOnPooledThread {
      val report = runCatching { sync(project, onlyIids) }
        .getOrElse { Report(emptyList(), 0, listOf(it.message ?: "error")) }
      UIUtil.invokeLaterIfNeeded {
        if (!project.isDisposed) onDone?.invoke(report)
      }
    }
  }

  fun sync(project: Project, onlyIids: Set<Int>? = null): Report {
    val conf = GitLabConfig.resolve(project)
    if (!GitLabConfig.hasApi(conf)) {
      return Report(emptyList(), 0, listOf("GitLab URL/project/token not configured"))
    }
    val rows = project.getService(MrReviewService::class.java)
      .fetchFromGitLab(conf)
      .filter { onlyIids == null || it.iid in onlyIids }
    val state = project.service<MirrorProjectSettingsService>().state
    val now = LocalDateTime.now()
    val uploaded = mutableListOf<Int>()
    val failures = mutableListOf<String>()
    var unchanged = 0
    for (row in rows) {
      if (row.discussions.isEmpty()) continue
      val markdown = MrNotesWriter.renderMarkdown(
        project, row.iid, row.title, row.sourceBranch, row.updatedAt,
        row.unresolved, row.totalThreads, row.discussions,
      )
      val hash = sha256Hex(markdown)
      val key = row.iid.toString()
      if (shouldSkipUpload(state.mrNotesHash[key], hash, state.mrNotesSentAt[key], now)) {
        unchanged++
        continue
      }
      when (val res = MrRepliesTransport.uploadResult(project, "mr-notes/mr-!${row.iid}.md", markdown)) {
        is MrUploadResult.Ok -> {
          state.mrNotesHash[key] = hash
          state.mrNotesSentAt[key] = now.format(TS_FORMAT)
          uploaded.add(row.iid)
        }
        is MrUploadResult.Failed -> failures.add("!${row.iid}: ${res.reason}")
      }
    }
    return Report(uploaded, unchanged, failures)
  }

  /** Hash dedup is bounded by the postbox TTL: an unchanged hash still re-uploads once the last successful upload is older than [RESYNC_DAYS]. */
  internal fun shouldSkipUpload(storedHash: String?, currentHash: String, sentAt: String?, now: LocalDateTime): Boolean {
    if (storedHash != currentHash) return false
    val at = sentAt?.let { runCatching { LocalDateTime.parse(it, TS_FORMAT) }.getOrNull() } ?: return false
    return at.isAfter(now.minusDays(RESYNC_DAYS))
  }

  private fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
      .joinToString("") { b -> "%02x".format(b) }

  private val TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
  private const val RESYNC_DAYS = 6L
}
