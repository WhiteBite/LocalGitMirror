package localgitmirror.idea.gitlab

/**
 * The agent's reply file `.mr-notes/replies-!N.md` (format v1, documented in
 * INDEX.md). Three section kinds:
 *
 *   ## thread <discussionId>   reply into an existing GitLab discussion
 *   ## new <file>:<line>       brand-new code-anchored discussion
 *   ## new                     brand-new general MR note
 *
 * Optional `resolve: yes|no` line inside a section closes the thread after a
 * successful reply (threads only). Sections with an empty body or a malformed
 * header are collected into [RepliesFile.errors] and never sent. The grammar
 * lives in [MrReviewHandoff.parseLegacy] — the shared cross-language pipeline
 * (Python twin: lgm_core/mr_review_handoff.py); [parse] delegates to it.
 */
object MrReplies {

  enum class Kind { THREAD, NEW_ANCHORED, NEW_GENERAL }

  data class Reply(
    val kind: Kind,
    val threadId: String,
    val file: String,
    val line: Int,
    val resolve: Boolean,
    val body: String,
  )

  data class RepliesFile(
    val iid: Int,
    val branch: String,
    val replies: List<Reply>,
    val errors: List<String>,
  )

  fun parse(markdown: String): RepliesFile = MrReviewHandoff.parseLegacy(markdown)

  data class Matched(
    val byThread: Map<String, Reply>,
    val newSections: List<Reply>,
  )

  /** Attach agent replies to the threads they answer; unmatched/new sections go to [Matched.newSections]. */
  fun matchThreads(discussions: List<GitLabApi.MrDiscussion>, replies: List<Reply>): Matched {
    val ids = discussions.map { it.id }.toSet()
    val byThread = linkedMapOf<String, Reply>()
    val newSections = mutableListOf<Reply>()
    for (r in replies) {
      if (r.kind == Kind.THREAD && r.threadId in ids) byThread[r.threadId] = r else newSections.add(r)
    }
    return Matched(byThread, newSections)
  }

  /** Rebuild a replies file from a subset of sections (the approval gate output). */
  fun render(iid: Int, branch: String, replies: List<Reply>): String {
    val sb = StringBuilder()
    sb.appendLine("<!-- lgm-replies v1 -->")
    sb.appendLine("# MR !$iid — answers")
    if (branch.isNotBlank()) sb.appendLine("- branch: $branch")
    sb.appendLine()
    for (r in replies) {
      when (r.kind) {
        Kind.THREAD -> sb.appendLine("## thread ${r.threadId}")
        Kind.NEW_ANCHORED -> sb.appendLine("## new ${r.file}:${r.line}")
        Kind.NEW_GENERAL -> sb.appendLine("## new")
      }
      if (r.kind == Kind.THREAD && r.resolve) sb.appendLine("resolve: yes")
      sb.appendLine(r.body)
      sb.appendLine()
    }
    return sb.toString()
  }
}
