package localgitmirror.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class LocalGitMirrorRuleSetProvider : RuleSetProvider {
  override val ruleSetId: String = "localgitmirror"

  override fun instance(config: Config): RuleSet = RuleSet(
    ruleSetId,
    listOf(
      HttpCallOnEdt(config),
      HardcodedStringInAction(config),
      SettingsLookupByDisplayName(config),
      StaleInitState(config),
    )
  )
}
