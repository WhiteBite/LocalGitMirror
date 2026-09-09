package localgitmirror.idea.gitlab

import com.intellij.openapi.components.service
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.net.HttpClient
import localgitmirror.idea.settings.MirrorSettingsService
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal GitLab REST v4 client for the MR-transfer feature.
 *
 * Style mirrors [localgitmirror.idea.mirror.MirrorApi]: HttpURLConnection via
 * the shared [HttpClient] (IDE proxy + optional trust-all TLS from
 * settings.mirrorInsecureTls), explicit timeouts, kotlinx-serialization-json
 * parsing and graceful HttpResult-style error objects — never thrown
 * exceptions.
 */
object GitLabApi {

  data class MrInfo(
    val iid: Int,
    val title: String,
    val sourceBranch: String,
    val updatedAt: String
  )

  data class MrsResult(val code: Int, val mrs: List<MrInfo>, val message: String)
  data class MrBranchResult(val code: Int, val branch: String?, val message: String)
  data class VerifyResult(val code: Int, val projectName: String?, val message: String)

  private fun insecureTls(): Boolean = try {
    service<MirrorSettingsService>().state.mirrorInsecureTls
  } catch (_: Throwable) {
    false
  }

  /** Project path must be URL-encoded as a single path segment (slash → %2F). */
  private fun projectPathEncoded(project: String): String =
    URLEncoder.encode(project.trim().trim('/'), "UTF-8").replace("+", "%20")

  private fun open(url: String, token: String): HttpURLConnection {
    val conn = HttpClient.open(URL(url), insecureTls())
    conn.requestMethod = "GET"
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.setRequestProperty("PRIVATE-TOKEN", token)
    conn.setRequestProperty("Accept", "application/json")
    return conn
  }

  /** GET {url}/api/v4/projects/{project} — proves url+project+token work together. */
  fun verify(conf: GitLabConfig.GitLabConf): VerifyResult {
    if (conf.url.isBlank() || conf.project.isBlank()) {
      return VerifyResult(0, null, "not-detected")
    }
    if (conf.token.isBlank()) {
      return VerifyResult(0, null, "no-token")
    }
    return try {
      val conn = open("${conf.url.trimEnd('/')}/api/v4/projects/${projectPathEncoded(conf.project)}", conf.token)
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
      if (code in 200..299) {
        val name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.getOrNull(1)
        VerifyResult(code, name, "")
      } else {
        VerifyResult(code, null, body.take(200))
      }
    } catch (t: Throwable) {
      VerifyResult(0, null, t.message ?: "error")
    }
  }

  data class MrNote(
    val author: String,
    val createdAt: String,
    val system: Boolean,
    val body: String,
    val resolved: Boolean
  )

  data class MrDiscussion(val resolved: Boolean, val notes: List<MrNote>)
  data class DiscussionsResult(val code: Int, val discussions: List<MrDiscussion>, val message: String)

  /**
   * GET {url}/api/v4/projects/{project}/merge_requests/{iid}/discussions —
   * every thread of the MR: open and resolved, current and outdated, system
   * notes included. Paginated via X-Next-Page, capped at 10 pages.
   */
  fun listMrDiscussions(conf: GitLabConfig.GitLabConf, iid: Int): DiscussionsResult {
    if (conf.url.isBlank() || conf.project.isBlank() || conf.token.isBlank()) {
      return DiscussionsResult(0, emptyList(), "not-detected")
    }
    return try {
      val out = mutableListOf<MrDiscussion>()
      var page = 1
      while (page in 1..10) {
        val url = "${conf.url.trimEnd('/')}/api/v4/projects/${projectPathEncoded(conf.project)}" +
          "/merge_requests/$iid/discussions?per_page=100&page=$page"
        val conn = open(url, conf.token)
        val code = conn.responseCode
        if (code !in 200..299) {
          val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
          return DiscussionsResult(code, out, err.take(200))
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = Json.parseToJsonElement(body).jsonArray
        if (arr.isEmpty()) break
        for (d in arr) {
          val notesObj = d.jsonObject["notes"]?.jsonArray ?: continue
          val notes = mutableListOf<MrNote>()
          var anyResolvable = false
          var resolved = true
          for (n in notesObj) {
            val o = n.jsonObject
            val resolvable = o["resolvable"]?.jsonPrimitive?.booleanOrNull ?: false
            val isResolved = o["resolved"]?.jsonPrimitive?.booleanOrNull ?: false
            if (resolvable) {
              anyResolvable = true
              if (!isResolved) resolved = false
            }
            notes.add(
              MrNote(
                author = o["author"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: "?",
                createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull ?: "",
                system = o["system"]?.jsonPrimitive?.booleanOrNull ?: false,
                body = o["body"]?.jsonPrimitive?.contentOrNull ?: "",
                resolved = isResolved
              )
            )
          }
          out.add(MrDiscussion(resolved = anyResolvable && resolved, notes = notes))
        }
        val next = conn.getHeaderField("X-Next-Page")?.trim().orEmpty()
        if (next.isEmpty()) break
        page = next.toIntOrNull() ?: break
      }
      DiscussionsResult(200, out, "")
    } catch (t: Throwable) {
      DiscussionsResult(0, emptyList(), t.message ?: "error")
    }
  }

  /**
   * GET {url}/api/v4/projects/{project}/merge_requests?state=opened
   * Newest-updated first, capped at 50 entries.
   */
  fun listOpenMrs(conf: GitLabConfig.GitLabConf): MrsResult {
    return try {
      val url = "${conf.url.trimEnd('/')}/api/v4/projects/${projectPathEncoded(conf.project)}" +
        "/merge_requests?state=opened&order_by=updated_at&sort=desc&per_page=50"
      val conn = open(url, conf.token)
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) return MrsResult(code, emptyList(), body.take(300))

      val arr = Json.parseToJsonElement(body).jsonArray
      val mrs = arr.mapNotNull { el ->
        val o = el.jsonObject
        val iid = o["iid"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
        MrInfo(
          iid = iid,
          title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
          sourceBranch = o["source_branch"]?.jsonPrimitive?.contentOrNull ?: "",
          updatedAt = o["updated_at"]?.jsonPrimitive?.contentOrNull ?: ""
        )
      }
      MrsResult(code, mrs, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      MrsResult(0, emptyList(), "${e.type}: ${e.message}")
    }
  }

  /** GET {url}/api/v4/projects/{project}/merge_requests/{iid} → source_branch. */
  fun getMrSourceBranch(conf: GitLabConfig.GitLabConf, iid: Int): MrBranchResult {
    return try {
      val url = "${conf.url.trimEnd('/')}/api/v4/projects/${projectPathEncoded(conf.project)}" +
        "/merge_requests/$iid"
      val conn = open(url, conf.token)
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) return MrBranchResult(code, null, body.take(300))

      val o = Json.parseToJsonElement(body).jsonObject
      val branch = o["source_branch"]?.jsonPrimitive?.contentOrNull
      MrBranchResult(code, branch, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      MrBranchResult(0, null, "${e.type}: ${e.message}")
    }
  }
}
