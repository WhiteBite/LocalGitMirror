package localgitmirror.idea.deps

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Locale

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

  private val PUBLIC_REGISTRY_HOSTS = listOf(
    "registry.npmjs.org",
    "registry.yarnpkg.com",
    "registry.npmmirror.com",
  )

  override fun detect(projectDir: File): Boolean =
    File(projectDir, "package.json").isFile

  // ── Corporate classification: registry probe (primary) + scope override ──────

  /** Public-npm probe outcome for a single coordinate. */
  enum class PublicAvailability { AVAILABLE, ABSENT, UNKNOWN }

  /**
   * Decide, for a set of lockfile-derived candidates, which are CORPORATE
   * (the dome can't fetch them and must request from work). Pure & testable:
   * the network probe and the scope list are injected.
   *
   * Logic per candidate (in order):
   *   1. Scope override force-include: name/scope matches a configured prefix
   *      → always corporate (even if public probe says available).
   *   2. Registry probe: ABSENT (404 on public npm) → corporate.
   *      AVAILABLE (200) → NOT corporate (dome installs it itself).
   *      UNKNOWN (network error/timeout) → fall through to step 3.
   *   3. Conservative fallback when probe is UNKNOWN: include it. Better to
   *      over-ship a package than to leave the dome unable to build.
   *
   * @param corporateScopes prefixes like "@krypto-ui" or "krypto-" (already split/trimmed)
   * @param probe maps a coordinate to its public-registry availability
   */
  internal fun filterCorporate(
    candidates: List<DepCoordinate>,
    corporateScopes: List<String>,
    probe: (DepCoordinate) -> PublicAvailability
  ): List<DepCoordinate> {
    val out = LinkedHashMap<String, DepCoordinate>()
    for (c in candidates) {
      if (c.ecosystem != id) continue
      val forced = matchesScope(c, corporateScopes)
      val keep = when {
        forced -> true
        else -> when (probe(c)) {
          PublicAvailability.ABSENT -> true       // not on public npm → corporate
          PublicAvailability.AVAILABLE -> false   // dome can fetch it itself
          PublicAvailability.UNKNOWN -> true       // probe failed → ship to be safe
        }
      }
      if (keep) out[c.key] = c
    }
    return out.values.toList()
  }

  /** Full package name `@scope/name` or `name` matched against scope/prefix list. */
  internal fun matchesScope(coord: DepCoordinate, scopes: List<String>): Boolean {
    if (scopes.isEmpty()) return false
    val full = if (coord.group.isNotEmpty()) "${coord.group}/${coord.name}" else coord.name
    return scopes.any { raw ->
      val s = raw.trim()
      s.isNotEmpty() && (full == s || full.startsWith(s) || coord.group == s)
    }
  }

  internal fun parseScopes(csv: String): List<String> =
    csv.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

  /**
   * Real probe of the public npm registry. HEAD/GET the version metadata:
   *   GET https://registry.npmjs.org/<name>/<version>
   *   200 → AVAILABLE, 404 → ABSENT, anything else/error → UNKNOWN.
   * Results are cached per (name,version) for the process so repeated lockfile
   * entries don't re-hit the network.
   */
  private val probeCache = java.util.concurrent.ConcurrentHashMap<String, PublicAvailability>()

  internal fun probePublicRegistry(coord: DepCoordinate, timeoutMs: Int = 5000): PublicAvailability {
    val full = if (coord.group.isNotEmpty()) "${coord.group}/${coord.name}" else coord.name
    val key = "$full@${coord.version}"
    probeCache[key]?.let { return it }
    // One retry: the public registry occasionally returns a transient non-200
    // (e.g. a 406 from a CDN edge) which we must NOT misread as "corporate".
    var result = PublicAvailability.UNKNOWN
    for (attempt in 1..2) {
      result = probeOnce(full, coord.version, timeoutMs)
      if (result != PublicAvailability.UNKNOWN) break
    }
    probeCache[key] = result
    return result
  }

  private fun probeOnce(full: String, version: String, timeoutMs: Int): PublicAvailability {
    return try {
      // npm scoped names are URL-encoded as %2F per the registry spec.
      val encodedName = full.replace("/", "%2f")
      val url = java.net.URL("https://registry.npmjs.org/$encodedName/$version")
      val conn = url.openConnection() as java.net.HttpURLConnection
      conn.requestMethod = "GET"
      conn.connectTimeout = timeoutMs
      conn.readTimeout = timeoutMs
      // NOTE: do NOT send "Accept: application/vnd.npm.install-v1+json" here — on
      // the /<name>/<version> endpoint some npm CDN edges answer it with 406,
      // which we'd misread as UNKNOWN and over-classify the package as corporate.
      // A plain GET reliably yields 200 (public) / 404 (corporate).
      val code = conn.responseCode
      runCatching { conn.inputStream.use { it.readBytes() } }  // drain & close (best-effort)
      when {
        code == 200 -> PublicAvailability.AVAILABLE
        code == 404 -> PublicAvailability.ABSENT
        else -> PublicAvailability.UNKNOWN
      }
    } catch (_: java.io.FileNotFoundException) {
      PublicAvailability.ABSENT   // 404 surfaces as FileNotFoundException on some JDKs
    } catch (_: Throwable) {
      PublicAvailability.UNKNOWN
    }
  }

  /**
   * Probe every candidate against the public registry IN PARALLEL and return a
   * key→availability map. Sequential probing of a whole dependency tree (often
   * 1000+ packages) is far too slow; daemon threads keep it off the shutdown path.
   */
  private fun probeAllParallel(candidates: List<DepCoordinate>): Map<String, PublicAvailability> {
    if (candidates.isEmpty()) return emptyMap()
    val workers = candidates.size.coerceIn(1, 24)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(workers) { r ->
      Thread(r, "lgm-npm-probe").apply { isDaemon = true }
    }
    return try {
      val futures = candidates.map { c -> c to pool.submit<PublicAvailability> { probePublicRegistry(c) } }
      futures.associate { (c, f) ->
        c.key to (try { f.get() } catch (_: Throwable) { PublicAvailability.UNKNOWN })
      }
    } finally {
      pool.shutdownNow()
    }
  }

  // ── DOME: what does the lockfile pin to a corporate registry? ───────────────

  override fun resolveMissing(projectDir: File, javaHome: String?): ResolveMissingResult {
    val started = System.currentTimeMillis()

    // Lockfile detection. Priority: npm → pnpm → yarn. The first lockfile found
    // wins; each maps to its own parser but the SAME corporate-vs-public logic
    // and the SAME DepCoordinate output shape.
    val npmLock = firstExisting(projectDir, "package-lock.json", "npm-shrinkwrap.json")
    val pnpmLock = File(projectDir, "pnpm-lock.yaml").takeIf { it.isFile }
    val yarnLock = File(projectDir, "yarn.lock").takeIf { it.isFile }

    val lock: File
    val parse: (String) -> List<DepCoordinate>
    when {
      npmLock != null -> { lock = npmLock; parse = ::parseLockfileForCorporate }
      pnpmLock != null -> { lock = pnpmLock; parse = ::parsePnpmLockForCorporate }
      yarnLock != null -> { lock = yarnLock; parse = ::parseYarnLockForCorporate }
      else -> return ResolveMissingResult(
        false, emptyList(),
        "no lockfile (package-lock.json / pnpm-lock.yaml / yarn.lock) found in " +
          "${projectDir.absolutePath} (run an install first)",
        System.currentTimeMillis() - started
      )
    }

    return try {
      // Step 1: parse lockfile → candidates resolved from a non-public registry.
      // With a global `registry=<nexus>` in .npmrc EVERY package resolves via
      // nexus, so this list can be the whole dependency tree. Step 2 narrows it
      // to packages that genuinely don't exist on the public npm registry.
      val candidates = parse(lock.readText(Charsets.UTF_8))
      val scopes = parseScopes(npmCorporateScopesProvider())
      // Probe the whole candidate set against public npm in parallel, then
      // classify. A package that 404s on public npm is corporate; one that
      // 200s the dome fetches itself (so it must NOT be requested).
      val probed = probeAllParallel(candidates)
      val corporate = filterCorporate(candidates, scopes) { probed[it.key] ?: PublicAvailability.UNKNOWN }
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

  /**
   * Parse a package-lock.json (lockfileVersion 2/3 "packages", or legacy v1
   * "dependencies") and return coordinates for packages resolved from a
   * non-public registry. Integrity is stored in [DepCoordinate.classifier].
   *
   * Internal/visible for tests.
   */
  internal fun parseLockfileForCorporate(json: String): List<DepCoordinate> {
    val root = JsonParser.parseString(json).asJsonObject
    val out = LinkedHashMap<String, DepCoordinate>()  // dedup by key

    fun consider(pkgPath: String, obj: JsonObject) {
      val resolved = obj.get("resolved")?.takeIf { it.isJsonPrimitive }?.asString ?: return
      if (!resolved.startsWith("http", ignoreCase = true)) return  // file:/git+ etc — skip
      if (isPublicRegistry(resolved)) return
      val version = obj.get("version")?.takeIf { it.isJsonPrimitive }?.asString ?: return
      val integrity = obj.get("integrity")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
      val (scope, name) = splitScopeName(pkgPathToPackageName(pkgPath))
      if (name.isBlank()) return
      val coord = DepCoordinate(id, scope, name, version, classifier = integrity)
      out[coord.key] = coord
    }

    // lockfileVersion 2/3: { "packages": { "node_modules/@scope/x": {...} } }
    root.getAsJsonObject("packages")?.entrySet()?.forEach { (path, el) ->
      if (path.isBlank()) return@forEach  // "" = the root project itself
      if (el.isJsonObject) consider(path, el.asJsonObject)
    }

    // lockfileVersion 1 (and the legacy "dependencies" mirror in v2): recurse.
    fun recurseDeps(deps: JsonObject, prefix: String) {
      deps.entrySet().forEach { (name, el) ->
        if (!el.isJsonObject) return@forEach
        val obj = el.asJsonObject
        consider("node_modules/$name", obj)
        obj.getAsJsonObject("dependencies")?.let { recurseDeps(it, prefix) }
      }
    }
    root.getAsJsonObject("dependencies")?.let { recurseDeps(it, "") }

    return out.values.toList()
  }

  // ── pnpm: pnpm-lock.yaml (hand-rolled minimal YAML, no new deps) ─────────────

  /**
   * Parse a `pnpm-lock.yaml` and return coordinates for packages resolved from a
   * non-public registry. Integrity is stored in [DepCoordinate.classifier].
   *
   * pnpm keys packages under `packages:` like `/@corp/ui-kit@2.3.1:` (scoped) or
   * `/lodash@4.17.21:`, each with a `resolution:` carrying `integrity` and, for
   * non-default registries, a `tarball` URL. Corporate detection:
   *   - an explicit `resolution.tarball` whose host is non-public  → corporate
   *   - no tarball, but the lockfile's top-level `registry:` is corporate AND the
   *     package has an integrity (i.e. it's a real registry tarball)  → corporate
   * Anything with a non-http tarball or a git/directory `type` is skipped.
   *
   * Deliberately tiny line-based parser — the plugin must not gain a YAML dep.
   *
   * Internal/visible for tests.
   */
  internal fun parsePnpmLockForCorporate(yaml: String): List<DepCoordinate> {
    if (yaml.isBlank()) return emptyList()
    val lines = yaml.split('\n')
    val out = LinkedHashMap<String, DepCoordinate>()

    // Top-level `registry:` (column-0 line), used as a fallback signal.
    var registry: String? = null
    for (l in lines) {
      if (firstNonSpace(l) != 0) continue
      val t = l.trim()
      if (t.startsWith("registry:")) {
        registry = unquote(t.substringAfter(':').trim()).ifBlank { null }
      }
    }
    val registryIsCorporate = registry != null &&
      registry.startsWith("http", ignoreCase = true) && !isPublicRegistry(registry)

    // Locate the `packages:` block (column-0 `packages:`), collect until the next
    // column-0 (non-blank) line, then split into per-package sub-blocks.
    val pkgIdx = lines.indexOfFirst { firstNonSpace(it) == 0 && it.trim() == "packages:" }
    if (pkgIdx < 0) return emptyList()

    val block = mutableListOf<String>()
    var j = pkgIdx + 1
    while (j < lines.size) {
      val line = lines[j]
      if (firstNonSpace(line) == 0 && line.isNotBlank()) break
      block.add(line)
      j++
    }

    var k = 0
    while (k < block.size) {
      val line = block[k]
      if (!isPnpmPackageHeader(line.trim())) { k++; continue }
      val headerIndent = firstNonSpace(line)
      val key = unquote(line.trim().removeSuffix(":"))

      // Body: subsequent lines indented deeper than the header.
      val body = mutableListOf<String>()
      var m = k + 1
      while (m < block.size) {
        val bl = block[m]
        val bi = firstNonSpace(bl)
        if (bi != -1 && bi <= headerIndent) break
        body.add(bl)
        m++
      }
      pnpmConsider(key, body, registryIsCorporate)?.let { out[it.key] = it }
      k = m
    }
    return out.values.toList()
  }

  private fun isPnpmPackageHeader(trimmed: String): Boolean =
    trimmed.endsWith(":") &&
      (trimmed.startsWith("/") || trimmed.startsWith("'/") || trimmed.startsWith("\"/"))

  private fun pnpmConsider(key: String, body: List<String>, registryIsCorporate: Boolean): DepCoordinate? {
    // key e.g. "/@corp/ui-kit@2.3.1" or "/lodash@4.17.21" (optionally with peer
    // suffix like "/foo@1.0.0(bar@2.0.0)").
    val spec = key.removePrefix("/").substringBefore('(')
    val at = spec.lastIndexOf('@')
    if (at <= 0) return null  // no version separator (leading '@' is scope, not it)
    val pkgName = spec.substring(0, at)
    val version = spec.substring(at + 1)
    if (pkgName.isBlank() || version.isBlank()) return null

    var integrity = ""
    var tarball: String? = null
    var type: String? = null
    for (b in body) {
      val bt = b.trim()
      when {
        bt.startsWith("resolution:") -> {
          val rest = bt.substringAfter("resolution:").trim()
          if (rest.startsWith("{")) {
            val inline = parseInlineMap(rest)
            inline["integrity"]?.let { integrity = it }
            inline["tarball"]?.let { tarball = it }
            inline["type"]?.let { type = it }
          }
        }
        bt.startsWith("integrity:") -> integrity = unquote(bt.substringAfter(':').trim())
        bt.startsWith("tarball:") -> tarball = unquote(bt.substringAfter(':').trim())
        bt.startsWith("type:") -> type = unquote(bt.substringAfter(':').trim())
      }
    }

    val tb = tarball
    val isCorporate = when {
      type == "git" || type == "directory" -> false       // not a registry tarball
      tb != null && !tb.startsWith("http", ignoreCase = true) -> false  // git/file tarball
      tb != null -> !isPublicRegistry(tb)                  // explicit tarball decides
      integrity.isNotBlank() && registryIsCorporate -> true // implied corporate registry
      else -> false
    }
    if (!isCorporate) return null

    val (scope, name) = splitScopeName(pkgName)
    if (name.isBlank()) return null
    return DepCoordinate(id, scope, name, version, classifier = integrity)
  }

  /** Parse a flat inline YAML map `{a: x, b: y}` into key→value (values unquoted). */
  private fun parseInlineMap(s: String): Map<String, String> {
    val inner = s.trim().removePrefix("{").substringBeforeLast('}')
    val map = LinkedHashMap<String, String>()
    for (part in inner.split(',')) {
      val colon = part.indexOf(':')
      if (colon <= 0) continue
      val key = part.substring(0, colon).trim()
      val value = unquote(part.substring(colon + 1).trim())
      if (key.isNotEmpty()) map[key] = value
    }
    return map
  }

  // ── yarn: yarn.lock (classic v1) ─────────────────────────────────────────────

  /**
   * Parse a classic `yarn.lock` and return coordinates for packages whose
   * `resolved` URL points at a non-public registry. Blocks look like:
   *
   *   "@corp/ui-kit@^2.3.0":
   *     version "2.3.1"
   *     resolved "https://nexus.corp.local/.../ui-kit-2.3.1.tgz#abc"
   *     integrity sha512-...
   *
   * The `resolved` host (via [isPublicRegistry]) decides corporate-vs-public;
   * git+/file resolutions are skipped. Integrity goes to [DepCoordinate.classifier].
   *
   * Internal/visible for tests.
   */
  internal fun parseYarnLockForCorporate(text: String): List<DepCoordinate> {
    if (text.isBlank()) return emptyList()
    val lines = text.split('\n')
    val out = LinkedHashMap<String, DepCoordinate>()

    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      val trimmed = line.trim()
      val isHeader = firstNonSpace(line) == 0 && trimmed.isNotEmpty() &&
        !trimmed.startsWith("#") && trimmed.endsWith(":")
      if (!isHeader) { i++; continue }

      // Header may list several comma-separated descriptors; they all name the
      // same package, so the first is enough to recover the name.
      val firstDesc = unquote(trimmed.removeSuffix(":").split(",").first().trim())
      val pkgName = yarnNameFromDescriptor(firstDesc)

      var version = ""
      var resolved: String? = null
      var integrity = ""
      var j = i + 1
      while (j < lines.size) {
        val bl = lines[j]
        if (firstNonSpace(bl) == 0 && bl.isNotBlank()) break  // next header
        val bt = bl.trim()
        when {
          bt.startsWith("version ") -> version = unquote(bt.substringAfter("version").trim())
          bt.startsWith("resolved ") -> resolved = unquote(bt.substringAfter("resolved").trim())
          bt.startsWith("integrity ") -> integrity = unquote(bt.substringAfter("integrity").trim())
        }
        j++
      }

      val r = resolved
      if (r != null && r.startsWith("http", ignoreCase = true) && !isPublicRegistry(r) &&
        pkgName.isNotBlank() && version.isNotBlank()
      ) {
        val (scope, name) = splitScopeName(pkgName)
        if (name.isNotBlank()) {
          val coord = DepCoordinate(id, scope, name, version, classifier = integrity)
          out[coord.key] = coord
        }
      }
      i = j
    }
    return out.values.toList()
  }

  /** "@corp/ui-kit@^2.3.0" -> "@corp/ui-kit"; "lodash@^4.17.0" -> "lodash". */
  private fun yarnNameFromDescriptor(descriptor: String): String {
    val at = descriptor.lastIndexOf('@')
    return if (at > 0) descriptor.substring(0, at) else descriptor
  }

  // ── tiny text helpers shared by the pnpm/yarn parsers ───────────────────────

  /** Index of the first non-space char, or -1 for an all-space/blank line. */
  private fun firstNonSpace(s: String): Int = s.indexOfFirst { it != ' ' }

  /** Strip a single layer of surrounding single or double quotes. */
  private fun unquote(s: String): String {
    if (s.length >= 2) {
      val a = s.first(); val b = s.last()
      if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) return s.substring(1, s.length - 1)
    }
    return s
  }

  // ── WORK: locate each tarball in the npm content-addressed cache ────────────

  override fun collect(
    coordinates: List<DepCoordinate>,
    presentIndex: Map<String, Set<String>>,
    onMissingLocally: (DepCoordinate) -> Unit
  ): List<DepFileEntry> {
    val cacacheContent = File(npmCacheDir(), "_cacache" + File.separator + "content-v2")
    val out = mutableListOf<DepFileEntry>()
    for (coord in coordinates) {
      if (coord.ecosystem != id) continue
      val tarball = locateByIntegrity(cacacheContent, coord.classifier)
      if (tarball == null || !tarball.isFile) {
        onMissingLocally(coord)
        continue
      }
      // npm's content-addressed cache uses the integrity hash as the content
      // address, so any presentIndex entry with the same coordinate + integrity
      // means the dome already has identical bytes — skip.
      // Stored shape: coord.coordKey -> set of "<sha1>/<filename>". For npm we
      // re-use the coord.classifier (integrity) as the file-key since that's
      // what uniquely identifies the tarball on disk in npm semantics.
      val coordKey = "${coord.group}:${coord.name}:${coord.version}"
      val alreadyAtDome = presentIndex[coordKey].orEmpty()
      val fileKey = "${coord.classifier}/${tarball.name}"
      if (fileKey in alreadyAtDome) continue

      out.add(DepFileEntry(coord, tarball.absolutePath, mirrorRelativePath(coord), tarball.length()))
    }
    return out
  }

  /**
   * Map an `integrity` (e.g. "sha512-BASE64==") to the cacache content path:
   * `_cacache/content-v2/sha512/<hh>/<hh>/<rest-of-hex>`. npm stores the digest
   * as hex of the raw hash bytes. Returns null if the integrity is missing or
   * the file isn't present.
   *
   * Internal for tests.
   */
  internal fun locateByIntegrity(contentRoot: File, integrity: String): File? {
    if (integrity.isBlank()) return null
    val dash = integrity.indexOf('-')
    if (dash <= 0) return null
    val algo = integrity.substring(0, dash).lowercase(Locale.ROOT)
    val b64 = integrity.substring(dash + 1)
    val hex = try {
      java.util.Base64.getDecoder().decode(b64).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { return null }
    if (hex.length < 4) return null
    // cacache shards: content-v2/<algo>/<hex[0:2]>/<hex[2:4]>/<hex[4:]>
    val f = File(contentRoot, "$algo/${hex.substring(0, 2)}/${hex.substring(2, 4)}/${hex.substring(4)}")
    return if (f.isFile) f else null
  }

  override fun cacheRoot(): File = npmOfflineMirror()

  // ── npm lockfile relocation (corporate registry -> npmjs) ───────────────────

  /** Registry base URLs declared in the project's .npmrc (default + scoped). */
  fun npmrcRegistries(projectDir: File): List<String> {
    val npmrc = File(projectDir, ".npmrc")
    if (!npmrc.isFile) return emptyList()
    val out = mutableListOf<String>()
    for (raw in npmrc.readLines()) {
      val line = raw.trim()
      if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue
      val key = line.substringBefore('=').trim()
      val value = line.substringAfter('=').trim()
      if (key.endsWith("registry") && value.startsWith("http")) {
        out.add(value.trimEnd('/') + "/")
      }
    }
    return out
  }

  /**
   * Rewrite npm-lockfile `resolved` URLs from the corporate registry (read from
   * the project's .npmrc) to public npmjs. nexus npm-all proxies npmjs, so the
   * tarball path tail is identical and the integrity stays valid. Corporate
   * packages 404 on npmjs but resolve from the local npm cache (cache-hit by
   * integrity), so their rewritten URL is never actually fetched.
   * Returns (rewrittenText, replacedCount).
   */
  fun rewriteLockToNpmjs(text: String, projectDir: File): Pair<String, Int> {
    val npmjs = "https://registry.npmjs.org/"
    var out = text
    var count = 0
    for (base in npmrcRegistries(projectDir)) {
      if (base == npmjs) continue
      count += out.split(base).size - 1
      out = out.replace(base, npmjs)
    }
    return out to count
  }

  // ── yarn (classic v1) offline-mirror support ────────────────────────────────

  fun yarnOfflineMirror(): File = File(System.getProperty("user.home"), ".lgm-yarn-offline")

  /** yarn v1 offline-mirror filename: scope '/' -> '-', then '-<version>.tgz'. */
  fun yarnTarballName(name: String, version: String): String =
    name.replace("/", "-") + "-" + version + ".tgz"

  private fun readFully(ins: java.io.InputStream, buf: ByteArray): Boolean {
    var off = 0
    while (off < buf.size) {
      val r = ins.read(buf, off, buf.size - off)
      if (r < 0) return false
      off += r
    }
    return true
  }

  /**
   * Read (name, version) from package/package.json inside an npm .tgz, using a
   * minimal gzip+tar reader (no external dependency). Returns null on any issue.
   */
  fun readTgzNameVersion(tgz: File): Pair<String, String>? {
    return try {
      java.util.zip.GZIPInputStream(java.io.BufferedInputStream(tgz.inputStream())).use { gz ->
        val header = ByteArray(512)
        while (true) {
          if (!readFully(gz, header)) break
          if (header.all { it.toInt() == 0 }) break
          val name = String(header, 0, 100, Charsets.US_ASCII).substringBefore('\u0000').trim()
          if (name.isEmpty()) break
          val sizeField = String(header, 124, 12, Charsets.US_ASCII).trim().trim('\u0000', ' ')
          val size = sizeField.takeWhile { it in '0'..'7' }.ifEmpty { "0" }.toLong(8)
          if (name == "package/package.json") {
            val data = ByteArray(size.toInt())
            if (!readFully(gz, data)) return null
            val text = String(data, Charsets.UTF_8)
            val nm = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            val ver = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            return if (nm != null && ver != null) nm to ver else null
          }
          // skip this entry's content, padded to 512-byte blocks
          var toSkip = ((size + 511) / 512) * 512
          val skip = ByteArray(8192)
          while (toSkip > 0) {
            val r = gz.read(skip, 0, minOf(skip.size.toLong(), toSkip).toInt())
            if (r <= 0) break
            toSkip -= r
          }
        }
        null
      }
    } catch (_: Throwable) {
      null
    }
  }

  /** Copy corporate .tgz from the npm offline-mirror into a yarn v1
   *  offline-mirror, renamed to yarn's `<name '/'->'-'>-<version>.tgz`. */
  fun buildYarnMirror(tarballsRoot: File, mirror: File): Int {
    if (!tarballsRoot.isDirectory) return 0
    mirror.mkdirs()
    var n = 0
    tarballsRoot.walkTopDown().filter { it.isFile && it.extension == "tgz" }.forEach { tgz ->
      val nv = readTgzNameVersion(tgz) ?: return@forEach
      runCatching {
        tgz.copyTo(File(mirror, yarnTarballName(nv.first, nv.second)), overwrite = true)
        n++
      }
    }
    return n
  }

  fun writeYarnrc(project: File, mirror: File) {
    val yarnrc = File(project, ".yarnrc")
    val existing = if (yarnrc.isFile) yarnrc.readText() else ""
    val keep = existing.lines().filterNot {
      it.trim().startsWith("yarn-offline-mirror") || it.trim().startsWith("registry ")
    }.toMutableList()
    val mp = mirror.absolutePath.replace("\\", "/")
    keep.add("yarn-offline-mirror \"$mp\"")
    keep.add("yarn-offline-mirror-pruning false")
    keep.add("registry \"https://registry.npmjs.org\"")
    yarnrc.writeText(keep.joinToString("\n").trim('\n') + "\n")
  }

  fun rewriteYarnLock(project: File): Int {
    val lock = File(project, "yarn.lock")
    if (!lock.isFile) return 0
    val (text, n) = rewriteLockToNpmjs(lock.readText(), project)
    lock.writeText(text)
    return n
  }

  /** Write the offline-mirror into the GLOBAL ~/.yarnrc (outside any repo) so it
   *  survives branch switches and never dirties a project. No registry is set
   *  (that could affect other projects); `yarn install --offline` needs none.
   *  Existing ~/.yarnrc lines are preserved. */
  fun setGlobalYarnMirror(mirror: File) {
    val yarnrc = File(System.getProperty("user.home"), ".yarnrc")
    val existing = if (yarnrc.isFile) yarnrc.readText() else ""
    val keep = existing.lines().filter {
      it.isNotBlank() && !it.trim().startsWith("yarn-offline-mirror")
    }.toMutableList()
    val mp = mirror.absolutePath.replace("\\", "/")
    keep.add("yarn-offline-mirror \"$mp\"")
    keep.add("yarn-offline-mirror-pruning false")
    yarnrc.writeText(keep.joinToString("\n") + "\n")
  }

  /**
   * DOME side. Feed each received tarball into npm's own cache via
   * `npm cache add <file>`, so a later `npm install` resolves them offline
   * without any .npmrc surgery. Best-effort: if npm isn't on PATH we just
   * report that and leave the tarballs in the mirror folder for manual use.
   */
  override fun postInstall(installedRelativePaths: List<String>): String {
    val mirror = npmOfflineMirror()
    val tarballs = installedRelativePaths
      .filter { it.endsWith(".tgz") }
      .map { File(mirror, it) }
      .filter { it.isFile }
    if (tarballs.isEmpty()) return ""

    val npm = npmCommand() ?: return "npm не найден в PATH — тарболы в ${mirror.absolutePath}, поставь вручную (npm install --offline)"
    var ok = 0
    var fail = 0
    for (tb in tarballs) {
      try {
        val proc = ProcessBuilder(npm + listOf("cache", "add", tb.absolutePath))
          .redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readText() }  // drain
        if (proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0) ok++ else fail++
      } catch (_: Exception) { fail++ }
    }
    return "npm cache add: $ok ok" + (if (fail > 0) ", $fail ошибок (см. ${mirror.absolutePath})" else "")
  }

  private fun npmCommand(): List<String>? {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    // On Windows npm is a .cmd shim and must be run via cmd /c.
    return if (isWindows) listOf("cmd", "/c", "npm") else listOf("npm")
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  internal fun isPublicRegistry(resolvedUrl: String): Boolean {
    val host = hostOf(resolvedUrl)
    return PUBLIC_REGISTRY_HOSTS.any { host.equals(it, ignoreCase = true) || host.endsWith(".$it", ignoreCase = true) }
  }

  private fun hostOf(url: String): String {
    val noScheme = url.substringAfter("://", url)
    return noScheme.substringBefore('/').substringBefore('@').let {
      // strip user:pass@ if present (handled by substringBefore('@') above for simple cases)
      it.substringAfterLast('@')
    }.substringBefore(':')
  }

  /** "node_modules/@scope/x" or "node_modules/a/node_modules/b" -> package name. */
  internal fun pkgPathToPackageName(pkgPath: String): String {
    val idx = pkgPath.lastIndexOf("node_modules/")
    return if (idx >= 0) pkgPath.substring(idx + "node_modules/".length) else pkgPath
  }

  /** "@scope/name" -> ("@scope","name"); "name" -> ("","name"). */
  internal fun splitScopeName(full: String): Pair<String, String> {
    return if (full.startsWith("@") && full.contains('/')) {
      val slash = full.indexOf('/')
      full.substring(0, slash) to full.substring(slash + 1)
    } else {
      "" to full
    }
  }

  /** Flat mirror path: `<scope>/<name>/<name>-<version>.tgz` (scope without '@'). */
  internal fun mirrorRelativePath(coord: DepCoordinate): String {
    val scopeDir = if (coord.group.isNotEmpty()) coord.group.removePrefix("@") + "/" else ""
    return "$scopeDir${coord.name}/${coord.name}-${coord.version}.tgz"
  }

  private fun firstExisting(dir: File, vararg names: String): File? =
    names.map { File(dir, it) }.firstOrNull { it.isFile }

  /** npm cache dir: $npm_config_cache or ~/.npm (Windows: %LocalAppData%\npm-cache). */
  private fun npmCacheDir(): File = npmCacheDirFrom(
    npmConfigCache = System.getenv("npm_config_cache"),
    localAppData = System.getenv("LOCALAPPDATA"),
    userHome = System.getProperty("user.home"),
    isWindows = System.getProperty("os.name").lowercase().contains("win")
  )

  /**
   * Pure cache-dir resolution, injectable for tests. Precedence:
   *   1. npm_config_cache (explicit override, any OS)
   *   2. Windows: %LocalAppData%\npm-cache  (fallback ~/AppData/Local/npm-cache)
   *   3. Unix: ~/.npm
   */
  internal fun npmCacheDirFrom(
    npmConfigCache: String?,
    localAppData: String?,
    userHome: String,
    isWindows: Boolean
  ): File {
    npmConfigCache?.takeIf { it.isNotBlank() }?.let { return File(it) }
    return if (isWindows) {
      if (!localAppData.isNullOrBlank()) File(localAppData, "npm-cache")
      else File(userHome, "AppData/Local/npm-cache")
    } else {
      File(userHome, ".npm")
    }
  }

  /** Offline tarball mirror the dome installs into. */
  private fun npmOfflineMirror(): File =
    File(System.getProperty("user.home"), ".lgm-npm-offline")
}
