package localgitmirror.idea.deps

import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import java.net.NetworkInterface
import java.net.URL

/**
 * Machine role for auto deps-sync.
 *
 *  - HOME: the machine hosting the LocalGitMirror server. It requests missing
 *    corporate deps and applies received responses.
 *  - WORK: the corporate machine. It responds to pending deps requests by
 *    shipping artifacts from its local cache.
 *  - UNDETERMINED: detection failed (blank/unparseable baseUrl or a network
 *    interface error). Role-specific behavior must stay off until the user
 *    sets an explicit machineRole override.
 */
enum class MachineRole { HOME, WORK, UNDETERMINED }

const val AUTO_ROLE_UNDETERMINED_KEY = "auto.role.undetermined"

/**
 * Detects whether this machine is HOME (hosts the Mirror server) or WORK
 * (corporate environment). Used by [DepsAutomationService] to decide which
 * auto-sync behaviors to activate.
 *
 * Detection strategy (in [detect]):
 *  1. If [MirrorSettingsService.State.machineRole] is explicitly "home" or
 *     "work", use that override.
 *  2. Otherwise ("auto", the default): parse the host from [State.baseUrl].
 *     If the host is localhost / 127.0.0.1 / ::1 → HOME.
 *     Otherwise enumerate local [NetworkInterface] addresses and compare the
 *     host string literally against each (string equality, no DNS) → match ⇒
 *     HOME else WORK. DNS is never used: a non-loopback hostname that isn't
 *     a literal local IP is treated as WORK.
 *  3. On ANY failure → UNDETERMINED (never a silent WORK: role-gated behavior
 *     stays off and the UI can explain instead of hiding the deps loop).
 *
 * The result is cached per session (per settings instance) so repeated calls
 * are cheap and consistent.
 */
object RoleDetector {

  private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")

  @Volatile
  private var cachedRole: MachineRole? = null

  @Volatile
  private var cachedForUrl: String? = null

  @Volatile
  private var cachedOverride: String? = null

  /**
   * Detect the machine role from [settings]. Results are cached per session
   * and invalidated when the baseUrl or machineRole override changes.
   */
  fun detect(settings: MirrorSettingsService.State): MachineRole {
    val override = settings.machineRole.trim().lowercase()
    val url = settings.baseUrl.trim()

    // Invalidate cache if inputs changed
    if (cachedOverride != override || cachedForUrl != url) {
      cachedRole = null
      cachedOverride = override
      cachedForUrl = url
    }

    cachedRole?.let { return it }

    val role = when (override) {
      "home" -> MachineRole.HOME
      "work" -> MachineRole.WORK
      else -> detectFromUrl(url)
    }
    cachedRole = role
    return role
  }

  /**
   * Convenience: detect using the current application settings.
   */
  fun current(settings: MirrorSettingsService.State): MachineRole = detect(settings)

  /**
   * Human-readable label for UI, e.g. "ДОМ (авто)" / "РАБОТА (ручная)".
   * Uses the plugin's own bundle for i18n.
   */
  fun describe(settings: MirrorSettingsService.State): String {
    val role = detect(settings)
    val isManual = settings.machineRole.trim().lowercase() in setOf("home", "work")
    val key = when {
      role == MachineRole.UNDETERMINED -> AUTO_ROLE_UNDETERMINED_KEY
      role == MachineRole.HOME && isManual -> "auto.role.home.manual"
      role == MachineRole.HOME -> "auto.role.home.auto"
      role == MachineRole.WORK && isManual -> "auto.role.work.manual"
      else -> "auto.role.work.auto"
    }
    return LocalGitMirrorBundle.message(key)
  }

  /**
   * Auto-detect from the baseUrl: localhost → HOME; otherwise compare the
   * host string literally against local network interface addresses (no DNS).
   * Falls back to UNDETERMINED on any error.
   */
  private fun detectFromUrl(baseUrl: String): MachineRole = runCatching {
    if (baseUrl.isBlank()) return@runCatching MachineRole.UNDETERMINED

    val host = parseHost(baseUrl) ?: return@runCatching MachineRole.UNDETERMINED
    if (host.lowercase() in LOCAL_HOSTS) return@runCatching MachineRole.HOME

    // mDNS: <hostname>.local always refers to this machine
    val localHostname = java.net.InetAddress.getLocalHost().hostName
    if (host.equals("$localHostname.local", ignoreCase = true)) return@runCatching MachineRole.HOME

    val localAddresses = mutableSetOf<String>()
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching MachineRole.UNDETERMINED
    for (ni in interfaces) {
      for (addr in ni.interfaceAddresses) {
        localAddresses.add(addr.address.hostAddress)
      }
    }

    if (host in localAddresses) MachineRole.HOME
    else MachineRole.WORK
  }.getOrElse { MachineRole.UNDETERMINED }

  /**
   * Extract the hostname from a URL string. Returns null on failure.
   * Handles "https://host:port/path", "http://host", "host", "host:port".
   */
  private fun parseHost(raw: String): String? = runCatching {
    val withScheme = if (raw.contains("://")) raw else "https://$raw"
    val url = URL(withScheme)
    url.host?.takeIf { it.isNotBlank() }
  }.getOrNull()
}
