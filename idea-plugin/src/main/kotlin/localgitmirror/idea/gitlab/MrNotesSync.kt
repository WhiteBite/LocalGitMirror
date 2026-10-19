package localgitmirror.idea.gitlab

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.settings.MirrorProjectSettingsService
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Work-side notes transfer, independent of branch sync: renders every open MR's discussions and uploads `mr-notes/mr-!N.md` only when the rendered content hash changed since the last upload (per-iid hash in project state), re-uploading unchanged notes once the last upload is older than six days because the postbox drops items after seven. */
object MrNotesSync {

  data class Report(val uploaded: List<Int>, val unchanged: Int, val failures: List<String>,
                    val closedMarked: List<Int> = emptyList())

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
    val mrsResult = GitLabApi.listOpenMrs(conf)
    if (mrsResult.code !in 200..299) {
      return Report(emptyList(), 0, listOf(mrsResult.message.ifBlank { "GitLab API error ${mrsResult.code}" }))
    }
    val state = project.service<MirrorProjectSettingsService>().state
    val now = LocalDateTime.now()
    val openIids = mrsResult.mrs.map { it.iid }.toSet()
    val uploaded = mutableListOf<Int>()
    val failures = mutableListOf<String>()
    var unchanged = 0
    for (mr in mrsResult.mrs) {
      if (onlyIids != null && mr.iid !in onlyIids) continue
      val key = mr.iid.toString()
      state.mrTitles[key] = mr.title
      state.mrBranches[key] = mr.sourceBranch
      if (state.mrClosedSentAt.containsKey(key)) state.mrClosedSentAt.remove(key)
      if (shouldSkipMr(state.mrUpdatedAt[key], mr.updatedAt, state.mrNotesSentAt[key], now)) {
        unchanged++
        continue
      }
      val discussions = GitLabApi.listMrDiscussions(conf, mr.iid).discussions
      if (discussions.isEmpty()) continue
      val unresolved = discussions.count { d -> !d.resolved && d.notes.any { !it.system } }
      val markdown = MrNotesWriter.renderMarkdown(
        project, mr.iid, mr.title, mr.sourceBranch, mr.updatedAt,
        unresolved, discussions.size, discussions,
      )
      val hash = sha256Hex(markdown)
      if (shouldSkipUpload(state.mrNotesHash[key], hash, state.mrNotesSentAt[key], now)) {
        state.mrUpdatedAt[key] = mr.updatedAt
        unchanged++
        continue
      }
      when (val res = MrRepliesTransport.uploadResult(project, "mr-notes/mr-!${mr.iid}.md", markdown)) {
        is MrUploadResult.Ok -> {
          state.mrNotesHash[key] = hash
          state.mrNotesSentAt[key] = now.format(TS_FORMAT)
          state.mrUpdatedAt[key] = mr.updatedAt
          uploaded.add(mr.iid)
        }
        is MrUploadResult.Failed -> failures.add("!${mr.iid}: ${res.reason}")
      }
    }
    val closed = closedCandidates(
      state.mrNotesHash.keys.mapNotNull { it.toIntOrNull() }.toSet(), openIids, onlyIids != null,
    )
    val closedMarked = markClosed(project, closed, state, now, failures)
    for (iid in evictStaleClosed(state.mrClosedSentAt, now)) {
      state.mrTitles.remove(iid)
      state.mrBranches.remove(iid)
      state.mrUpdatedAt.remove(iid)
    }
    return Report(uploaded, unchanged, failures, closedMarked)
  }

  /** Closure is decided only on a full sync: a filtered run knows nothing about MRs outside its selection. */
  internal fun closedCandidates(tracked: Set<Int>, openIids: Set<Int>, filtered: Boolean): List<Int> =
    if (filtered) emptyList() else tracked.filter { it !in openIids }.sorted()

  /** An unchanged MR whose last note upload is still inside the postbox TTL budget does not need a discussions refetch. */
  internal fun shouldSkipMr(prevUpdatedAt: String?, currentUpdatedAt: String, lastNoteSentAt: String?, now: LocalDateTime): Boolean {
    if (prevUpdatedAt.isNullOrBlank() || prevUpdatedAt != currentUpdatedAt) return false
    val at = lastNoteSentAt?.let { runCatching { LocalDateTime.parse(it, TS_FORMAT) }.getOrNull() } ?: return false
    return at.isAfter(now.minusDays(RESYNC_DAYS))
  }

  /** Closed-marker state outliving the postbox TTL is dead weight; malformed stamps stay (fail-safe). */
  internal fun evictStaleClosed(map: MutableMap<String, String>, now: LocalDateTime): List<String> {
    val cutoff = now.minusDays(RESYNC_DAYS + 1)
    val stale = map.entries
      .filter { (_, stamp) ->
        runCatching { LocalDateTime.parse(stamp, TS_FORMAT) }.getOrNull()?.isBefore(cutoff) == true
      }
      .map { it.key }
    stale.forEach { map.remove(it) }
    return stale
  }

  private fun markClosed(
    project: Project,
    closed: List<Int>,
    state: MirrorProjectSettingsService.State,
    now: LocalDateTime,
    failures: MutableList<String>,
  ): List<Int> {
    val marked = mutableListOf<Int>()
    for (iid in closed) {
      val key = iid.toString()
      if (shouldSkipClosure(state.mrClosedSentAt[key], now)) continue
      val markdown = MrClosedMarker.render(
        iid, state.mrTitles[key] ?: "", state.mrBranches[key] ?: "",
        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
      )
      when (val res = MrRepliesTransport.uploadResult(project, "mr-notes/mr-!$iid.closed.md", markdown)) {
        is MrUploadResult.Ok -> {
          state.mrNotesHash.remove(key)
          state.mrNotesSentAt.remove(key)
          state.mrClosedSentAt[key] = now.format(TS_FORMAT)
          marked.add(iid)
        }
        is MrUploadResult.Failed -> failures.add("!$iid closed-marker: ${res.reason}")
      }
    }
    return marked
  }

  /** Closed markers re-upload on the same TTL budget as notes: the postbox drops items after [RESYNC_DAYS]+1 days. */
  internal fun shouldSkipClosure(sentAt: String?, now: LocalDateTime): Boolean {
    val at = sentAt?.let { runCatching { LocalDateTime.parse(it, TS_FORMAT) }.getOrNull() } ?: return false
    return at.isAfter(now.minusDays(RESYNC_DAYS))
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
