package localgitmirror.idea.gitlab

import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MrNotesSyncPlanTest {

  private val now: LocalDateTime = LocalDateTime.of(2026, 10, 15, 20, 0)

  @Test
  fun `filtered run never yields closure candidates`() {
    assertEquals(emptyList(), MrNotesSync.closedCandidates(setOf(41, 42, 99), setOf(41, 42), filtered = true))
  }

  @Test
  fun `full run flags tracked MRs missing from the open list`() {
    assertEquals(listOf(99), MrNotesSync.closedCandidates(setOf(41, 42, 99), setOf(41, 42), filtered = false))
  }

  @Test
  fun `discussions refetch is skipped when the stamp matches and the last upload is fresh`() {
    assertTrue(MrNotesSync.shouldSkipMr("2026-10-15T10:00:00Z", "2026-10-15T10:00:00Z", "2026-10-15 19:00", now))
  }

  @Test
  fun `discussions refetch happens when the last upload aged past the resync window`() {
    assertFalse(MrNotesSync.shouldSkipMr("2026-10-15T10:00:00Z", "2026-10-15T10:00:00Z", "2026-10-01 10:00", now))
  }

  @Test
  fun `discussions refetch happens when the stamp changed`() {
    assertFalse(MrNotesSync.shouldSkipMr("2026-10-14T10:00:00Z", "2026-10-15T10:00:00Z", "2026-10-15 19:00", now))
  }

  @Test
  fun `discussions refetch happens on a missing or blank previous stamp`() {
    assertFalse(MrNotesSync.shouldSkipMr(null, "2026-10-15T10:00:00Z", "2026-10-15 19:00", now))
    assertFalse(MrNotesSync.shouldSkipMr("  ", "2026-10-15T10:00:00Z", "2026-10-15 19:00", now))
  }

  @Test
  fun `discussions refetch happens on a malformed upload stamp`() {
    assertFalse(MrNotesSync.shouldSkipMr("2026-10-15T10:00:00Z", "2026-10-15T10:00:00Z", "garbage", now))
  }

  @Test
  fun `eviction drops closed state older than the postbox ttl and keeps the rest`() {
    val map = mutableMapOf(
      "41" to "2026-10-07 10:00",
      "42" to "2026-10-14 10:00",
      "43" to "garbage",
    )
    val evicted = MrNotesSync.evictStaleClosed(map, now)
    assertEquals(listOf("41"), evicted)
    assertEquals(setOf("42", "43"), map.keys)
  }

  @Test
  fun `fetchFromGitLab covers every open MR`() {
    val mrsJson = (1..12).joinToString(",") { i ->
      """{"iid":$i,"title":"MR $i","source_branch":"b$i","updated_at":"2026-10-15T10:00:00.000Z"}"""
    }
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/v4/projects/p/merge_requests") { ex ->
      try {
        val body = if (ex.requestURI.rawPath.endsWith("/discussions")) "[]" else "[$mrsJson]"
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
      } finally {
        ex.close()
      }
    }
    server.start()
    try {
      val conf = GitLabConfig.GitLabConf("http://127.0.0.1:${server.address.port}", "p", "t")
      val rows = MrReviewService(project).fetchFromGitLab(conf)
      assertEquals((1..12).toList(), rows.map { it.iid })
    } finally {
      server.stop(0)
    }
  }

  private val project: Project = java.lang.reflect.Proxy.newProxyInstance(
    Project::class.java.classLoader,
    arrayOf(Project::class.java)
  ) { _, _, _ -> null } as Project
}
