package localgitmirror.idea.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import localgitmirror.idea.actions.PullFromMirrorAction
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorSyncApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import java.io.File
import javax.swing.JFileChooser

/** Pull selected branches (multi-select support). Falls back to single-branch picker if nothing selected. */
internal fun LocalGitMirrorPanel.pullSelectedBranches() {
  val selected = branchList.selectedValuesList
  if (selected.isEmpty()) {
    // No selection — use existing single-branch dialog
    pullFromMirror()
    return
  }
  if (selected.size == 1) {
    pullFromMirror(selected.first().name)
    return
  }
  // Multiple branches — pull each in sequence
  pullMultipleBranches(selected.map { it.name })
}

/** Send selected branches (multi-select support). Falls back to current branch if nothing selected. */
internal fun LocalGitMirrorPanel.sendSelectedBranches() {
  val selected = branchList.selectedValuesList
  if (selected.isEmpty()) {
    syncCurrentBranch()
    return
  }
  if (selected.size == 1) {
    syncBranch(selected.first().name)
    return
  }
  syncMultipleBranches(selected.map { it.name })
}

/** Pull multiple branches in sequence. */
internal fun LocalGitMirrorPanel.pullMultipleBranches(branches: List<String>) {
  if (isSyncing) {
    notify("Операция уже выполняется", NotificationType.WARNING)
    return
  }

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Стягивание ${branches.size} веток", true) {
    override fun run(indicator: ProgressIndicator) {
      for ((index, branch) in branches.withIndex()) {
        indicator.checkCanceled()
        indicator.fraction = index.toDouble() / branches.size
        indicator.text = "Стягивание $branch (${index + 1}/${branches.size})"

        try {
          PullFromMirrorAction(preselectedBranch = branch).actionPerformed(
            com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
              "MultiPull", null,
              com.intellij.openapi.actionSystem.DataContext { dataId ->
                if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(dataId)) project else null
              }
            )
          )
        } catch (e: Exception) {
          notify("Ошибка стягивания $branch: ${e.message}", NotificationType.ERROR)
        }
      }

      notify("Стянуто ${branches.size} веток: ${branches.joinToString(", ")}", NotificationType.INFORMATION)
    }

    override fun onSuccess() {
      isSyncing = false
      refreshBranchCombo(withMirror = true)
    }

    override fun onThrowable(error: Throwable) {
      isSyncing = false
      notify("Ошибка: ${error.message}", NotificationType.ERROR)
    }
  })
}

/** Sync a specific branch to Mirror. */
internal fun LocalGitMirrorPanel.syncBranch(branchName: String) {
  if (isSyncing) {
    notify("Операция уже выполняется", NotificationType.WARNING)
    return
  }

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Отправка $branchName", true) {
    override fun run(indicator: ProgressIndicator) {
      val dir = baseDir() ?: run {
        notify("Проект не найден", NotificationType.ERROR)
        return
      }
      val settings = service<MirrorSettingsService>().state

      indicator.text = "Отправка $branchName"
      try {
        val result = syncFacade.runFullSync(dir, settings, additionalBranches = listOf(branchName))
        if (!result.step.ok) {
          notify("Ошибка отправки $branchName: ${result.step.message}", NotificationType.ERROR)
        } else {
          notify("Ветка $branchName отправлена", NotificationType.INFORMATION)
        }
      } catch (e: Exception) {
        notify("Ошибка отправки $branchName: ${e.message}", NotificationType.ERROR)
      }
    }

    override fun onSuccess() {
      isSyncing = false
      refreshBranchCombo(withMirror = true)
    }

    override fun onThrowable(error: Throwable) {
      isSyncing = false
      notify("Ошибка: ${error.message}", NotificationType.ERROR)
    }
  })
}

/** Send all selected branches (multi-select support). */
internal fun LocalGitMirrorPanel.syncSelectedBranches() {
  val selected = branchList.selectedValuesList
  if (selected.isEmpty()) {
    notify("Выберите ветки для отправки", NotificationType.WARNING)
    return
  }
  val branchNames = selected.map { it.name }
  if (branchNames.size == 1) {
    syncCurrentBranch()
  } else {
    // Send multiple branches
    syncMultipleBranches(branchNames)
  }
}

