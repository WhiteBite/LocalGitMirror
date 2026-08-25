package localgitmirror.idea.sync.v2

/**
 * Tuning parameters for incremental sync negotiation.
 * 
 * These values balance bundle size vs negotiation overhead:
 * - Larger values = better chance of finding common ancestor = smaller bundles
 * - Larger values = more memory/CPU during negotiation
 * 
 * Current tuning (2024):
 * - 200 commits covers ~2 weeks of active development for most projects
 * - 300 haves gives server enough context to exclude old commits
 * - 300 candidates per branch prevents hasCommits payload explosion
 */
object SyncConstants {
  /**
   * How many recent commits to walk from HEAD when building negotiation candidates.
   * Used in negotiateMultiBranch() to find common ancestors with server.
   */
  const val RECENT_COMMITS_DEPTH = 200

  /**
   * How many recent commits to walk from each additional branch.
   * Same purpose as RECENT_COMMITS_DEPTH but for non-current branches.
   */
  const val ADDITIONAL_BRANCH_DEPTH = 200

  /**
   * How many commits to report as "haves" during pull.
   * Server uses this to exclude commits we already have from the bundle.
   */
  const val PULL_HAVES_DEPTH = 200

  /**
   * Maximum number of haves to send to server.
   * Caps the payload even if PULL_HAVES_DEPTH finds more.
   */
  const val PULL_HAVES_LIMIT = 300

  /**
   * Maximum number of candidates to send in hasCommits request.
   * Prevents payload explosion when many branches have deep history.
   */
  const val HAS_COMMITS_CANDIDATE_LIMIT = 300

  /**
   * Hard cap on `merge-base --is-ancestor` git calls per branch during
   * exclude-base selection. Each call is a commit-graph walk that can take
   * seconds on fragmented object stores (dozens of packs + AV scanning),
   * so an uncapped candidate loop is what turned sends into 30-minute hangs.
   */
  const val ANCESTRY_CHECK_CAP = 8
}
