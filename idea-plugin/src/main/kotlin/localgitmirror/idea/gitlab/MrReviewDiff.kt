package localgitmirror.idea.gitlab

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.ui.JBUI
import localgitmirror.idea.i18n.LocalGitMirrorBundle.message
import java.awt.BorderLayout
import java.awt.GridLayout
import java.io.File
import java.io.IOException
import java.util.concurrent.Future
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField

internal class MrReviewDiff(
  private val project: Project,
  row: MrReviewService.MrRowItem,
  private val filesLoaded: (List<String>) -> Unit,
) : JPanel(BorderLayout()), Disposable {
  private val source = JTextField(row.sourceBranch, 14)
  private val target = JTextField(row.targetBranch, 14)
  private val summary = JTextArea().apply {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    isOpaque = false
    font = JBUI.Fonts.label()
    rows = 3
    border = JBUI.Borders.empty(4, 8)
  }
  private val viewer = DiffManager.getInstance().createRequestPanel(project, this, null)
  private var snapshot: MrReviewSnapshot? = null
  private var requested: kotlin.Pair<String?, Int?> = null to null
  private var revisionTask: Future<*>? = null
  private var contentTask: Future<*>? = null
  private var generation = 0
  private var selection = 0
  @Volatile private var disposed = false

  init {
    val inputs = JPanel(GridLayout(0, 1, 0, JBUI.scale(4)))
    listOf("mrworkspace.source" to source, "mrworkspace.target" to target).forEach { (key, field) ->
      inputs.add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        add(JLabel(message(key)).apply { labelFor = field }, BorderLayout.WEST)
        add(field, BorderLayout.CENTER)
      })
    }
    val toolbar = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
      border = JBUI.Borders.empty(4, 8)
      add(inputs, BorderLayout.CENTER)
      add(JButton(message("mrworkspace.compare")).apply { addActionListener { load() } }, BorderLayout.EAST)
    }
    add(JPanel(BorderLayout()).apply {
      add(toolbar, BorderLayout.NORTH)
      add(summary, BorderLayout.CENTER)
    }, BorderLayout.NORTH)
    add(viewer.component, BorderLayout.CENTER)
    viewer.setRequest(MessageDiffRequest(message("mrworkspace.choose")))
  }

  fun load() {
    revisionTask?.cancel(true)
    contentTask?.cancel(true)
    val stamp = ++generation
    snapshot = null
    filesLoaded(emptyList())
    val sourceRef = source.text.trim()
    val targetRef = target.text.trim()
    summary.text = message("mrworkspace.loading")
    viewer.setRequest(MessageDiffRequest(message("mrworkspace.loading")))
    revisionTask = ApplicationManager.getApplication().executeOnPooledThread {
      try {
        val captured = git().capture(sourceRef, targetRef)
        onEdt(stamp) {
          snapshot = captured
          summary.text = message("mrworkspace.snapshot", captured.baseSha.take(8), captured.sourceSha.take(8),
            captured.targetSha.take(8), captured.files.size)
          filesLoaded(captured.files)
          showPath(requested.first, requested.second)
        }
      } catch (error: IOException) {
        onEdt(stamp) {
          summary.text = message("mrworkspace.revisionError", error.message.orEmpty())
          viewer.setRequest(MessageDiffRequest(summary.text))
        }
      }
    }
  }

  fun showPath(path: String?, line: Int?) {
    requested = path to line
    contentTask?.cancel(true)
    val stamp = generation
    val chosen = ++selection
    val captured = snapshot ?: return
    if (path.isNullOrBlank()) {
      viewer.setRequest(MessageDiffRequest(message("mrworkspace.choose")))
      return
    }
    viewer.setRequest(MessageDiffRequest(message("mrworkspace.loading")))
    contentTask = ApplicationManager.getApplication().executeOnPooledThread {
      try {
        val content = git().content(captured, path)
        onEdt(stamp) {
          if (chosen != selection) return@onEdt
          if (content.binary) {
            viewer.setRequest(MessageDiffRequest(message("mrworkspace.binary", path)))
            return@onEdt
          }
          val factory = DiffContentFactory.getInstance()
          val type = FileTypeManager.getInstance().getFileTypeByFileName(path)
          val request = SimpleDiffRequest(path,
            factory.create(project, StringUtil.convertLineSeparators(content.before.orEmpty()), type),
            factory.create(project, StringUtil.convertLineSeparators(content.after.orEmpty()), type),
            "${captured.baseSha.take(8)} (${message("mrworkspace.base")})", captured.sourceSha.take(8))
          request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
          if (line != null && line > 0) {
            val side = if (content.after == null) Side.LEFT else Side.RIGHT
            val text = content.after ?: content.before.orEmpty()
            val last = text.count { it == '\n' }
            request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, Pair.create(side, (line - 1).coerceAtMost(last)))
          }
          viewer.setRequest(request)
        }
      } catch (error: IOException) {
        onEdt(stamp) {
          if (chosen == selection) viewer.setRequest(MessageDiffRequest(error.message.orEmpty()))
        }
      }
    }
  }

  private fun git(): MrReviewGit = MrReviewGit(File(project.basePath ?: throw IOException("No project directory")))

  private fun onEdt(stamp: Int, action: () -> Unit) {
    ApplicationManager.getApplication().invokeLater {
      if (!disposed && !project.isDisposed && stamp == generation) action()
    }
  }

  override fun dispose() {
    disposed = true
    revisionTask?.cancel(true)
    contentTask?.cancel(true)
  }
}
