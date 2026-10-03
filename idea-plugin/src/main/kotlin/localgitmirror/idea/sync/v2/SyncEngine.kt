package localgitmirror.idea.sync.v2

import com.intellij.openapi.project.Project
import localgitmirror.idea.mirror.HttpResult
import localgitmirror.idea.mirror.MirrorCrypto
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.git.RepoMaintenance
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import java.io.File

/** Blank sync password is acceptable when a v3 server key is pinned (relay crypto replaces it). */
fun syncPasswordConfigured(syncPassword: String, v3Pinned: Boolean): Boolean =
  syncPassword.isNotBlank() || v3Pinned

class SyncEngine(
  internal val mirror: MirrorPort = DefaultMirrorPort,
  internal val git: GitPort = DefaultGitPort,
  internal val workKit: WorkKitPort = DefaultWorkKitPort,
  internal val state: SyncStatePort = DefaultSyncStatePort,
  private val resolver: RepoResolverPort = DefaultRepoResolverPort
) {
  data class StepResult(
    val ok: Boolean,
    val message: String,
    val details: String = ""
  )

  data class FullSyncResult(
    val step: StepResult,
    val http: HttpResult?,
    val dump: File?,
    val repo: String?,
    val traceId: String,
    val diagnostics: List<SyncStep>
  )

  data class NegotiationResult(
    val pointerCommit: String?,
    val baseCommit: String?
  )

  fun validateSettings(settings: MirrorSettingsService.State): StepResult {
    val snap = SettingsSnapshot.from(settings, SecretsStore.mirrorApiKey, SecretsStore.syncPassword)
    return validateSettings(snap)
  }

  private fun validateSettings(settings: SettingsSnapshot): StepResult {
    if (settings.baseUrl.isBlank()) {
      return StepResult(false, "Configure server URL in settings")
    }
    if (!syncPasswordConfigured(settings.syncPassword, MirrorCrypto.isV3Pinned())) {
      return StepResult(false, "Configure Sync Password in settings")
    }
    return StepResult(true, "OK")
  }

  fun sanitizeRepoName(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return ""
    return trimmed
      .lowercase()
      .replace(Regex("[^a-z0-9_-]+"), "-")
      .replace(Regex("-+"), "-")
      .trim('-')
  }

  fun inferRepoName(project: Project, projectDir: File, settings: MirrorSettingsService.State): String {
    // Repo is per-project (RepoResolver reads the project's own override / git
    // remote); pass "" so the global setting is never used here.
    return resolver.resolve(project, projectDir, "").sanitized
  }

  fun ensureRemoteRepo(baseUrl: String, apiKey: String, repo: String, syncPassword: String, insecureTls: Boolean, projectDir: File? = null): StepResult {
    val res = mirror.ensureRepoExists(baseUrl, apiKey, repo, syncPassword, insecureTls, projectDir)
    if (res.code !in 200..299) {
      return StepResult(false, "Failed to ensure Cache repo", "HTTP ${res.code}: ${res.body.take(300)}")
    }
    return StepResult(true, "Repo ready", res.body.take(300))
  }

  fun ensureWorkTreeClean(project: Project, projectDir: File): StepResult {
    if (!git.isCleanWorkTree(project, projectDir)) {
      return StepResult(false, "Working tree has uncommitted changes. Commit/stash before syncing.")
    }
    return StepResult(true, "OK")
  }

  data class MultiBranchNegotiation(
    val pointerCommit: String?,           // non-null if current HEAD already on Mirror
    val excludeBases: List<String>         // best known bases for ALL branches
  )

  private fun diag(
    projectDir: java.io.File?,
    diagnostics: SyncDiagnostics,
    id: String,
    outcome: SyncStepOutcome,
    message: String,
    fields: Map<String, String> = emptyMap()
  ) {
    if (projectDir != null) {
      val msg = "[$id] [${outcome.name}] $message" + if (fields.isNotEmpty()) " $fields" else ""
      localgitmirror.idea.sync.SyncLogger.log(projectDir, msg)
    }
    diagnostics.add(SyncStep(id = id, outcome = outcome, message = message, fields = fields))
  }

  @Suppress("HttpCallOnEdt") // always called from Task.Backgroundable
  fun runFullSyncWithSnapshot(
    project: Project,
    projectDir: File,
    snapshot: SettingsSnapshot,
    additionalBranches: List<String> = emptyList()
  ): FullSyncResult {
    val diagnostics = SyncDiagnostics()
    val traceId = diagnostics.traceId
    return try {
      state.migrateLegacyIfPresent(projectDir)
      val removedJunk = git.removeJunkHeadBranches(project, projectDir)
      if (removedJunk.isNotEmpty()) {
        diag(
          projectDir,
          diagnostics,
          id = "junk-cleanup",
          outcome = SyncStepOutcome.OK,
          message = "Removed junk HEAD-named local branches",
          fields = mapOf("branches" to removedJunk.joinToString(","))
        )
      }
      diag(
        projectDir,
        diagnostics,
        id = "snapshot",
        outcome = SyncStepOutcome.OK,
        message = "Captured immutable settings snapshot",
        fields = mapOf(
          "baseUrl" to snapshot.baseUrl,
          "repoConfigured" to snapshot.repoConfigured,
          "offlineGenerateOnly" to snapshot.offlineGenerateOnly.toString()
        )
      )

      val repoResolution = resolver.resolve(project, projectDir, snapshot.repoConfigured)
      if (!repoResolution.error.isNullOrBlank()) {
        val step = StepResult(false, repoResolution.error)
        diag(projectDir, diagnostics, "resolve-repo", SyncStepOutcome.FAIL, step.message, mapOf("repoConfigured" to snapshot.repoConfigured))
        return FullSyncResult(step, null, null, null, traceId, diagnostics.steps)
      }
      val repoName = repoResolution.sanitized
      if (repoName.isBlank()) {
        val step = StepResult(false, "Unable to infer repository name")
        diag(projectDir, diagnostics, "resolve-repo", SyncStepOutcome.FAIL, step.message)
        return FullSyncResult(step, null, null, null, traceId, diagnostics.steps)
      }
      diag(
        projectDir,
        diagnostics,
        id = "resolve-repo",
        outcome = SyncStepOutcome.OK,
        message = "Resolved target repository",
        fields = mapOf("repo" to repoName, "source" to repoResolution.source.name)
      )

      val cfg = validateSettings(snapshot)
      if (!cfg.ok) {
        diag(projectDir, diagnostics, "validate-settings", SyncStepOutcome.FAIL, cfg.message)
        return FullSyncResult(cfg, null, null, repoName, traceId, diagnostics.steps)
      }
      diag(projectDir, diagnostics, "validate-settings", SyncStepOutcome.OK, cfg.message)

      val hs = verifyBackendHandshake(snapshot)
      if (!hs.ok) {
        diag(projectDir, diagnostics, "handshake", SyncStepOutcome.FAIL, hs.message, mapOf("details" to hs.details))
        return FullSyncResult(hs, null, null, repoName, traceId, diagnostics.steps)
      }
      diag(projectDir, diagnostics, "handshake", SyncStepOutcome.OK, hs.message)

      val ensureRepo = ensureRemoteRepo(snapshot.baseUrl, snapshot.mirrorApiKey, repoName, snapshot.syncPassword, snapshot.mirrorInsecureTls, projectDir)
      if (!ensureRepo.ok) {
        diag(projectDir, diagnostics, "ensure-remote-repo", SyncStepOutcome.FAIL, ensureRepo.message, mapOf("repo" to repoName))
        return FullSyncResult(ensureRepo, null, null, repoName, traceId, diagnostics.steps)
      }
      diag(projectDir, diagnostics, "ensure-remote-repo", SyncStepOutcome.OK, ensureRepo.message, mapOf("repo" to repoName))

      val clean = ensureWorkTreeClean(project, projectDir)
      if (!clean.ok) {
        diag(projectDir, diagnostics, "ensure-work-tree-clean", SyncStepOutcome.FAIL, clean.message)
        return FullSyncResult(clean, null, null, repoName, traceId, diagnostics.steps)
      }
      diag(projectDir, diagnostics, "ensure-work-tree-clean", SyncStepOutcome.OK, clean.message)

      val negotiation = negotiateMultiBranch(project, projectDir, snapshot, repoName, additionalBranches)
      diag(
        projectDir,
        diagnostics,
        id = "negotiate",
        outcome = SyncStepOutcome.OK,
        message = "Multi-branch negotiation completed",
        fields = mapOf(
          "pointerCommit" to (negotiation.pointerCommit ?: ""),
          "excludeBases" to negotiation.excludeBases.joinToString(",")
        )
      )

      val pointerHead = negotiation.pointerCommit
      if (!pointerHead.isNullOrBlank()) {
        // Collect all local branch names for auto-prune on server
        val localBranches = GitLocal.localBranches(project, projectDir)

        // Build branch→hash map for all branches the sender has
        val branchMap = mutableMapOf<String, String>()
        val currentBranch = git.currentBranch(project, projectDir).orEmpty()
        if (currentBranch.isNotBlank()) {
          branchMap[currentBranch] = pointerHead
        }
        for (br in additionalBranches) {
          if (br.isBlank() || localgitmirror.idea.git.GitLocal.isJunkBranchName(br)) continue
          val tip = git.branchHash(project, projectDir, br)
          if (!tip.isNullOrBlank()) {
            branchMap[br] = tip
          }
        }

        val applied = mirror.applyKnown(snapshot.baseUrl, snapshot.mirrorApiKey, repoName, pointerHead, branches = branchMap, syncPassword = snapshot.syncPassword, insecureTls = snapshot.mirrorInsecureTls, localBranches = localBranches)
        if (applied.code in 200..299) {
          val branchName = currentBranch
          state.updateAfterSend(projectDir, branchName, pointerHead)
          val okStep = StepResult(true, "Cache already had all commits; applied pointer-only (${branchMap.size} branch(es))", applied.body.take(500))
          diag(projectDir, diagnostics, "apply-known", SyncStepOutcome.OK, okStep.message, mapOf("repo" to repoName, "commit" to pointerHead, "branches" to branchMap.keys.joinToString(",")))
          RepoMaintenance.autoGcIfNeeded(project, projectDir)
          return FullSyncResult(okStep, applied, null, repoName, traceId, diagnostics.steps)
        }
        diag(projectDir, diagnostics, "apply-known", SyncStepOutcome.FAIL, "Pointer-only apply failed", mapOf("repo" to repoName, "httpCode" to applied.code.toString()))
      }

      // Marker BEFORE the long local step (git bundle + encrypt) so the sync
      // log shows we entered it — previously the log went silent for the
      // whole duration of bundle generation, which read as "hung, no logs".
      diag(
        projectDir,
        diagnostics,
        id = "generate-dump-start",
        outcome = SyncStepOutcome.OK,
        message = "Generating sync package locally (git bundle + encrypt)",
        fields = mapOf(
          "repo" to repoName,
          "branches" to (listOf(git.currentBranch(project, projectDir).orEmpty()) + additionalBranches)
            .filter { it.isNotBlank() }.distinct().joinToString(","),
          "excludeBases" to negotiation.excludeBases.joinToString(",")
        )
      )

      val dumpGen = generateDump(project, projectDir, snapshot, repoName, excludeBases = negotiation.excludeBases, additionalBranches = additionalBranches)
      if (!dumpGen.ok) {
        diag(projectDir, diagnostics, "generate-dump", SyncStepOutcome.FAIL, dumpGen.message, mapOf("repo" to repoName))
        return FullSyncResult(dumpGen, null, null, repoName, traceId, diagnostics.steps)
      }

      if (dumpGen.message == "No new changes to sync") {
        val branchName = git.currentBranch(project, projectDir).orEmpty()
        val headNow = git.headHash(project, projectDir)
        if (!headNow.isNullOrBlank()) {
          state.updateAfterSend(projectDir, branchName, headNow)
        }
        val okNoop = StepResult(true, "No new changes to sync; skipped upload", dumpGen.details)
        diag(projectDir, diagnostics, "generate-dump", SyncStepOutcome.SKIP, okNoop.message, mapOf("repo" to repoName, "branch" to branchName, "head" to (headNow ?: "")))
        return FullSyncResult(okNoop, null, null, repoName, traceId, diagnostics.steps)
      }

      diag(projectDir, diagnostics, "generate-dump", SyncStepOutcome.OK, dumpGen.message, mapOf("repo" to repoName))

      val (findRes, dump) = findLatestDump(projectDir, repoName, generationOutput = dumpGen.details)
      if (!findRes.ok || dump == null) {
        diag(projectDir, diagnostics, "find-dump", SyncStepOutcome.FAIL, findRes.message, mapOf("repo" to repoName))
        return FullSyncResult(findRes, null, null, repoName, traceId, diagnostics.steps)
      }
      diag(projectDir, diagnostics, "find-dump", SyncStepOutcome.OK, findRes.message, mapOf("dump" to dump.absolutePath))

      if (snapshot.offlineGenerateOnly) {
        diag(projectDir, diagnostics, "offline-mode", SyncStepOutcome.SKIP, "Skipping upload-and-apply due to offline mode", mapOf("repo" to repoName))
        return FullSyncResult(
          StepResult(true, "Dump generated (offline mode)", dump.absolutePath),
          null,
          dump,
          repoName,
          traceId,
          diagnostics.steps
        )
      }

      val localBranchesForUpload = GitLocal.localBranches(project, projectDir)
      val (uploadRes, http) = uploadAndApply(snapshot, repoName, dump, projectDir, localBranchesForUpload)
      if (!uploadRes.ok) {
        diag(projectDir, diagnostics, "upload-and-apply", SyncStepOutcome.FAIL, uploadRes.message, mapOf("repo" to repoName, "httpCode" to http.code.toString()))
        return FullSyncResult(uploadRes, http, dump, repoName, traceId, diagnostics.steps)
      }
      diag(projectDir, diagnostics, "upload-and-apply", SyncStepOutcome.OK, uploadRes.message, mapOf("repo" to repoName, "httpCode" to http.code.toString()))

      val branchName = git.currentBranch(project, projectDir).orEmpty()
      val headNow = git.headHash(project, projectDir)
      if (!headNow.isNullOrBlank()) {
        state.updateAfterSend(projectDir, branchName, headNow)
      }
      state.cleanupOldSyncFiles(projectDir)
      diag(projectDir, diagnostics, "update-state", SyncStepOutcome.OK, "State updated", mapOf("branch" to branchName, "head" to (headNow ?: "")))

      // A successful send means new objects landed in the local store; if it
      // is fragmented, consolidate it in the background (throttled, off-path).
      RepoMaintenance.autoGcIfNeeded(project, projectDir)

      FullSyncResult(StepResult(true, "Sync completed", uploadRes.details), http, dump, repoName, traceId, diagnostics.steps)
    } catch (t: Throwable) {
      diag(projectDir, diagnostics, "unexpected-error", SyncStepOutcome.FAIL, "Sync failed", mapOf("error" to (t.message ?: "Unexpected error")))
      FullSyncResult(StepResult(false, "Sync failed", t.message ?: "Unexpected error"), null, null, null, traceId, diagnostics.steps)
    }
  }

  fun runFullSync(
    project: Project,
    projectDir: File,
    settings: MirrorSettingsService.State,
    additionalBranches: List<String> = emptyList()
  ): FullSyncResult {
    val snapshot = SettingsSnapshot.from(settings, SecretsStore.mirrorApiKey, SecretsStore.syncPassword)
    return runFullSyncWithSnapshot(project, projectDir, snapshot, additionalBranches)
  }
}
