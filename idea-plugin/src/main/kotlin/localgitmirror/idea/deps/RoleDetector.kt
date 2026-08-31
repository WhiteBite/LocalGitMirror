package localgitmirror.idea.deps

import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

/**
 * Machine role for auto deps-sync.
 *
 *  - HOME: the machine hosting the LocalGitMirror server. It requests missing
 *    corporate deps and applies received responses.
 *  - WORK: the corporate machine. It responds to pending deps requests by
 *    shipping artifacts from its local cache.
 */
enum class MachineRole { HOME, WORK }

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
 *     Otherwise resolve the host via [InetAddress.getAllByName] and compare
 *     against all local [NetworkInterface] addresses → match ⇒ HOME else WORK.
 *  3. On ANY failure → WORK (safe default: never auto-request from a machine
 *     we can't identify as the dome).
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
      role == MachineRole.HOME && isManual -> "auto.role.home.manual"
      role == MachineRole.HOME -> "auto.role.home.auto"
      role == MachineRole.WORK && isManual -> "auto.role.work.manual"
      else -> "auto.role.work.auto"
    }
    return LocalGitMirrorBundle.message(key)
  }

  /**
   * Auto-detect from the baseUrl: localhost → HOME; otherwise compare the
   * resolved host against local network interface addresses.
   * Falls back to WORK on any error.
   */
  private fun detectFromUrl(baseUrl: String): MachineRole = runCatching {
    if (baseUrl.isBlank()) return@runCatching MachineRole.WORK

    val host = parseHost(baseUrl) ?: return@runCatching MachineRole.WORK
    if (host.lowercase() in LOCAL_HOSTS) return@runCatching MachineRole.HOME

    // Resolve the target host's addresses
    val targetAddresses = InetAddress.getAllByName(host).map { it.hostAddress }.toSet()

    // Walk all local network interfaces and compare
    val localAddresses = mutableSetOf<String>()
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching MachineRole.WORK
    for (ni in interfaces) {
      for (addr in ni.interfaceAddresses) {
        localAddresses.add(addr.address.hostAddress)
      }
    }

    // If any target address matches a local address → HOME
    if (targetAddresses.any { it in localAddresses }) MachineRole.HOME
    else MachineRole.WORK
  }.getOrElse { MachineRole.WORK }

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
