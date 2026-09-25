package localgitmirror.idea.gitlab

/**
 * Parser for the agent's reply file `.mr-notes/replies-!N.md` (format v1,
 * documented in INDEX.md). Three section kinds:
 *
 *   ## thread <discussionId>   reply into an existing GitLab discussion
 *   ## new <file>:<line>       brand-new code-anchored discussion
 *   ## new                     brand-new general MR note
 *
 * Optional `resolve: yes|no` line inside a section closes the thread after a
 * successful reply (threads only). Sections with an empty body or a malformed
 * header are collected into [RepliesFile.errors] and never sent.
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

  private val headerRe = Regex("^#\\s+MR\\s+!(\\d+)")
  private val threadRe = Regex("^##\\s+thread\\s+(\\S+)\\s*$")
  private val anchoredRe = Regex("^##\\s+new\\s+(\\S+?):(\\d+)\\s*$")
  private val generalRe = Regex("^##\\s+new\\s*$")
  private val resolveRe = Regex("^resolve:\\s*(yes|no)\\s*$", RegexOption.IGNORE_CASE)

  fun parse(markdown: String): RepliesFile {
    var iid = 0
    var branch = ""
    val replies = mutableListOf<Reply>()
    val errors = mutableListOf<String>()

    var kind: Kind? = null
    var threadId = ""
    var file = ""
    var line = 0
    var resolve = false
    var resolveSeen = false
    val body = StringBuilder()
    var sectionNo = 0

    fun flush() {
      val k = kind ?: return
      sectionNo++
      val text = body.toString().trim()
      when {
        text.isEmpty() -> errors.add("section $sectionNo: empty body, skipped")
        k == Kind.THREAD && threadId.isBlank() -> errors.add("section $sectionNo: blank thread id, skipped")
        k == Kind.NEW_ANCHORED && (file.isBlank() || line <= 0) -> errors.add("section $sectionNo: bad file:line, skipped")
        else -> replies.add(Reply(k, threadId, file, line, resolve && k == Kind.THREAD, text))
      }
      kind = null
      threadId = ""
      file = ""
      line = 0
      resolve = false
      resolveSeen = false
      body.setLength(0)
    }

    for (raw in markdown.lines()) {
      val lineText = raw.trimEnd()
      headerRe.find(lineText)?.let { iid = it.groupValues[1].toIntOrNull() ?: iid }
      if (lineText.startsWith("- branch:")) {
        branch = lineText.substringAfter("- branch:").trim()
        continue
      }
      val threadM = threadRe.find(lineText)
      val anchoredM = anchoredRe.find(lineText)
      val generalM = generalRe.find(lineText)
      if (threadM != null || anchoredM != null || generalM != null) {
        flush()
        when {
          threadM != null -> {
            kind = Kind.THREAD
            threadId = threadM.groupValues[1]
          }
          anchoredM != null -> {
            kind = Kind.NEW_ANCHORED
            file = anchoredM.groupValues[1]
            line = anchoredM.groupValues[2].toIntOrNull() ?: 0
          }
          else -> kind = Kind.NEW_GENERAL
        }
        continue
      }
      if (kind == null) continue
      val resolveM = resolveRe.find(lineText)
      if (resolveM != null && !resolveSeen && body.toString().isBlank()) {
        resolve = resolveM.groupValues[1].equals("yes", ignoreCase = true)
        resolveSeen = true
        continue
      }
      body.appendLine(lineText)
    }
    flush()

    return RepliesFile(iid, branch, replies, errors)
  }

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
