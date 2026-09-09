package localgitmirror.idea.ui

enum class BranchStatus { SYNCED, AHEAD, BEHIND, MIRROR_ONLY, LOCAL_ONLY }

data class BranchListItem(
    val name: String,
    val status: BranchStatus,
    val localHash: String?,
    val mirrorHash: String?,
    val aheadCount: Int? = null,
    val behindCount: Int? = null,
    val mrIid: Int? = null,
    val mrUnresolved: Int = 0,
    val isCurrent: Boolean = false
)
