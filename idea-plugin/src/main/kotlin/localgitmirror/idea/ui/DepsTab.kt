package localgitmirror.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.ui.exchange.formatBufferTs
import localgitmirror.idea.ui.exchange.formatSize
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel

private fun LocalGitMirrorPanel.buildDepsBanner(): EditorNotificationPanel =
  EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
    isVisible = false
    createActionLabel(LocalGitMirrorBundle.message("panel.deps.banner.all")) {
      triggerLgmAction("LocalGitMirror.DepsRespond")
    }
  }

internal fun LocalGitMirrorPanel.buildDepsTab(): JComponent {
  val banner = buildDepsBanner()
  depsBanner = banner

  val responsesPanel = EditorNotificationPanel(EditorNotificationPanel.Status.Info).apply {
    isVisible = false
    createActionLabel(LocalGitMirrorBundle.message("panel.deps.responses.apply")) {
      triggerLgmAction("LocalGitMirror.DepsApply")
    }
  }
  depsResponsesPanel = responsesPanel

  val banners = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    add(banner)
    add(responsesPanel)
  }

  val label = sectionHeader(LocalGitMirrorBundle.message("panel.deps.requests"))
  depsSectionLabel = label

  val center = JPanel(BorderLayout()).apply {
    isOpaque = false
    add(label, BorderLayout.NORTH)
    add(JScrollPane(depsList).apply {
      border = BorderFactory.createEmptyBorder()
      viewportBorder = BorderFactory.createEmptyBorder()
    }, BorderLayout.CENTER)
  }

  val bottom = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
    isOpaque = false
    border = JBUI.Borders.empty(4, 0, 0, 0)
  }
  val respondBtn = greenBtn(LocalGitMirrorBundle.message("deps.menu.respond")) {
    triggerLgmAction("LocalGitMirror.DepsRespond")
  }
  respondButton = respondBtn
  bottom.add(respondBtn)
  val requestBtn = btn(LocalGitMirrorBundle.message("deps.menu.request")) {
    triggerLgmAction("LocalGitMirror.DepsRequest")
  }
  requestDepsButton = requestBtn
  bottom.add(requestBtn)
  val applyBtn = btn(LocalGitMirrorBundle.message("deps.menu.apply")) {
    triggerLgmAction("LocalGitMirror.DepsApply")
  }
  applyDepsButton = applyBtn
  bottom.add(applyBtn)

  return JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(4, 8)
    add(banners, BorderLayout.NORTH)
    add(center, BorderLayout.CENTER)
    add(bottom, BorderLayout.SOUTH)
  }
}

/** Fetch pending deps requests (WORK role) off the EDT and refresh the banner/counters. */
internal fun LocalGitMirrorPanel.refreshDepsInBackground() {
  if (project.isDisposed || ApplicationManager.getApplication().isDisposeInProgress) return
  val s = service<MirrorSettingsService>().state
  if (s.baseUrl.isBlank() || SecretsStore.syncPassword.isBlank()) return
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: deps", true) {
    override fun run(indicator: ProgressIndicator) {
      val dir = baseDir() ?: return
      val repo = try { syncFacade.resolveRepo(dir, s).sanitized } catch (_: Throwable) { "" }
      if (repo.isBlank()) return
      indicator.checkCanceled()
      val pending = MirrorApi.depsPending(
        baseUrl = s.baseUrl,
        apiKey = SecretsStore.mirrorApiKey,
        repo = repo,
        insecureTls = s.mirrorInsecureTls
      )
      val role = localgitmirror.idea.deps.RoleDetector.detect(s)
      indicator.checkCanceled()
      val responses = if (role == localgitmirror.idea.deps.MachineRole.HOME)
        MirrorApi.depsResponses(
          baseUrl = s.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repo,
          insecureTls = s.mirrorInsecureTls
        )
      else null
      UIUtil.invokeLaterIfNeeded {
        if (project.isDisposed) return@invokeLaterIfNeeded
        if (pending.code in 200..299) {
          localgitmirror.idea.deps.RespondDepsAction.lastKnownPendingCount.set(pending.items.size)
          depsListModel.clear()
          pending.items.forEachIndexed { idx, item ->
            depsListModel.addElement(
              LocalGitMirrorBundle.message(
                "panel.deps.row",
                idx + 1,
                formatSize(item.size),
                formatBufferTs(item.mtime.toDouble())
              )
            )
          }
        }
        if (responses != null && responses.code in 200..299) {
          localgitmirror.idea.deps.ApplyDepsAction.lastKnownResponseCount.set(responses.items.size)
        }
        refreshStatus()
      }
    }
  })
}

internal class IconTextCellRenderer(private val rowIcon: Icon) :
  ColoredListCellRenderer<String>() {
  override fun customizeCellRenderer(
    list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean
  ) {
    icon = rowIcon
    iconTextGap = JBUI.scale(6)
    border = JBUI.Borders.empty(1, 6)
    append(value ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
  }
}
