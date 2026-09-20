package localgitmirror.idea.workkit

/**
 * Deterministic clamp for chat text bubbles: a message longer than [MAX_LINES]
 * hard lines or [MAX_CHARS] characters renders as a preview — first
 * [PREVIEW_LINES] lines, cut at a word boundary within [PREVIEW_CHARS] — plus
 * an ellipsis. Pure string logic so the preview is stable and unit-testable.
 */
object BubbleText {
  const val MAX_LINES = 8
  const val MAX_CHARS = 400
  const val PREVIEW_LINES = 6
  const val PREVIEW_CHARS = 300
  const val ELLIPSIS = "\u2026"

  data class Clamped(val text: String, val truncated: Boolean)

  fun clamp(text: String): Clamped {
    val lines = text.lines()
    if (lines.size <= MAX_LINES && text.length <= MAX_CHARS) return Clamped(text, false)
    var preview = lines.take(PREVIEW_LINES).joinToString("\n")
    if (preview.length > PREVIEW_CHARS) preview = cutAtWord(preview, PREVIEW_CHARS)
    return Clamped(preview + ELLIPSIS, true)
  }

  /** Cut at the last space/newline at or before [limit]; hard-cut when there is none. */
  fun cutAtWord(text: String, limit: Int): String {
    if (text.length <= limit) return text
    val boundary = maxOf(text.lastIndexOf(' ', limit), text.lastIndexOf('\n', limit))
    return if (boundary > 0) text.substring(0, boundary).trimEnd() else text.substring(0, limit)
  }
}
