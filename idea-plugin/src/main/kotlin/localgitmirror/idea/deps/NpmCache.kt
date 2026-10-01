package localgitmirror.idea.deps

import java.io.File
import java.util.Locale

internal object NpmCache {
  /**
   * Map an `integrity` (e.g. "sha512-BASE64==") to the cacache content path:
   * `_cacache/content-v2/sha512/<hh>/<hh>/<rest-of-hex>`. npm stores the digest
   * as hex of the raw hash bytes. Returns null if the integrity is missing or
   * the file isn't present.
   *
   * Internal for tests.
   */
  fun locateByIntegrity(contentRoot: File, integrity: String): File? {
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

  /** Flat mirror path: `<scope>/<name>/<name>-<version>.tgz` (scope without '@'). */
  fun mirrorRelativePath(coord: DepCoordinate): String {
    val scopeDir = if (coord.group.isNotEmpty()) coord.group.removePrefix("@") + "/" else ""
    return "$scopeDir${coord.name}/${coord.name}-${coord.version}.tgz"
  }

  /** npm cache dir: $npm_config_cache or ~/.npm (Windows: %LocalAppData%\npm-cache). */
  fun npmCacheDir(): File = npmCacheDirFrom(
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
  fun npmCacheDirFrom(
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
  fun npmOfflineMirror(): File =
    File(System.getProperty("user.home"), ".lgm-npm-offline")
}
