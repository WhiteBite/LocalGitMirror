package localgitmirror.idea.gitlab

import com.intellij.openapi.project.Project
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class MrNotesWriterExcludeTest {

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

  private fun projectWithBase(dir: File): Project =
    java.lang.reflect.Proxy.newProxyInstance(
      Project::class.java.classLoader,
      arrayOf(Project::class.java),
    ) { _, method, _ -> if (method.name == "getBasePath") dir.absolutePath else null } as Project

  private fun initRepo(dir: File) {
    dir.mkdirs()
    git(dir, "init", "-b", "master")
    git(dir, "config", "user.email", "test@example.com")
    git(dir, "config", "user.name", "Tester")
  }

  private fun commitGitignore(dir: File, content: String) {
    File(dir, ".gitignore").writeText(content)
    git(dir, "add", ".gitignore")
    git(dir, "commit", "-m", "gitignore")
  }

  private fun createIndex(dir: File) {
    File(dir, ".mr-notes").mkdirs()
    File(dir, ".mr-notes/INDEX.md").writeText("# index\n")
  }

  private fun statusPorcelain(dir: File): String = git(dir, "status", "--porcelain")

  private fun checkIgnore(dir: File, path: String): String = git(dir, "check-ignore", "-v", path)

  @Test
  fun `ensureGitignore preserves committed gitignore bytes and keeps status clean`() {
    val repo = File(root, "repo")
    initRepo(repo)
    commitGitignore(repo, "build/\n.idea/\n")
    val before = File(repo, ".gitignore").readText()

    MrNotesWriter.ensureGitignore(projectWithBase(repo))
    createIndex(repo)

    assertEquals(before, File(repo, ".gitignore").readText(), "tracked .gitignore must not be mutated")
    assertEquals("", statusPorcelain(repo), "working tree must stay clean")
  }

  @Test
  fun `ensureGitignore excludes mr-notes via info-exclude not tracked gitignore`() {
    val repo = File(root, "repo")
    initRepo(repo)
    commitGitignore(repo, "build/\n")

    MrNotesWriter.ensureGitignore(projectWithBase(repo))
    createIndex(repo)

    val out = checkIgnore(repo, ".mr-notes/INDEX.md")
    assertTrue(out.contains(".git/info/exclude"), "expected info/exclude source, got: $out")
  }

  @Test
  fun `ensureGitignore is idempotent`() {
    val repo = File(root, "repo")
    initRepo(repo)
    commitGitignore(repo, "build/\n")

    MrNotesWriter.ensureGitignore(projectWithBase(repo))
    MrNotesWriter.ensureGitignore(projectWithBase(repo))
    createIndex(repo)

    assertEquals("", statusPorcelain(repo))
    val exclude = File(repo, ".git/info/exclude").readText()
    assertEquals(1, exclude.lines().count { it.trim() == ".mr-notes/" }, "entry must appear exactly once")
  }

  @Test
  fun `ensureGitignore handles preexisting exclude entry without trailing newline`() {
    val repo = File(root, "repo")
    initRepo(repo)
    commitGitignore(repo, "build/\n")
    File(repo, ".git/info/exclude").writeText(".mr-notes/")

    MrNotesWriter.ensureGitignore(projectWithBase(repo))
    createIndex(repo)

    assertEquals("", statusPorcelain(repo))
    val exclude = File(repo, ".git/info/exclude").readText()
    assertEquals(1, exclude.lines().count { it.trim() == ".mr-notes/" }, "entry must appear exactly once")
  }

  @Test
  fun `ensureGitignore works in linked worktree without dirtying it`() {
    val main = File(root, "main")
    initRepo(main)
    commitGitignore(main, "build/\n")
    val wt = File(root, "wt")
    git(main, "worktree", "add", wt.absolutePath)

    MrNotesWriter.ensureGitignore(projectWithBase(wt))
    createIndex(wt)

    assertEquals("", statusPorcelain(wt), "linked worktree must stay clean")
    val out = checkIgnore(wt, ".mr-notes/INDEX.md")
    assertTrue(out.contains("info/exclude"), "expected info/exclude source, got: $out")
  }

  @Test
  fun `writeIndex preserves gitignore and separates an unterminated exclude entry`() {
    val repo = File(root, "repo")
    initRepo(repo)
    commitGitignore(repo, "build/")
    val exclude = File(repo, ".git/info/exclude")
    exclude.writeText("local-cache/")

    val index = MrNotesWriter.writeIndex(projectWithBase(repo), emptyList())

    assertTrue(index.toFile().isFile)
    assertEquals("build/", File(repo, ".gitignore").readText())
    assertEquals("local-cache/\n.mr-notes/\n", exclude.readText())
    assertEquals("", statusPorcelain(repo))
    assertTrue(checkIgnore(repo, ".mr-notes/INDEX.md").contains(".git/info/exclude"))
  }

  @Test
  fun `ensureGitignore preserves an existing anchored exclude entry`() {
    val repo = File(root, "repo")
    initRepo(repo)
    commitGitignore(repo, "build/\n")
    val exclude = File(repo, ".git/info/exclude")
    exclude.writeText("/.mr-notes/")

    MrNotesWriter.ensureGitignore(projectWithBase(repo))

    assertEquals("/.mr-notes/", exclude.readText())
    assertEquals("", statusPorcelain(repo))
  }

  @Test
  fun `writeIndex creates a missing local exclude file`() {
    val repo = File(root, "repo")
    initRepo(repo)
    commitGitignore(repo, "build/\n")
    val exclude = File(repo, ".git/info/exclude")
    assertTrue(exclude.delete())

    MrNotesWriter.writeIndex(projectWithBase(repo), emptyList())

    assertEquals(".mr-notes/\n", exclude.readText())
    assertEquals("", statusPorcelain(repo))
  }

  @Test
  fun `ensureGitignore works in repo without tracked gitignore`() {
    val repo = File(root, "repo")
    initRepo(repo)
    File(repo, "src").mkdirs()
    File(repo, "src/Foo.java").writeText("x\n")
    git(repo, "add", ".")
    git(repo, "commit", "-m", "initial")

    MrNotesWriter.ensureGitignore(projectWithBase(repo))
    createIndex(repo)

    assertEquals("", statusPorcelain(repo), "no tracked .gitignore should be created")
    assertTrue(!File(repo, ".gitignore").exists(), "must not create a tracked .gitignore")
  }
}
