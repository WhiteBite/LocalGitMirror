package localgitmirror.idea.git

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class GitDirCacheTest {

  @TempDir
  lateinit var tempDir: File

  @BeforeEach
  fun resetCache() {
    GitDirCache.clearAll()
  }

  private fun git(cwd: File, vararg args: String): String {
    val p = ProcessBuilder(listOf("git", *args))
      .directory(cwd).redirectErrorStream(false).start()
    val out = p.inputStream.bufferedReader().readText()
    val err = p.errorStream.bufferedReader().readText()
    val code = p.waitFor()
    if (code != 0) throw IllegalStateException("git ${args.joinToString(" ")} failed: $out $err")
    return out.trim()
  }

  private fun newRepo(name: String): File {
    val repo = File(tempDir, name).also { it.mkdirs() }
    git(repo, "init")
    return repo
  }

  @Test
  fun `gitDir returns canonical absolute dot-git path`() {
    val repo = newRepo("repo-a")
    val dir = GitDirCache.gitDir(repo)
    assertTrue(dir.isAbsolute)
    assertEquals(File(repo, ".git").canonicalFile, dir)
  }

  @Test
  fun `second call hits the cache without respawning`() {
    val repo = newRepo("repo-b")
    GitDirCache.gitDir(repo)
    val spawns = GitDirCache.spawnCount()
    GitDirCache.gitDir(repo)
    GitDirCache.gitDir(repo)
    assertEquals(spawns, GitDirCache.spawnCount())
  }

  @Test
  fun `invalidate forces a respawn on next call`() {
    val repo = newRepo("repo-c")
    GitDirCache.gitDir(repo)
    val spawns = GitDirCache.spawnCount()
    GitDirCache.invalidate(repo)
    GitDirCache.gitDir(repo)
    assertEquals(spawns + 1, GitDirCache.spawnCount())
  }

  @Test
  fun `non repo dir throws and failure is not cached`() {
    val plain = File(tempDir, "plain").also { it.mkdirs() }
    assertFailsWith<IOException> { GitDirCache.gitDir(plain) }
    val spawns = GitDirCache.spawnCount()
    assertFailsWith<IOException> { GitDirCache.gitDir(plain) }
    assertEquals(spawns + 1, GitDirCache.spawnCount())
  }

  @Test
  fun `subdir of repo resolves to the repo git dir`() {
    val repo = newRepo("repo-e")
    val sub = File(repo, "sub/nested").also { it.mkdirs() }
    assertEquals(File(repo, ".git").canonicalFile, GitDirCache.gitDir(sub))
  }
}
