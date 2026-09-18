package localgitmirror.idea.workkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExchangeMetaTest {

  @Test
  fun `hint json roundtrip through encryption keeps side and text`() {
    val json = ExchangeMeta.hintJson("hello world\nsecond line")
    val encrypted = ExchangeCrypto.encryptHint(json, "pw")
    val meta = ExchangeMeta.parseHint(ExchangeCrypto.decryptHint(encrypted, "pw"))

    assertEquals(ExchangeMeta.SIDE_PLUGIN, meta.side)
    assertEquals("hello world", meta.text)
  }

  @Test
  fun `hint override replaces the first-line preview`() {
    val meta = ExchangeMeta.parseHint(ExchangeMeta.hintJson("body", "idea.log tail"))
    assertEquals("idea.log tail", meta.text)
  }

  @Test
  fun `hint truncates to 80 chars`() {
    val meta = ExchangeMeta.parseHint(ExchangeMeta.hintJson("x".repeat(200)))
    assertEquals(80, meta.text.length)
  }

  @Test
  fun `name json roundtrip keeps side and real name`() {
    val meta = ExchangeMeta.parseName(ExchangeMeta.nameJson("отчёт \"final\".png"))
    assertEquals(ExchangeMeta.SIDE_PLUGIN, meta.side)
    assertEquals("отчёт \"final\".png", meta.text)
  }

  @Test
  fun `legacy bare hint parses as unknown side`() {
    val meta = ExchangeMeta.parseHint("just a plaintext hint")
    assertNull(meta.side)
    assertEquals("just a plaintext hint", meta.text)
  }

  @Test
  fun `bare mr-notes path stays intact for the review read path`() {
    val meta = ExchangeMeta.parseName("mr-notes/mr-!42.md")
    assertNull(meta.side)
    assertEquals("mr-notes/mr-!42.md", meta.text)
  }

  @Test
  fun `json without required keys falls back to the whole string`() {
    val meta = ExchangeMeta.parseHint("""{"s":"w"}""")
    assertNull(meta.side)
    assertEquals("""{"s":"w"}""", meta.text)
  }

  @Test
  fun `web side marker is recognized`() {
    val meta = ExchangeMeta.parseHint("""{"s":"h","h":"from web"}""")
    assertEquals(ExchangeMeta.SIDE_WEB, meta.side)
    assertEquals("from web", meta.text)
  }

  @Test
  fun `multiline body preview takes the first non-empty representation`() {
    val meta = ExchangeMeta.parseHint(ExchangeMeta.hintJson("  padded first line  \nrest"))
    assertEquals("padded first line", meta.text)
    assertTrue(meta.text.length <= 80)
  }
}
