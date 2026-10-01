package localgitmirror.idea.deps

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

internal object NpmLockfiles {
  /**
   * Parse a package-lock.json (lockfileVersion 2/3 "packages", or legacy v1
   * "dependencies") and return coordinates for packages resolved from a
   * non-public registry. Integrity is stored in [DepCoordinate.classifier].
   *
   * Internal/visible for tests.
   */
  fun parseLockfileForCorporate(json: String): List<DepCoordinate> {
    val root = JsonParser.parseString(json).asJsonObject
    val out = LinkedHashMap<String, DepCoordinate>()  // dedup by key

    fun consider(pkgPath: String, obj: JsonObject) {
      val resolved = obj.get("resolved")?.takeIf { it.isJsonPrimitive }?.asString ?: return
      if (!resolved.startsWith("http", ignoreCase = true)) return  // file:/git+ etc — skip
      if (NpmRegistryProbe.isPublicRegistry(resolved)) return
      val version = obj.get("version")?.takeIf { it.isJsonPrimitive }?.asString ?: return
      val integrity = obj.get("integrity")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
      val (scope, name) = splitScopeName(pkgPathToPackageName(pkgPath))
      if (name.isBlank()) return
      val coord = DepCoordinate("npm", scope, name, version, classifier = integrity)
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
  fun parsePnpmLockForCorporate(yaml: String): List<DepCoordinate> {
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
      registry.startsWith("http", ignoreCase = true) && !NpmRegistryProbe.isPublicRegistry(registry)

    // locate the packages: block (column-0), collect until the next column-0 non-blank line
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
    // key e.g. "/@corp/ui-kit@2.3.1" or "/lodash@4.17.21", optionally with a peer suffix "(bar@2.0.0)"
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
      tb != null -> !NpmRegistryProbe.isPublicRegistry(tb)                  // explicit tarball decides
      integrity.isNotBlank() && registryIsCorporate -> true // implied corporate registry
      else -> false
    }
    if (!isCorporate) return null

    val (scope, name) = splitScopeName(pkgName)
    if (name.isBlank()) return null
    return DepCoordinate("npm", scope, name, version, classifier = integrity)
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
   * The `resolved` host (via [NpmRegistryProbe.isPublicRegistry]) decides corporate-vs-public;
   * git+/file resolutions are skipped. Integrity goes to [DepCoordinate.classifier].
   *
   * Internal/visible for tests.
   */
  fun parseYarnLockForCorporate(text: String): List<DepCoordinate> {
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

      // several comma-separated descriptors all name the same package; the first recovers the name
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
      if (r != null && r.startsWith("http", ignoreCase = true) && !NpmRegistryProbe.isPublicRegistry(r) &&
        pkgName.isNotBlank() && version.isNotBlank()
      ) {
        val (scope, name) = splitScopeName(pkgName)
        if (name.isNotBlank()) {
          val coord = DepCoordinate("npm", scope, name, version, classifier = integrity)
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

  fun rewriteYarnLock(project: File): Int {
    val lock = File(project, "yarn.lock")
    if (!lock.isFile) return 0
    val (text, n) = rewriteLockToNpmjs(lock.readText(), project)
    lock.writeText(text)
    return n
  }

  /** "node_modules/@scope/x" or "node_modules/a/node_modules/b" -> package name. */
  fun pkgPathToPackageName(pkgPath: String): String {
    val idx = pkgPath.lastIndexOf("node_modules/")
    return if (idx >= 0) pkgPath.substring(idx + "node_modules/".length) else pkgPath
  }

  /** "@scope/name" -> ("@scope","name"); "name" -> ("","name"). */
  fun splitScopeName(full: String): Pair<String, String> {
    return if (full.startsWith("@") && full.contains('/')) {
      val slash = full.indexOf('/')
      full.substring(0, slash) to full.substring(slash + 1)
    } else {
      "" to full
    }
  }
}
