package localgitmirror.idea.actions

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for PullLogic — pure pull-side branch logic.
 * No git processes, no IntelliJ dependencies.
 */
class PullLogicTest {

  // ──────────────────────────────────────────────────────────────────────────
  // applyBranch — branch already exists locally
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `applyBranch - already up to date returns no-op message`() {
    val hash = "abc1234567890"
    val result = applyBranch(
      branchName = "main",
      newHash = hash,
      localBranches = setOf("main"),
      localHash = hash,         // same as newHash
      isAncestor = false        // won't be called
    )
    assertTrue(result.contains("уже актуальна"), "Expected 'уже актуальна' in: $result")
    assertTrue(result.contains(hash.take(7)), "Expected short hash in: $result")
  }

  @Test
  fun `applyBranch - fast-forward updates ref and reports new hash`() {
    val oldHash = "aaa0000000000"
    val newHash = "bbb1111111111"
    var updatedRef: Pair<String, String>? = null

    val result = PullLogic.applyBranch(
      branchName = "feature",
      newHash = newHash,
      localBranches = setOf("feature"),
      revParse = { oldHash },
      isAncestor = { _, _ -> true },   // clean fast-forward
      updateRef = { ref, hash -> updatedRef = ref to hash },
      createBranch = { _, _ -> },
      createSuffixedBranch = { _, _ -> "feature-local-1" }
    )

    assertEquals("feature" to newHash, updatedRef, "updateRef should be called with branch + newHash")
    assertTrue(result.contains("обновлена до"), "Expected 'обновлена до' in: $result")
    assertTrue(result.contains(newHash.take(7)), "Expected short hash in: $result")
  }

  @Test
  fun `applyBranch - diverged creates backup branch and still updates`() {
    val oldHash = "aaa0000000000"
    val newHash = "ccc2222222222"
    var updatedRef: Pair<String, String>? = null
    val createdBranches = mutableListOf<String>()

    val result = PullLogic.applyBranch(
      branchName = "main",
      newHash = newHash,
      localBranches = setOf("main"),
      revParse = { oldHash },
      isAncestor = { _, _ -> false },  // diverged
      updateRef = { ref, hash -> updatedRef = ref to hash },
      createBranch = { _, _ -> },
      createSuffixedBranch = { base, _ ->
        val name = "$base-local-1"
        createdBranches.add(name)
        name
      }
    )

    assertEquals("main" to newHash, updatedRef, "main ref must be updated to newHash")
    assertTrue(createdBranches.contains("main-local-1"), "Backup branch must be created")
    assertTrue(result.contains("расходилась"), "Expected 'расходилась' in: $result")
    assertTrue(result.contains("main-local-1"), "Expected backup branch name in: $result")
    assertTrue(result.contains(newHash.take(7)), "Expected new hash in: $result")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // applyBranch — branch does not exist locally
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `applyBranch - new branch is created`() {
    val newHash = "ddd3333333333"
    val created = mutableListOf<Pair<String, String>>()

    val result = PullLogic.applyBranch(
      branchName = "develop",
      newHash = newHash,
      localBranches = emptySet(),      // branch doesn't exist locally
      revParse = { "" },
      isAncestor = { _, _ -> false },
      updateRef = { _, _ -> },
      createBranch = { ref, hash -> created.add(ref to hash) },
      createSuffixedBranch = { _, _ -> "" }
    )

    assertTrue(created.contains("develop" to newHash), "createBranch must be called with name + hash")
    assertTrue(result.contains("создана новая ветка"), "Expected 'создана новая ветка' in: $result")
    assertTrue(result.contains(newHash.take(7)), "Expected short hash in: $result")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // findSinceHash
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `findSinceHash - returns matching local hash`() {
    val sharedHash = "shared000000000"
    val remoteRefs = mapOf("main" to sharedHash, "develop" to "remote999")

    val result = PullLogic.findSinceHash(
      dir = File("."),
      remoteRefs = remoteRefs,
      forEachRef = { _ -> "$sharedHash\nlocal111\nlocal222" }
    )

    assertEquals(sharedHash, result)
  }

  @Test
  fun `findSinceHash - returns null when no common hash`() {
    val remoteRefs = mapOf("main" to "remote000", "develop" to "remote111")

    val result = PullLogic.findSinceHash(
      dir = File("."),
      remoteRefs = remoteRefs,
      forEachRef = { _ -> "local_aaa\nlocal_bbb\nlocal_ccc" }
    )

    assertNull(result, "Should return null when no shared commit exists")
  }

  @Test
  fun `findSinceHash - returns null for empty remote refs`() {
    val result = PullLogic.findSinceHash(
      dir = File("."),
      remoteRefs = emptyMap(),
      forEachRef = { _ -> "local_aaa" }
    )
    assertNull(result)
  }

  @Test
  fun `findSinceHash - returns first matching hash when multiple match`() {
    val hash1 = "first_shared_hash"
    val hash2 = "second_shared"
    val remoteRefs = mapOf("main" to hash1, "other" to hash2)

    val result = PullLogic.findSinceHash(
      dir = File("."),
      remoteRefs = remoteRefs,
      // hash1 appears first in the local list
      forEachRef = { _ -> "$hash1\n$hash2\nlocal_only" }
    )

    assertEquals(hash1, result, "Should return the first matching hash")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  /** Convenience wrapper that injects fixed state for most tests. */
  private fun applyBranch(
    branchName: String,
    newHash: String,
    localBranches: Set<String>,
    localHash: String,
    isAncestor: Boolean
  ): String {
    var updatedRef: String? = null
    return PullLogic.applyBranch(
      branchName = branchName,
      newHash = newHash,
      localBranches = localBranches,
      revParse = { localHash },
      isAncestor = { _, _ -> isAncestor },
      updateRef = { ref, _ -> updatedRef = ref },
      createBranch = { _, _ -> },
      createSuffixedBranch = { base, _ -> "$base-local-1" }
    )
  }
}
