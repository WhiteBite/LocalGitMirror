package localgitmirror.idea.net

import java.net.URL

sealed interface TlsDecision {
  data class Pinned(val storedHex: String) : TlsDecision
  object TrustAll : TlsDecision
  object Default : TlsDecision

  data class Input(
    val baseUrl: String,
    val pinEnabled: Boolean,
    val mirrorInsecure: Boolean,
    val callerInsecure: Boolean,
    val storedPin: String
  )

  companion object {
    fun resolve(url: URL, input: Input): TlsDecision {
      if (url.protocol != "https") return Default
      val base = try {
        URL(input.baseUrl.trim())
      } catch (_: Throwable) {
        null
      }
      val isMirror = base != null &&
        url.host.equals(base.host, ignoreCase = true) &&
        effectivePort(url) == effectivePort(base)
      if (isMirror) {
        if (input.mirrorInsecure) return TrustAll
        if (input.pinEnabled) return Pinned(input.storedPin)
        return Default
      }
      return if (input.callerInsecure) TrustAll else Default
    }

    private fun effectivePort(url: URL): Int = if (url.port != -1) url.port else url.defaultPort
  }
}
