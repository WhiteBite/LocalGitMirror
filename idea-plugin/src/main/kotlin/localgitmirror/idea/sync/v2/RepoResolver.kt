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

  /** Repo-local git config key used to pin the resolved Mirror repo name. */
  const val PIN_KEY = "localgitmirror.repo"

  fun resolve(project: Project, projectDir: File, configuredRepo: String): RepoResolution {
    val remoteUrl = try {
      val remote = GitLocal.defaultRemote(project, projectDir)
      GitLocal.remoteUrl(project, projectDir, remote)
    } catch (_: Throwable) { "" }

    val pinned = try {
      GitLocal.getConfigLocal(project, projectDir, PIN_KEY) ?: ""
    } catch (_: Throwable) { "" }

    val resolution = resolveByNames(project.name, projectDir.name, configuredRepo, remoteUrl, pinned)

    // Pin strong identities into .git/config so the key never silently drifts
    // later (folder/project rename, cleared IDE settings, transient remote read
    // failure). We only pin values that came from an explicit setting or the git
    // remote — never the weak project/directory-name guesses that caused drift.
    if (resolution.error == null &&
      resolution.sanitized.isNotBlank() &&
      (resolution.source == RepoSource.SETTINGS || resolution.source == RepoSource.GIT_REMOTE) &&
      sanitize(pinned) != resolution.sanitized
    ) {
      try {
        GitLocal.setConfigLocal(project, projectDir, PIN_KEY, resolution.sanitized)
      } catch (_: Throwable) { /* best-effort: pinning is an optimization, not required */ }
    }

    return resolution
  }

  fun resolveByNames(
    projectName: String,
    directoryName: String,
    configuredRepo: String,
    remoteUrl: String = "",
    pinnedRepo: String = ""
  ): RepoResolution {
    // 1. Explicit user override always wins.
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

    // 2. Pinned value: a previously-resolved strong identity cached in
    // .git/config. Survives folder/project renames and transient remote-read
    // failures, so the key stays stable on this machine. Both machines pin the
    // same value because both derive it from the same remote URL.
    val pinned = sanitize(pinnedRepo)
    if (pinned.isNotBlank()) {
      return RepoResolution(RepoSource.PINNED, pinnedRepo.trim(), pinned)
    }

    // 3. Git remote URL: identical on every machine, so the dome and the work
    // laptop derive the SAME repo key without manual config.
    val fromRemote = repoNameFromRemoteUrl(remoteUrl)
    if (fromRemote.isNotBlank()) {
      return RepoResolution(RepoSource.GIT_REMOTE, remoteUrl.trim(), fromRemote)
    }

    // 4-5. Weak fallbacks. These differ between machines and over time, so they
    // are NEVER pinned by resolve(); they exist only so a local-only repo (no
    // remote) still gets a usable name. The caller should surface a warning.
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
