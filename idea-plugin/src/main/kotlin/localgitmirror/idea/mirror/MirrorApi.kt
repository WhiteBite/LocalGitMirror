package localgitmirror.idea.mirror

import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

import localgitmirror.idea.net.HttpClient

object MirrorApi {
  data class HttpResult(val code: Int, val body: String)

  /** Read git user.name / user.email from projectDir and set as HTTP headers. */
  private fun setGitIdentityHeaders(conn: HttpURLConnection, projectDir: File) {
    try {
      fun gitConfig(key: String): String? {
        val proc = ProcessBuilder(listOf("git", "config", key))
          .directory(projectDir).redirectErrorStream(false).start()
        val value = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        proc.waitFor()
        return value.ifBlank { null }
      }
      gitConfig("user.name")?.let { conn.setRequestProperty("X-Sync-Author", it) }
      gitConfig("user.email")?.let { conn.setRequestProperty("X-Sync-Email", it) }
    } catch (_: Exception) { /* best-effort */ }
  }

  data class CapabilitiesResult(
    val code: Int,
    val body: String,
    val apiVersion: Int?,
    val protocolVersion: Int?,
    val preflight: Boolean,
    val dryRun: Boolean,
    val passwordProbe: Boolean
  )

  data class ProbeResult(
    val code: Int,
    val bytes: ByteArray?,
    val message: String
  )

  data class DownloadResult(
    val code: Int,
    val file: File?,
    val message: String,
    val head: String? = null,
    val repo: String? = null
  )

