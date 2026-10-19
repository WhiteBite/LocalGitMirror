package localgitmirror.idea.settings

import java.net.URI

internal data class MirrorSettingsFieldError(
  val messageKey: String,
  val args: List<Any> = emptyList()
)

internal fun validateMirrorSettings(rawBaseUrl: String, rawDepsPollSec: String): List<MirrorSettingsFieldError> {
  val errors = mutableListOf<MirrorSettingsFieldError>()
  val url = rawBaseUrl.trim()
  if (url.isBlank()) {
    errors += MirrorSettingsFieldError("settings.validation.url.blank")
  } else {
    val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    val authority = runCatching { URI(normalized).rawAuthority }.getOrNull()
    if (authority.isNullOrBlank()) errors += MirrorSettingsFieldError("settings.validation.url.malformed")
  }
  val pollSec = rawDepsPollSec.trim().toIntOrNull()
  when {
    pollSec == null -> errors += MirrorSettingsFieldError("settings.validation.pollSec.invalid")
    pollSec !in 15..600 -> errors += MirrorSettingsFieldError("settings.validation.pollSec.range", listOf(15, 600))
  }
  return errors
}
