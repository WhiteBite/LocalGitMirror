package localgitmirror.idea.gitlab

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
}
