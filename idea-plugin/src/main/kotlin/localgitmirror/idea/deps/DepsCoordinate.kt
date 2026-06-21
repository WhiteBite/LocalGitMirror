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
 * One file the DOME already has cached locally.
 *
 * The (sha1, fileName) pair uniquely identifies a single file under
 * `<gradle-cache>/<g>/<n>/<v>/<sha1>/<fileName>` — gradle's own content layout.
 *
 * The work side uses this to filter what it ships: if the dome reports the
 * same (g, n, v, sha1, fileName) as something the work cache holds, we skip
 * that file entirely. Any same-coordinate file with a *different* sha1 means
 * the cached bytes differ (re-release) and should still ship.
 *
 * `ecosystem` is implicit — the manifest's `ecosystem` field already covers it.
 */
data class PresentArtifact(
  val g: String,
  val n: String,
  val v: String,
  val sha1: String,
  val fileName: String
) {
  /** "g:n:v" — same shape as DepCoordinate.label for gradle, used as a Map key. */
  val coordKey: String get() = "$g:$n:$v"

  /** "<sha1>/<fileName>" — uniquely identifies the on-disk file under the coord dir. */
  val fileKey: String get() = "$sha1/$fileName"
}

/**
 * Manifest the DOME side sends to the WORK side.
 *
 * v3 (current — minimum-traffic protocol):
 *   - `missing` is the set of coordinates the dome cannot resolve locally
 *     (gradle reported them as unresolvable in --offline mode, after subtracting
 *     own subprojects and locally-cached g:n:v).
 *   - `present` is an exhaustive enumeration of every cache file the dome
 *     already has under any coordinate that *might* appear in the response.
 *     The work side uses this to suppress files the dome doesn't need to
 *     receive again, which is what makes the response small in practice.
 *
 * Earlier versions (v1 = "everything I have", v2 = "missing only, no present
 * suppression") are intentionally NOT supported. The plugin has a single user
 * and we'd rather force a re-build than carry compatibility shims.
 */
data class DepsRequestManifest(
  val version: Int = 3,
  val requester: String = "",
  val project: String = "",
  val ecosystem: String = "",
  val missing: List<DepCoordinate> = emptyList(),
  val present: List<PresentArtifact> = emptyList()
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

  /**
   * Index `present` by g:n:v → set of "<sha1>/<fileName>" — the shape the work
   * side needs for O(1) "does the dome already have THIS file?" lookups during
   * collect().
   */
  fun presentIndex(): Map<String, Set<String>> {
    if (present.isEmpty()) return emptyMap()
    val out = HashMap<String, MutableSet<String>>()
    for (p in present) {
      out.getOrPut(p.coordKey) { HashSet() }.add(p.fileKey)
    }
    return out
  }
}
