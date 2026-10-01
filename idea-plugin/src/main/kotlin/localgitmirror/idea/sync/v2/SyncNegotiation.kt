package localgitmirror.idea.sync.v2

import com.intellij.openapi.project.Project
import localgitmirror.idea.mirror.MirrorCrypto
import localgitmirror.idea.mirror.MirrorSyncApi
import localgitmirror.idea.sync.HandshakeCache
import localgitmirror.idea.sync.v2.SyncEngine.MultiBranchNegotiation
import localgitmirror.idea.sync.v2.SyncEngine.NegotiationResult
import localgitmirror.idea.sync.v2.SyncEngine.StepResult
import localgitmirror.idea.workkit.BundleCrypto
import java.io.File

private val hashRe = Regex("^[0-9a-fA-F]{7,40}$")

internal fun SyncEngine.verifyBackendHandshake(settings: SettingsSnapshot): StepResult {
  val caps = HandshakeCache.capabilities(settings.baseUrl, settings.mirrorApiKey, settings.syncPassword, settings.mirrorInsecureTls, mirror)
  if (caps.code !in 200..299) {
    return StepResult(false, "Backend capabilities unavailable", "HTTP ${caps.code}: ${caps.body.take(200)}")
  }
  if (caps.apiVersion != 1 || caps.protocolVersion != 1) {
    return StepResult(false, "Backend protocol mismatch", "api=${caps.apiVersion} sync=${caps.protocolVersion}")
  }

  // v3 fast path: pinned hybrid key makes the password probe redundant
  val clientHasPin = MirrorCrypto.isV3Pinned()
  if (caps.v3Key && clientHasPin) {
    return StepResult(true, "Handshake OK (v3)")
  }

  // Legacy password probe.
  if (!caps.passwordProbe) {
    return StepResult(false, "Backend missing password probe", "Update backend or configure SYNC_PASSWORD")
  }
  val probe = HandshakeCache.passwordProbe(settings.baseUrl, settings.mirrorApiKey, settings.syncPassword, settings.mirrorInsecureTls, mirror)
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

internal fun SyncEngine.parseKnownCommitHashes(body: String): Set<String> {
  return Regex("""[0-9a-fA-F]{7,40}""")
    .findAll(body)
    .map { it.value.lowercase() }
    .toSet()
}

internal fun SyncEngine.pickBestKnownBase(head: String, candidates: List<String>, known: Set<String>): String? {
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

private fun SyncEngine.buildNegotiationCandidates(project: Project, projectDir: File, head: String): List<String> {
  return buildNegotiationCandidatesWithGuaranteed(project, projectDir, head).first
}

/**
 * Returns (candidates, guaranteedAncestors).
 *
 * Candidates = tip + state hints + recent history. State hints (last-sent
 * hashes from .git/lgm-sync-state) are NOT guaranteed ancestors: after a
 * rebase/amend the recorded commit may no longer be on the branch, and
 * feeding it to the bundle builder as an unverified ^base breaks
 * ensureCommitReachable. Guaranteed = the tip's own recent history from
 * `git log` — those are ancestors by construction and need no git check.
 */
private fun SyncEngine.buildNegotiationCandidatesWithGuaranteed(project: Project, projectDir: File, head: String): Pair<List<String>, List<String>> {
  val branch = git.currentBranch(project, projectDir).orEmpty()
  val byBranch = state.readLastByBranch(projectDir)
  val lastForBranch = byBranch[branch].orEmpty()
  val lastSent = state.readLastSent(projectDir).orEmpty()
  // full hashes: abbreviated --oneline hashes never match known
  val recent = git.recentCommitsOfRef(project, projectDir, "HEAD", SyncConstants.RECENT_COMMITS_DEPTH)

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
  val guaranteed = recent.filter { hashRe.matches(it) }
  return dedup.toList().take(100) to guaranteed
}

private fun SyncEngine.negotiateWithMirror(project: Project, projectDir: File, settings: SettingsSnapshot, repo: String): NegotiationResult {
  val head = git.headHash(project, projectDir) ?: return NegotiationResult(null, null)
  val candidates = buildNegotiationCandidates(project, projectDir, head)
  if (candidates.isEmpty()) return NegotiationResult(null, null)

  val has = mirror.hasCommits(settings.baseUrl, settings.mirrorApiKey, repo, candidates, settings.syncPassword, settings.mirrorInsecureTls)
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

internal fun SyncEngine.negotiateMultiBranch(
  project: Project,
  projectDir: File,
  settings: SettingsSnapshot,
  repo: String,
  additionalBranches: List<String>
): MultiBranchNegotiation {
  val head = git.headHash(project, projectDir) ?: return MultiBranchNegotiation(null, emptyList())
  val currentBranch = git.currentBranch(project, projectDir).orEmpty()

  // server tips are exclude-base candidates even with empty local state
  val serverRefs = mirror.getRefs(settings.baseUrl, settings.mirrorApiKey, repo, settings.syncPassword, settings.mirrorInsecureTls)
  val serverKnown: Set<String> = serverRefs.refs?.values
    ?.mapNotNull { it.sha.takeIf { s -> s.isNotBlank() && hashRe.matches(s) }?.lowercase() }
    ?.toHashSet()
    ?: emptySet()

  // first confirmed ancestor of the tip becomes the ^base
  data class BranchInfo(val name: String, val tip: String, val candidates: List<String>, val guaranteed: List<String> = emptyList())
  val branchInfos = mutableListOf<BranchInfo>()

  // Current branch — head + recent N reachable from HEAD + state hints
  val (currentCandidates, currentGuaranteed) = buildNegotiationCandidatesWithGuaranteed(project, projectDir, head)
  branchInfos.add(BranchInfo(currentBranch.ifBlank { "HEAD" }, head, currentCandidates, currentGuaranteed))

  // walk the branch's own history, not HEAD
  val byBranch = state.readLastByBranch(projectDir)
  for (br in additionalBranches) {
    if (br.isBlank() || br == currentBranch || localgitmirror.idea.git.GitLocal.isJunkBranchName(br)) continue
    val tip = git.branchHash(project, projectDir, br) ?: continue
    val recentOfBranch = git.recentCommitsOfRef(project, projectDir, br, SyncConstants.ADDITIONAL_BRANCH_DEPTH)
    val extras = LinkedHashSet<String>()
    extras.add(tip)
    recentOfBranch.forEach { extras.add(it) }
    byBranch[br]?.takeIf { it.isNotBlank() }?.let { extras.add(it) }
    val deduped = extras.filter { hashRe.matches(it) }.toList()
    branchInfos.add(BranchInfo(br, tip, deduped, recentOfBranch.filter { hashRe.matches(it) }))
  }

  // single hasCommits over the union, minus confirmed server tips
  val askable = branchInfos.flatMap { it.candidates }
    .filter { it.lowercase() !in serverKnown }
    .distinct()
    .take(SyncConstants.HAS_COMMITS_CANDIDATE_LIMIT)

  val knownFromHas: Set<String> = if (askable.isEmpty()) emptySet() else {
    val has = mirror.hasCommits(settings.baseUrl, settings.mirrorApiKey, repo, askable, settings.syncPassword, settings.mirrorInsecureTls)
    if (has.code !in 200..299) emptySet() else parseKnownCommitHashes(has.body)
  }

  val known: Set<String> = HashSet<String>(serverKnown.size + knownFromHas.size).also {
    it.addAll(serverKnown)
    it.addAll(knownFromHas)
  }

  if (known.isEmpty()) return MultiBranchNegotiation(null, emptyList())

  // pointer-only fast path: server knows every tip, no bundle needed
  val allTipsKnown = known.contains(head.lowercase()) && additionalBranches.all { br ->
    val tip = git.branchHash(project, projectDir, br)
    tip == null || known.contains(tip.lowercase())
  }
  if (allTipsKnown) {
    return MultiBranchNegotiation(pointerCommit = head, excludeBases = emptyList())
  }

  // merge-base, then own history, then verified hints; git calls capped
  val serverTipsByRecency = serverRefs.refs?.values
    ?.filter { it.sha.isNotBlank() && hashRe.matches(it.sha) }
    ?.sortedWith(compareByDescending<MirrorSyncApi.RefInfo> { it.updated }.thenBy { it.sha })
    ?.map { it.sha }
    ?: emptyList()

  val excludeBases = mutableListOf<String>()
  for (info in branchInfos) {
    val startedAt = System.currentTimeMillis()

    // Try merge-base with server tip first (fastest path)
    val serverTip = serverRefs.refs?.get(info.name)?.sha
    if (serverTip != null && known.contains(serverTip.lowercase())) {
      val mergeBase = git.mergeBase(project, projectDir, info.tip, serverTip)
      if (mergeBase != null && mergeBase.lowercase() != info.tip.lowercase()) {
        excludeBases.add(mergeBase)
        localgitmirror.idea.sync.SyncLogger.log(
          projectDir,
          "[negotiate-branch] ${info.name}: base=$mergeBase via merge-base with server tip (${System.currentTimeMillis() - startedAt} ms)"
        )
        continue
      }
    }

    // Guaranteed ancestors (own recent history of the tip) — no git call.
    val ownBase = pickBestKnownBase(info.tip, info.guaranteed, known)
    if (ownBase != null) {
      excludeBases.add(ownBase)
      localgitmirror.idea.sync.SyncLogger.log(
        projectDir,
        "[negotiate-branch] ${info.name}: base=$ownBase from own history (${System.currentTimeMillis() - startedAt} ms)"
      )
      continue
    }

    // verified candidates: hints first, then server tips; git calls capped
    val guaranteedSet = info.guaranteed.mapTo(HashSet()) { it.lowercase() }
    val hints = info.candidates.filter { it.lowercase() !in guaranteedSet }
    val toVerify = (hints + serverTipsByRecency).distinct()
    var checks = 0
    var base: String? = null
    val tipLc = info.tip.lowercase()
    for (cand in toVerify) {
      if (checks >= SyncConstants.ANCESTRY_CHECK_CAP) break
      val lc = cand.lowercase()
      if (lc == tipLc || !known.contains(lc)) continue
      checks++
      if (git.isAncestor(project, projectDir, cand, info.tip)) {
        base = cand
        break
      }
    }
    if (base != null) excludeBases.add(base)
    localgitmirror.idea.sync.SyncLogger.log(
      projectDir,
      "[negotiate-branch] ${info.name}: base=${base ?: "none (full bundle)"} after $checks ancestry check(s) (${System.currentTimeMillis() - startedAt} ms)"
    )
  }

  return MultiBranchNegotiation(pointerCommit = null, excludeBases = excludeBases.distinct())
}
