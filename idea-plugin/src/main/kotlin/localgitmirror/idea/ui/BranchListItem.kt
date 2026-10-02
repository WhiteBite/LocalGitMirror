package localgitmirror.idea.ui

enum class BranchStatus { SYNCED, AHEAD, BEHIND, MIRROR_ONLY, LOCAL_ONLY }

/** Chip filter above the branch list; combines with the free-text filter. */
internal enum class BranchFilterMode { ALL, WITH_MR, LOCAL }

data class BranchListItem(
    val name: String,
    val status: BranchStatus,
    val localHash: String?,
    val mirrorHash: String?,
    val aheadCount: Int? = null,
    val behindCount: Int? = null,
    val mrIid: Int? = null,
    val mrUnresolved: Int = 0,
    val isCurrent: Boolean = false,
    /** True when the row exists only as an open-MR source branch (no local, no Cache copy). */
    val mrOnly: Boolean = false
)
