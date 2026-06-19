package localgitmirror.idea.deps

import java.io.File

/**
 * A concrete file to ship, plus the cache-relative path it must land at on the
 * dome side. [relativePath] uses '/' separators and is always relative to the
 * ecosystem's cache root (so it is machine-independent).
 */
data class DepFileEntry(
  val coordinate: DepCoordinate,
  val absolutePath: String,   // source file on the WORK machine
  val relativePath: String,   // where it lands under the dome cache root
  val size: Long
)

/** Outcome of resolving what a project needs but cannot fetch locally. */
data class ResolveMissingResult(
  val ok: Boolean,
  val missing: List<DepCoordinate>,
  val log: String,
  val durationMs: Long
)

/** Outcome of installing received files into the local cache. */
data class InstallResult(
  val installed: Int,
  val skipped: Int,
  val invalid: Int,
  val totalBytes: Long,
  val installedLabels: List<String> = emptyList(),
  val skippedLabels: List<String> = emptyList()
)

/**
 * Pluggable dependency ecosystem. One implementation per package manager.
 *
 * The transport (encrypted postbox), packing (ZIP) and UI are shared; only the
 * three ecosystem-specific steps differ:
 *
 *   DOME:  resolveMissing  -> what the project needs but can't fetch here
 *   WORK:  collect         -> pull those coordinates out of the local cache
 *   DOME:  cacheRootFor / install via DepsBundler -> drop files into the cache
 */
interface DepsEcosystem {
  /** Stable id stored in manifests: "gradle" | "npm". */
  val id: String

  /** True if this ecosystem is used by the project at [projectDir]. */
  fun detect(projectDir: File): Boolean

  /**
   * DOME side. Determine which coordinates the project needs that are NOT
   * resolvable from the repositories/registries available on this machine.
   * These are exactly the corporate/internal artifacts to request from work.
   */
  fun resolveMissing(projectDir: File, javaHome: String?): ResolveMissingResult

  /**
   * WORK side. For each requested coordinate, locate the backing file(s) in the
   * local cache and return shippable entries. Coordinates not found locally are
   * reported via [onMissingLocally] so the UI can warn.
   */
  fun collect(
    coordinates: List<DepCoordinate>,
    onMissingLocally: (DepCoordinate) -> Unit = {}
  ): List<DepFileEntry>

  /** The local cache root this ecosystem unpacks into on the dome side. */
  fun cacheRoot(): File

  /**
   * DOME side, optional. Called after files were unpacked into [cacheRoot].
   * Lets an ecosystem do a best-effort "make these usable" step (e.g. npm
   * `cache add`). Returns a short human status; must never throw. Default no-op.
   */
  fun postInstall(installedRelativePaths: List<String>): String = ""
}

object DepsEcosystems {
  /** All known ecosystems, in detection-priority order. */
  fun all(): List<DepsEcosystem> = listOf(GradleEcosystem, NpmEcosystem)

  fun byId(id: String): DepsEcosystem? = all().firstOrNull { it.id == id }

  /** Ecosystems actually present in the project. */
  fun detect(projectDir: File): List<DepsEcosystem> = all().filter { it.detect(projectDir) }
}
