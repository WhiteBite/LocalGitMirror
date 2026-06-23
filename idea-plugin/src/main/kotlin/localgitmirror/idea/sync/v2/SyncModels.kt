package localgitmirror.idea.sync.v2

import localgitmirror.idea.settings.MirrorSettingsService
import java.time.Instant
import java.util.UUID

enum class RepoSource {
  SETTINGS,
  PINNED,
  GIT_REMOTE,
  PROJECT_NAME,
  DIRECTORY_NAME,
  DEFAULT
}

data class RepoResolution(
  val source: RepoSource,
  val raw: String,
  val sanitized: String,
  val error: String? = null
)

data class SettingsSnapshot(
  val baseUrl: String,
  val repoConfigured: String,
  val mirrorInsecureTls: Boolean,
  val offlineGenerateOnly: Boolean,
  val mirrorApiKey: String,
  val syncPassword: String
) {
  companion object {
    fun from(state: MirrorSettingsService.State, mirrorApiKey: String, syncPassword: String): SettingsSnapshot {
      return SettingsSnapshot(
        baseUrl = state.baseUrl,
        repoConfigured = state.repo,
        mirrorInsecureTls = state.mirrorInsecureTls,
        offlineGenerateOnly = state.offlineGenerateOnly,
        mirrorApiKey = mirrorApiKey,
        syncPassword = syncPassword
      )
    }
  }
}

enum class SyncStepOutcome {
  OK,
  FAIL,
  SKIP
}

data class SyncStep(
  val id: String,
  val outcome: SyncStepOutcome,
  val message: String,
  val fields: Map<String, String> = emptyMap(),
  val at: Instant = Instant.now()
)

class SyncDiagnostics(
  val traceId: String = UUID.randomUUID().toString()
) {
  private val _steps = mutableListOf<SyncStep>()
  val steps: List<SyncStep>
    get() = _steps.toList()

  fun add(step: SyncStep) {
    _steps.add(step)
  }
}
