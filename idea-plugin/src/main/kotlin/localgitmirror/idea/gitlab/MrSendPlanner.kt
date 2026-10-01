package localgitmirror.idea.gitlab

// Parity twin of lgm_core/ops_mr.py op_mr_send — keep the decision logic identical (docs/mr-send-contract.md).
object MrSendPlanner {

  data class Plan(
    val sent: List<String>,
    val skipped: List<String>,
    val excludeShas: List<String>,
  )

  fun planSend(
    mirrorRefs: Map<String, String>,
    localTips: Map<String, String>,
    existingShas: Set<String>,
    branches: List<String>,
    newCommitCount: Int,
  ): Plan {
    val sent = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    for (branch in branches) {
      val tip = localTips[branch] ?: throw IllegalArgumentException("cannot resolve refs/heads/$branch")
      val mirrorSha = mirrorRefs[branch] ?: ""
      if (mirrorSha.isNotEmpty() && mirrorSha == tip) skipped.add(branch) else sent.add(branch)
    }
    val excludes = if (sent.isEmpty()) emptyList()
      else mirrorRefs.values.filter { it.isNotEmpty() }.distinct().filter { it in existingShas }
    if (sent.isNotEmpty() && excludes.isNotEmpty() && newCommitCount == 0) {
      skipped.addAll(sent)
      sent.clear()
    }
    return Plan(
      sent = sent.toList(),
      skipped = skipped.toList(),
      excludeShas = if (sent.isEmpty()) emptyList() else excludes,
    )
  }
}
