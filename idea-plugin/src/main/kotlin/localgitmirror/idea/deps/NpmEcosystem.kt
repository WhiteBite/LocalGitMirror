package localgitmirror.idea.deps

import java.io.File

/**
 * npm implementation of [DepsEcosystem].
 *
 * Machine-independent signal: the committed `package-lock.json`. Every locked
 * package records a `resolved` URL and an `integrity` (sha512/sha1). Packages
 * whose `resolved` host is NOT a public registry are corporate-only — exactly
 * what the dome can't fetch and must request from work.
 *
 * Cross-machine identity: npm's cache (`_cacache/content-v2/<algo>/<hex>`) is
 * content-addressed by the same `integrity` value that's in the lockfile, so
 * the work side can locate each tarball with zero network and zero npm run.
 * The integrity is carried in [DepCoordinate.classifier].
 *
 * Install target: a flat offline tarball mirror under the user's home
 * (`~/.lgm-npm-offline/<name>/<name>-<version>.tgz`). The dome consumes it by
 * pointing npm at the folder (see the install notification / README).
 */
object NpmEcosystem : DepsEcosystem {
  override val id: String = "npm"

  override fun detect(projectDir: File): Boolean =
    File(projectDir, "package.json").isFile

  // ── DOME: what does the lockfile pin to a corporate registry? ───────────────

  override fun resolveMissing(projectDir: File, javaHome: String?): ResolveMissingResult {
    val started = System.currentTimeMillis()

    // lockfile priority npm → pnpm → yarn; first found wins, same logic and shape
    val npmLock = firstExisting(projectDir, "package-lock.json", "npm-shrinkwrap.json")
    val pnpmLock = File(projectDir, "pnpm-lock.yaml").takeIf { it.isFile }
    val yarnLock = File(projectDir, "yarn.lock").takeIf { it.isFile }

    val lock: File
    val parse: (String) -> List<DepCoordinate>
    when {
      npmLock != null -> { lock = npmLock; parse = NpmLockfiles::parseLockfileForCorporate }
      pnpmLock != null -> { lock = pnpmLock; parse = NpmLockfiles::parsePnpmLockForCorporate }
      yarnLock != null -> { lock = yarnLock; parse = NpmLockfiles::parseYarnLockForCorporate }
      else -> return ResolveMissingResult(
        false, emptyList(),
        "no lockfile (package-lock.json / pnpm-lock.yaml / yarn.lock) found in " +
          "${projectDir.absolutePath} (run an install first)",
        System.currentTimeMillis() - started
      )
    }

    return try {
      // candidates may be the whole tree (.npmrc registry=nexus); the probe narrows it
      val candidates = parse(lock.readText(Charsets.UTF_8))
      val scopes = NpmRegistryProbe.parseScopes(npmCorporateScopesProvider())
      // probe in parallel: 404 = corporate, 200 = the dome fetches it itself
      val probed = NpmRegistryProbe.probeAllParallel(candidates)
      val corporate = NpmRegistryProbe.filterCorporate(candidates, scopes) { probed[it.key] ?: NpmRegistryProbe.PublicAvailability.UNKNOWN }
      ResolveMissingResult(
        ok = true,
        missing = corporate,
        log = "parsed ${lock.name}: ${candidates.size} non-public candidate(s), " +
          "${corporate.size} corporate after registry probe" +
          (if (scopes.isNotEmpty()) " (scopes=${scopes.joinToString(",")})" else ""),
        durationMs = System.currentTimeMillis() - started
      )
    } catch (t: Throwable) {
      ResolveMissingResult(false, emptyList(), "lockfile parse failed: ${t.message}", System.currentTimeMillis() - started)
    }
  }

  /**
   * Reads the configured corporate-scope override from settings. Indirected so
   * pure tests of [filterCorporate] don't need IntelliJ services. Returns "" if
   * the service isn't available (e.g. in a unit-test JVM).
   */
  private fun npmCorporateScopesProvider(): String = try {
    com.intellij.openapi.application.ApplicationManager.getApplication()
      ?.getService(localgitmirror.idea.settings.MirrorSettingsService::class.java)
      ?.state?.npmCorporateScopes ?: ""
  } catch (_: Throwable) { "" }

  private fun firstExisting(dir: File, vararg names: String): File? =
    names.map { File(dir, it) }.firstOrNull { it.isFile }

