package localgitmirror.idea.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import localgitmirror.idea.gitlab.GitLabApi
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Read-only browser of open GitLab merge requests with a "send this MR to
 * Cache" affordance: OK on a selected row hands the MR back to the caller.
 */
class MrListDialog(private val mrs: List<GitLabApi.MrInfo>) : DialogWrapper(true) {

  private val list = JBList(mrs.map { mrText(it) }.toTypedArray())

  init {
    title = LocalGitMirrorBundle.message("gitlab.mrlist.title")
    setOKButtonText(LocalGitMirrorBundle.message("gitlab.mrlist.send"))
    init()
    if (mrs.isNotEmpty()) list.selectedIndex = 0
  }

  val selected: GitLabApi.MrInfo?
    get() = list.selectedIndex.takeIf { it in mrs.indices }?.let { mrs[it] }

  override fun createCenterPanel(): JComponent {
    list.visibleRowCount = 12
    return JBScrollPane(list).apply {
      preferredSize = Dimension(JBUI.scale(560), JBUI.scale(320))
    }
  }

  private fun mrText(mr: GitLabApi.MrInfo): String =
    "!${mr.iid}  ${mr.title}  (${mr.sourceBranch})"
}
