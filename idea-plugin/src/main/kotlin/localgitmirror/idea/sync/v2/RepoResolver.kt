package localgitmirror.idea.sync.v2

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.settings.MirrorProjectSettingsService
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

  /** Repo-local git config key that OLDER versions used to pin the repo name.
   * We no longer write it; [resolve] removes it if present so stale values
   * stop overriding the folder/remote name. */
  const val PIN_KEY = "localgitmirror.repo"

  fun resolve(project: Project, projectDir: File, configuredRepo: String): RepoResolution {
    val remoteUrl = try {
      val remote = GitLocal.defaultRemote(project, projectDir)
      GitLocal.remoteUrl(project, projectDir, remote)
    } catch (_: Throwable) { "" }

    // Self-heal: older versions wrote a "pinned" repo name into .git/config
    // (localgitmirror.repo). In practice that pin got STUCK — a stale value
    // (e.g. an old "onyx-platform") kept overriding the correct folder/remote
    // name even after the settings field was cleared. We no longer pin; strip
    // any leftover pin so the name is always derived fresh.
    try {
      if (!GitLocal.getConfigLocal(project, projectDir, PIN_KEY).isNullOrBlank()) {
        GitLocal.unsetConfigLocal(project, projectDir, PIN_KEY)
      }
    } catch (_: Throwable) { /* best-effort cleanup, never required */ }

    // The repo name is stored PER PROJECT (workspace), never globally. An
    // explicit arg (rare) still wins; otherwise use this project's own value.
    val projectSettings = try {
      project.service<MirrorProjectSettingsService>()
    } catch (_: Throwable) { null }
    val effective = configuredRepo.ifBlank { projectSettings?.state?.repoOverride ?: "" }

    val resolution = resolveByNames(project.name, projectDir.name, effective, remoteUrl)

    // Auto-record the resolved name into THIS project's store so the open
    // project always has its repo written down — without the user typing it.
    // Only when nothing was set yet and the source is the deterministic git
    // remote (identical on every machine); never a weak folder/project guess,
    // so a stored value can't get "stuck" pointing at the wrong repo.
    if (projectSettings != null &&
      effective.isBlank() &&
      resolution.error == null &&
      resolution.source == RepoSource.GIT_REMOTE &&
      resolution.sanitized.isNotBlank()
    ) {
      try {
        projectSettings.state.repoOverride = resolution.sanitized
      } catch (_: Throwable) { /* best-effort persistence */ }
    }

    return resolution
  }

  fun resolveByNames(
    projectName: String,
    directoryName: String,
    configuredRepo: String,
    remoteUrl: String = ""
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

    // 2. Git remote URL: identical on every machine, so the dome and the work
    // laptop derive the SAME repo key without manual config.
    val fromRemote = repoNameFromRemoteUrl(remoteUrl)
    if (fromRemote.isNotBlank()) {
      return RepoResolution(RepoSource.GIT_REMOTE, remoteUrl.trim(), fromRemote)
    }

    // 3-4. Weak fallbacks for a local-only repo (no remote). The caller may
    // surface a warning since these can differ between machines.
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
