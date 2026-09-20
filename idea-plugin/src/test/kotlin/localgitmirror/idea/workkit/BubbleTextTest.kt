package localgitmirror.idea.workkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BubbleTextTest {

  @Test
  fun `short text passes through unchanged`() {
    val text = "привет\nмир"
    val c = BubbleText.clamp(text)
    assertFalse(c.truncated)
    assertEquals(text, c.text)
  }

  @Test
  fun `eight lines within char budget are not truncated`() {
    val text = (1..8).joinToString("\n") { "строка $it" }
    assertFalse(BubbleText.clamp(text).truncated)
  }

  @Test
  fun `nine lines truncate to a six-line preview with ellipsis`() {
    val text = (1..20).joinToString("\n") { "L$it" }
    val c = BubbleText.clamp(text)
    assertTrue(c.truncated)
    assertEquals("L1\nL2\nL3\nL4\nL5\nL6" + BubbleText.ELLIPSIS, c.text)
  }

  @Test
  fun `exactly 400 chars stay, 401 truncate`() {
    assertFalse(BubbleText.clamp("x".repeat(400)).truncated)
    assertTrue(BubbleText.clamp("x".repeat(401)).truncated)
  }

  @Test
  fun `long single line cuts at 300 chars hard when there are no spaces`() {
    val c = BubbleText.clamp("kryptonite.onfluence.service.sync;".repeat(30))
    assertTrue(c.truncated)
    assertEquals(BubbleText.PREVIEW_CHARS, c.text.removeSuffix(BubbleText.ELLIPSIS).length)
  }

  @Test
  fun `long wordy text cuts at a word boundary`() {
    val text = buildString {
      var i = 0
      while (length < 900) {
        append("слово").append(i).append(' ')
        i++
      }
    }
    val c = BubbleText.clamp(text)
    assertTrue(c.truncated)
    val preview = c.text.removeSuffix(BubbleText.ELLIPSIS)
    assertTrue(preview.length <= BubbleText.PREVIEW_CHARS)
    assertTrue(text.startsWith(preview))
    assertEquals(' ', text[preview.length])
  }

  @Test
  fun `preview is a strict prefix of the original plus ellipsis`() {
    val text = (1..320).joinToString("\n") { i ->
      if (i % 2 == 0) "    val строка$i = kryptonite.onfluence.service.sync$i;"
      else "fun метод$i(): String = \"текст $i\""
    }
    val c = BubbleText.clamp(text)
    assertTrue(c.truncated)
    assertTrue(c.text.endsWith(BubbleText.ELLIPSIS))
    val preview = c.text.removeSuffix(BubbleText.ELLIPSIS)
    assertTrue(text.startsWith(preview))
    assertTrue(preview.length < text.length)
    assertTrue(preview.lines().size <= BubbleText.PREVIEW_LINES)
  }

  @Test
  fun `cutAtWord keeps text at or below the limit`() {
    assertEquals("abc", BubbleText.cutAtWord("abc", 10))
    assertEquals("ab", BubbleText.cutAtWord("ab cd", 2))
    assertEquals("ab cd", BubbleText.cutAtWord("ab cd ef", 6))
  }

  @Test
  fun `cutAtWord prefers the last space before the limit`() {
    val cut = BubbleText.cutAtWord("one two three four", 13)
    assertEquals("one two three", cut)
  }

  @Test
  fun `cutAtWord prefers a newline boundary over an earlier space`() {
    val cut = BubbleText.cutAtWord("ab cdef\nghij", 9)
    assertEquals("ab cdef", cut)
  }
}
