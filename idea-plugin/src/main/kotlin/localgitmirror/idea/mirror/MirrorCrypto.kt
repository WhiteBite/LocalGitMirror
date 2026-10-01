package localgitmirror.idea.mirror

import java.nio.charset.StandardCharsets
import java.util.UUID

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.workkit.BundleCrypto
import localgitmirror.idea.workkit.EnvelopeCrypto
import localgitmirror.idea.workkit.ExchangeCrypto
import localgitmirror.idea.workkit.HybridCrypto
import com.intellij.openapi.components.service

internal object MirrorCrypto {
  // v3: pinned server key => ephemeral X25519 sealing; no key => legacy password envelope.

  /** Pinned server public key (32 raw bytes), or null => legacy password mode. */
  fun pinnedServerPub(): ByteArray? = try {
    val b64 = service<MirrorSettingsService>().state.serverPubKeyB64
    if (b64.isBlank()) null else HybridCrypto.decodeServerPub(b64)
  } catch (_: Throwable) {
    null
  }

  /** True when a server public key is pinned — i.e. v3 mode is active. */
  fun isV3Pinned(): Boolean = pinnedServerPub() != null

  /** Per-call codec: hybrid session when pinned, else password envelope. */
  class Codec(val session: HybridCrypto.Session?, val password: String) {
    val epkB64: String? get() = session?.epkB64

    fun sealEnvelope(obj: JsonObject): String =
      session?.sealEnvelope(obj.toString()) ?: EnvelopeCrypto.encryptJson(obj, password)

    fun openEnvelopeStr(e: String): String =
      session?.openEnvelope(e) ?: EnvelopeCrypto.decrypt(e, password)

    fun openEnvelopeJson(e: String): JsonObject =
      session?.let { Json.parseToJsonElement(it.openEnvelope(e)).jsonObject }
        ?: EnvelopeCrypto.decryptJson(e, password)

    /**
     * Turn the response "d" field into the bytes to write to disk.
     * Hybrid: open the sealed bundle → raw git bundle (plaintext). Password:
     * pass the encrypted dump through unchanged (downstream decrypts it).
     */
    fun bundleToDisk(rawD: ByteArray): ByteArray = session?.openBundle(rawD) ?: rawD

    fun wipe() = session?.wipe()
  }

  fun beginCall(password: String): Codec {
    val pub = pinnedServerPub()
    return Codec(if (pub != null) HybridCrypto.Session.create(pub) else null, password)
  }

  /** JSON request body: {"epk":"..","e":".."} in hybrid mode, else {"e":".."}. */
  fun envelopeBody(codec: Codec, e: String): String {
    val epk = codec.epkB64
    return if (epk != null) "{\"epk\":\"$epk\",\"e\":\"$e\"}" else "{\"e\":\"$e\"}"
  }

  /** Payload sealed for upload: v3 relay (epk set) or legacy password bundle (epk null). */
  data class SealedPublication(val epkB64: String?, val bytes: ByteArray,
                               /** v3 only: relay-sealed routing metadata {"path","plain_size"} for the postbox. */
                               val meta: ByteArray? = null)

  /** Seal a vault publication: v3 relay to the pinned server key, else the shared-password bundle. */
  fun sealVaultPublication(zipBytes: ByteArray, syncPassword: String): SealedPublication =
    sealRelayPayload(zipBytes, syncPassword, pinnedServerPub(), HybridCrypto.RELAY_AAD_VAULT)

  /** Seal a deps manifest (req AAD) or response ZIP (resp AAD) for upload. */
  fun sealDepsPayload(plaintext: ByteArray, syncPassword: String, aad: ByteArray): SealedPublication =
    sealRelayPayload(plaintext, syncPassword, pinnedServerPub(), aad)

  /** Seal a postbox file body plus its routing metadata: v3 relay to the pinned server key, else the shared-password bundle. */
  fun sealPostboxPayload(plaintext: ByteArray, syncPassword: String,
                         displayPath: String, plainSize: Long): SealedPublication =
    sealPostboxPayload(plaintext, syncPassword, pinnedServerPub(), displayPath, plainSize)

  internal fun sealPostboxPayload(plaintext: ByteArray, syncPassword: String,
                                  serverPub: ByteArray?, displayPath: String,
                                  plainSize: Long): SealedPublication {
    if (serverPub == null) {
      return SealedPublication(null, BundleCrypto.encryptBundleBytes(plaintext, syncPassword))
    }
    val session = HybridCrypto.Session.create(serverPub)
    try {
      val meta = buildJsonObject {
        put("path", displayPath)
        put("plain_size", plainSize)
      }.toString().toByteArray(StandardCharsets.UTF_8)
      return SealedPublication(
        session.epkB64,
        session.sealRelay(plaintext, HybridCrypto.RELAY_AAD_POSTBOX),
        session.sealRelay(meta, HybridCrypto.RELAY_AAD_POSTBOX),
      )
    } finally {
      session.wipe()
    }
  }

  /** Routing pair for [MirrorPostboxApi.fileSyncUpload]: the display path travels as path_enc with a password, inside the sealed meta in v3, in the clear only with neither. */
  fun postboxRoute(displayPath: String, syncPassword: String): Pair<String, String?> =
    if (syncPassword.isBlank() && pinnedServerPub() == null) displayPath to null
    else "x/${UUID.randomUUID().toString().take(8)}" to
      (if (syncPassword.isBlank()) null else ExchangeCrypto.encryptHint(displayPath, syncPassword))

  internal fun sealRelayPayload(
    plaintext: ByteArray,
    syncPassword: String,
    serverPub: ByteArray?,
    aad: ByteArray
  ): SealedPublication {
    if (serverPub == null) {
      return SealedPublication(null, BundleCrypto.encryptBundleBytes(plaintext, syncPassword))
    }
    val session = HybridCrypto.Session.create(serverPub)
    try {
      return SealedPublication(session.epkB64, session.sealRelay(plaintext, aad))
    } finally {
      session.wipe()
    }
  }

  /** Seal a clipboard buffer body: v3 relay to the pinned server key, else the shared-password bundle. */
  fun sealBufferPayload(plaintext: ByteArray, syncPassword: String): SealedPublication =
    sealRelayPayload(plaintext, syncPassword, pinnedServerPub(), HybridCrypto.RELAY_AAD_BUFFER)

  /** Wire body for [MirrorBufferApi.bufferPut]: `k` rides along only in v3 mode, `hint` only when non-blank. */
  internal fun buildBufferPutJson(
    ciphertextB64: String,
    hintEnc: String,
    pinned: Boolean,
    epkB64: String?,
    hint: String
  ): String = buildJsonObject {
    put("ciphertext_b64", ciphertextB64)
    put("hint_enc", hintEnc)
    put("pinned", pinned)
    if (epkB64 != null) put("k", epkB64)
    if (hint.isNotBlank()) put("hint", hint)
  }.toString()
}