/** Send multiple branches as one sync: single bundle, single upload. */
internal fun LocalGitMirrorPanel.syncMultipleBranches(branches: List<String>) {
  if (isSyncing) {
    notify("Операция уже выполняется", NotificationType.WARNING)
    return
  }

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Отправка ${branches.size} веток", true) {
    override fun run(indicator: ProgressIndicator) {
      val dir = baseDir() ?: run {
        notify("Проект не найден", NotificationType.ERROR)
        return
      }
      val settings = service<MirrorSettingsService>().state

      indicator.text = "Отправка ${branches.size} веток: ${branches.joinToString(", ")}"
      try {
        val result = syncFacade.runFullSync(dir, settings, additionalBranches = branches)
        if (!result.step.ok) {
          notify("Ошибка отправки ${branches.size} веток: ${result.step.message}", NotificationType.ERROR)
        } else {
          notify("Отправлено ${branches.size} веток: ${branches.joinToString(", ")}", NotificationType.INFORMATION)
        }
      } catch (e: Exception) {
        notify("Ошибка отправки ${branches.size} веток: ${e.message}", NotificationType.ERROR)
      }
    }

    override fun onSuccess() {
      isSyncing = false
      refreshBranchCombo(withMirror = true)
    }

    override fun onThrowable(error: Throwable) {
      isSyncing = false
      notify("Ошибка: ${error.message}", NotificationType.ERROR)
    }
  })
}

/** Export bundle for offline transfer. */
internal fun LocalGitMirrorPanel.exportBundle() {
  val dir = baseDir() ?: run {
    notify("Проект не найден", NotificationType.ERROR)
    return
  }

  val selected = branchList.selectedValuesList
  if (selected.isEmpty()) {
    notify("Выберите ветки для экспорта", NotificationType.WARNING)
    return
  }

  val branchNames = selected.map { it.name }

  // Ask user where to save
  val fileChooser = JFileChooser().apply {
    dialogTitle = "Сохранить bundle файл"
    selectedFile = File("${project.name}-${branchNames.joinToString("-")}.bundle")
    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Git Bundle", "bundle")
  }

  if (fileChooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
    return
  }

  val targetFile = fileChooser.selectedFile

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Экспорт bundle", true) {
    override fun run(indicator: ProgressIndicator) {
      indicator.text = "Создание bundle для ${branchNames.joinToString(", ")}"

      try {
        // Use git bundle create
        val refs = branchNames.map { "refs/heads/$it" }
        val cmd = mutableListOf("git", "bundle", "create", targetFile.absolutePath) + refs

        val proc = ProcessBuilder(cmd)
          .directory(dir)
          .redirectErrorStream(false)
          .start()

        val exitCode = proc.waitFor()
        val stderr = proc.errorStream.bufferedReader().readText()

        if (exitCode != 0) {
          notify("Ошибка экспорта: $stderr", NotificationType.ERROR)
          historyService.add(LocalGitMirrorBundle.message("history.op.exportBundle"), false,
            "branches=${branchNames.joinToString(",")} err=${stderr.take(300)}")
        } else {
          notify("Bundle сохранён: ${targetFile.absolutePath}\nРазмер: ${targetFile.length() / 1024} KB", NotificationType.INFORMATION)
          historyService.add(LocalGitMirrorBundle.message("history.op.exportBundle"), true,
            "branches=${branchNames.joinToString(",")} size=${targetFile.length() / 1024}KB -> ${targetFile.absolutePath}")
        }
      } catch (e: Exception) {
        notify("Ошибка экспорта: ${e.message}", NotificationType.ERROR)
        historyService.add(LocalGitMirrorBundle.message("history.op.exportBundle"), false,
          "branches=${branchNames.joinToString(",")} err=${e.message?.take(300)}")
      }
    }

    override fun onSuccess() {
      isSyncing = false
    }

    override fun onThrowable(error: Throwable) {
      isSyncing = false
      notify("Ошибка: ${error.message}", NotificationType.ERROR)
    }
  })
}

