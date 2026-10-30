package localgitmirror.idea.gitlab

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File
import java.nio.file.Files

internal object MrReviewSnapshotAction {

  fun prepareInBackground(project: Project, iids: List<Int>, onDone: ((Boolean) -> Unit)? = null) {
    if (iids.isEmpty()) return
    ProgressManager.getInstance().run(object : Task.Backgroundable(
      project,
      LocalGitMirrorBundle.message("review.prepare.title", iids.size),
      true,
    ) {
      override fun run(indicator: ProgressIndicator) {
        val conf = GitLabConfig.resolve(project)
        if (!GitLabConfig.hasApi(conf)) {
          finish(project, false, listOf(LocalGitMirrorBundle.message("review.prepare.noApi")),
                 NotificationType.ERROR, onDone)
          return
        }
        val baseDir = project.basePath
        val projectDir = baseDir?.let { File(it) }
        val remoteUrl = projectDir?.let { dir ->
          runCatching {
            GitLocal.remoteUrl(project, dir, GitLocal.defaultRemote(project, dir))
          }.getOrNull()
        }.orEmpty()
        if (projectDir == null || remoteUrl.isBlank()) {
          finish(project, false, listOf(LocalGitMirrorBundle.message("review.prepare.noRemote")),
                 NotificationType.ERROR, onDone)
          return
        }
        val settings = service<MirrorSettingsService>().state
        val repo = runCatching {
          project.getService(SyncFacadeService::class.java).resolveRepo(projectDir, settings).sanitized
        }.getOrNull().orEmpty()
        if (repo.isBlank()) {
          finish(project, false, listOf(LocalGitMirrorBundle.message("review.prepare.noRepo")),
                 NotificationType.ERROR, onDone)
          return
        }

        val postbox = PluginMrReviewPostbox(project)
        val outputDir = Files.createTempDirectory("lgm-review-manual-").toFile()
        val lines = mutableListOf<String>()
        var failures = 0
        try {
          for (iid in iids) {
            indicator.checkCanceled()
            indicator.text = LocalGitMirrorBundle.message("review.prepare.progress", iid)
            when (val prep = MrReviewSnapshotSync.prepare(conf, remoteUrl, repo, iid, outputDir)) {
              is MrReviewSnapshotSync.Failed -> {
                failures++
                lines += LocalGitMirrorBundle.message("review.prepare.fail", iid, coded(prep.code, prep.message))
              }
              is MrReviewSnapshotSync.Ready ->
                when (val t = MrReviewTransport.transfer(postbox, prep, allowCodeUpload = true)) {
                  is MrReviewTransport.Transferred -> {
                    lines += LocalGitMirrorBundle.message(
                      "review.prepare.transferred", iid, t.snapshotId.take(12), t.bundleAction, t.bundleSize)
                    t.limitations.forEach { lines += LocalGitMirrorBundle.message("review.prepare.limitation", it) }
                  }
                  is MrReviewTransport.Skipped -> {
                    failures++
                    lines += LocalGitMirrorBundle.message("review.prepare.skipped", iid, t.snapshotId.take(12))
                    t.limitations.forEach { lines += LocalGitMirrorBundle.message("review.prepare.limitation", it) }
                  }
                  is MrReviewTransport.TransferFailed -> {
                    failures++
                    lines += LocalGitMirrorBundle.message("review.prepare.transferFail", iid, coded(t.code, t.message))
                  }
                }
            }
          }
        } finally {
          runCatching { outputDir.deleteRecursively() }
        }
        finish(
          project,
          failures == 0,
          lines,
          if (failures == 0) NotificationType.INFORMATION else NotificationType.WARNING,
          onDone,
          historyDetails = "iids=${iids.joinToString(",")} failures=$failures",
        )
      }

      override fun onCancel() {
        UIUtil.invokeLaterIfNeeded {
          if (!project.isDisposed) {
            notify(project, LocalGitMirrorBundle.message("review.prepare.cancelled"), NotificationType.INFORMATION)
          }
        }
      }

      override fun onThrowable(error: Throwable) {
        if (error is ProcessCanceledException) {
          onCancel()
          return
        }
        UIUtil.invokeLaterIfNeeded {
          if (!project.isDisposed) {
            notify(project, LocalGitMirrorBundle.message("review.prepare.crash", error.message ?: "error"),
                   NotificationType.ERROR)
            service<OperationsHistoryService>().add(
              LocalGitMirrorBundle.message("history.op.prepareSnapshot"), false,
              "err=${error.message ?: "error"}",
            )
          }
        }
      }
    })
  }

  private fun finish(
    project: Project,
    ok: Boolean,
    lines: List<String>,
    type: NotificationType,
    onDone: ((Boolean) -> Unit)?,
    historyDetails: String = "",
  ) {
    UIUtil.invokeLaterIfNeeded {
      if (project.isDisposed) return@invokeLaterIfNeeded
      if (lines.isNotEmpty()) notify(project, lines.joinToString("\n"), type)
      service<OperationsHistoryService>().add(
        LocalGitMirrorBundle.message("history.op.prepareSnapshot"), ok, historyDetails,
      )
      onDone?.invoke(ok)
    }
  }

  private fun coded(code: String?, message: String): String =
    if (code.isNullOrBlank()) message else "[$code] $message"

  private fun notify(project: Project, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("DocCache")
      .createNotification(content, type)
      .notify(project)
  }
}
