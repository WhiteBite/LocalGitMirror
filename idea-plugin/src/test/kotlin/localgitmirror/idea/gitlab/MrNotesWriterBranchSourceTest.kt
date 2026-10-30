package localgitmirror.idea.gitlab

import com.intellij.openapi.project.Project
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class MrNotesWriterBranchSourceTest {

  @TempDir
  lateinit var root: File

  private fun git(cwd: File, vararg args: String): String {
    val p = ProcessBuilder(listOf("git", *args))
      .directory(cwd).redirectErrorStream(false).start()
    val out = p.inputStream.bufferedReader().readText()
    val err = p.errorStream.bufferedReader().readText()
    val code = p.waitFor()
    if (code != 0) throw IllegalStateException("git ${args.joinToString(" ")} failed: $out $err")
    return out.trim()
  }

  private fun repoWithBranchDivergence(): File {
    val repo = File(root, "repo").also { it.mkdirs() }
    git(repo, "init", "-b", "master")
    git(repo, "config", "user.email", "test@example.com")
    git(repo, "config", "user.name", "Tester")
    val src = File(repo, "src").also { it.mkdirs() }
    File(src, "Foo.java").writeText("l1\nl2\nWORKTREE_ONLY\nl4\nl5\n")
    git(repo, "add", ".")
    git(repo, "commit", "-m", "worktree version")
    git(repo, "checkout", "-b", "feature")
    File(src, "Foo.java").writeText("l1\nl2\nBRANCH_ONLY\nl4\nl5\n")
    git(repo, "add", ".")
    git(repo, "commit", "-m", "branch version")
    git(repo, "checkout", "master")
    return repo
  }

  @Test
  fun `branchFileLines returns branch content not worktree content`() {
    val repo = repoWithBranchDivergence()
    val worktree = File(repo, "src/Foo.java").readText()
    assertTrue(worktree.contains("WORKTREE_ONLY"), "precondition: worktree back on master")
    val lines = MrNotesWriter.branchFileLines(repo, "feature", "src/Foo.java")
    assertEquals(listOf("l1", "l2", "BRANCH_ONLY", "l4", "l5"), lines)
  }

  @Test
  fun `branchFileLines returns null for unknown branch`() {
    val repo = repoWithBranchDivergence()
    assertNull(MrNotesWriter.branchFileLines(repo, "no-such-branch", "src/Foo.java"))
  }

  @Test
  fun `branchFileLines returns null for unknown file on existing branch`() {
    val repo = repoWithBranchDivergence()
    assertNull(MrNotesWriter.branchFileLines(repo, "feature", "src/Bar.java"))
  }

  @Test
  fun `branchFileLines returns null for blank branch`() {
    val repo = repoWithBranchDivergence()
    assertNull(MrNotesWriter.branchFileLines(repo, "", "src/Foo.java"))
  }

  @Test
  fun `renderCodeBlockLines marks anchor line with arrow inside +-2 window`() {
    val repo = repoWithBranchDivergence()
    val lines = MrNotesWriter.branchFileLines(repo, "feature", "src/Foo.java")!!
    val block = MrNotesWriter.renderCodeBlockLines(lines, anchorLine = 3)
    assertTrue(block.contains(">3: BRANCH_ONLY"), block)
    assertTrue(block.contains(" 1: l1"), block)
    assertTrue(block.contains(" 5: l5"), block)
    assertTrue(!block.contains("WORKTREE_ONLY"), block)
  }

  private fun projectWithBase(dir: File): Project =
    java.lang.reflect.Proxy.newProxyInstance(
      Project::class.java.classLoader,
      arrayOf(Project::class.java),
    ) { _, method, _ -> if (method.name == "getBasePath") dir.absolutePath else null } as Project

  private fun discussionAt(file: String, line: Int) = GitLabApi.MrDiscussion(
    id = "t1", resolved = false, notes = listOf(
      GitLabApi.MrNote(
        author = "Ivan", createdAt = "t", system = false,
        body = "fix this", resolved = false, filePath = file, line = line,
      ),
    )
  )

  @Test
  fun `renderMarkdown shows unavailable marker instead of worktree when source branch missing`() {
    val repo = repoWithBranchDivergence()
    File(repo, "src/OnlyLocal.java").writeText("LOCAL_DIRTY_TEXT")
    val md = MrNotesWriter.renderMarkdown(
      project = projectWithBase(repo), iid = 9, title = "T", sourceBranch = "no-such-branch",
      updatedAt = "u", unresolved = 2, totalThreads = 2,
      discussions = listOf(discussionAt("src/Foo.java", 3), discussionAt("src/OnlyLocal.java", 1)),
    )
    assertTrue(md.contains(
      "**Контекст:** недоступен — в локальном репозитории нет " +
        "`no-such-branch:src/Foo.java`, фрагмент кода не показан."), md)
    assertTrue(md.contains("`no-such-branch:src/OnlyLocal.java`"), md)
    assertTrue(!md.contains("WORKTREE_ONLY"), md)
    assertTrue(!md.contains("LOCAL_DIRTY_TEXT"), md)
    assertTrue(!md.contains("```"), md)
  }

  @Test
  fun `renderMarkdown shows unavailable marker when file absent on the source branch`() {
    val repo = repoWithBranchDivergence()
    File(repo, "src/Untracked.java").writeText("UNTRACKED_LOCAL_TEXT")
    val md = MrNotesWriter.renderMarkdown(
      project = projectWithBase(repo), iid = 9, title = "T", sourceBranch = "feature",
      updatedAt = "u", unresolved = 1, totalThreads = 1,
      discussions = listOf(discussionAt("src/Untracked.java", 1)),
    )
    assertTrue(md.contains(
      "**Контекст:** недоступен — в локальном репозитории нет " +
        "`feature:src/Untracked.java`, фрагмент кода не показан."), md)
    assertTrue(!md.contains("UNTRACKED_LOCAL_TEXT"), md)
  }

  @Test
  fun `renderMarkdown renders branch content when the revision is available`() {
    val repo = repoWithBranchDivergence()
    val md = MrNotesWriter.renderMarkdown(
      project = projectWithBase(repo), iid = 9, title = "T", sourceBranch = "feature",
      updatedAt = "u", unresolved = 1, totalThreads = 1,
      discussions = listOf(discussionAt("src/Foo.java", 3)),
    )
    assertTrue(md.contains(">3: BRANCH_ONLY"), md)
    assertTrue(!md.contains("WORKTREE_ONLY"), md)
    assertTrue(!md.contains("**Контекст:** недоступен"), md)
  }

  @Test
  fun `contextUnavailableLine matches the python marker and hides blank branch`() {
    assertEquals(
      "**Контекст:** недоступен — в локальном репозитории нет " +
        "`feature:src/Foo.java`, фрагмент кода не показан.",
      MrNotesWriter.contextUnavailableLine("feature", "src/Foo.java"),
    )
    assertTrue(MrNotesWriter.contextUnavailableLine("", "F.txt").contains("`?:F.txt`"))
  }
}
