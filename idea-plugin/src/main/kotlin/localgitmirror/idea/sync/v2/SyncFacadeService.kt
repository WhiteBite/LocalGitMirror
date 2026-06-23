package localgitmirror.idea.sync.v2

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.SyncStateStore
import localgitmirror.idea.workkit.BundleCrypto
import java.io.File

@Service(Service.Level.PROJECT)
class SyncFacadeService(private val project: Project) {
  private val engine = SyncEngine()

  fun resolveRepo(projectDir: File, settings: MirrorSettingsService.State): RepoResolution {
    // Repo is per-project: pass "" so RepoResolver uses this project's own
    // stored override / git remote, never the global setting.
    return RepoResolver.resolve(project, projectDir, "")
  }

  fun inferRepoName(projectDir: File, settings: MirrorSettingsService.State): String {
    return resolveRepo(projectDir, settings).sanitized
  }

  fun validateSettings(settings: MirrorSettingsService.State): SyncEngine.StepResult {
    return engine.validateSettings(settings)
  }

  fun ensureRemoteRepo(baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean): SyncEngine.StepResult {
    return engine.ensureRemoteRepo(baseUrl, apiKey, repo, insecureTls)
  }

  fun runFullSync(projectDir: File, settings: MirrorSettingsService.State, additionalBranches: List<String> = emptyList()): SyncEngine.FullSyncResult {
    return engine.runFullSync(project, projectDir, settings, additionalBranches)
  }

  fun describeRepoTarget(projectDir: File, settings: MirrorSettingsService.State): String {
    val r = resolveRepo(projectDir, settings)
    val base = "repo='${r.sanitized}' source=${r.source.name.lowercase()}"
    return if (r.error.isNullOrBlank()) base else "$base error='${r.error}'"
  }

  fun runPreflight(projectDir: File, settings: MirrorSettingsService.State): PreflightReport {
    val diags = mutableListOf<Diagnostic>()
    val resolved = resolveRepo(projectDir, settings)
    if (!resolved.error.isNullOrBlank()) {
      diags += Diagnostic(Severity.ERROR, "REPO_INVALID", resolved.error, "Set Mirror repo explicitly in settings")
    } else {
      diags += Diagnostic(Severity.INFO, "REPO_TARGET", "Target repo: ${resolved.sanitized}")
    }

    if (settings.baseUrl.isBlank()) {
      diags += Diagnostic(Severity.ERROR, "BASE_URL_MISSING", "Mirror URL is not configured")
    }
    if (SecretsStore.syncPassword.isBlank()) {
      diags += Diagnostic(Severity.ERROR, "PASSWORD_MISSING", "Sync Password is not configured")
    }

    if (!GitLocal.isCleanWorkTree(project, projectDir)) {
      diags += Diagnostic(Severity.ERROR, "WORKTREE_DIRTY", "Working tree has uncommitted changes", "Commit or stash before sync")
    }

    val branch = GitLocal.currentBranch(project, projectDir)
    val head = GitLocal.headHash(project, projectDir)
    if (branch.isNullOrBlank()) {
      diags += Diagnostic(Severity.ERROR, "BRANCH_DETACHED", "Cannot determine current branch")
    } else {
      diags += Diagnostic(Severity.INFO, "BRANCH", "Current branch: $branch")
    }
    if (head.isNullOrBlank()) {
      diags += Diagnostic(Severity.ERROR, "HEAD_MISSING", "Cannot determine HEAD")
    } else {
      diags += Diagnostic(Severity.INFO, "HEAD", "HEAD: ${head.take(12)}")
    }

    if (settings.baseUrl.isNotBlank()) {
      val caps = MirrorApi.capabilities(settings.baseUrl, SecretsStore.mirrorApiKey, settings.mirrorInsecureTls)
      if (caps.code !in 200..299) {
        diags += Diagnostic(Severity.ERROR, "CAPABILITIES_UNAVAILABLE", "Backend capabilities unavailable (HTTP ${caps.code})", "Update backend to latest")
      } else {
        if (caps.apiVersion != 1 || caps.protocolVersion != 1) {
          diags += Diagnostic(Severity.ERROR, "CAPABILITIES_MISMATCH", "Unsupported backend protocol api=${caps.apiVersion} sync=${caps.protocolVersion}")
        } else {
          diags += Diagnostic(Severity.INFO, "CAPABILITIES_OK", "Backend capabilities OK")
        }

        if (caps.passwordProbe && SecretsStore.syncPassword.isNotBlank()) {
          val probe = MirrorApi.passwordProbe(settings.baseUrl, SecretsStore.mirrorApiKey, settings.mirrorInsecureTls)
          if (probe.code in 200..299 && probe.bytes != null) {
            try {
              val plain = BundleCrypto.decryptDumpBytes(probe.bytes, SecretsStore.syncPassword)
              if (String(plain).trim().let { it == "LGM-PROBE" || it == "SYNC-PROBE" }) {
                diags += Diagnostic(Severity.INFO, "PASSWORD_MATCH", "Password probe: OK")
              } else {
                diags += Diagnostic(Severity.ERROR, "PASSWORD_MISMATCH", "Password probe failed", "Re-enter Sync Password in plugin and backend")
              }
            } catch (_: Exception) {
              diags += Diagnostic(Severity.ERROR, "PASSWORD_MISMATCH", "Password probe failed", "Re-enter Sync Password in plugin and backend")
            }
          } else {
            diags += Diagnostic(Severity.WARN, "PASSWORD_PROBE_UNAVAILABLE", "Password probe unavailable (HTTP ${probe.code})")
          }
        }
      }
    }

    return PreflightReport(
      ok = diags.none { it.severity == Severity.ERROR },
      targetRepo = resolved.sanitized.ifBlank { null },
      diagnostics = diags
    )
  }

