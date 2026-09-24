package localgitmirror.idea.workkit

import com.intellij.openapi.project.Project
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import localgitmirror.idea.git.GitLocal

class NativeBundleBuilderJunkTest {

  private val project: Project = java.lang.reflect.Proxy.newProxyInstance(
    Project::class.java.classLoader,
    arrayOf(Project::class.java)
  ) { _, _, _ -> null } as Project

  private fun git(cwd: File, vararg args: String): String {
    val p = ProcessBuilder(listOf("git", *args))
      .directory(cwd).redirectErrorStream(false).start()
    val out = p.inputStream.bufferedReader().readText()
    val err = p.errorStream.bufferedReader().readText()
    val code = p.waitFor()
    if (code != 0) throw IllegalStateException("git ${args.joinToString(" ")} failed: $out $err")
    return out.trim()
  }

  private fun newRepo(root: File): File {
    val repo = File(root, "repo").also { it.mkdirs() }
    git(repo, "init")
    git(repo, "config", "user.email", "test@example.com")
    git(repo, "config", "user.name", "Tester")
    git(repo, "checkout", "-B", "feature")
    File(repo, "a.txt").writeText("A\n")
    git(repo, "add", "a.txt")
    git(repo, "commit", "-m", "A")
    return repo
  }

  @Test
  fun `bundle from junked repo still packs the current branch`() {
    val root = createTempDir(prefix = "tmp-junk-bundle-")
    try {
      val repo = newRepo(root)
      val tip = git(repo, "rev-parse", "HEAD")
      git(repo, "update-ref", "refs/heads/HEAD", tip)

      val res = NativeBundleBuilder.createBundle(repo)
      val bundle = File(root, "out.bundle")
      bundle.writeBytes(res.bundleBytes)
      val heads = git(repo, "bundle", "list-heads", bundle.absolutePath)
      assertTrue(heads.contains("refs/heads/feature"), "current branch missing from bundle: $heads")
      assertTrue(!heads.lines().any { it.endsWith(" refs/heads/HEAD") }, "junk ref packed: $heads")
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `self-heal removes local junk HEAD branch`() {
    val root = createTempDir(prefix = "tmp-junk-heal-")
    try {
      val repo = newRepo(root)
      val tip = git(repo, "rev-parse", "HEAD")
      git(repo, "update-ref", "refs/heads/HEAD", tip)

      val removed = GitLocal.removeJunkHeadBranches(project, repo)
      assertEquals(listOf("HEAD"), removed)
      val branches = git(repo, "for-each-ref", "--format=%(refname)", "refs/heads")
      assertTrue(!branches.lines().contains("refs/heads/HEAD"), branches)
      assertEquals("feature", GitLocal.currentBranch(project, repo))
    } finally {
      root.deleteRecursively()
    }
  }
}
