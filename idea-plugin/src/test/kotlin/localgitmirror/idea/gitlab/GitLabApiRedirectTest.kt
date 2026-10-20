package localgitmirror.idea.gitlab

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitLabApiRedirectTest {
  private lateinit var server: HttpServer
  private var redirectHits = 0
  private var loopHits = 0
  private var tokenSeenOnFinalHop: String? = null

  @BeforeTest
  fun up() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/redirect") { ex ->
      redirectHits++
      ex.responseHeaders.add("Location", "/api/v4/projects/x/merge_requests/1/discussions")
      ex.sendResponseHeaders(301, -1)
      ex.close()
    }
    server.createContext("/loop") { ex ->
      loopHits++
      ex.responseHeaders.add("Location", "/loop")
      ex.sendResponseHeaders(301, -1)
      ex.close()
    }
    server.createContext("/api/v4") { ex ->
      tokenSeenOnFinalHop = ex.requestHeaders.getFirst("PRIVATE-TOKEN")
      val body = "[]".toByteArray(Charsets.UTF_8)
      ex.responseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(200, body.size.toLong())
      ex.responseBody.use { it.write(body) }
    }
    server.start()
  }

  @AfterTest
  fun down() {
    server.stop(0)
  }

  private fun base(): String = "http://127.0.0.1:${server.address.port}"

  @Test
  fun `discussions follow a redirect and keep the token`() {
    val conf = GitLabConfig.GitLabConf("${base()}/redirect", "x", "secret-token")

    val result = GitLabApi.listMrDiscussions(conf, 1)

    assertEquals(200, result.code, "redirect must be followed: ${result.message}")
    assertEquals(1, redirectHits)
    assertEquals("secret-token", tokenSeenOnFinalHop, "PRIVATE-TOKEN must survive the hop")
  }

  @Test
  fun `cyclic redirect stops after a bounded number of hops`() {
    val conf = GitLabConfig.GitLabConf("${base()}/loop", "x", "t")

    val result = GitLabApi.listMrDiscussions(conf, 1)

    assertEquals(301, result.code)
    assertTrue(loopHits <= 4, "hops must be bounded, got $loopHits")
    assertTrue(result.message.contains("redirect", ignoreCase = true), result.message)
  }
}
