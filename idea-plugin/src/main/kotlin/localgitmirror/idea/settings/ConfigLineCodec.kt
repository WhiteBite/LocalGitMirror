package localgitmirror.idea.settings

import java.nio.charset.StandardCharsets
import java.util.Base64

data class ConfigSnapshot(
  val baseUrl: String,
  val repo: String,
  val mirrorInsecureTls: Boolean,
  val offlineGenerateOnly: Boolean,
  val simpleUiMode: Boolean,
  val gitLabBaseUrl: String,
  val gitLabProject: String,
  val gitLabInsecureTls: Boolean,
  val gitRemoteName: String,
  val pullBackDefaultMode: String,
  val mirrorApiKey: String,
  val syncPassword: String,
  val gitLabToken: String
)

object ConfigLineCodec {
  const val PREFIX = "LGM_CONFIG_V1:"
  private const val PREFIX_MARKER = "LGM_CONFIG_V1"

  private val TOKEN_REGEX = Regex(
    pattern = """(?is)\bLGM_CONFIG_V1\s*[:=]\s*""",
    options = setOf(RegexOption.IGNORE_CASE)
  )

  private val ANSI_REGEX = Regex("""\u001B\[[;\d]*m""")

  private fun stripZeroWidth(text: String): String {
    return text
      .replace("\uFEFF", "")
      .replace("\u200B", "")
      .replace("\u200C", "")
      .replace("\u200D", "")
  }

  private fun stripAnsi(text: String): String = ANSI_REGEX.replace(text, "")

  private fun sanitize(text: String): String = stripAnsi(stripZeroWidth(text))

  fun encode(snapshot: ConfigSnapshot): String {
    val raw = listOf(
      "baseUrl=${snapshot.baseUrl}",
      "repo=${snapshot.repo}",
      "mirrorInsecureTls=${snapshot.mirrorInsecureTls}",
      "offlineGenerateOnly=${snapshot.offlineGenerateOnly}",
      "simpleUiMode=${snapshot.simpleUiMode}",
      "gitLabBaseUrl=${snapshot.gitLabBaseUrl}",
      "gitLabProject=${snapshot.gitLabProject}",
      "gitLabInsecureTls=${snapshot.gitLabInsecureTls}",
      "gitRemoteName=${snapshot.gitRemoteName}",
      "pullBackDefaultMode=${snapshot.pullBackDefaultMode}",
      "mirrorApiKey=${snapshot.mirrorApiKey}",
      "syncPassword=${snapshot.syncPassword}",
      "gitLabToken=${snapshot.gitLabToken}"
    ).joinToString("\n")

    val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    return "$PREFIX$encoded"
  }

  fun decode(token: String): ConfigSnapshot? {
    val line = sanitize(token).trim()
    if (line.isBlank()) return null

    // Accept direct key=value payload (useful for manual paste/debug)
    if (line.contains("baseUrl=") && line.contains("repo=")) {
      return parseRawPayload(line)
    }

    val normalized = extractToken(line) ?: return null
    val payload = normalized.removePrefix(PREFIX).trim()
    if (payload.isBlank()) return null

    val decoded = decodePayloadToRaw(payload) ?: return null

    return parseRawPayload(decoded)
  }

  private fun decodePayloadToRaw(payload: String): String? {
    val compact = payload.filterNot { it.isWhitespace() }
    if (compact.isBlank()) return null

    val normalized = compact.replace('-', '+').replace('_', '/')
    val padLen = (4 - (normalized.length % 4)) % 4
    val padded = normalized + "=".repeat(padLen)

    val direct = runCatching {
      String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
    }.getOrNull()
    if (direct != null) return direct

    val urlPadded = compact + "=".repeat((4 - (compact.length % 4)) % 4)
    return runCatching {
      String(Base64.getUrlDecoder().decode(urlPadded), StandardCharsets.UTF_8)
    }.getOrNull()
  }

  private fun isValidPayloadCandidate(candidate: String): Boolean {
    val raw = decodePayloadToRaw(candidate) ?: return false
    return parseRawPayload(raw) != null
  }

