package localgitmirror.idea.gitlab

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.settings.MirrorProjectSettingsService
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Work-side notes transfer, independent of branch sync: renders every open MR's discussions and uploads `mr-notes/mr-!N.md` only when the rendered content hash changed since the last upload (per-iid hash in project state). */
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
      if (hash == state.mrNotesHash[row.iid.toString()]) {
        unchanged++
        continue
      }
      when (val res = MrRepliesTransport.uploadResult(project, "mr-notes/mr-!${row.iid}.md", markdown)) {
        is MrUploadResult.Ok -> {
          state.mrNotesHash[row.iid.toString()] = hash
          state.mrNotesSentAt[row.iid.toString()] = LocalDateTime.now().format(TS_FORMAT)
          uploaded.add(row.iid)
        }
        is MrUploadResult.Failed -> failures.add("!${row.iid}: ${res.reason}")
      }
    }
    return Report(uploaded, unchanged, failures)
  }

  private fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
      .joinToString("") { b -> "%02x".format(b) }

  private val TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
