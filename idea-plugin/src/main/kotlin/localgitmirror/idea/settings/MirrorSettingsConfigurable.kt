package localgitmirror.idea.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.components.service
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

class MirrorSettingsConfigurable : Configurable {
  private var panel: JPanel? = null

  private val mirrorBaseUrl = JTextField()
  private val mirrorApiKey = JPasswordField()
  private val mirrorRepo = JTextField()
  private val uiLanguage = javax.swing.JComboBox(arrayOf("auto", "en", "ru"))
  private val syncPassword = JPasswordField()
  private val mirrorInsecureTls = JCheckBox(LocalGitMirrorBundle.message("settings.insecureTls"))

  private val gitLabBaseUrl = JTextField()
  private val gitLabToken = JPasswordField()
  private val gitLabProject = JTextField()
  private val gitLabTargetBranch = JTextField()
  private val gitLabInsecureTls = JCheckBox(LocalGitMirrorBundle.message("settings.insecureTls"))

  private val gitRemoteName = JTextField()
  private val pullBackDefaultMode = JTextField()
  private val offlineGenerateOnly = JCheckBox(LocalGitMirrorBundle.message("settings.sync.offlineMode"))
  private val autoCheckPullOnStartup = JCheckBox(LocalGitMirrorBundle.message("settings.sync.autoCheck"))
  private val simpleUiMode = JCheckBox(LocalGitMirrorBundle.message("settings.ui.simpleMode"))

  override fun getDisplayName(): String = "LocalGitMirror"

  override fun createComponent(): JComponent {
    val root = JPanel()
    root.layout = BoxLayout(root, BoxLayout.Y_AXIS)

    fun row(label: String, comp: JComponent) {
      val r = JPanel()
      r.layout = BoxLayout(r, BoxLayout.Y_AXIS)
      r.add(JLabel(label))
      r.add(comp)
      root.add(r)
    }

    row(LocalGitMirrorBundle.message("settings.mirror.baseUrl"), mirrorBaseUrl)
    row(LocalGitMirrorBundle.message("settings.mirror.apiKey"), mirrorApiKey)
    row(LocalGitMirrorBundle.message("settings.mirror.repo"), mirrorRepo)
    row(LocalGitMirrorBundle.message("settings.ui.language"), uiLanguage)
    row(LocalGitMirrorBundle.message("settings.mirror.syncPassword"), syncPassword)
    root.add(mirrorInsecureTls)

    root.add(JLabel(" "))
    root.add(JLabel(LocalGitMirrorBundle.message("settings.gitlab.title")))
    row(LocalGitMirrorBundle.message("settings.gitlab.baseUrl"), gitLabBaseUrl)
    row(LocalGitMirrorBundle.message("settings.gitlab.token"), gitLabToken)
    row(LocalGitMirrorBundle.message("settings.gitlab.project"), gitLabProject)
    row(LocalGitMirrorBundle.message("settings.gitlab.defaultTarget"), gitLabTargetBranch)
    root.add(gitLabInsecureTls)

    root.add(JLabel(" "))
    root.add(JLabel(LocalGitMirrorBundle.message("settings.git.title")))
    row(LocalGitMirrorBundle.message("settings.git.remote"), gitRemoteName)
    row(LocalGitMirrorBundle.message("settings.git.pullMode"), pullBackDefaultMode)
    root.add(offlineGenerateOnly)
    root.add(autoCheckPullOnStartup)
    root.add(simpleUiMode)

    panel = root
    reset()
    return root
  }

  private fun state(): MirrorSettingsService.State = service<MirrorSettingsService>().state

  override fun isModified(): Boolean {
    val state = state()
    return mirrorBaseUrl.text != state.baseUrl ||
      String(mirrorApiKey.password) != SecretsStore.mirrorApiKey ||
      mirrorRepo.text != state.repo ||
      (uiLanguage.selectedItem?.toString() ?: "auto") != state.uiLanguage ||
      String(syncPassword.password) != SecretsStore.syncPassword ||
      mirrorInsecureTls.isSelected != state.mirrorInsecureTls ||
      gitLabBaseUrl.text != state.gitLabBaseUrl ||
      String(gitLabToken.password) != SecretsStore.gitLabToken ||
      gitLabProject.text != state.gitLabProject ||
      gitLabTargetBranch.text != state.gitLabDefaultTargetBranch ||
      gitLabInsecureTls.isSelected != state.gitLabInsecureTls ||
      gitRemoteName.text != state.gitRemoteName ||
      pullBackDefaultMode.text != state.pullBackDefaultMode ||
      offlineGenerateOnly.isSelected != state.offlineGenerateOnly ||
      autoCheckPullOnStartup.isSelected != state.autoCheckPullOnStartup ||
      simpleUiMode.isSelected != state.simpleUiMode
  }

  override fun apply() {
    val s = state()
    s.baseUrl = mirrorBaseUrl.text.trim()
    SecretsStore.mirrorApiKey = String(mirrorApiKey.password)
    s.repo = mirrorRepo.text.trim()
    s.uiLanguage = (uiLanguage.selectedItem?.toString() ?: "auto").lowercase()
    SecretsStore.syncPassword = String(syncPassword.password)
    s.mirrorInsecureTls = mirrorInsecureTls.isSelected

    s.gitLabBaseUrl = gitLabBaseUrl.text.trim()
    SecretsStore.gitLabToken = String(gitLabToken.password)
    s.gitLabProject = gitLabProject.text.trim()
    s.gitLabDefaultTargetBranch = gitLabTargetBranch.text.trim().ifBlank { "main" }
    s.gitLabInsecureTls = gitLabInsecureTls.isSelected

    s.gitRemoteName = gitRemoteName.text.trim().ifBlank { "origin" }
    s.pullBackDefaultMode = pullBackDefaultMode.text.trim().ifBlank { "new-branch" }
    s.offlineGenerateOnly = offlineGenerateOnly.isSelected
    s.autoCheckPullOnStartup = autoCheckPullOnStartup.isSelected
    s.simpleUiMode = simpleUiMode.isSelected
  }

  override fun reset() {
    val state = state()
    mirrorBaseUrl.text = state.baseUrl
    mirrorApiKey.text = SecretsStore.mirrorApiKey
    mirrorRepo.text = state.repo
    uiLanguage.selectedItem = state.uiLanguage.ifBlank { "auto" }
    syncPassword.text = SecretsStore.syncPassword
    mirrorInsecureTls.isSelected = state.mirrorInsecureTls

    gitLabBaseUrl.text = state.gitLabBaseUrl
    gitLabToken.text = SecretsStore.gitLabToken
    gitLabProject.text = state.gitLabProject
    gitLabTargetBranch.text = state.gitLabDefaultTargetBranch
    gitLabInsecureTls.isSelected = state.gitLabInsecureTls

    gitRemoteName.text = state.gitRemoteName
    pullBackDefaultMode.text = state.pullBackDefaultMode
    offlineGenerateOnly.isSelected = state.offlineGenerateOnly
    autoCheckPullOnStartup.isSelected = state.autoCheckPullOnStartup
    simpleUiMode.isSelected = state.simpleUiMode
  }

  override fun disposeUIResources() {
    panel = null
  }
}
