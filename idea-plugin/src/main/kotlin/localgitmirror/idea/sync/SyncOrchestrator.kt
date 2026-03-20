package localgitmirror.idea.sync

import com.intellij.openapi.project.Project
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.sync.v2.SettingsSnapshot
import localgitmirror.idea.sync.v2.SyncEngine
import localgitmirror.idea.sync.v2.SyncStep
import java.io.File

/**
 * Compatibility wrapper over SyncEngine.
 *
 * New code should use localgitmirror.idea.sync.v2.SyncFacadeService.
 */
object SyncOrchestrator {
  private val engine = SyncEngine()

  data class StepResult(
    val ok: Boolean,
    val message: String,
    val details: String = ""
  )

  data class FullSyncResult(
    val step: StepResult,
    val http: MirrorApi.HttpResult?,
    val dump: File?,
    val repo: String?,
    val traceId: String,
    val diagnostics: List<SyncStep>
  )

  fun validateSettings(settings: MirrorSettingsService.State): StepResult {
    return engine.validateSettings(settings).toLegacy()
  }

  fun sanitizeRepoName(input: String): String {
    return engine.sanitizeRepoName(input)
  }

  fun inferRepoName(project: Project, projectDir: File, settings: MirrorSettingsService.State): String {
    return engine.inferRepoName(project, projectDir, settings)
  }

  fun ensureRemoteRepo(baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean): StepResult {
    return engine.ensureRemoteRepo(baseUrl, apiKey, repo, insecureTls).toLegacy()
  }

  fun ensureWorkTreeClean(project: Project, projectDir: File): StepResult {
    return engine.ensureWorkTreeClean(project, projectDir).toLegacy()
  }

  fun generateDump(
    project: Project,
    projectDir: File,
    settings: SettingsSnapshot,
    repoName: String,
    preferredBase: String? = null
  ): StepResult {
    return engine.generateDump(project, projectDir, settings, repoName, preferredBase).toLegacy()
  }

  fun parseKnownCommitHashes(body: String): Set<String> {
    return engine.parseKnownCommitHashes(body)
  }

  fun pickBestKnownBase(head: String, candidates: List<String>, known: Set<String>): String? {
    return engine.pickBestKnownBase(head, candidates, known)
  }

  fun findLatestDump(projectDir: File, repoName: String): Pair<StepResult, File?> {
    val (step, dump) = engine.findLatestDump(projectDir, repoName)
    return step.toLegacy() to dump
  }

  fun uploadAndApply(settings: SettingsSnapshot, repoName: String, dump: File): Pair<StepResult, MirrorApi.HttpResult> {
    val (step, http) = engine.uploadAndApply(settings, repoName, dump)
    return step.toLegacy() to http
  }

  fun parseJsonSuccess(body: String): Boolean? {
    return engine.parseJsonSuccess(body)
  }

  fun runFullSync(project: Project, projectDir: File, settings: MirrorSettingsService.State): FullSyncResult {
    return engine.runFullSync(project, projectDir, settings).toLegacy()
  }

  private fun SyncEngine.StepResult.toLegacy(): StepResult {
    return StepResult(ok = ok, message = message, details = details)
  }

  private fun SyncEngine.FullSyncResult.toLegacy(): FullSyncResult {
    return FullSyncResult(
      step = step.toLegacy(),
      http = http,
      dump = dump,
      repo = repo,
      traceId = traceId,
      diagnostics = diagnostics
    )
  }
}
