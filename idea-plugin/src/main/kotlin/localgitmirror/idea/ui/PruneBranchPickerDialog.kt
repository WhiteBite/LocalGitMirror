package localgitmirror.idea.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Multi-select picker for Mirror prune candidates (same style as
 * [CommitPickerDialog]: filter field + multi-selection list).
 *
 * All candidate branches start SELECTED — the branches to delete. The user
 * deselects the branches to keep; [selectedBranches] (read after OK) returns
 * the still-selected branches in the original candidate order. Selection
 * state is tracked by branch name, so it survives filter changes.
 */
class PruneBranchPickerDialog(
  project: Project,
  private val candidates: List<String>
) : DialogWrapper(project, true) {
  private val searchField = JBTextField()
  private val listModel = DefaultListModel<String>()
  private val list = JBList(listModel)

  private var filtered: List<String> = candidates
  private val selectedNames: MutableSet<String> = LinkedHashSet(candidates)

  var selectedBranches: List<String> = emptyList()
    private set

  init {
    title = LocalGitMirrorBundle.message("prune.picker.title")
    list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    list.visibleRowCount = 12
    refreshList("", capture = false)

    searchField.emptyText.text = LocalGitMirrorBundle.message("prune.picker.filter")
    searchField.document.addDocumentListener(object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent?) = refreshList(searchField.text)
      override fun removeUpdate(e: DocumentEvent?) = refreshList(searchField.text)
      override fun changedUpdate(e: DocumentEvent?) = refreshList(searchField.text)
    })

    init()
  }

  override fun createCenterPanel(): JComponent {
    val top = JPanel(BorderLayout(0, 4))
    top.add(JBLabel(LocalGitMirrorBundle.message("prune.notify.candidates", candidates.size)), BorderLayout.NORTH)
    top.add(searchField, BorderLayout.CENTER)
    top.add(JBLabel(LocalGitMirrorBundle.message("prune.picker.hint")), BorderLayout.SOUTH)

    val panel = JPanel(BorderLayout(0, 8))
    panel.add(top, BorderLayout.NORTH)
    panel.add(JBScrollPane(list), BorderLayout.CENTER)
    return panel
  }

  override fun doOKAction() {
    captureSelection()
    selectedBranches = candidates.filter { it in selectedNames }
    super.doOKAction()
  }

  /**
   * Rebuild the visible list from [filter]. When [capture] is true, the
   * selection state of the currently visible items is captured first (so
   * user tweaks are not lost); hidden items keep their previous state.
   */
  private fun refreshList(filter: String, capture: Boolean = true) {
    if (capture) captureSelection()
    val q = filter.trim().lowercase()
    filtered = if (q.isBlank()) {
      candidates
    } else {
      candidates.filter { it.lowercase().contains(q) }
    }

    listModel.clear()
    filtered.forEach { listModel.addElement(it) }
    applySelection()
  }

  /** Set the list selection from the tracked selection state. */
  private fun applySelection() {
    list.clearSelection()
    filtered.forEachIndexed { i, name ->
      if (name in selectedNames) list.addSelectionInterval(i, i)
    }
  }

  /** Record the selection state of the currently visible items. */
  private fun captureSelection() {
    val picked = list.selectedValuesList.toSet()
    for (name in filtered) {
      if (name in picked) selectedNames.add(name) else selectedNames.remove(name)
    }
  }
}
