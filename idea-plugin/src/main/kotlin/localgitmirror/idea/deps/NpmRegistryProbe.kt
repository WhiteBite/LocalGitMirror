package localgitmirror.idea.deps

internal object NpmRegistryProbe {
  private val PUBLIC_REGISTRY_HOSTS = listOf(
    "registry.npmjs.org",
    "registry.yarnpkg.com",
    "registry.npmmirror.com",
  )

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
  fun filterCorporate(
    candidates: List<DepCoordinate>,
    corporateScopes: List<String>,
    probe: (DepCoordinate) -> PublicAvailability
  ): List<DepCoordinate> {
    val out = LinkedHashMap<String, DepCoordinate>()
    for (c in candidates) {
      if (c.ecosystem != "npm") continue
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
  fun matchesScope(coord: DepCoordinate, scopes: List<String>): Boolean {
    if (scopes.isEmpty()) return false
    val full = if (coord.group.isNotEmpty()) "${coord.group}/${coord.name}" else coord.name
    return scopes.any { raw ->
      val s = raw.trim()
      s.isNotEmpty() && (full == s || full.startsWith(s) || coord.group == s)
    }
  }

  fun parseScopes(csv: String): List<String> =
    csv.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

  /**
   * Real probe of the public npm registry. HEAD/GET the version metadata:
   *   GET https://registry.npmjs.org/<name>/<version>
   *   200 → AVAILABLE, 404 → ABSENT, anything else/error → UNKNOWN.
   * Results are cached per (name,version) for the process so repeated lockfile
   * entries don't re-hit the network.
   */
  private val probeCache = java.util.concurrent.ConcurrentHashMap<String, PublicAvailability>()

  fun probePublicRegistry(coord: DepCoordinate, timeoutMs: Int = 5000): PublicAvailability {
    val full = if (coord.group.isNotEmpty()) "${coord.group}/${coord.name}" else coord.name
    val key = "$full@${coord.version}"
    probeCache[key]?.let { return it }
    // one retry: a transient CDN non-200 (e.g. 406) must not be misread as "corporate"
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
      // no install-v1+json Accept: some CDN edges answer 406 on /<name>/<version>
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
  fun probeAllParallel(candidates: List<DepCoordinate>): Map<String, PublicAvailability> {
    if (candidates.isEmpty()) return emptyMap()
    val workers = candidates.size.coerceIn(1, 24)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(workers) { r ->
      Thread(r, "doccache-probe").apply { isDaemon = true }
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

  fun isPublicRegistry(resolvedUrl: String): Boolean {
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
}
