package localgitmirror.idea.workkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Side-marker convention for exchange metadata. The plaintext that gets
 * encrypted into hint_enc / path_enc is a JSON object {"s":<side>, "h"|"n":<text>};
 * "w" marks the IDEA plugin (work side), "h" the web client (home side).
 * Anything that is not parseable JSON with the required keys is legacy:
 * the whole decrypted string is the text and the side is unknown.
 * The server never sees the plaintext, so old servers stay compatible.
 */
object ExchangeMeta {
  const val SIDE_PLUGIN = "w"
  const val SIDE_WEB = "h"

  data class Meta(val side: String?, val text: String)

  private val json = Json { ignoreUnknownKeys = true }

  fun hintJson(body: String, hintOverride: String? = null, side: String = SIDE_PLUGIN): String {
    val h = hintOverride ?: body.lineSequence().firstOrNull()?.trim()?.take(80) ?: ""
    return encode(side, "h", h)
  }

  fun nameJson(realName: String, side: String = SIDE_PLUGIN): String = encode(side, "n", realName)

  /** "w" for the work machine, "h" for home — the plugin stamps its detected role, not its client type. */
  fun sideOfRole(isWork: Boolean): String = if (isWork) SIDE_PLUGIN else SIDE_WEB

  fun parseHint(plain: String): Meta = decode(plain, "h")

  fun parseName(plain: String): Meta = decode(plain, "n")

  private fun encode(side: String, key: String, text: String): String =
    buildJsonObject {
      put("s", side)
      put(key, text)
    }.toString()

  private fun decode(plain: String, key: String): Meta {
    val obj = runCatching { json.parseToJsonElement(plain) as? JsonObject }.getOrNull()
    val side = obj?.get("s")?.jsonPrimitive?.content
    val text = obj?.get(key)?.jsonPrimitive?.content
    if (obj != null && side != null && text != null) return Meta(side, text)
    return Meta(null, plain)
  }
}
