package localgitmirror.idea.mirror

/**
 * Stable HTTP contract shared by connection checking and LAN discovery.
 * Keep these paths separate: discovery is deliberately public, while health
 * verifies that the configured API key grants access to the Mirror API.
 */
internal object MirrorConnectionContract {
  const val PUBLIC_CAPABILITIES_PATH = "/api/capabilities"
  const val AUTHENTICATED_HEALTH_PATH = "/api/health"

  fun authenticatedHealthUrl(baseUrl: String): String =
    baseUrl.trimEnd('/') + AUTHENTICATED_HEALTH_PATH

  fun missingConfigurationMessage(baseUrl: String, apiKey: String): String? = when {
    baseUrl.isBlank() -> "Укажите Mirror URL."
    apiKey.isBlank() -> "Укажите API key из консоли Mirror-сервера."
    else -> null
  }
}