  // ── WORK: locate each tarball in the npm content-addressed cache ────────────

  override fun collect(
    coordinates: List<DepCoordinate>,
    presentIndex: Map<String, Set<String>>,
    onMissingLocally: (DepCoordinate) -> Unit
  ): List<DepFileEntry> {
    val cacacheContent = File(NpmCache.npmCacheDir(), "_cacache" + File.separator + "content-v2")
    val out = mutableListOf<DepFileEntry>()
    val missingFromCacache = mutableListOf<DepCoordinate>()
    for (coord in coordinates) {
      if (coord.ecosystem != id) continue
      val tarball = NpmCache.locateByIntegrity(cacacheContent, coord.classifier)
      if (tarball == null || !tarball.isFile) {
        missingFromCacache.add(coord)
        continue
      }
      // file-key "<integrity>/<filename>": same integrity at dome = same bytes, skip
      val coordKey = "${coord.group}:${coord.name}:${coord.version}"
      val alreadyAtDome = presentIndex[coordKey].orEmpty()
      val fileKey = "${coord.classifier}/${tarball.name}"
      if (fileKey in alreadyAtDome) continue

      out.add(DepFileEntry(coord, tarball.absolutePath, NpmCache.mirrorRelativePath(coord), tarball.length()))
    }

    if (missingFromCacache.isNotEmpty()) {
      val recovered = YarnMirror.recoverFromYarn(missingFromCacache, presentIndex)
      out.addAll(recovered)
      val recoveredKeys = recovered.map { it.coordinate.key }.toSet()
      for (coord in missingFromCacache) {
        if (coord.key !in recoveredKeys) onMissingLocally(coord)
      }
    }
    return out
  }

  override fun cacheRoot(): File = NpmCache.npmOfflineMirror()

  override fun postInstall(installedRelativePaths: List<String>): String =
    NpmRepack.postInstall(installedRelativePaths)

  internal fun filterCorporate(
    candidates: List<DepCoordinate>,
    corporateScopes: List<String>,
    probe: (DepCoordinate) -> NpmRegistryProbe.PublicAvailability
  ): List<DepCoordinate> = NpmRegistryProbe.filterCorporate(candidates, corporateScopes, probe)

  internal fun matchesScope(coord: DepCoordinate, scopes: List<String>): Boolean =
    NpmRegistryProbe.matchesScope(coord, scopes)

  internal fun parseScopes(csv: String): List<String> = NpmRegistryProbe.parseScopes(csv)

  internal fun isPublicRegistry(resolvedUrl: String): Boolean = NpmRegistryProbe.isPublicRegistry(resolvedUrl)

  internal fun parseLockfileForCorporate(json: String): List<DepCoordinate> =
    NpmLockfiles.parseLockfileForCorporate(json)

  internal fun parsePnpmLockForCorporate(yaml: String): List<DepCoordinate> =
    NpmLockfiles.parsePnpmLockForCorporate(yaml)

  internal fun parseYarnLockForCorporate(text: String): List<DepCoordinate> =
    NpmLockfiles.parseYarnLockForCorporate(text)

  internal fun splitScopeName(full: String): Pair<String, String> = NpmLockfiles.splitScopeName(full)

  internal fun locateByIntegrity(contentRoot: File, integrity: String): File? =
    NpmCache.locateByIntegrity(contentRoot, integrity)

  internal fun mirrorRelativePath(coord: DepCoordinate): String = NpmCache.mirrorRelativePath(coord)

  internal fun npmCacheDirFrom(
    npmConfigCache: String?,
    localAppData: String?,
    userHome: String,
    isWindows: Boolean
  ): File = NpmCache.npmCacheDirFrom(npmConfigCache, localAppData, userHome, isWindows)

  fun yarnOfflineMirror(): File = YarnMirror.yarnOfflineMirror()

  fun buildYarnMirror(tarballsRoot: File, mirror: File): Int = YarnMirror.buildYarnMirror(tarballsRoot, mirror)

  fun setGlobalYarnMirror(mirror: File) = YarnMirror.setGlobalYarnMirror(mirror)

  fun rewriteLockToNpmjs(text: String, projectDir: File): Pair<String, Int> =
    NpmLockfiles.rewriteLockToNpmjs(text, projectDir)
}
