package localgitmirror.idea.workkit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeStealthApplyTest {

  @Test
  fun `apply dump ff-only advances head`() {
    val root = createTempDir(prefix = "lgm-native-apply-")
    try {
      val source = File(root, "source").also { it.mkdirs() }
      git(source, "init")
      git(source, "config", "user.email", "test@example.com")
      git(source, "config", "user.name", "Tester")

      val file = File(source, "a.txt")
      file.writeText("A\n")
      git(source, "add", "a.txt")
      git(source, "commit", "-m", "A")
      val a = gitOut(source, "rev-parse", "HEAD")

      file.appendText("B\n")
      git(source, "add", "a.txt")
      git(source, "commit", "-m", "B")
      val b = gitOut(source, "rev-parse", "HEAD")

      val target = File(root, "target")
      git(root, "clone", source.absolutePath, target.absolutePath)
      git(target, "reset", "--hard", a)

      val bundle = File(root, "x.bundle")
      git(source, "bundle", "create", bundle.absolutePath, "--all")
      val dump = File(root, "x.dmp")
      dump.writeBytes(NativeStealthDump.encryptBundleBytes(bundle.readBytes(), "dandan"))

      val res = NativeStealthApply.applyDump(
        workDir = target,
        password = "dandan",
        dumpFile = dump,
        mode = "ff-only"
      )
      assertTrue(res.ok, res.stderr)
      assertEquals(b, gitOut(target, "rev-parse", "HEAD"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `apply dump fails on wrong password`() {
    val root = createTempDir(prefix = "lgm-native-apply-pw-")
    try {
      val repo = File(root, "repo").also { it.mkdirs() }
      git(repo, "init")
      git(repo, "config", "user.email", "test@example.com")
      git(repo, "config", "user.name", "Tester")
      File(repo, "a.txt").writeText("A\n")
      git(repo, "add", "a.txt")
      git(repo, "commit", "-m", "A")

      val bundle = File(root, "x.bundle")
      git(repo, "bundle", "create", bundle.absolutePath, "--all")
      val dump = File(root, "x.dmp")
      dump.writeBytes(NativeStealthDump.encryptBundleBytes(bundle.readBytes(), "dandan"))

      val res = NativeStealthApply.applyDump(
        workDir = repo,
        password = "wrong",
        dumpFile = dump,
        mode = "ff-only"
      )
      assertFalse(res.ok)
      assertTrue(res.stderr.isNotBlank())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `apply dump new-branch creates branch`() {
    val root = createTempDir(prefix = "lgm-native-apply-nb-")
    try {
      val repo = File(root, "repo").also { it.mkdirs() }
      git(repo, "init")
      git(repo, "config", "user.email", "test@example.com")
      git(repo, "config", "user.name", "Tester")
      File(repo, "a.txt").writeText("A\n")
      git(repo, "add", "a.txt")
      git(repo, "commit", "-m", "A")

      val bundle = File(root, "x.bundle")
      git(repo, "bundle", "create", bundle.absolutePath, "--all")
      val dump = File(root, "x.dmp")
      dump.writeBytes(NativeStealthDump.encryptBundleBytes(bundle.readBytes(), "dandan"))

      val res = NativeStealthApply.applyDump(
        workDir = repo,
        password = "dandan",
        dumpFile = dump,
        mode = "new-branch",
        newBranchName = "test-pull-branch"
      )
      assertTrue(res.ok, res.stderr)
      assertTrue(res.stdout.contains("new-branch"), "Expected new-branch in output: ${res.stdout}")
      val currentBranch = gitOut(repo, "rev-parse", "--abbrev-ref", "HEAD")
      assertEquals("test-pull-branch", currentBranch)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `apply dump auto-suffixes on branch collision`() {
    val root = createTempDir(prefix = "lgm-native-apply-coll-")
    try {
      val repo = File(root, "repo").also { it.mkdirs() }
      git(repo, "init")
      git(repo, "config", "user.email", "test@example.com")
      git(repo, "config", "user.name", "Tester")
      File(repo, "a.txt").writeText("A\n")
      git(repo, "add", "a.txt")
      git(repo, "commit", "-m", "A")

      // Create a branch with the target name to force collision
      git(repo, "branch", "my-pull")

      val bundle = File(root, "x.bundle")
      git(repo, "bundle", "create", bundle.absolutePath, "--all")
      val dump = File(root, "x.dmp")
      dump.writeBytes(NativeStealthDump.encryptBundleBytes(bundle.readBytes(), "dandan"))

      val res = NativeStealthApply.applyDump(
        workDir = repo,
        password = "dandan",
        dumpFile = dump,
        mode = "new-branch",
        newBranchName = "my-pull"
      )
      assertTrue(res.ok, "Expected success with auto-suffix but got: ${res.stderr}")
      assertTrue(res.stdout.contains("my-pull-1"), "Expected auto-suffixed branch name in output: ${res.stdout}")
      val currentBranch = gitOut(repo, "rev-parse", "--abbrev-ref", "HEAD")
      assertEquals("my-pull-1", currentBranch)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun git(cwd: File, vararg args: String) {
    val p = ProcessBuilder(listOf("git", *args)).directory(cwd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    val code = p.waitFor()
    if (code != 0) {
      throw IllegalStateException("git ${args.joinToString(" ")} failed in ${cwd.absolutePath}: $out")
    }
  }

  private fun gitOut(cwd: File, vararg args: String): String {
    val p = ProcessBuilder(listOf("git", *args)).directory(cwd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    val code = p.waitFor()
    if (code != 0) {
      throw IllegalStateException("git ${args.joinToString(" ")} failed in ${cwd.absolutePath}: $out")
    }
    return out.trim()
  }
}