  fun ping(
    baseUrl: String,
    apiKey: String,
    insecureTls: Boolean
  ): HttpResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/sync/state")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 15_000
      conn.readTimeout = 15_000
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      HttpResult(code, body)
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  fun capabilities(
    baseUrl: String,
    apiKey: String,
    insecureTls: Boolean
  ): CapabilitiesResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/capabilities")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 20_000
      conn.readTimeout = 20_000
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)

      fun intField(name: String): Int? {
        val m = Regex("\"$name\"\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(body)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull()
      }
      fun boolField(name: String): Boolean {
        val m = Regex("\"$name\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE).find(body)
        val v = m?.groupValues?.getOrNull(1)?.lowercase() ?: return false
        return v == "true"
      }

      CapabilitiesResult(
        code = code,
        body = body,
        apiVersion = intField("apiVersion"),
        protocolVersion = intField("protocolVersion"),
        preflight = boolField("preflight"),
        dryRun = boolField("dryRun"),
        passwordProbe = boolField("passwordProbe")
      )
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      CapabilitiesResult(0, "${e.type}: ${e.message}", null, null, preflight = false, dryRun = false, passwordProbe = false)
    }
  }

  fun passwordProbe(
    baseUrl: String,
    apiKey: String,
    insecureTls: Boolean
  ): ProbeResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/sync/password-probe")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 20_000
      conn.readTimeout = 20_000
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }
      val code = conn.responseCode
      if (code !in 200..299) {
        return ProbeResult(code, null, HttpClient.readBody(conn).take(500))
      }
      val bytes = conn.inputStream.use { it.readBytes() }
      ProbeResult(code, bytes, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      ProbeResult(0, null, "${e.type}: ${e.message}")
    }
  }

  data class RefsResult(val code: Int, val message: String, val head: String?, val refs: Map<String, String>?)

  fun getRefs(
    baseUrl: String,
    apiKey: String,
    repo: String,
    insecureTls: Boolean
  ): RefsResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/sync/refs?repo=${java.net.URLEncoder.encode(repo, "UTF-8")}")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 15_000
      conn.readTimeout = 15_000
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)

      if (code in 200..299) {
        val root = com.google.gson.JsonParser.parseString(body).asJsonObject
        val head = if (root.has("head") && !root.get("head").isJsonNull) root.get("head").asString else null
        val refsMap = mutableMapOf<String, String>()
        if (root.has("refs") && root.get("refs").isJsonObject) {
          val refsObj = root.getAsJsonObject("refs")
          for (entry in refsObj.entrySet()) {
            refsMap[entry.key] = entry.value.asString
          }
        }
        RefsResult(code, "OK", head, refsMap)
      } else {
        RefsResult(code, body.take(500), null, null)
      }
    } catch (e: Exception) {
      RefsResult(0, e.message ?: "Network error", null, null)
    }
  }

  fun uploadAndApply(
    baseUrl: String,
    apiKey: String,
    repo: String,
    dumpFile: File,
    insecureTls: Boolean,
    projectDir: File? = null
  ): HttpResult {
    return try {
      val boundary = "----FormBoundary${UUID.randomUUID()}"
      val url = URL("${baseUrl.trimEnd('/')}/api/sync/upload-and-apply")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 60_000
      conn.readTimeout = 60_000
      conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }
      projectDir?.let { setGitIdentityHeaders(conn, it) }

      conn.outputStream.use { os ->
        val writer = OutputStreamWriter(os, StandardCharsets.UTF_8)

        fun partHeader(name: String, filename: String? = null, contentType: String? = null) {
          writer.write("--$boundary\r\n")
          if (filename != null) {
            writer.write("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n")
          } else {
            writer.write("Content-Disposition: form-data; name=\"$name\"\r\n")
          }
          if (contentType != null) {
            writer.write("Content-Type: $contentType\r\n")
          }
          writer.write("\r\n")
          writer.flush()
        }

        partHeader("repo")
        writer.write(repo)
        writer.write("\r\n")
        writer.flush()

        partHeader("dump_file", "data.bin", "application/octet-stream")
        dumpFile.inputStream().use { it.copyTo(os) }
        writer.write("\r\n")
        writer.flush()

        writer.write("--$boundary--\r\n")
        writer.flush()
      }

      val code = conn.responseCode
      val body = try {
        HttpClient.readBody(conn)
      } catch (_: Exception) {
        ""
      }
      HttpResult(code, body)
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  fun ensureRepoExists(
    baseUrl: String,
    apiKey: String,
    repo: String,
    insecureTls: Boolean,
    projectDir: File? = null
  ): HttpResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/repos/create")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }
      projectDir?.let { setGitIdentityHeaders(conn, it) }

      val payload = "{\"name\":\"$repo\"}"
      conn.outputStream.use { os ->
        os.write(payload.toByteArray(StandardCharsets.UTF_8))
      }

      val code = conn.responseCode
      val body = HttpClient.readBody(conn)

      // Idempotent behavior: repo already exists should be treated as success.
      if (code == 400 && (body.contains("уже существует", ignoreCase = true) || body.contains("already exists", ignoreCase = true))) {
        return HttpResult(200, "Repository already exists")
      }

      HttpResult(code, body)
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  fun hasCommits(
    baseUrl: String,
    apiKey: String,
    repo: String,
    commits: List<String>,
    insecureTls: Boolean
  ): HttpResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/sync/has-commits")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }

      val commitsJson = commits.joinToString(",") { "\"${it}\"" }
      val payload = "{\"repo\":\"$repo\",\"commits\":[${commitsJson}]}"
      conn.outputStream.use { os ->
        os.write(payload.toByteArray(StandardCharsets.UTF_8))
      }

      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  fun applyKnown(
    baseUrl: String,
    apiKey: String,
    repo: String,
    commit: String,
    branches: Map<String, String> = emptyMap(),
    insecureTls: Boolean
  ): HttpResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/sync/apply-known")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 30_000
      conn.readTimeout = 60_000
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }

      val branchesJson = if (branches.isNotEmpty()) {
        val entries = branches.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
        ",\"branches\":{$entries}"
      } else ""
      val payload = "{\"repo\":\"$repo\",\"commit\":\"$commit\"$branchesJson}"
      conn.outputStream.use { os ->
        os.write(payload.toByteArray(StandardCharsets.UTF_8))
      }

      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  fun exportDump(
    baseUrl: String,
    apiKey: String,
    repo: String,
    since: String?,
    insecureTls: Boolean,
    outFile: File
  ): DownloadResult {
    return try {
      val boundary = "----FormBoundary${UUID.randomUUID()}"
      val url = URL("${baseUrl.trimEnd('/')}/api/sync/export-dump")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 60_000
      conn.readTimeout = 120_000
      conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }

      conn.outputStream.use { os ->
        val writer = OutputStreamWriter(os, StandardCharsets.UTF_8)
        fun part(name: String, value: String) {
          writer.write("--$boundary\r\n")
          writer.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
          writer.write(value)
          writer.write("\r\n")
          writer.flush()
        }

        part("repo", repo)
        if (!since.isNullOrBlank()) {
          part("since", since)
        }
        writer.write("--$boundary--\r\n")
        writer.flush()
      }

      val code = conn.responseCode
      val head = conn.getHeaderField("X-Sync-Head") ?: conn.getHeaderField("X-LGM-Head")
      val hdrRepo = conn.getHeaderField("X-Sync-Repo") ?: conn.getHeaderField("X-LGM-Repo")

      if (code == 204) {
        return DownloadResult(204, null, "No new commits", head = head, repo = hdrRepo)
      }
      if (code !in 200..299) {
        val body = HttpClient.readBody(conn)
        return DownloadResult(code, null, body.take(500), head = head, repo = hdrRepo)
      }

      outFile.outputStream().use { out ->
        conn.inputStream.use { input ->
          input.copyTo(out)
        }
      }
      DownloadResult(code, outFile, "OK", head = head, repo = hdrRepo)
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      DownloadResult(0, null, "${e.type}: ${e.message}")
    }
  }

  data class PreviewPullResult(
    val code: Int,
    val remoteHead: String?,
    val hasUpdates: Boolean,
    val reason: String,
    val message: String
  )

  fun previewPull(
    baseUrl: String,
    apiKey: String,
    repo: String,
    since: String?,
    insecureTls: Boolean
  ): PreviewPullResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/sync/preview-pull")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }

      val sinceJson = if (!since.isNullOrBlank()) "\"$since\"" else "null"
      val payload = """{"repo":"$repo","since":$sinceJson}"""
      conn.outputStream.use { os ->
        os.write(payload.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
      }

      val code = conn.responseCode
      val body = HttpClient.readBody(conn)

      val remoteHead = Regex(""""remoteHead"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1)
      val hasUpdates = Regex(""""hasUpdates"\s*:\s*(true|false)""").find(body)?.groupValues?.getOrNull(1)?.toBoolean() ?: false
      val reason = Regex(""""reason"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1) ?: "unknown"

      PreviewPullResult(code, remoteHead, hasUpdates, reason, body.take(500))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      PreviewPullResult(0, null, false, "error", "${e.type}: ${e.message}")
    }
  }

  data class CommitInfo(val hash: String, val message: String)

  data class PreviewPullDetailsResult(
    val code: Int,
    val commits: List<CommitInfo>,
    val diffstat: String,
    val message: String
  )

  fun previewPullDetails(
    baseUrl: String,
    apiKey: String,
    repo: String,
    since: String?,
    insecureTls: Boolean
  ): PreviewPullDetailsResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/sync/preview-pull-details")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }

      val sinceJson = if (!since.isNullOrBlank()) "\"$since\"" else "null"
      val payload = """{"repo":"$repo","since":$sinceJson}"""
      conn.outputStream.use { it.write(payload.toByteArray(java.nio.charset.StandardCharsets.UTF_8)) }

      val code = conn.responseCode
      val body = HttpClient.readBody(conn)

      if (code !in 200..299) {
          return PreviewPullDetailsResult(code, emptyList(), "", body.take(500))
      }

      // Simple regex parsing for commits and diffstat
      val commits = mutableListOf<CommitInfo>()
      val commitRegex = Regex("""\{"hash"\s*:\s*"([^"]+)"\s*,\s*"message"\s*:\s*"([^"]*)"\}""")
      commitRegex.findAll(body).forEach {
          commits.add(CommitInfo(it.groupValues[1], it.groupValues[2]))
      }

      val diffstat = Regex(""""diffstat"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1)
          ?.replace("\\n", "\n")
          ?.replace("\\t", "\t")
          ?: ""

      PreviewPullDetailsResult(code, commits, diffstat, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      PreviewPullDetailsResult(0, emptyList(), "", "${e.type}: ${e.message}")
    }
  }
}
