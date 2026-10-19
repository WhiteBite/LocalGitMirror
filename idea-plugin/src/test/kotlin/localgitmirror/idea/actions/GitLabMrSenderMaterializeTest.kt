package localgitmirror.idea.actions

import kotlin.test.Test
import kotlin.test.assertEquals

class GitLabMrSenderMaterializeTest {

  private class Probe(
    val locals: Map<String, String>,
    val remotes: Map<String, String>,
    val ancestors: Set<Pair<String, String>> = emptySet(),
    val createOk: Boolean = true,
  ) {
    val created = mutableListOf<Pair<String, String>>()
    val updated = mutableListOf<Pair<String, String>>()

    fun run(branches: List<String>) = GitLabMrSender.materializeRemoteRefs(
      branches,
      branchHash = { locals[it] },
      remoteTip = { remotes[it] },
      createBranch = { b, t -> created += b to t; createOk },
      isAncestor = { a, d -> (a to d) in ancestors },
      updateRef = { b, t -> updated += b to t; true },
    )
  }

  @Test
  fun `local tip equal to remote tip is left untouched`() {
    val probe = Probe(locals = mapOf("feat" to "aaa1111"), remotes = mapOf("feat" to "aaa1111"))

    val res = probe.run(listOf("feat"))

    assertEquals(emptyList(), res.unresolved)
    assertEquals(emptyList(), res.divergent)
    assertEquals(emptyList(), probe.created)
    assertEquals(emptyList(), probe.updated)
  }

  @Test
  fun `local tip behind remote is fast-forwarded to the remote tip`() {
    val probe = Probe(
      locals = mapOf("feat" to "aaa1111"),
      remotes = mapOf("feat" to "bbb2222"),
      ancestors = setOf("aaa1111" to "bbb2222"),
    )

    val res = probe.run(listOf("feat"))

    assertEquals(listOf("feat" to "bbb2222"), probe.updated)
    assertEquals(emptyList(), probe.created)
    assertEquals(emptyList(), res.unresolved)
    assertEquals(emptyList(), res.divergent)
  }

  @Test
  fun `diverged local branch is not touched and reported divergent`() {
    val probe = Probe(locals = mapOf("feat" to "ccc3333"), remotes = mapOf("feat" to "bbb2222"))

    val res = probe.run(listOf("feat"))

    assertEquals(listOf("feat"), res.divergent)
    assertEquals(emptyList(), res.unresolved)
    assertEquals(emptyList(), probe.created)
    assertEquals(emptyList(), probe.updated)
  }

  @Test
  fun `missing local branch is created from the remote tip`() {
    val probe = Probe(locals = emptyMap(), remotes = mapOf("feat" to "bbb2222"))

    val res = probe.run(listOf("feat"))

    assertEquals(listOf("feat" to "bbb2222"), probe.created)
    assertEquals(emptyList(), probe.updated)
    assertEquals(emptyList(), res.unresolved)
    assertEquals(emptyList(), res.divergent)
  }

  @Test
  fun `missing local branch is unresolved when creation fails`() {
    val probe = Probe(locals = emptyMap(), remotes = mapOf("feat" to "bbb2222"), createOk = false)

    val res = probe.run(listOf("feat"))

    assertEquals(listOf("feat"), res.unresolved)
    assertEquals(emptyList(), res.divergent)
  }

  @Test
  fun `missing local branch is unresolved when the remote tip is missing`() {
    val probe = Probe(locals = emptyMap(), remotes = emptyMap())

    val res = probe.run(listOf("ghost"))

    assertEquals(listOf("ghost"), res.unresolved)
    assertEquals(emptyList(), res.divergent)
    assertEquals(emptyList(), probe.created)
  }

  @Test
  fun `mixed branches return the correct unresolved and divergent lists`() {
    val probe = Probe(
      locals = mapOf("same" to "aaa1111", "behind" to "aaa1111", "diverged" to "ccc3333"),
      remotes = mapOf(
        "same" to "aaa1111",
        "behind" to "bbb2222",
        "diverged" to "bbb2222",
        "new" to "ddd4444",
      ),
      ancestors = setOf("aaa1111" to "bbb2222"),
    )

    val res = probe.run(listOf("same", "behind", "diverged", "new", "ghost"))

    assertEquals(listOf("ghost"), res.unresolved)
    assertEquals(listOf("diverged"), res.divergent)
    assertEquals(listOf("behind" to "bbb2222"), probe.updated)
    assertEquals(listOf("new" to "ddd4444"), probe.created)
  }
}