/** Import bundle file. */
internal fun LocalGitMirrorPanel.importBundle() {
  val dir = baseDir() ?: run {
    notify("Проект не найден", NotificationType.ERROR)
    return
  }

  // Ask user to select bundle file
  val fileChooser = JFileChooser().apply {
    dialogTitle = "Выберите bundle файл"
    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Git Bundle", "bundle")
  }

  if (fileChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
    return
  }

  val bundleFile = fileChooser.selectedFile

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Импорт bundle", true) {
    override fun run(indicator: ProgressIndicator) {
      indicator.text = "Импорт ${bundleFile.name}"

      try {
        // Use git bundle verify first
        val verifyProc = ProcessBuilder("git", "bundle", "verify", bundleFile.absolutePath)
          .directory(dir)
          .redirectErrorStream(false)
          .start()

        val verifyExit = verifyProc.waitFor()
        val verifyOutput = verifyProc.inputStream.bufferedReader().readText()

        if (verifyExit != 0) {
          notify("Bundle невалиден: $verifyOutput", NotificationType.ERROR)
          historyService.add(LocalGitMirrorBundle.message("history.op.importBundle"), false,
            "file=${bundleFile.name} verify failed: ${verifyOutput.take(300)}")
          return
        }

        // Extract branch names from verify output
        val branches = verifyOutput.lines()
          .filter { it.contains("refs/heads/") }
          .map { it.substringAfter("refs/heads/").trim() }

        // Use git fetch to import
        val fetchProc = ProcessBuilder("git", "fetch", bundleFile.absolutePath)
          .directory(dir)
          .redirectErrorStream(false)
          .start()

        val fetchExit = fetchProc.waitFor()
        val fetchStderr = fetchProc.errorStream.bufferedReader().readText()

        if (fetchExit != 0) {
          notify("Ошибка импорта: $fetchStderr", NotificationType.ERROR)
          historyService.add(LocalGitMirrorBundle.message("history.op.importBundle"), false,
            "file=${bundleFile.name} fetch err=${fetchStderr.take(300)}")
        } else {
          notify("Импортировано ${branches.size} веток: ${branches.joinToString(", ")}", NotificationType.INFORMATION)
          historyService.add(LocalGitMirrorBundle.message("history.op.importBundle"), true,
            "file=${bundleFile.name} branches=${branches.joinToString(",")}")
        }
      } catch (e: Exception) {
        notify("Ошибка импорта: ${e.message}", NotificationType.ERROR)
        historyService.add(LocalGitMirrorBundle.message("history.op.importBundle"), false,
          "file=${bundleFile.name} err=${e.message?.take(300)}")
      }
    }

    override fun onSuccess() {
      isSyncing = false
      refreshBranchCombo(withMirror = true)
    }

    override fun onThrowable(error: Throwable) {
      isSyncing = false
      notify("Ошибка: ${error.message}", NotificationType.ERROR)
    }
  })
}

/** Delete selected branches (locally and on Mirror). */
internal fun LocalGitMirrorPanel.deleteSelectedBranches() {
  val selected = branchList.selectedValuesList
  if (selected.isEmpty()) {
    notify("Выберите ветки для удаления", NotificationType.WARNING)
    return
  }

  val branchNames = selected.map { it.name }
  val currentBranch = GitLocal.currentBranch(project, baseDir() ?: return)

  // Don't allow deleting current branch
  if (branchNames.contains(currentBranch)) {
    notify("Нельзя удалить текущую ветку '$currentBranch'", NotificationType.WARNING)
    return
  }

  val confirm = Messages.showYesNoDialog(
    project,
    "Удалить ${branchNames.size} веток локально и на Cache?\n\n${branchNames.joinToString("\n")}",
    "Подтверждение удаления",
    "Удалить",
    "Отмена",
    Messages.getWarningIcon()
  )

  if (confirm != Messages.YES) return

  val dir = baseDir() ?: return
  val settings = service<MirrorSettingsService>().state
  val repo = syncFacade.resolveRepo(dir, settings).sanitized

  isSyncing = true
  ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Удаление веток", true) {
    override fun run(indicator: ProgressIndicator) {
      val deleted = mutableListOf<String>()
      val errors = mutableListOf<String>()

      for (branch in branchNames) {
        indicator.checkCanceled()
        indicator.text = "Удаление $branch"

        // Delete locally
        val localResult = GitLocal.deleteLocalBranch(project, dir, branch, force = true)
        if (!localResult.ok()) {
          errors.add("$branch (локально): ${localResult.stderr}")
        }

        // Delete on Mirror
        val mirrorResult = MirrorSyncApi.deleteRef(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          repo = repo,
          branch = branch,
          syncPassword = SecretsStore.syncPassword,
          insecureTls = settings.mirrorInsecureTls
        )
        if (mirrorResult.code !in 200..299) {
          errors.add("$branch (Cache): ${mirrorResult.body.take(100)}")
        }

        if (localResult.ok() || mirrorResult.code in 200..299) {
          deleted.add(branch)
        }
      }

      if (deleted.isNotEmpty()) {
        notify("Удалено ${deleted.size} веток: ${deleted.joinToString(", ")}", NotificationType.INFORMATION)
      }
      if (errors.isNotEmpty()) {
        notify("Ошибки: ${errors.joinToString("; ")}", NotificationType.WARNING)
      }
    }

    override fun onSuccess() {
      isSyncing = false
      refreshBranchCombo(withMirror = true)
    }

    override fun onThrowable(error: Throwable) {
      isSyncing = false
      notify("Ошибка: ${error.message}", NotificationType.ERROR)
    }
  })
}
