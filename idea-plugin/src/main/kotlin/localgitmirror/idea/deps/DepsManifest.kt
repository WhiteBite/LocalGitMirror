package localgitmirror.idea.deps

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Wire format for the encrypted manifest blob.
 *
 * The dome side serialises a manifest of artifacts ALREADY PRESENT in its
 * Gradle cache and posts it to the server. The work side downloads, parses,
 * computes the diff (work - dome), and sends back the missing files.
 */
data class DepsManifest(
  val version: Int = 1,
  val requester: String = "",          // human-readable origin tag, e.g. "DOM"
  val project: String = "",            // repo / project name
  val artifacts: List<Entry> = emptyList()
) {
  data class Entry(
    val g: String,    // group
    val n: String,    // name
    val v: String,    // version
    val sha: String,  // sha1
    val f: String     // filename (helps detect classifier/extension differences)
  ) {
    /** Stable identity used for diff. */
    val key: String get() = "$g:$n:$v:$sha:$f"
  }

  companion object {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun fromArtifacts(requester: String, project: String, artifacts: List<DepsScanner.Artifact>): DepsManifest =
      DepsManifest(
        version = 1,
        requester = requester,
        project = project,
        artifacts = artifacts.map { Entry(it.group, it.name, it.version, it.sha1, it.fileName) }
      )

    fun toJsonBytes(m: DepsManifest): ByteArray = gson.toJson(m).toByteArray(Charsets.UTF_8)

    fun fromJsonBytes(bytes: ByteArray): DepsManifest =
      gson.fromJson(String(bytes, Charsets.UTF_8), DepsManifest::class.java)
        ?: DepsManifest()
  }

  /** Returns the set of artifact keys the dome side already has. */
  fun keys(): Set<String> = artifacts.map { it.key }.toSet()
}

/**
 * Compute the artifacts the WORK side needs to send.
 *
 * @param workArtifacts everything in the work cache
 * @param domeManifest  what the dome already has
 * @param internalRepoSubstrings if non-empty, restricts to artifacts that
 *        came from an "internal" repo (Nexus etc.) — Maven Central artifacts
 *        the dome can fetch on its own are skipped.
 */
object DepsDiff {
  fun compute(
    workArtifacts: List<DepsScanner.Artifact>,
    domeManifest: DepsManifest,
    internalRepoSubstrings: List<String>
  ): List<DepsScanner.Artifact> {
    val have = domeManifest.keys()
    return workArtifacts.filter { art ->
      val key = "${art.group}:${art.name}:${art.version}:${art.sha1}:${art.fileName}"
      key !in have && DepsScanner.matchesInternalRepo(art, internalRepoSubstrings)
    }
  }
}
