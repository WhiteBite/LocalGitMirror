package localgitmirror.idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore

/**
 * Shows all branches on the Mirror server, lets the user select stale ones and
 * delete them. Internally uses a simple editable-chooser dialog (no custom Swing
 * needed) so it works across all platform versions.
 *
 * Safety rules (also enforced server-side):
 *  - HEAD branch cannot be deleted.
 *  - The last branch cannot be deleted.
 *  - Explicit confirmation required before each deletion batch.
 */
class ManageMirrorBranchesAction : AnAction() {
  override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val settings = service<MirrorSettingsService>().state
    if (settings.baseUrl.isBlank()) {
      notify(project, "Настройте Mirror URL в настройках.", NotificationType.WARNING)
      return
    }
    val repo = localgitmirror.idea.sync.v2.RepoResolver
      .resolve(project, java.io.File(project.basePath ?: "."), "")
      .sanitized
      .ifBlank { project.name }
    val insecure = settings.mirrorInsecureTls
    val apiKey = SecretsStore.mirrorApiKey
    val history = service<OperationsHistoryService>()

    ProgressManager.getInstance().run(
      object : Task.Backgroundable(project, "LocalGitMirror: загружаем ветки Mirror…", true) {
        override fun run(indicator: ProgressIndicator) {
          indicator.isIndeterminate = true

          // 1. Fetch ref list
          val result = MirrorApi.getRefs(settings.baseUrl, apiKey, repo, SecretsStore.syncPassword, insecure)
          if (result.code !in 200..299) {
            notify(project, "Не удалось загрузить ветки (${result.code}): ${result.message}", NotificationType.ERROR)
            return
          }
          val refs = result.refs
          if (refs.isNullOrEmpty()) {
            notify(project, "На Mirror нет веток для репозитория '$repo'.", NotificationType.INFORMATION)
            return
          }

          // 2. Build display list — sorted: HEAD first, then alpha
          data class BEntry(val name: String, val info: MirrorApi.RefInfo) {
            val label: String get() {
              val h = if (info.isHead) " ★ HEAD" else ""
              val d = if (info.updated.length >= 10) " [${info.updated.take(10)}]" else ""
              return "$name$d$h"
            }
          }
          val all = refs.entries
            .map { BEntry(it.key, it.value) }
            .sortedWith(compareByDescending<BEntry> { it.info.isHead }.thenBy { it.name })

          val deletable = all.filter { !it.info.isHead }
          val headBranch = all.firstOrNull { it.info.isHead }?.name ?: "(HEAD неизвестен)"

          if (deletable.isEmpty()) {
            notify(project, "Все ветки на Mirror — HEAD-ветка ('$headBranch'). Удалять нечего.", NotificationType.INFORMATION)
            return
          }

          // 3. Show picker on EDT — repeat until user cancels or picks a valid branch
          val toDelete = UIUtil.invokeAndWaitIfNeeded<List<String>> {
            val options = all.map { it.label }.toTypedArray()
            val deletableLabels = deletable.map { it.label }.toSet()

            val chosen = Messages.showEditableChooseDialog(
              buildString {
                appendLine("Выберите ветку для удаления с Mirror (repo='$repo').")
                appendLine()
                appendLine("★ HEAD = текущая ветка ('$headBranch') — удалить нельзя.")
                append("Всего веток: ${all.size}")
              },
              "LocalGitMirror: управление ветками Mirror",
              null, options, deletable.firstOrNull()?.label, null
            ) ?: return@invokeAndWaitIfNeeded emptyList()

            if (chosen !in deletableLabels) {
              Messages.showInfoMessage(
                project,
                "Ветку '$headBranch' (HEAD) удалить нельзя.",
                "LocalGitMirror"
              )
              return@invokeAndWaitIfNeeded emptyList()
            }

            // Resolve back to branch name from label
            listOfNotNull(deletable.firstOrNull { it.label == chosen }?.name)
          }

          if (toDelete.isEmpty()) return

          // 4. Confirm
          val confirmed = UIUtil.invokeAndWaitIfNeeded<Int> {
            Messages.showYesNoDialog(
              project,
              "Удалить ветку «${toDelete.first()}» с Mirror?\n\nЭто действие необратимо.",
              "LocalGitMirror: удалить ветку",
              "Удалить", "Отмена", null
            )
          }
          if (confirmed != Messages.YES) return

          // 5. Delete
          val deleted = mutableListOf<String>()
          val failed  = mutableListOf<String>()
          for (branch in toDelete) {
            indicator.text = "Удаляем $branch…"
            val r = MirrorApi.deleteRef(settings.baseUrl, apiKey, repo, branch, SecretsStore.syncPassword, insecure)
            if (r.code in 200..299) deleted.add(branch)
            else failed.add("$branch (HTTP ${r.code}: ${r.body.take(120)})")
          }

          val msg = buildString {
            if (deleted.isNotEmpty()) append("Удалено: ${deleted.joinToString(", ")}.")
            if (failed.isNotEmpty()) { if (deleted.isNotEmpty()) append("\n"); append("Ошибка: ${failed.joinToString("; ")}") }
          }
          notify(project, msg.ifBlank { "Готово." }, if (failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
          history.add("Delete Mirror branch", failed.isEmpty(),
            "deleted=${deleted.size} failed=${failed.size} branches=${(deleted + toDelete).distinct()} repo='$repo'")
        }
      }
    )
  }

  private fun notify(project: Project, msg: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("LocalGitMirror")
      .createNotification(msg, type)
      .notify(project)
  }
}