  fun runDryRun(projectDir: File, settings: MirrorSettingsService.State): DryRunReport {
    val diags = mutableListOf<Diagnostic>()
    val resolved = resolveRepo(projectDir, settings)
    if (!resolved.error.isNullOrBlank()) {
      return DryRunReport(
        ok = false,
        targetRepo = null,
        branch = null,
        head = null,
        predictedMode = "unknown",
        commitRange = "",
        commitCount = -1,
        diagnostics = listOf(Diagnostic(Severity.ERROR, "REPO_INVALID", resolved.error))
      )
    }
    val repo = resolved.sanitized

    val branch = GitLocal.currentBranch(project, projectDir)
    val head = GitLocal.headHash(project, projectDir)
    if (branch.isNullOrBlank() || head.isNullOrBlank()) {
      return DryRunReport(
        ok = false,
        targetRepo = repo,
        branch = branch,
        head = head,
        predictedMode = "unknown",
        commitRange = "",
        commitCount = -1,
        diagnostics = listOf(Diagnostic(Severity.ERROR, "HEAD_OR_BRANCH_MISSING", "Cannot determine branch/head for dry-run"))
      )
    }

    val byBranch = SyncStateStore.readLastByBranch(projectDir)
    val lastForBranch = byBranch[branch].orEmpty()
    val lastSent = SyncStateStore.readLastSent(projectDir).orEmpty()
    val recent = GitLocal.recentCommits(project, projectDir, 50).map { it.hash }
    val candidates = linkedSetOf<String>().apply {
      add(head)
      if (lastForBranch.isNotBlank()) add(lastForBranch)
      if (lastSent.isNotBlank()) add(lastSent)
      addAll(recent)
    }.toList()

    val has = MirrorApi.hasCommits(settings.baseUrl, SecretsStore.mirrorApiKey, repo, candidates, settings.mirrorInsecureTls)
    val known = if (has.code in 200..299) engine.parseKnownCommitHashes(has.body) else emptySet()
    val remoteHasHead = known.contains(head.lowercase())
    val bestBase = engine.pickBestKnownBase(head, candidates, known)

    val rangeCount = if (!bestBase.isNullOrBlank()) GitLocal.commitCount(project, projectDir, "$bestBase..$head") else null
    val fullCount = GitLocal.commitCount(project, projectDir, null)
    val prediction = SyncPlanning.predict(head, bestBase, remoteHasHead, rangeCount, fullCount)

    diags += Diagnostic(Severity.INFO, "DRYRUN_TARGET", "Target repo: $repo")
    diags += Diagnostic(Severity.INFO, "DRYRUN_MODE", "Predicted mode: ${prediction.mode}")
    if (has.code !in 200..299) {
      diags += Diagnostic(Severity.WARN, "DRYRUN_NEGOTIATION_UNAVAILABLE", "has-commits unavailable (HTTP ${has.code})")
    } else {
      diags += Diagnostic(Severity.INFO, "DRYRUN_REMOTE_KNOWN", "Mirror knows ${known.size} candidate commits")
    }

    if (remoteHasHead) {
      diags += Diagnostic(
        Severity.INFO,
        "DRYRUN_POINTER_ONLY",
        "Mirror already has HEAD object; will use pointer-only apply",
        "If you expected changes to send, verify you're on the right branch and repo target"
      )
    }

    return DryRunReport(
      ok = true,
      targetRepo = repo,
      branch = branch,
      head = head,
      predictedMode = prediction.mode,
      commitRange = prediction.range,
      commitCount = prediction.count,
      diagnostics = diags
    )
  }

