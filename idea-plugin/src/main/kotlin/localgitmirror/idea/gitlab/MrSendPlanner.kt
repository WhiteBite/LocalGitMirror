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
  ): Plan {
    val sent = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    for (branch in branches) {
      val tip = localTips[branch] ?: throw IllegalArgumentException("cannot resolve refs/heads/$branch")
      val mirrorSha = mirrorRefs[branch] ?: ""
      if (mirrorSha.isNotEmpty() && mirrorSha == tip) skipped.add(branch) else sent.add(branch)
    }
    if (sent.isEmpty()) return Plan(emptyList(), skipped.toList(), emptyList())
    // sent tips stay out of the exclusions: excluding one makes git bundle refuse an empty pack
    val sentTips = sent.mapTo(HashSet()) { localTips.getValue(it) }
    val excludes = mirrorRefs.values.filter { it.isNotEmpty() }.distinct()
      .filter { it in existingShas && it !in sentTips }
    return Plan(sent.toList(), skipped.toList(), excludes)
  }
}
