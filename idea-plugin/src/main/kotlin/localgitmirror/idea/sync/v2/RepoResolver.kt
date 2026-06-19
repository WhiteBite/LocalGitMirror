package localgitmirror.idea.sync.v2

import com.intellij.openapi.project.Project
import localgitmirror.idea.git.GitLocal
import java.io.File

object RepoResolver {
  private fun sanitize(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return ""
    return trimmed
      .lowercase()
      .replace(Regex("[^a-z0-9_-]+"), "-")
      .replace(Regex("-+"), "-")
      .trim('-')
  }

  /**
   * Derive a stable repo slug from a git remote URL.
   *
   * The same repository has the same remote URL on every machine, so this is
   * the only identity that stays consistent between the dome PC and the work
   * laptop (project name / directory name differ per checkout). Used as the
   * preferred auto-source for the Mirror repo key.
   *
   * Handles the common remote forms:
   *   - https://host/group/sub/repo.git        -> repo
   *   - https://host:8443/group/repo           -> repo
   *   - git@host:group/repo.git                -> repo
   *   - ssh://git@host:22/group/repo.git       -> repo
   *   - /local/path/to/repo.git (file remote)  -> repo
   *
   * Returns "" when no usable last path segment can be extracted.
   * Internal/pure for honest unit testing.
   */
  fun repoNameFromRemoteUrl(remoteUrl: String): String {
    var s = remoteUrl.trim()
    if (s.isBlank()) return ""

    // Strip a trailing slash so ".../repo/" still yields "repo".
    s = s.trimEnd('/')

    // scp-like syntax: git@host:group/repo(.git) — the path is after the first ':'
    // but ONLY when there's no scheme (no "://"). For scheme URLs the ':' may be a port.
    val path: String = if (!s.contains("://") && s.contains(':') && s.contains('@')) {
      s.substringAfter(':')
    } else if (s.contains("://")) {
      // scheme://[user@]host[:port]/path → take everything after the host part
      val afterScheme = s.substringAfter("://")
      val slash = afterScheme.indexOf('/')
      if (slash < 0) "" else afterScheme.substring(slash + 1)
    } else {
      // bare path or windows path
      s.replace('\\', '/')
    }

    // Last non-empty path segment, minus an optional .git suffix.
    val lastSegment = path.replace('\\', '/').trimEnd('/').substringAfterLast('/')
    val withoutGit = if (lastSegment.endsWith(".git", ignoreCase = true))
      lastSegment.dropLast(4) else lastSegment

    return sanitize(withoutGit)
  }

  fun resolve(project: Project, projectDir: File, configuredRepo: String): RepoResolution {
    val remoteUrl = try {
      val remote = GitLocal.defaultRemote(project, projectDir)
      GitLocal.remoteUrl(project, projectDir, remote)
    } catch (_: Throwable) { "" }
    return resolveByNames(project.name, projectDir.name, configuredRepo, remoteUrl)
  }

  fun resolveByNames(projectName: String, directoryName: String, configuredRepo: String): RepoResolution =
    resolveByNames(projectName, directoryName, configuredRepo, remoteUrl = "")

  fun resolveByNames(
    projectName: String,
    directoryName: String,
    configuredRepo: String,
    remoteUrl: String
  ): RepoResolution {
    val trimmed = configuredRepo.trim()
    if (trimmed.isNotBlank()) {
      val sanitized = sanitize(trimmed)
      if (sanitized.isBlank()) {
        return RepoResolution(
          source = RepoSource.SETTINGS,
          raw = trimmed,
          sanitized = "",
          error = "Configured repo name is invalid after sanitization"
        )
      }
      return RepoResolution(RepoSource.SETTINGS, trimmed, sanitized)
    }

    // Prefer the git remote URL: it's identical on every machine, so the dome
    // and the work laptop derive the SAME repo key without manual config.
    val fromRemote = repoNameFromRemoteUrl(remoteUrl)
    if (fromRemote.isNotBlank()) {
      return RepoResolution(RepoSource.GIT_REMOTE, remoteUrl.trim(), fromRemote)
    }

    val fromProject = sanitize(projectName)
    if (fromProject.isNotBlank()) {
      return RepoResolution(RepoSource.PROJECT_NAME, projectName, fromProject)
    }

    val fromDir = sanitize(directoryName)
    if (fromDir.isNotBlank()) {
      return RepoResolution(RepoSource.DIRECTORY_NAME, directoryName, fromDir)
    }

    return RepoResolution(RepoSource.DEFAULT, "default", "default")
  }
}
