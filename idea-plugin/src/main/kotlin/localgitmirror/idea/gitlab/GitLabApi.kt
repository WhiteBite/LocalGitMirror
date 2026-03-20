package localgitmirror.idea.gitlab

import java.net.URL
import java.net.URLEncoder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.net.HttpClient

object GitLabApi {
  data class HttpResult(val code: Int, val body: String)
  data class MergeRequestSummary(
    val iid: Int,
    val title: String,
    val sourceBranch: String,
    val targetBranch: String,
    val state: String
  ) {
    fun display(): String = "!$iid [$state] $sourceBranch -> $targetBranch | $title"
  }

  private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

  fun getMergeRequest(
    baseUrl: String,
    token: String,
    projectIdOrPath: String,
    mrIid: String,
    insecureTls: Boolean
  ): HttpResult {
    return try {
      val project = enc(projectIdOrPath)
      val url = URL("${baseUrl.trimEnd('/')}/api/v4/projects/$project/merge_requests/${enc(mrIid)}")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.setRequestProperty("PRIVATE-TOKEN", token)
      conn.connectTimeout = 15000
      conn.readTimeout = 15000
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      HttpResult(code, body)
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  fun getMergeRequestSourceBranch(
    baseUrl: String,
    token: String,
    projectIdOrPath: String,
    mrIid: String,
    insecureTls: Boolean
  ): Pair<HttpResult, String?> {
    val res = getMergeRequest(baseUrl, token, projectIdOrPath, mrIid, insecureTls)
    if (res.code !in 200..299) return res to null
    val source = parseSourceBranch(res.body)
    return res to source
  }

  fun listOpenMergeRequests(
    baseUrl: String,
    token: String,
    projectIdOrPath: String,
    insecureTls: Boolean,
    perPage: Int = 20
  ): Pair<HttpResult, List<MergeRequestSummary>> {
    return try {
      val project = enc(projectIdOrPath)
      val url = URL(
        "${baseUrl.trimEnd('/')}/api/v4/projects/$project/merge_requests?state=opened&order_by=updated_at&sort=desc&per_page=$perPage"
      )
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.setRequestProperty("PRIVATE-TOKEN", token)
      conn.connectTimeout = 15000
      conn.readTimeout = 15000
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      val res = HttpResult(code, body)
      if (code !in 200..299) return res to emptyList()
      res to parseOpenMrs(body)
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}") to emptyList()
    }
  }

  private fun parseSourceBranch(body: String): String? {
    return try {
      val obj = Json.parseToJsonElement(body).jsonObject
      obj["source_branch"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
      null
    }
  }

  private fun parseOpenMrs(body: String): List<MergeRequestSummary> {
    return try {
      val arr = Json.parseToJsonElement(body).jsonArray
      arr.mapNotNull { parseMrSummary(it) }
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun parseMrSummary(el: JsonElement): MergeRequestSummary? {
    val obj = el as? JsonObject ?: return null
    val iid = obj["iid"]?.jsonPrimitive?.intOrNull ?: return null
    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
    val source = obj["source_branch"]?.jsonPrimitive?.contentOrNull ?: ""
    val target = obj["target_branch"]?.jsonPrimitive?.contentOrNull ?: ""
    val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "opened"
    if (source.isBlank()) return null
    return MergeRequestSummary(iid = iid, title = title, sourceBranch = source, targetBranch = target, state = state)
  }
}
