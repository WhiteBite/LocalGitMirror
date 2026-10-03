package localgitmirror.idea.mirror

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MirrorSyncApiExportBinaryModeTest {

  private val codec = MirrorCrypto.Codec(null, "test-password")

  @Test
  fun `octet-stream with env header is binary`() {
    assertTrue(MirrorSyncApi.isBinaryExportResponse("application/octet-stream", "abc"))
  }

  @Test
  fun `json without env header stays legacy`() {
    assertFalse(MirrorSyncApi.isBinaryExportResponse("application/json", null))
  }

  @Test
  fun `env header presence decides binary even without octet-stream content type`() {
    assertTrue(MirrorSyncApi.isBinaryExportResponse(null, "abc"))
  }

  @Test
  fun `octet-stream with charset is binary`() {
    assertTrue(MirrorSyncApi.isBinaryExportResponse("application/octet-stream; charset=binary", null))
  }

  @Test
  fun `missing content type and missing header stays legacy`() {
    assertFalse(MirrorSyncApi.isBinaryExportResponse(null, null))
  }

  @Test
  fun `blank env header does not flip legacy json to binary`() {
    assertFalse(MirrorSyncApi.isBinaryExportResponse("application/json", "   "))
  }

  @Test
  fun `sealed envelope header opens to status head repo`() {
    val e = codec.sealEnvelope(buildJsonObject {
      put("status", "ok")
      put("head", "deadbeefcafe")
      put("repo", "myrepo")
    })

    val inner = MirrorSyncApi.parseExportEnvelopeHeader(e, codec)

    assertNotNull(inner)
    assertEquals("ok", inner["status"]?.jsonPrimitive?.contentOrNull)
    assertEquals("deadbeefcafe", inner["head"]?.jsonPrimitive?.contentOrNull)
    assertEquals("myrepo", inner["repo"]?.jsonPrimitive?.contentOrNull)
  }

  @Test
  fun `no_content envelope header opens like any other status`() {
    val e = codec.sealEnvelope(buildJsonObject {
      put("status", "no_content")
      put("head", "feedface")
      put("repo", "myrepo")
    })

    val inner = MirrorSyncApi.parseExportEnvelopeHeader(e, codec)

    assertNotNull(inner)
    assertEquals("no_content", inner["status"]?.jsonPrimitive?.contentOrNull)
  }

  @Test
  fun `garbage envelope header parses to null`() {
    assertNull(MirrorSyncApi.parseExportEnvelopeHeader("not-a-sealed-envelope-###", codec))
  }

  @Test
  fun `envelope sealed under another password parses to null`() {
    val other = MirrorCrypto.Codec(null, "other-password")
    val e = other.sealEnvelope(buildJsonObject { put("status", "ok") })

    assertNull(MirrorSyncApi.parseExportEnvelopeHeader(e, codec))
  }

  @Test
  fun `blank and null envelope header parse to null`() {
    assertNull(MirrorSyncApi.parseExportEnvelopeHeader("", codec))
    assertNull(MirrorSyncApi.parseExportEnvelopeHeader(null, codec))
  }
}
