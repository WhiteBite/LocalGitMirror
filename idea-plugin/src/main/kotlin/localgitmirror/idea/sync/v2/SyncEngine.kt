package localgitmirror.idea.sync.v2

import com.intellij.openapi.project.Project
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.workkit.NativeStealthDump
import java.io.File

class SyncEngine(
  private val mirror: MirrorPort = DefaultMirrorPort,
  private val git: GitPort = DefaultGitPort,
  private val workKit: WorkKitPort = DefaultWorkKitPort,
  private val state: SyncStatePort = DefaultSyncStatePort,
  private val resolver: RepoResolverPort = DefaultRepoResolverPort
) {
  private val hashRe = Regex("^[0-9a-fA-F]{7,40}$")

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
      return StepResult(false, "Configure Mirror URL in settings")
    }
    if (settings.syncPassword.isBlank()) {
      return StepResult(false, "Configure Sync Password in settings")
    }
    return StepResult(true, "OK")
  }

  private fun verifyBackendHandshake(settings: SettingsSnapshot): StepResult {
    val caps = mirror.capabilities(settings.baseUrl, settings.mirrorApiKey, settings.mirrorInsecureTls)
    if (caps.code !in 200..299) {
      return StepResult(false, "Backend capabilities unavailable", "HTTP ${caps.code}: ${caps.body.take(200)}")
    }
    if (caps.apiVersion != 1 || caps.protocolVersion != 1) {
      return StepResult(false, "Backend protocol mismatch", "api=${caps.apiVersion} sync=${caps.protocolVersion}")
    }
    if (!caps.passwordProbe) {
      return StepResult(false, "Backend missing password probe", "Update backend")
    }

    val probe = mirror.passwordProbe(settings.baseUrl, settings.mirrorApiKey, settings.mirrorInsecureTls)
    if (probe.code !in 200..299 || probe.bytes == null) {
      return StepResult(false, "Password probe unavailable", "HTTP ${probe.code}: ${probe.message.take(200)}")
    }

    return try {
      val plain = NativeStealthDump.decryptDumpBytes(probe.bytes, settings.syncPassword)
      val ok = String(plain).trim() == "LGM-PROBE"
      if (!ok) {
        StepResult(false, "Sync password mismatch", "Re-enter Sync Password in plugin/backend")
      } else {
        StepResult(true, "Handshake OK")
      }
    } catch (t: Throwable) {
      StepResult(false, "Sync password mismatch", t.message ?: "Invalid password")
    }
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
    return resolver.resolve(project, projectDir, settings.repo).sanitized
  }

  fun ensureRemoteRepo(baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean): StepResult {
    val res = mirror.ensureRepoExists(baseUrl, apiKey, repo, insecureTls)
    if (res.code !in 200..299) {
      return StepResult(false, "Failed to ensure mirror repo '$repo'", "HTTP ${res.code}: ${res.body.take(300)}")
    }
    return StepResult(true, "Repo ready", res.body.take(300))
  }

  fun ensureWorkTreeClean(project: Project, projectDir: File): StepResult {
    if (!git.isCleanWorkTree(project, projectDir)) {
      return StepResult(false, "Working tree has uncommitted changes. Commit/stash before syncing.")
    }
    return StepResult(true, "OK")
  }

  fun generateDump(
    project: Project,
    projectDir: File,
    settings: SettingsSnapshot,
    repoName: String,
    preferredBase: String? = null
  ): StepResult {
    val head = git.headHash(project, projectDir)
    val lastSent = state.readLastSent(projectDir)
    val branch = git.currentBranch(project, projectDir)
    val byBranch = state.readLastByBranch(projectDir)
    val lastForBranch = if (!branch.isNullOrBlank()) byBranch[branch] else null

    val baseCandidate = (lastForBranch ?: lastSent).orEmpty()
    val localBase = if (!head.isNullOrBlank() && baseCandidate.isNotBlank() && git.isAncestor(project, projectDir, baseCandidate, head)) {
      baseCandidate
    } else null

    val base = preferredBase?.takeIf {
      !head.isNullOrBlank() && it.isNotBlank() && git.isAncestor(project, projectDir, it, head)
    } ?: localBase

    val kitRes = workKit.runBackupWorkStealth(
      workDir = projectDir,
      password = settings.syncPassword,
      repoName = repoName,
      baseCommit = base
    )
    if (!kitRes.ok() && isNoChangesToSync(kitRes)) {
      // If incremental base leads to no-op, retry full dump once to avoid false failures.
      if (!base.isNullOrBlank()) {
        val full = workKit.runBackupWorkStealth(
          workDir = projectDir,
          password = settings.syncPassword,
          repoName = repoName,
          baseCommit = null
        )
        if (full.ok()) {
          return StepResult(true, "Dump generated", full.stdout)
        }
      }
      return StepResult(true, "No new changes to sync", kitRes.stderr.ifBlank { kitRes.stdout })
    }
    if (!kitRes.ok()) {
      return StepResult(false, "Dump generation failed", "exit=${kitRes.exitCode} ${kitRes.stderr}")
    }
    return StepResult(true, "Dump generated", kitRes.stdout)
  }

  private fun isNoChangesToSync(result: localgitmirror.idea.workkit.WorkKit.Result): Boolean {
    val text = (result.stderr + "\n" + result.stdout).lowercase()
    return text.contains("no new changes to sync")
  }

  fun parseKnownCommitHashes(body: String): Set<String> {
    return Regex("""[0-9a-fA-F]{7,40}""")
      .findAll(body)
      .map { it.value.lowercase() }
      .toSet()
  }

  fun pickBestKnownBase(head: String, candidates: List<String>, known: Set<String>): String? {
    val headLc = head.lowercase()
    for (c in candidates) {
      val candidate = c.trim()
      if (candidate.isBlank()) continue
      if (!hashRe.matches(candidate)) continue
      val lc = candidate.lowercase()
      if (lc == headLc) continue
      if (known.contains(lc)) return candidate
    }
    return null
  }

  private fun buildNegotiationCandidates(project: Project, projectDir: File, head: String): List<String> {
    val branch = git.currentBranch(project, projectDir).orEmpty()
    val byBranch = state.readLastByBranch(projectDir)
    val lastForBranch = byBranch[branch].orEmpty()
    val lastSent = state.readLastSent(projectDir).orEmpty()
    val recent = git.recentCommits(project, projectDir, 80).map { it.hash }

    val raw = mutableListOf<String>()
    raw.add(head)
    if (lastForBranch.isNotBlank()) raw.add(lastForBranch)
    if (lastSent.isNotBlank()) raw.add(lastSent)
    raw.addAll(recent)

    val dedup = LinkedHashSet<String>()
    for (v in raw) {
      val h = v.trim()
      if (h.isBlank()) continue
      if (!hashRe.matches(h)) continue
      dedup.add(h)
    }
    return dedup.toList().take(100)
  }

  private fun negotiateWithMirror(project: Project, projectDir: File, settings: SettingsSnapshot, repo: String): NegotiationResult {
    val head = git.headHash(project, projectDir) ?: return NegotiationResult(null, null)
    val candidates = buildNegotiationCandidates(project, projectDir, head)
    if (candidates.isEmpty()) return NegotiationResult(null, null)

    val has = mirror.hasCommits(settings.baseUrl, settings.mirrorApiKey, repo, candidates, settings.mirrorInsecureTls)
    if (has.code !in 200..299) return NegotiationResult(null, null)

    val known = parseKnownCommitHashes(has.body)
    if (known.contains(head.lowercase())) {
      return NegotiationResult(pointerCommit = head, baseCommit = null)
    }

    var best = pickBestKnownBase(head, candidates, known)
    while (!best.isNullOrBlank()) {
      if (git.isAncestor(project, projectDir, best, head)) {
        return NegotiationResult(pointerCommit = null, baseCommit = best)
      }
      val remaining = candidates.filter { !it.equals(best, ignoreCase = true) }
      best = pickBestKnownBase(head, remaining, known)
    }

    return NegotiationResult(null, null)
  }

  fun findLatestDump(projectDir: File, repoName: String): Pair<StepResult, File?> {
    return findLatestDump(projectDir, repoName, generationOutput = null)
  }

  fun findLatestDump(projectDir: File, repoName: String, generationOutput: String?): Pair<StepResult, File?> {
    val fromOutput = findDumpFromGeneratorOutput(projectDir, generationOutput)
    if (fromOutput != null) {
      return StepResult(true, "Found dump from generator output", fromOutput.name) to fromOutput
    }

    val dump = workKit.findLatestDump(projectDir, repoName)
    if (dump != null) {
      return StepResult(true, "Found dump", dump.name) to dump
    }

    val fallback = findLatestAnyDump(projectDir)
    if (fallback != null) {
      return StepResult(true, "Found fallback dump", fallback.name) to fallback
    }

    return StepResult(false, "No dump_*.dmp found after generation") to null
  }

  private fun findDumpFromGeneratorOutput(projectDir: File, output: String?): File? {
    if (output.isNullOrBlank()) return null

    val m = Regex("(?im)^\\s*File:\\s*(.+?\\.dmp)\\b").find(output) ?: return null
    val raw = m.groupValues.getOrNull(1)?.trim()?.trim('"') ?: return null
    if (raw.isBlank()) return null

    val fromRaw = File(raw)
    if (fromRaw.isAbsolute && fromRaw.exists() && fromRaw.isFile) return fromRaw

    val tmpCandidate = File(File(projectDir, ".localgitmirror/tmp"), raw)
    if (tmpCandidate.exists() && tmpCandidate.isFile) return tmpCandidate

    val rootCandidate = File(projectDir, raw)
    if (rootCandidate.exists() && rootCandidate.isFile) return rootCandidate

    return null
  }

  private fun findLatestAnyDump(projectDir: File): File? {
    val dirs = listOf(File(projectDir, ".localgitmirror/tmp"), projectDir)
    val dumps = mutableListOf<File>()
    for (dir in dirs) {
      if (!dir.exists() || !dir.isDirectory) continue
      val local = dir.listFiles { f -> f.isFile && f.name.startsWith("dump_") && f.name.endsWith(".dmp") } ?: continue
      dumps.addAll(local)
    }
    return dumps.maxByOrNull { it.lastModified() }
  }

  fun uploadAndApply(settings: SettingsSnapshot, repoName: String, dump: File): Pair<StepResult, MirrorApi.HttpResult> {
    val res = mirror.uploadAndApply(
      baseUrl = settings.baseUrl,
      apiKey = settings.mirrorApiKey,
      repo = repoName,
      dumpFile = dump,
      insecureTls = settings.mirrorInsecureTls
    )
    if (res.code !in 200..299) {
      return StepResult(false, "Mirror error HTTP ${res.code}", res.body.take(500)) to res
    }

    val success = parseJsonSuccess(res.body)
    if (success == false) {
      return StepResult(false, "Mirror rejected sync", res.body.take(500)) to res
    }

    return StepResult(true, "Upload-and-apply success", res.body.take(500)) to res
  }

  fun parseJsonSuccess(body: String): Boolean? {
    return try {
      val json = Json.parseToJsonElement(body).jsonObject
      json["success"]?.jsonPrimitive?.booleanOrNull
    } catch (_: Exception) {
      val m = Regex("\"success\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE).find(body)
      val v = m?.groupValues?.getOrNull(1)?.lowercase()
      when (v) {
        "true" -> true
        "false" -> false
        else -> null
      }
    }
  }

  private fun diag(
    diagnostics: SyncDiagnostics,
    id: String,
    outcome: SyncStepOutcome,
    message: String,
    fields: Map<String, String> = emptyMap()
  ) {
    diagnostics.add(SyncStep(id = id, outcome = outcome, message = message, fields = fields))
  }

  fun runFullSyncWithSnapshot(project: Project, projectDir: File, snapshot: SettingsSnapshot): FullSyncResult {
    val diagnostics = SyncDiagnostics()
    val traceId = diagnostics.traceId
    return try {
      state.migrateLegacyIfPresent(projectDir)
      diag(
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
        diag(diagnostics, "resolve-repo", SyncStepOutcome.FAIL, step.message, mapOf("repoConfigured" to snapshot.repoConfigured))
        return FullSyncResult(step, null, null, null, traceId, diagnostics.steps)
      }
      val repoName = repoResolution.sanitized
      if (repoName.isBlank()) {
        val step = StepResult(false, "Unable to infer repository name")
        diag(diagnostics, "resolve-repo", SyncStepOutcome.FAIL, step.message)
        return FullSyncResult(step, null, null, null, traceId, diagnostics.steps)
      }
      diag(
        diagnostics,
        id = "resolve-repo",
        outcome = SyncStepOutcome.OK,
        message = "Resolved target repository",
        fields = mapOf("repo" to repoName, "source" to repoResolution.source.name)
      )

      val cfg = validateSettings(snapshot)
      if (!cfg.ok) {
        diag(diagnostics, "validate-settings", SyncStepOutcome.FAIL, cfg.message)
        return FullSyncResult(cfg, null, null, repoName, traceId, diagnostics.steps)
      }
      diag(diagnostics, "validate-settings", SyncStepOutcome.OK, cfg.message)

      val hs = verifyBackendHandshake(snapshot)
      if (!hs.ok) {
        diag(diagnostics, "handshake", SyncStepOutcome.FAIL, hs.message, mapOf("details" to hs.details))
        return FullSyncResult(hs, null, null, repoName, traceId, diagnostics.steps)
      }
      diag(diagnostics, "handshake", SyncStepOutcome.OK, hs.message)

      val ensureRepo = ensureRemoteRepo(snapshot.baseUrl, snapshot.mirrorApiKey, repoName, snapshot.mirrorInsecureTls)
      if (!ensureRepo.ok) {
        diag(diagnostics, "ensure-remote-repo", SyncStepOutcome.FAIL, ensureRepo.message, mapOf("repo" to repoName))
        return FullSyncResult(ensureRepo, null, null, repoName, traceId, diagnostics.steps)
      }
      diag(diagnostics, "ensure-remote-repo", SyncStepOutcome.OK, ensureRepo.message, mapOf("repo" to repoName))

      val clean = ensureWorkTreeClean(project, projectDir)
      if (!clean.ok) {
        diag(diagnostics, "ensure-work-tree-clean", SyncStepOutcome.FAIL, clean.message)
        return FullSyncResult(clean, null, null, repoName, traceId, diagnostics.steps)
      }
      diag(diagnostics, "ensure-work-tree-clean", SyncStepOutcome.OK, clean.message)

      val negotiation = negotiateWithMirror(project, projectDir, snapshot, repoName)
      diag(
        diagnostics,
        id = "negotiate",
        outcome = SyncStepOutcome.OK,
        message = "Negotiation completed",
        fields = mapOf(
          "pointerCommit" to (negotiation.pointerCommit ?: ""),
          "baseCommit" to (negotiation.baseCommit ?: "")
        )
      )

      val pointerHead = negotiation.pointerCommit
      if (!pointerHead.isNullOrBlank()) {
        val applied = mirror.applyKnown(snapshot.baseUrl, snapshot.mirrorApiKey, repoName, pointerHead, snapshot.mirrorInsecureTls)
        if (applied.code in 200..299) {
          val branchName = git.currentBranch(project, projectDir).orEmpty()
          state.updateAfterSend(projectDir, branchName, pointerHead)
          val okStep = StepResult(true, "Mirror already had commit; applied pointer-only", applied.body.take(500))
          diag(diagnostics, "apply-known", SyncStepOutcome.OK, okStep.message, mapOf("repo" to repoName, "commit" to pointerHead))
          return FullSyncResult(okStep, applied, null, repoName, traceId, diagnostics.steps)
        }
        diag(diagnostics, "apply-known", SyncStepOutcome.FAIL, "Pointer-only apply failed", mapOf("repo" to repoName, "httpCode" to applied.code.toString()))
      }

      val dumpGen = generateDump(project, projectDir, snapshot, repoName, preferredBase = negotiation.baseCommit)
      if (!dumpGen.ok) {
        diag(diagnostics, "generate-dump", SyncStepOutcome.FAIL, dumpGen.message, mapOf("repo" to repoName))
        return FullSyncResult(dumpGen, null, null, repoName, traceId, diagnostics.steps)
      }

      if (dumpGen.message == "No new changes to sync") {
        val branchName = git.currentBranch(project, projectDir).orEmpty()
        val headNow = git.headHash(project, projectDir)
        if (!headNow.isNullOrBlank()) {
          state.updateAfterSend(projectDir, branchName, headNow)
        }
        val okNoop = StepResult(true, "No new changes to sync; skipped upload", dumpGen.details)
        diag(diagnostics, "generate-dump", SyncStepOutcome.SKIP, okNoop.message, mapOf("repo" to repoName, "branch" to branchName, "head" to (headNow ?: "")))
        return FullSyncResult(okNoop, null, null, repoName, traceId, diagnostics.steps)
      }

      diag(diagnostics, "generate-dump", SyncStepOutcome.OK, dumpGen.message, mapOf("repo" to repoName))

      val (findRes, dump) = findLatestDump(projectDir, repoName, generationOutput = dumpGen.details)
      if (!findRes.ok || dump == null) {
        diag(diagnostics, "find-dump", SyncStepOutcome.FAIL, findRes.message, mapOf("repo" to repoName))
        return FullSyncResult(findRes, null, null, repoName, traceId, diagnostics.steps)
      }
      diag(diagnostics, "find-dump", SyncStepOutcome.OK, findRes.message, mapOf("dump" to dump.absolutePath))

      if (snapshot.offlineGenerateOnly) {
        diag(diagnostics, "offline-mode", SyncStepOutcome.SKIP, "Skipping upload-and-apply due to offline mode", mapOf("repo" to repoName))
        return FullSyncResult(
          StepResult(true, "Dump generated (offline mode)", dump.absolutePath),
          null,
          dump,
          repoName,
          traceId,
          diagnostics.steps
        )
      }

      val (uploadRes, http) = uploadAndApply(snapshot, repoName, dump)
      if (!uploadRes.ok) {
        diag(diagnostics, "upload-and-apply", SyncStepOutcome.FAIL, uploadRes.message, mapOf("repo" to repoName, "httpCode" to http.code.toString()))
        return FullSyncResult(uploadRes, http, dump, repoName, traceId, diagnostics.steps)
      }
      diag(diagnostics, "upload-and-apply", SyncStepOutcome.OK, uploadRes.message, mapOf("repo" to repoName, "httpCode" to http.code.toString()))

      val branchName = git.currentBranch(project, projectDir).orEmpty()
      val headNow = git.headHash(project, projectDir)
      if (!headNow.isNullOrBlank()) {
        state.updateAfterSend(projectDir, branchName, headNow)
      }
      state.cleanupOldDumps(projectDir)
      diag(diagnostics, "update-state", SyncStepOutcome.OK, "State updated", mapOf("branch" to branchName, "head" to (headNow ?: "")))

      FullSyncResult(StepResult(true, "Sync completed", uploadRes.details), http, dump, repoName, traceId, diagnostics.steps)
    } catch (t: Throwable) {
      diag(diagnostics, "unexpected-error", SyncStepOutcome.FAIL, "Sync failed", mapOf("error" to (t.message ?: "Unexpected error")))
      FullSyncResult(StepResult(false, "Sync failed", t.message ?: "Unexpected error"), null, null, null, traceId, diagnostics.steps)
    }
  }

  fun runFullSync(project: Project, projectDir: File, settings: MirrorSettingsService.State): FullSyncResult {
    val snapshot = SettingsSnapshot.from(settings, SecretsStore.mirrorApiKey, SecretsStore.syncPassword)
    return runFullSyncWithSnapshot(project, projectDir, snapshot)
  }
}