  fun runPullDryRun(projectDir: File, settings: MirrorSettingsService.State): PullDryRunReport {
    val diags = mutableListOf<Diagnostic>()
    val resolved = resolveRepo(projectDir, settings)
    if (!resolved.error.isNullOrBlank()) {
      return PullDryRunReport(
        ok = false, targetRepo = null, localHead = null, remoteHead = null,
        hasUpdates = false, reason = "repo-invalid",
        diagnostics = listOf(Diagnostic(Severity.ERROR, "REPO_INVALID", resolved.error))
      )
    }
    val repo = resolved.sanitized

    if (settings.baseUrl.isBlank()) {
      return PullDryRunReport(
        ok = false, targetRepo = repo, localHead = null, remoteHead = null,
        hasUpdates = false, reason = "not-configured",
        diagnostics = listOf(Diagnostic(Severity.ERROR, "BASE_URL_MISSING", "Mirror URL is not configured"))
      )
    }

    val localHead = GitLocal.headHash(project, projectDir)
    val since = SyncStateStore.readLastPulledHead(projectDir)

    val preview = MirrorApi.previewPull(
      baseUrl = settings.baseUrl,
      apiKey = SecretsStore.mirrorApiKey,
      repo = repo,
      since = since,
      insecureTls = settings.mirrorInsecureTls
    )

    if (preview.code !in 200..299) {
      diags += Diagnostic(Severity.ERROR, "PREVIEW_PULL_FAILED", "preview-pull HTTP ${preview.code}: ${preview.message}")
      return PullDryRunReport(
        ok = false, targetRepo = repo, localHead = localHead, remoteHead = null,
        hasUpdates = false, reason = "http-error",
        diagnostics = diags
      )
    }

    diags += Diagnostic(Severity.INFO, "PULL_TARGET", "Target repo: $repo")
    diags += Diagnostic(Severity.INFO, "PULL_LOCAL_HEAD", "Local HEAD: ${localHead?.take(12) ?: "(unknown)"}")
    diags += Diagnostic(Severity.INFO, "PULL_SINCE", "Last pulled: ${since?.take(12) ?: "(never)"}")
    diags += Diagnostic(Severity.INFO, "PULL_REMOTE_HEAD", "Remote HEAD: ${preview.remoteHead?.take(12) ?: "(empty)"}")
    diags += Diagnostic(
      if (preview.hasUpdates) Severity.INFO else Severity.INFO,
      "PULL_HAS_UPDATES",
      "Has updates: ${preview.hasUpdates} (${preview.reason})"
    )

    return PullDryRunReport(
      ok = true,
      targetRepo = repo,
      localHead = localHead,
      remoteHead = preview.remoteHead,
      hasUpdates = preview.hasUpdates,
      reason = preview.reason,
      diagnostics = diags
    )
  }
}
