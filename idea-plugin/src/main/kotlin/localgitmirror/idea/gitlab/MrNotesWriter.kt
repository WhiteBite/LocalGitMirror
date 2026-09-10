package localgitmirror.idea.gitlab

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.settings.OperationsHistoryService
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object MrNotesWriter {

  fun renderMarkdown(
    project: Project? = null,
    iid: Int,
    title: String,
    sourceBranch: String,
    updatedAt: String,
    unresolved: Int,
    totalThreads: Int,
    discussions: List<GitLabApi.MrDiscussion>,
  ): String {
    val sb = StringBuilder()
    sb.appendLine("<!-- Сгенерировано плагином DocCache из обсуждений GitLab MR !$iid. Не редактируйте вручную. -->")
    sb.appendLine("# MR !$iid — $title")
    sb.appendLine()
    sb.appendLine("> Инструкция агенту: прочитай нерешённые треды ниже и внеси правки.")
    sb.appendLine("> Строка «Место: файл:строка» показывает, где смотреть. Решённые треды не трогай.")
    sb.appendLine()
    sb.appendLine("- **Ветка:** `$sourceBranch`")
    sb.appendLine("- **Статус:** открыт · **$unresolved нерешённых** из $totalThreads тредов")
    sb.appendLine("- **Обновлено:** $updatedAt · **Источник:** GitLab MR !$iid")
    sb.appendLine()
    sb.appendLine("---")
    sb.appendLine()

    val unresolvedThreads = discussions.filter { !it.resolved && it.notes.any { n -> !n.system } }
    val resolvedThreads = discussions.filter { it.resolved && it.notes.any { n -> !n.system } }
    val systemNotes = discussions.flatMap { it.notes.filter { n -> n.system } }

    for ((idx, d) in unresolvedThreads.withIndex()) {
      renderThread(sb, project, idx + 1, d, unresolved = true)
    }
    for ((idx, d) in resolvedThreads.withIndex()) {
      renderThread(sb, project, idx + 1, d, unresolved = false)
    }

    if (systemNotes.isNotEmpty()) {
      sb.appendLine("## ✓ системные события (свёрнуто · ${systemNotes.size})")
      for (n in systemNotes) {
        sb.appendLine("- ${n.body} · ${n.createdAt}")
      }
    }

    return sb.toString()
  }

  private fun renderThread(
    sb: StringBuilder,
    project: Project?,
    n: Int,
    d: GitLabApi.MrDiscussion,
    unresolved: Boolean,
  ) {
    val notes = d.notes.filter { !it.system }
    if (notes.isEmpty()) return

    val first = notes.first()
    val replies = notes.drop(1)

    if (unresolved) {
      sb.appendLine("## ⚠ НЕ РЕШЕНО · тред $n")
    } else {
      val closedBy = notes.lastOrNull()?.author
      val suffix = if (closedBy != null) " · закрыл: $closedBy" else ""
      sb.appendLine("## ✓ решено · тред $n$suffix")
    }
    sb.appendLine()

    val anchorFile = d.anchorFile
    val anchorLine = d.anchorLine
    if (anchorFile == null) {
      sb.appendLine("**Место:** общий комментарий (не привязан к коду)")
    } else {
      sb.appendLine("**Место:** `$anchorFile:${anchorLine ?: "?"}`")
    }
    sb.appendLine()

    if (anchorFile != null && project != null && anchorLine != null) {
      renderCodeBlock(sb, project, anchorFile, anchorLine)
      sb.appendLine()
    }

    sb.appendLine("**${first.author}** _(reviewer · ${first.createdAt})_:")
    sb.appendLine(first.body)
    sb.appendLine()

    for (reply in replies) {
      sb.appendLine("&nbsp;&nbsp;↳ **${reply.author}** _(${reply.createdAt})_:")
      sb.appendLine(reply.body)
      sb.appendLine()
    }

    sb.appendLine("---")
    sb.appendLine()
  }

  private fun renderCodeBlock(
    sb: StringBuilder,
    project: Project,
    anchorFile: String,
    anchorLine: Int,
  ) {
    val base = project.basePath ?: return
    val file = File(base, anchorFile)
    if (!file.isFile) return

    val ext = anchorFile.substringAfterLast('.', "")
    val lines = try {
      file.readLines(Charsets.UTF_8)
    } catch (_: Throwable) {
      return
    }
    if (lines.isEmpty()) return

    val start = (anchorLine - 2).coerceAtLeast(1)
    val end = (anchorLine + 2).coerceAtMost(lines.size)

    sb.appendLine("```$ext")
    for (i in start..end) {
      val text = lines.getOrNull(i - 1) ?: ""
      val prefix = if (i == anchorLine) ">" else " "
      sb.appendLine("$prefix$i: $text")
    }
    sb.appendLine("```")
  }

  fun writeForAgent(project: Project, row: MrReviewService.MrRowItem): Path {
    ensureGitignore(project)
    val base = project.basePath ?: error("project.basePath is null")
    val dir = File(base, ".mr-notes").apply { mkdirs() }
    val file = File(dir, "mr-!${row.iid}.md")

    val markdown = if (row.source == MrReviewService.Source.CACHE && row.receivedMarkdown != null) {
      row.receivedMarkdown!!
    } else {
      renderMarkdown(
        project, row.iid, row.title, row.sourceBranch, row.updatedAt,
        row.unresolved, row.totalThreads, row.discussions,
      )
    }

    file.writeText(markdown, Charsets.UTF_8)

    val rel = ".mr-notes/mr-!${row.iid}.md"
    service<OperationsHistoryService>().add(
      LocalGitMirrorBundle.message("mrnotes.history.save"), true, "mr=!${row.iid} path=$rel"
    )

    return file.toPath()
  }

  fun writeIndex(project: Project, rows: List<MrReviewService.MrRowItem>): Path {
    ensureGitignore(project)
    val base = project.basePath ?: error("project.basePath is null")
    val dir = File(base, ".mr-notes").apply { mkdirs() }
    val file = File(dir, "INDEX.md")

    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    val baseDirName = File(base).name
    val total = rows.size
    val needAttention = rows.count { it.unresolved > 0 }

    val sorted = rows.sortedWith(
      compareByDescending<MrReviewService.MrRowItem> { it.unresolved }.thenBy { it.iid }
    )

    val sb = StringBuilder()
    sb.appendLine("<!-- Сгенерировано плагином DocCache. Не редактируйте вручную — файл перезаписывается. -->")
    sb.appendLine("# Ревью MR — сводка для агента")
    sb.appendLine()
    sb.appendLine("> Это рабочие заметки код-ревью, перенесённые с GitLab (с рабочего ПК).")
    sb.appendLine("> Для каждого MR есть отдельный файл с тредами, кодом и статусом.")
    sb.appendLine("> Приоритет — треды со статусом «НЕ РЕШЕНО»: их нужно починить.")
    sb.appendLine()
    sb.appendLine("Обновлено: $now · проект `$baseDirName` · всего MR: $total · требуют внимания: $needAttention")
    sb.appendLine()
    sb.appendLine("| MR | Заголовок | Ветка | Нерешённых | Файл |")
    sb.appendLine("|----|-----------|-------|:----------:|------|")
    for (row in sorted) {
      val link = "[!${row.iid}](./mr-!${row.iid}.md)"
      val unresolvedCell = if (row.unresolved > 0) "**${row.unresolved}** ⚠" else "0 ✓"
      sb.appendLine("| $link | ${row.title} | `${row.sourceBranch}` | $unresolvedCell | `mr-!${row.iid}.md` |")
    }
    sb.appendLine()
    sb.appendLine("## Как использовать (для агента)")
    sb.appendLine("1. Открой файлы MR с нерешёнными тредами (колонка «Нерешённых» > 0).")
    sb.appendLine("2. Каждый тред помечен `## ⚠ НЕ РЕШЕНО` и содержит `файл:строка` + фрагмент кода.")
    sb.appendLine("3. Внеси правки по замечаниям. Решённые треды (`## ✓ решено`) — уже закрыты, трогать не нужно.")

    file.writeText(sb.toString(), Charsets.UTF_8)
    return file.toPath()
  }

  fun ensureGitignore(project: Project) {
    val base = project.basePath ?: return
    val gitignore = File(base, ".gitignore")
    val entry = ".mr-notes/"
    if (!gitignore.isFile) {
      gitignore.writeText("$entry\n", Charsets.UTF_8)
      return
    }
    val content = gitignore.readText(Charsets.UTF_8)
    val hasLine = content.lines().any { it.trim() == entry }
    if (!hasLine) {
      val prefix = if (content.isNotEmpty() && !content.endsWith("\n")) "\n" else ""
      gitignore.writeText(content + prefix + "$entry\n", Charsets.UTF_8)
    }
  }
}
