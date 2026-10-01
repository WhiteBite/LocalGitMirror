package localgitmirror.idea.ui.exchange

internal const val EMPTY_HINT_MARK = "\u2022\u2022\u2022"

/** One message of the Exchange chat: either a buffer entry or a repo postbox file. */
internal data class ExchangeItem(
  val kind: Kind,
  val id: String,
  val ts: Double,
  val size: Long,
  val title: String,
  val pinned: Boolean,
  val displayPath: String,
  val repo: String,
  val side: Side = Side.UNKNOWN,
  val state: State = State.SENT,
  val serverId: String? = null,
  val localText: String? = null,
  val localFile: String? = null,
  val localTemp: Boolean = false,
) {
  enum class Kind { BUFFER, FILE }
  enum class Side { WORK, HOME, UNKNOWN }
  enum class State { PENDING, SENT, FAILED }

  val isEcho: Boolean get() = id.startsWith("local-")
  val effectiveId: String get() = serverId ?: id
  val canOpen: Boolean get() = !isEcho || serverId != null

  private val ext: String get() = displayPath.substringAfterLast('.', "").lowercase()
  val isMrNotes: Boolean get() = displayPath.startsWith("mr-notes/") && ext == "md"
  val isImage: Boolean get() = ext in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
  val isLog: Boolean get() = ext == "log"
  val isText: Boolean
    get() = isLog || ext in setOf(
      "txt", "md", "json", "xml", "yaml", "yml", "properties", "csv", "kt", "java",
      "py", "ts", "js", "html", "css", "sh", "bat", "ini", "toml", "sql", "gradle", "kts"
    )

  companion object {
    fun isImageFileName(name: String): Boolean =
      name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
  }
}
