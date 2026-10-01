package localgitmirror.idea.mirror

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MirrorApiBufferPutJsonTest {

  @Test
  fun `v3 body carries k and hint`() {
    val json = MirrorCrypto.buildBufferPutJson("Q0lQSEVS", "aGludA==", pinned = true, epkB64 = "ZXBr", hint = "превью")
    assertTrue(json.contains("\"k\":\"ZXBr\""), "raw body must embed the ephemeral key: $json")
    val o = Json.parseToJsonElement(json).jsonObject
    assertEquals("Q0lQSEVS", o.getValue("ciphertext_b64").jsonPrimitive.content)
    assertEquals("aGludA==", o.getValue("hint_enc").jsonPrimitive.content)
    assertEquals(true, o.getValue("pinned").jsonPrimitive.booleanOrNull)
    assertEquals("ZXBr", o.getValue("k").jsonPrimitive.content)
    assertEquals("превью", o.getValue("hint").jsonPrimitive.content)
  }

  @Test
  fun `legacy body omits k and blank hint`() {
    val json = MirrorCrypto.buildBufferPutJson("Q0lQSEVS", "aGludA==", pinned = false, epkB64 = null, hint = "")
    val o = Json.parseToJsonElement(json).jsonObject
    assertNull(o["k"])
    assertNull(o["hint"])
    assertEquals("Q0lQSEVS", o.getValue("ciphertext_b64").jsonPrimitive.content)
    assertEquals(false, o.getValue("pinned").jsonPrimitive.booleanOrNull)
  }

  @Test
  fun `hint with quote and backslash is escaped`() {
    val hint = """pre"view\path"""
    val json = MirrorCrypto.buildBufferPutJson("Q0lQSEVS", "", pinned = false, epkB64 = null, hint = hint)
    val o = Json.parseToJsonElement(json).jsonObject
    assertEquals(hint, o.getValue("hint").jsonPrimitive.content)
  }

  @Test
  fun `hint_enc with json-breaking characters is escaped`() {
    val hintEnc = """enc"quoted\slash"""
    val json = MirrorCrypto.buildBufferPutJson("Q0lQSEVS", hintEnc, pinned = false, epkB64 = null, hint = "")
    val o = Json.parseToJsonElement(json).jsonObject
    assertEquals(hintEnc, o.getValue("hint_enc").jsonPrimitive.content)
  }
}
