package localgitmirror.idea.gitlab

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class MrReviewSnapshotTest {
  @TempDir lateinit var repo: File
  private lateinit var reader: MrReviewGit
  private lateinit var baseSha: String

  @BeforeEach
  fun prepare() {
    git("init", "-b", "main")
    git("config", "user.email", "review@example.com")
    git("config", "user.name", "Review")
    git("config", "core.autocrlf", "false")
    File(repo, "Changed.java").writeText("class Before {}\n\n")
    File(repo, "Deleted.java").writeText("class Deleted {}\n")
    commit("base")
    baseSha = git("rev-parse", "HEAD").trim()
    git("checkout", "-b", "feature")
    File(repo, "Changed.java").writeText("class After {}\n\n")
    File(repo, "Added.java").writeText("class Added {}\n")
    File(repo, "Deleted.java").delete()
    commit("feature")
    git("checkout", "main")
    File(repo, "TargetOnly.java").writeText("class TargetOnly {}\n")
    commit("target advances")
    reader = MrReviewGit(repo)
  }

  @Test
  fun `comparison uses merge base rather than target tip without touching checkout or dirty files`() {
    val head = git("rev-parse", "HEAD")
    File(repo, "Changed.java").writeText("dirty local file\n")

    val snapshot = reader.capture("feature", "main")
    val content = reader.content(snapshot, "Changed.java")

    assertEquals(baseSha, snapshot.baseSha)
    assertEquals(setOf("Changed.java", "Added.java", "Deleted.java"), snapshot.files.toSet())
    assertEquals("class Before {}\n\n", content.before)
    assertEquals("class After {}\n\n", content.after)
    assertEquals(head, git("rev-parse", "HEAD"))
    assertEquals("dirty local file\n", File(repo, "Changed.java").readText())
  }

  @Test
  fun `captured source remains stable when branch advances`() {
    val snapshot = reader.capture("feature", "main")
    git("checkout", "feature")
    File(repo, "Changed.java").writeText("newer code\n")
    commit("another revision")

    val content = reader.content(snapshot, "Changed.java")

    assertEquals("class After {}\n\n", content.after)
  }

  @Test
  fun `added and deleted files keep their missing side empty`() {
    val snapshot = reader.capture("feature", "main")

    val added = reader.content(snapshot, "Added.java")
    val deleted = reader.content(snapshot, "Deleted.java")

    assertNull(added.before)
    assertEquals("class Added {}\n", added.after)
    assertEquals("class Deleted {}\n", deleted.before)
    assertNull(deleted.after)
  }

  @Test
  fun `missing revision or file fails instead of using worktree`() {
    File(repo, "Untracked.java").writeText("local only\n")
    val snapshot = reader.capture("feature", "main")

    assertFailsWith<IOException> { reader.capture("missing", "main") }
    assertFailsWith<IOException> { reader.capture("feature", "") }
    assertFailsWith<IOException> { reader.content(snapshot, "Untracked.java") }
    assertFailsWith<IOException> { reader.capture("--help", "main") }
  }

  @Test
  fun `binary content and literal special character paths are handled explicitly`() {
    git("checkout", "feature")
    File(repo, "a[1].java").writeText("literal\n")
    File(repo, "a1.java").writeText("not the selected file\n")
    File(repo, "src/deep").mkdirs()
    File(repo, "src/deep/Nested.java").writeText("nested file\n")
    File(repo, "image.bin").writeBytes(byteArrayOf(0, 1, 2))
    commit("special files")
    val snapshot = reader.capture("feature", "main")

    val literal = reader.content(snapshot, "a[1].java")
    val binary = reader.content(snapshot, "image.bin")

    assertEquals("literal\n", literal.after)
    assertEquals("nested file\n", reader.content(snapshot, "src/deep/Nested.java").after)
    assertFalse(literal.binary)
    assertTrue(binary.binary)
  }

  private fun commit(message: String) {
    git("add", ".")
    git("commit", "-m", message)
  }

  private fun git(vararg args: String): String {
    val process = ProcessBuilder(listOf("git", *args)).directory(repo).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output }
    return output
  }
}
