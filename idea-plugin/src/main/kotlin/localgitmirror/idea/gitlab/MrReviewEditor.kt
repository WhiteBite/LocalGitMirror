package localgitmirror.idea.gitlab

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

internal class MrReviewVirtualFile(val row: MrReviewService.MrRowItem) :
  LightVirtualFile("MR !${row.iid} - ${row.title}") {
  init { isWritable = false }
}

class MrReviewEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean = file is MrReviewVirtualFile
  override fun createEditor(project: Project, file: VirtualFile): FileEditor =
    MrReviewEditor(project, file as MrReviewVirtualFile)
  override fun getEditorTypeId(): String = "doccache-mr-review"
  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class MrReviewEditor(project: Project, private val file: MrReviewVirtualFile) : UserDataHolderBase(), FileEditor {
  private val review = MrReviewDialog(project, file.row)
  override fun getComponent(): JComponent = review
  override fun getPreferredFocusedComponent(): JComponent = review
  override fun getName(): String = "DocCache Review"
  override fun getFile(): VirtualFile = file
  override fun setState(state: FileEditorState) = Unit
  override fun isModified(): Boolean = false
  override fun isValid(): Boolean = file.isValid
  override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
  override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
  override fun dispose() = Disposer.dispose(review)
}

internal fun openMrReviewEditor(project: Project, row: MrReviewService.MrRowItem) {
  val manager = FileEditorManager.getInstance(project)
  val existing = manager.openFiles.filterIsInstance<MrReviewVirtualFile>()
    .firstOrNull { it.row.iid == row.iid && it.row.repo == row.repo }
  manager.openFile(existing ?: MrReviewVirtualFile(row), true)
}
