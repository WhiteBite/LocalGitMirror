package localgitmirror.idea.ui

import localgitmirror.idea.deps.MachineRole

const val REVIEW_ROLE_UNDETERMINED_KEY = "review.btn.roleUndetermined"
const val REVIEW_ROLE_HOME_KEY = "review.btn.roleHome"
const val REVIEW_SEND_ROLE_KEY = "review.sendSelected.roleReason"
const val REVIEW_SEND_SELECTION_KEY = "review.sendSelected.selectionReason"
const val REVIEW_STATE_PENDING_WORK_KEY = "review.state.pending.work"
const val REVIEW_STATE_PENDING_AUTO_KEY = "review.state.pending.auto"

data class ReviewButtonPolicy(
  val enabled: Boolean,
  val reasonKey: String? = null
)

data class SendSelectedPolicy(
  val enabled: Boolean,
  val reasonKey: String? = null
)

/** Notes sync and reply publishing run on the work PC; other roles stay visible but disabled with a reason. */
fun reviewWorkButtonPolicy(role: MachineRole): ReviewButtonPolicy = when (role) {
  MachineRole.WORK -> ReviewButtonPolicy(enabled = true)
  MachineRole.HOME -> ReviewButtonPolicy(enabled = false, reasonKey = REVIEW_ROLE_HOME_KEY)
  MachineRole.UNDETERMINED -> ReviewButtonPolicy(enabled = false, reasonKey = REVIEW_ROLE_UNDETERMINED_KEY)
}

/** Sending MR branches needs GitLab access (work) and at least one GitLab-source row in the selection. */
fun sendSelectedPolicy(role: MachineRole, hasGitlabSelection: Boolean): SendSelectedPolicy = when {
  role != MachineRole.WORK -> SendSelectedPolicy(enabled = false, reasonKey = REVIEW_SEND_ROLE_KEY)
  !hasGitlabSelection -> SendSelectedPolicy(enabled = false, reasonKey = REVIEW_SEND_SELECTION_KEY)
  else -> SendSelectedPolicy(enabled = true)
}

/** Pending replies read differently on work: awaiting approval, or auto-published when autoPushReplies is on. */
fun reviewReplyStateKey(replyState: String, role: MachineRole, autoPushReplies: Boolean): String = when {
  replyState != "pending" -> "review.state.$replyState"
  role != MachineRole.WORK -> "review.state.pending"
  autoPushReplies -> REVIEW_STATE_PENDING_AUTO_KEY
  else -> REVIEW_STATE_PENDING_WORK_KEY
}
