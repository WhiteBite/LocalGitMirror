package localgitmirror.idea.mirror

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UploadResponseEnvelopeTest {

  @Test
  fun `envelope body opens to the inner json`() {
    val codec = MirrorCrypto.Codec(null, "test-pw")
    val sealed = codec.sealEnvelope(buildJsonObject { put("success", JsonPrimitive(false)) })
    val body = """{"e":"$sealed"}"""

    val opened = MirrorSyncApi.openEnvelopeBody(body, codec)

    assertNotNull(opened)
    val inner = Json.parseToJsonElement(opened).jsonObject
    assertEquals(false, inner["success"]?.jsonPrimitive?.booleanOrNull)
  }

  @Test
  fun `non envelope body returns null`() {
    val codec = MirrorCrypto.Codec(null, "test-pw")
    assertNull(MirrorSyncApi.openEnvelopeBody("""{"ok":1}""", codec))
    assertNull(MirrorSyncApi.openEnvelopeBody("not json", codec))
    assertNull(MirrorSyncApi.openEnvelopeBody("""{"e":"garbage"}""", codec))
    assertNull(MirrorSyncApi.openEnvelopeBody("", codec))
  }

  @Test
  fun `envelope sealed by a different password is not opened`() {
    val codec = MirrorCrypto.Codec(null, "test-pw")
    val other = MirrorCrypto.Codec(null, "other-pw")
    val sealed = other.sealEnvelope(buildJsonObject { put("success", JsonPrimitive(false)) })

    assertNull(MirrorSyncApi.openEnvelopeBody("""{"e":"$sealed"}""", codec))
  }
}
