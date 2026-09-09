package localgitmirror.idea.gitlab

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import java.io.File

/**
 * Resolve the effective GitLab connection for a project.
 *
 * Precedence:
 *  1. URL: explicit override from settings (gitlabUrl in MirrorSettingsService.State),
 *     otherwise auto-detected from the project's default git remote
 *     (base = scheme://host[:port]).
 *  2. Project: always auto-detected from the current project's default git
 *     remote (remote path minus a trailing ".git") - different projects map
 *     to different GitLab projects without any settings.
 *  3. Token: SecretsStore.gitlabToken, falling back to the GITLAB_TOKEN
 *     environment variable when the store is empty.
 */
object GitLabConfig {

  data class GitLabConf(
    val url: String,
    val project: String,
    val token: String
  )

  fun resolve(project: Project): GitLabConf {
    val state = service<MirrorSettingsService>().state
    val urlOverride = state.gitlabUrl.trim().trimEnd('/')

    // Auto-detect from the default git remote of this project.
    var autoUrl = ""
    var autoProject = ""
    val projectDir = project.basePath?.let { File(it) }
    if (projectDir != null) {
      try {
        val remote = GitLocal.defaultRemote(project, projectDir)
        val remoteUrl = GitLocal.remoteUrl(project, projectDir, remote)
        val parsed = parseRemoteUrl(remoteUrl)
        autoUrl = parsed.first
        autoProject = parsed.second
      } catch (_: Throwable) {
        // best-effort auto-detection: no remote / not a git repo / git missing
      }
    }

    val token = SecretsStore.gitlabToken.ifBlank {
      try {
        System.getenv("GITLAB_TOKEN") ?: ""
      } catch (_: Throwable) {
        ""
      }
    }

    return GitLabConf(
      url = urlOverride.ifBlank { autoUrl },
      project = autoProject,
      token = token.trim()
    )
  }

  /** True when the GitLab REST API can be called (list/resolve MRs). */
  fun hasApi(conf: GitLabConf): Boolean =
    conf.url.isNotBlank() && conf.project.isNotBlank() && conf.token.isNotBlank()

  /**
   * Parse a git remote URL into (apiBaseUrl, projectPath).
   *
   * Examples:
   *   https://gitlab.example.com:8443/group/sub/repo.git
   *     -> ("https://gitlab.example.com:8443", "group/sub/repo")
   *   http://gitlab.local/group/repo
   *     -> ("http://gitlab.local", "group/repo")
   *   git@gitlab.example.com:group/repo.git          (scp-like)
   *     -> ("https://gitlab.example.com", "group/repo")
   *   ssh://git@gitlab.example.com:2222/group/repo.git
   *     -> ("https://gitlab.example.com", "group/repo")
   *
   * Non-http(s) schemes are normalized to https (the REST API is HTTP); their
   * port is dropped because an ssh port is not an API port. Local file paths
   * yield ("", "") — no host, no API.
   */
  fun parseRemoteUrl(remoteUrl: String): Pair<String, String> {
    val s = remoteUrl.trim()
    if (s.isBlank()) return "" to ""

    var scheme = "https"
    var host: String
    var port = ""
    var path = ""

    if (s.contains("://")) {
      // scheme://[user@]host[:port]/path
      val rawScheme = s.substringBefore("://").lowercase()
      val afterScheme = s.substringAfter("://")
      val slash = afterScheme.indexOf('/')
      val hostPart = if (slash < 0) afterScheme else afterScheme.substring(0, slash)
      path = if (slash < 0) "" else afterScheme.substring(slash + 1)
      val hostNoUser = hostPart.substringAfter('@')
      val colon = hostNoUser.lastIndexOf(':')
      if (colon > 0) {
        host = hostNoUser.substring(0, colon)
        port = hostNoUser.substring(colon + 1)
      } else {
        host = hostNoUser
      }
      if (rawScheme == "http" || rawScheme == "https") {
        scheme = rawScheme
      } else {
        // ssh://, git:// … — the API is served over http(s); drop the ssh port.
        port = ""
      }
    } else if (s.contains(':') && s.contains('@')) {
      // scp-like: git@host:group/repo(.git) — path is after the first ':'
      val afterAt = s.substringAfter('@')
      val colon = afterAt.indexOf(':')
      if (colon > 0) {
        host = afterAt.substring(0, colon)
        path = afterAt.substring(colon + 1)
      } else {
        host = afterAt
      }
    } else {
      // bare/local path (file remote) — no host, no API base
      return "" to ""
    }

    if (host.isBlank()) return "" to ""

    path = path.replace('\\', '/').trim().trim('/')
    if (path.endsWith(".git", ignoreCase = true)) path = path.dropLast(4)
    if (path.isBlank()) return "" to ""

    val base = buildString {
      append(scheme)
      append("://")
      append(host)
      if (port.isNotBlank()) append(':').append(port)
    }
    return base to path
  }
}
