package localgitmirror.idea.sync.v2

import com.intellij.openapi.project.Project
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

  fun resolve(project: Project, projectDir: File, configuredRepo: String): RepoResolution {
    return resolveByNames(project.name, projectDir.name, configuredRepo)
  }

  fun resolveByNames(projectName: String, directoryName: String, configuredRepo: String): RepoResolution {
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