  private fun findValidPayloadPrefix(compact: String): String? {
    if (isValidPayloadCandidate(compact)) return compact

    // Try trimming trailing junk from noisy clipboard content.
    for (end in compact.length - 1 downTo 8) {
      val candidate = compact.substring(0, end)
      if (isValidPayloadCandidate(candidate)) {
        return candidate
      }
    }
    return null
  }

  private fun parseRawPayload(raw: String): ConfigSnapshot? {
    val map = stripZeroWidth(raw)
      .lines()
      .mapNotNull { ln ->
        val idx = ln.indexOf('=')
        if (idx <= 0) null else ln.substring(0, idx) to ln.substring(idx + 1)
      }
      .toMap()

    if (map["baseUrl"].isNullOrBlank()) {
      return null
    }

    return ConfigSnapshot(
      baseUrl = map["baseUrl"].orEmpty(),
      repo = map["repo"].orEmpty(),
      mirrorInsecureTls = map["mirrorInsecureTls"].equals("true", ignoreCase = true),
      offlineGenerateOnly = map["offlineGenerateOnly"].equals("true", ignoreCase = true),
      simpleUiMode = map["simpleUiMode"].equals("true", ignoreCase = true),
      gitLabBaseUrl = map["gitLabBaseUrl"].orEmpty(),
      gitLabProject = map["gitLabProject"].orEmpty(),
      gitLabInsecureTls = map["gitLabInsecureTls"].equals("true", ignoreCase = true),
      gitRemoteName = map["gitRemoteName"].orEmpty().ifBlank { "origin" },
      pullBackDefaultMode = map["pullBackDefaultMode"].orEmpty().ifBlank { "new-branch" },
      mirrorApiKey = map["mirrorApiKey"].orEmpty(),
      syncPassword = map["syncPassword"].orEmpty(),
      gitLabToken = map["gitLabToken"].orEmpty()
    )
  }

  fun extractToken(text: String): String? {
    val trimmed = sanitize(text).trim()
    if (trimmed.isBlank()) return null

    val match = TOKEN_REGEX.find(trimmed) ?: return null
    val tail = trimmed.substring(match.range.last + 1)

    // Primary strategy: token usually sits on one line after prefix.
    val firstLine = tail.lineSequence().firstOrNull().orEmpty().trim()
    val firstLineCompact = firstLine.takeWhile {
      it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_'
    }
    if (firstLineCompact.isNotBlank()) {
      val payload = findValidPayloadPrefix(firstLineCompact)
      if (!payload.isNullOrBlank()) {
        return PREFIX + payload
      }
    }

    // Fallback: handle wrapped payloads in noisy text.
    val payloadLike = tail
      .takeWhile { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' || it.isWhitespace() }
    val compact = payloadLike.filterNot { it.isWhitespace() }
    if (compact.isBlank()) return null

    val payload = findValidPayloadPrefix(compact) ?: return null
    return PREFIX + payload
  }

  /**
   * Extracts first valid token from arbitrary text, or null.
   * More tolerant than extractToken(): also supports heavily wrapped text.
   */
  fun extractOrNull(text: String): String? {
    val direct = extractToken(text)
    if (!direct.isNullOrBlank()) return direct

    val sanitized = sanitize(text)
    val compact = sanitized.replace("\r", "").replace("\n", "").replace("\t", "").replace(" ", "")
    val idx = compact.indexOf(PREFIX_MARKER, ignoreCase = true)
    if (idx >= 0) {
      // Support both ':' and '=' after marker.
      val after = compact.substring(idx + PREFIX_MARKER.length)
      if (after.isNotEmpty() && (after[0] == ':' || after[0] == '=')) {
        val payloadRaw = after.substring(1)
        val payload = payloadRaw.takeWhile {
          it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_'
        }
        if (payload.isNotBlank()) {
          val normalized = PREFIX + payload
          if (decode(normalized) != null) return normalized
        }
      }
    }

    // Last fallback: try to detect standalone payload chunks (copied without prefix).
    val candidates = Regex("""[A-Za-z0-9+/=_-]{80,}""").findAll(compact).map { it.value }
    for (c in candidates) {
      val normalized = PREFIX + c
      if (decode(normalized) != null) return normalized
    }
    return null
  }
}
