package localgitmirror.idea.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.git.GitLocal
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class CommitPickerDialog(
  project: Project,
  private val commits: List<GitLocal.CommitSummary>
) : DialogWrapper(project, true) {
  private val searchField = JBTextField()
  private val listModel = DefaultListModel<String>()
  private val list = JBList(listModel)

  private var filtered: List<GitLocal.CommitSummary> = commits
  var selectedHashes: List<String> = emptyList()
    private set

  init {
    title = LocalGitMirrorBundle.message("dialog.commitPicker.title")
    list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    refreshList("")

    searchField.emptyText.text = LocalGitMirrorBundle.message("dialog.commitPicker.filter")
    searchField.document.addDocumentListener(object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent?) = refreshList(searchField.text)
      override fun removeUpdate(e: DocumentEvent?) = refreshList(searchField.text)
      override fun changedUpdate(e: DocumentEvent?) = refreshList(searchField.text)
    })

    init()
  }

  override fun createCenterPanel(): JComponent {
    val panel = JPanel(BorderLayout(0, 8))
    panel.add(searchField, BorderLayout.NORTH)
    panel.add(JBScrollPane(list), BorderLayout.CENTER)
    return panel
  }

  override fun doOKAction() {
    val idx = list.selectedIndices.toList()
    val chosen = idx.mapNotNull { i -> filtered.getOrNull(i) }
    selectedHashes = chosen.map { it.hash }
    super.doOKAction()
  }

  private fun refreshList(filter: String) {
    val q = filter.trim().lowercase()
    filtered = if (q.isBlank()) {
      commits
    } else {
      commits.filter {
        it.hash.lowercase().contains(q) || it.subject.lowercase().contains(q)
      }
    }

    listModel.clear()
    filtered.forEach { listModel.addElement(it.display()) }

    if (listModel.size > 0 && list.selectedIndices.isEmpty()) {
      list.setSelectionInterval(0, 0)
    }
  }
}
