package localgitmirror.idea.gitlab

/** Marker contract for closed/merged MRs (mr-notes/mr-!N.closed.md), parity with lgm_core. */
object MrClosedMarker {

  const val HEAD = "<!-- lgm-mr-closed v1 -->"

  data class Data(val iid: Int, val title: String, val branch: String, val detected: String)

  fun iidOf(path: String): Int? =
    Regex("mr-!(\\d+)\\.closed\\.md$").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()

  fun render(iid: Int, title: String, branch: String, detected: String): String =
    "$HEAD\n" +
      "# MR !$iid — ${title.ifBlank { "MR !$iid" }}\n" +
      "- **Ветка:** `$branch`\n" +
      "- **Статус:** закрыт или влит (нет в списке открытых)\n" +
      "- **Обнаружено:** $detected\n"

  fun parse(markdown: String): Data? {
    if (!markdown.startsWith(HEAD)) return null
    var iid = 0
    var title = ""
    var branch = ""
    var detected = ""
    for (line in markdown.lineSequence()) {
      val t = TITLE.find(line)
      if (t != null) {
        iid = t.groupValues[1].toIntOrNull() ?: 0
        title = t.groupValues[2].trim()
        continue
      }
      val b = BRANCH.find(line)
      if (b != null) {
        branch = b.groupValues[1]
        continue
      }
      val d = DETECTED.find(line)
      if (d != null) detected = d.groupValues[1]
    }
    if (iid == 0) return null
    return Data(iid, title, branch, detected)
  }

  private val TITLE = Regex("^# MR !(\\d+) — (.*)$")
  private val BRANCH = Regex("^- \\*\\*Ветка:\\*\\* `([^`]+)`")
  private val DETECTED = Regex("^- \\*\\*Обнаружено:\\*\\* (\\S+)")
}