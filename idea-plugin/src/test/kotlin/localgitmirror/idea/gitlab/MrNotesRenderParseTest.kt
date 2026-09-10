package localgitmirror.idea.gitlab

import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MrNotesRenderParseTest {

  private val project: Project = java.lang.reflect.Proxy.newProxyInstance(
    Project::class.java.classLoader,
    arrayOf(Project::class.java)
  ) { _, _, _ -> null } as Project

  private fun note(author: String, body: String, system: Boolean = false, file: String? = null, line: Int? = null) =
    GitLabApi.MrNote(
      author = author,
      createdAt = "2026-09-09 18:22",
      system = system,
      body = body,
      resolved = false,
      filePath = file,
      line = line,
    )

  private fun discussions() = listOf(
    GitLabApi.MrDiscussion(resolved = false, notes = listOf(
      note("Ivan", "EDT call", file = "ArchTarget.kt", line = 88),
      note("Me", "ok fixing"),
    )),
    GitLabApi.MrDiscussion(resolved = true, notes = listOf(
      note("Ivan", "rename getRefs"),
    )),
    GitLabApi.MrDiscussion(resolved = true, notes = listOf(
      note("system", "assigned reviewer", system = true),
    )),
  )

  @Test
  fun `render puts unresolved first then resolved then system section`() {
    val md = MrNotesWriter.renderMarkdown(
      project = null, iid = 412, title = "Refactor", sourceBranch = "refactor/x",
      updatedAt = "2026-09-10 00:40", unresolved = 1, totalThreads = 2,
      discussions = discussions(),
    )
    val unres = md.indexOf("## ⚠ НЕ РЕШЕНО")
    val res = md.indexOf("## ✓ решено")
    val sys = md.indexOf("## ✓ системные события")
    assertTrue(unres in 0 until res, "unresolved must precede resolved")
    assertTrue(res in 0 until sys, "resolved must precede system section")
    assertTrue(md.contains("**Место:** `ArchTarget.kt:88`"))
    assertTrue(md.contains("&nbsp;&nbsp;↳ **Me**"), "replies must be indented with arrow")
    assertTrue(!md.contains("```kt"), "no code block without project")
    assertTrue(md.startsWith("<!-- Сгенерировано плагином DocCache"))
  }

  @Test
  fun `parse round-trips rendered markdown without counting system section as thread`() {
    val md = MrNotesWriter.renderMarkdown(
      project = null, iid = 412, title = "Refactor", sourceBranch = "refactor/x",
      updatedAt = "2026-09-10 00:40", unresolved = 1, totalThreads = 2,
      discussions = discussions(),
    )
    val row = MrReviewService(project).parseMrMarkdown(412, md)
    assertEquals("Refactor", row.title)
    assertEquals("refactor/x", row.sourceBranch)
    assertEquals(1, row.unresolved)
    assertEquals(2, row.totalThreads)
    assertEquals(MrReviewService.Source.CACHE, row.source)
  }

  @Test
  fun `parse of system-only markdown yields zero threads`() {
    val md = MrNotesWriter.renderMarkdown(
      project = null, iid = 7, title = "T", sourceBranch = "b",
      updatedAt = "", unresolved = 0, totalThreads = 0,
      discussions = listOf(GitLabApi.MrDiscussion(resolved = true, notes = listOf(note("s", "ev", system = true)))),
    )
    val row = MrReviewService(project).parseMrMarkdown(7, md)
    assertEquals(0, row.unresolved)
    assertEquals(0, row.totalThreads)
  }
}
