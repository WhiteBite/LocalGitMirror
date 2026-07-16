package localgitmirror.idea.ui

/**
 * An item in the tool-window branch selector. Mirror-only branches remain
 * selectable for Pull while their raw [name] is kept separate from the label.
 */
internal data class BranchChoice(
  val name: String,
  val isLocal: Boolean
) {
  val isMirrorOnly: Boolean get() = !isLocal

  override fun toString(): String = if (isLocal) name else "★ $name (Mirror)"
}

/** Pure branch-selector rules, kept outside Swing so they can be unit tested. */
internal object BranchSelectorModel {
  /**
   * List local branches first, then branches known only to Mirror. Both groups
   * are sorted for a stable selector and duplicate/blank names are removed.
   */
  fun merge(localBranches: Collection<String>, mirrorBranches: Collection<String>): List<BranchChoice> {
    val local = localBranches.asSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .toSortedSet()
    val mirrorOnly = mirrorBranches.asSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .filterNot(local::contains)
      .toSortedSet()

    return local.map { BranchChoice(it, isLocal = true) } +
      mirrorOnly.map { BranchChoice(it, isLocal = false) }
  }

  /** Keep a valid previous selection; otherwise prefer the current local branch. */
  fun preferredSelection(
    selectedName: String?,
    currentLocalBranch: String?,
    choices: Collection<BranchChoice>
  ): String? = when {
    selectedName != null && choices.any { it.name == selectedName } -> selectedName
    currentLocalBranch != null && choices.any { it.name == currentLocalBranch } -> currentLocalBranch
    else -> choices.firstOrNull()?.name
  }
}
