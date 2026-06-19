package localgitmirror.idea.deps

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Ecosystem-agnostic dependency coordinate.
 *
 * For Gradle:  group = "com.foojay", name = "foojay-resolver", version = "0.8.0"
 * For npm:     group = "@corp" (scope, may be ""), name = "ui-kit", version = "2.3.1"
 *
 * The [classifier] disambiguates artifacts that share g:n:v but differ in
 * type (e.g. gradle jar vs aar vs the module metadata). Empty for npm.
 */
data class DepCoordinate(
  val ecosystem: String,   // "gradle" | "npm"
  val group: String,
  val name: String,
  val version: String,
  val classifier: String = ""
) {
  /** Machine-independent identity used for matching across machines. */
  val key: String get() = buildString {
    append(ecosystem); append('|')
    append(group); append(':')
    append(name); append(':')
    append(version)
    if (classifier.isNotEmpty()) { append(':'); append(classifier) }
  }

  /** Human label, e.g. "com.foojay:foojay-resolver:0.8.0" or "@corp/ui-kit@2.3.1". */
  val label: String get() = when (ecosystem) {
    "npm" -> (if (group.isNotEmpty()) "$group/$name" else name) + "@" + version
    else -> "$group:$name:$version"
  }
}

/**
 * The "I'm missing these" payload the DOME side sends.
 *
 * v2 (this) replaces the old v1 "here's everything I have" manifest. The dome
 * computes exactly what it can't resolve locally, so the work side does zero
 * classification — it just looks each coordinate up in its cache and ships it.
 */
data class DepsRequestManifest(
  val version: Int = 2,
  val requester: String = "",
  val project: String = "",
  val ecosystem: String = "",
  val missing: List<DepCoordinate> = emptyList()
) {
  companion object {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun toJsonBytes(m: DepsRequestManifest): ByteArray =
      gson.toJson(m).toByteArray(Charsets.UTF_8)

    fun fromJsonBytes(bytes: ByteArray): DepsRequestManifest =
      gson.fromJson(String(bytes, Charsets.UTF_8), DepsRequestManifest::class.java)
        ?: DepsRequestManifest()
  }

  fun keys(): Set<String> = missing.map { it.key }.toSet()
}
