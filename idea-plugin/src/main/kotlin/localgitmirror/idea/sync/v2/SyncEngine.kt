package localgitmirror.idea.sync.v2

import com.intellij.openapi.project.Project
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.workkit.BundleCrypto
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
      val plain = BundleCrypto.decryptDumpBytes(probe.bytes, settings.syncPassword)
      val ok = String(plain).trim().let { it == "LGM-PROBE" || it == "SYNC-PROBE" }
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

  fun ensureRemoteRepo(baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean, projectDir: File? = null): StepResult {
    val res = mirror.ensureRepoExists(baseUrl, apiKey, repo, insecureTls, projectDir)
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
    excludeBases: List<String> = emptyList(),
    additionalBranches: List<String> = emptyList()
  ): StepResult {
    // negotiationUsed=true tells bundle builder to trust excludeBases from negotiation
    // and NOT fall back to stale .git/lgm-sync-state when excludeBases is empty.
    val kitRes = workKit.createSyncPackage(
      workDir = projectDir,
      password = settings.syncPassword,
      repoName = repoName,
      excludeBases = excludeBases,
      additionalBranches = additionalBranches,
      negotiationUsed = true
    )
    if (!kitRes.ok() && isNoChangesToSync(kitRes)) {
      // If incremental bases lead to no-op, retry full dump once to avoid false failures.
      if (excludeBases.isNotEmpty()) {
        val full = workKit.createSyncPackage(
          workDir = projectDir,
          password = settings.syncPassword,
          repoName = repoName,
          excludeBases = emptyList(),
          additionalBranches = additionalBranches,
          negotiationUsed = true
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

  data class MultiBranchNegotiation(
    val pointerCommit: String?,           // non-null if current HEAD already on Mirror
    val excludeBases: List<String>         // best known bases for ALL branches
  )

  fun negotiateMultiBranch(
    project: Project,
    projectDir: File,
    settings: SettingsSnapshot,
    repo: String,
    additionalBranches: List<String>
  ): MultiBranchNegotiation {
    val head = git.headHash(project, projectDir) ?: return MultiBranchNegotiation(null, emptyList())
    val currentBranch = git.currentBranch(project, projectDir).orEmpty()

    // Collect candidate hashes for all branches
    data class BranchInfo(val name: String, val tip: String, val candidates: List<String>)
    val branchInfos = mutableListOf<BranchInfo>()

    // Current branch
    val currentCandidates = buildNegotiationCandidates(project, projectDir, head)
    branchInfos.add(BranchInfo(currentBranch.ifBlank { "HEAD" }, head, currentCandidates))

    // Additional branches
    for (br in additionalBranches) {
      if (br.isBlank() || br == currentBranch) continue
      val tip = git.branchHash(project, projectDir, br) ?: continue
      // For additional branches we collect: tip + last synced hash for that branch
      val extras = mutableListOf(tip)
      val byBranch = state.readLastByBranch(projectDir)
      val lastForBr = byBranch[br]
      if (!lastForBr.isNullOrBlank()) extras.add(lastForBr)
      branchInfos.add(BranchInfo(br, tip, extras))
    }

    // Merge all candidates into one list for a single has-commits call
    val allCandidates = branchInfos.flatMap { it.candidates }.distinct().take(200)
    if (allCandidates.isEmpty()) return MultiBranchNegotiation(null, emptyList())

    val has = mirror.hasCommits(settings.baseUrl, settings.mirrorApiKey, repo, allCandidates, settings.mirrorInsecureTls)
    if (has.code !in 200..299) return MultiBranchNegotiation(null, emptyList())

    val known = parseKnownCommitHashes(has.body)

    // Check if Mirror already has current HEAD (pointer-only)
    if (known.contains(head.lowercase()) && additionalBranches.all { br ->
        val tip = git.branchHash(project, projectDir, br)
        tip == null || known.contains(tip.lowercase())
      }) {
      return MultiBranchNegotiation(pointerCommit = head, excludeBases = emptyList())
    }

    // Find best known base for each branch
    val excludeBases = mutableListOf<String>()
    for (info in branchInfos) {
      val best = pickBestKnownBase(info.tip, info.candidates, known)
      if (!best.isNullOrBlank() && git.isAncestor(project, projectDir, best, info.tip)) {
        excludeBases.add(best)
      }
    }

    return MultiBranchNegotiation(pointerCommit = null, excludeBases = excludeBases.distinct())
  }

  fun findLatestDump(projectDir: File, repoName: String): Pair<StepResult, File?> {
    return findLatestDump(projectDir, repoName, generationOutput = null)
  }

  fun findLatestDump(projectDir: File, repoName: String, generationOutput: String?): Pair<StepResult, File?> {
    val fromOutput = findSyncFileFromGeneratorOutput(projectDir, generationOutput)
    if (fromOutput != null) {
      return StepResult(true, "Found sync package from generator output", fromOutput.name) to fromOutput
    }

    val dump = workKit.findLatestDump(projectDir, repoName)
    if (dump != null) {
      return StepResult(true, "Found sync package", dump.name) to dump
    }

    val fallback = findLatestAnySyncFile(projectDir)
    if (fallback != null) {
      return StepResult(true, "Found fallback sync package", fallback.name) to fallback
    }

    return StepResult(false, "No sync package found after generation") to null
  }

  private fun findSyncFileFromGeneratorOutput(projectDir: File, output: String?): File? {
    if (output.isNullOrBlank()) return null

    val m = Regex("(?im)^\\s*File:\\s*(.+?\\.bin)\\b").find(output) ?: return null
    val raw = m.groupValues.getOrNull(1)?.trim()?.trim('"') ?: return null
    if (raw.isBlank()) return null

    val fromRaw = File(raw)
    if (fromRaw.isAbsolute && fromRaw.exists() && fromRaw.isFile) return fromRaw

    val rootCandidate = File(projectDir, raw)
    if (rootCandidate.exists() && rootCandidate.isFile) return rootCandidate

    return null
  }

  private fun findLatestAnySyncFile(projectDir: File): File? {
    // Resolve .git/.cache/ directory for sync files
    val proc = ProcessBuilder(listOf("git", "rev-parse", "--git-dir"))
      .directory(projectDir).redirectErrorStream(false).start()
    val rawGitDir = proc.inputStream.bufferedReader().readText().trim()
    proc.waitFor()
    val gitDir = if (File(rawGitDir).isAbsolute) File(rawGitDir) else File(projectDir, rawGitDir)
    val syncDir = File(gitDir, ".cache")
    val dirs = listOf(syncDir, projectDir)
    val files = mutableListOf<File>()
    for (dir in dirs) {
      if (!dir.exists() || !dir.isDirectory) continue
      val local = dir.listFiles { f -> f.isFile && (f.name.startsWith(".tmp_") || (f.name.startsWith("cache_") && f.name.endsWith(".bin"))) } ?: continue
      files.addAll(local)
    }
    return files.maxByOrNull { it.lastModified() }
  }

  @Suppress("HttpCallOnEdt")
  fun uploadAndApply(settings: SettingsSnapshot, repoName: String, dump: File, projectDir: File? = null): Pair<StepResult, MirrorApi.HttpResult> {
    // Random jitter (0-15s) to avoid predictable traffic patterns
    val jitterMs = (0..15_000).random()
    Thread.sleep(jitterMs.toLong())

    val res = mirror.uploadAndApply(
      baseUrl = settings.baseUrl,
      apiKey = settings.mirrorApiKey,
      repo = repoName,
      dumpFile = dump,
      insecureTls = settings.mirrorInsecureTls,
      projectDir = projectDir
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

      val ensureRepo = ensureRemoteRepo(snapshot.baseUrl, snapshot.mirrorApiKey, repoName, snapshot.mirrorInsecureTls, projectDir)
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
        // Build branch→hash map for all branches the sender has
        val branchMap = mutableMapOf<String, String>()
        val currentBranch = git.currentBranch(project, projectDir).orEmpty()
        if (currentBranch.isNotBlank()) {
          branchMap[currentBranch] = pointerHead
        }
        for (br in additionalBranches) {
          if (br.isBlank()) continue
          val tip = git.branchHash(project, projectDir, br)
          if (!tip.isNullOrBlank()) {
            branchMap[br] = tip
          }
        }

        val applied = mirror.applyKnown(snapshot.baseUrl, snapshot.mirrorApiKey, repoName, pointerHead, branches = branchMap, insecureTls = snapshot.mirrorInsecureTls)
        if (applied.code in 200..299) {
          val branchName = currentBranch
          state.updateAfterSend(projectDir, branchName, pointerHead)
          val okStep = StepResult(true, "Mirror already had all commits; applied pointer-only (${branchMap.size} branch(es))", applied.body.take(500))
          diag(projectDir, diagnostics, "apply-known", SyncStepOutcome.OK, okStep.message, mapOf("repo" to repoName, "commit" to pointerHead, "branches" to branchMap.keys.joinToString(",")))
          return FullSyncResult(okStep, applied, null, repoName, traceId, diagnostics.steps)
        }
        diag(projectDir, diagnostics, "apply-known", SyncStepOutcome.FAIL, "Pointer-only apply failed", mapOf("repo" to repoName, "httpCode" to applied.code.toString()))
      }

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

      val (uploadRes, http) = uploadAndApply(snapshot, repoName, dump, projectDir)
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
