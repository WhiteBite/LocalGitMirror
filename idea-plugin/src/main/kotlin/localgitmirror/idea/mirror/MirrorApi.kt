package localgitmirror.idea.mirror

import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64 as JavaBase64
import java.util.UUID

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
      gitConfig("user.name")?.let { conn.setRequestProperty("X-User-Name", it) }
      gitConfig("user.email")?.let { conn.setRequestProperty("X-User-Email", it) }
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
      val url = URL("${baseUrl.trimEnd('/')}/api/session/state")
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
      val url = URL("${baseUrl.trimEnd('/')}/api/health")
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
      val url = URL("${baseUrl.trimEnd('/')}/api/auth/verify")
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
      val url = URL("${baseUrl.trimEnd('/')}/api/documents/list?repo=${java.net.URLEncoder.encode(repo, "UTF-8")}")
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
      val url = URL("${baseUrl.trimEnd('/')}/api/documents/upload")
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

        partHeader("attachment", "document.bin", "application/octet-stream")
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
      val url = URL("${baseUrl.trimEnd('/')}/api/documents/check")
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
      val url = URL("${baseUrl.trimEnd('/')}/api/documents/link")
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
    outFile: File,
    /** Bundle ONLY this branch on the server (instead of --all). */
    branch: String? = null,
    /** Commit hashes the client already has; server excludes them (^hash) to send only the delta. */
    haves: List<String> = emptyList(),
    /** Called periodically during body download: (bytesRead, totalBytes). totalBytes = -1 if unknown. */
    onProgress: ((read: Long, total: Long) -> Unit)? = null
  ): DownloadResult {
    return try {
      val boundary = "----FormBoundary${UUID.randomUUID()}"
      val url = URL("${baseUrl.trimEnd('/')}/api/documents/export")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 90_000
      conn.readTimeout = 300_000
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
        if (!branch.isNullOrBlank()) {
          part("branch", branch)
        }
        if (haves.isNotEmpty()) {
          part("haves", haves.joinToString(","))
        }
        writer.write("--$boundary--\r\n")
        writer.flush()
      }

      val code = conn.responseCode
      if (code !in 200..299) {
        val body = HttpClient.readBody(conn)
        return DownloadResult(code, null, body.take(500))
      }

      // Response is JSON: {"status", "head", "repo", "data" (base64), "filename"}
      // Use streaming read so caller can show download progress.
      val body = HttpClient.readBodyWithProgress(conn, onProgress)
      val json = Json.parseToJsonElement(body).jsonObject
      val status = json["status"]?.jsonPrimitive?.contentOrNull ?: ""
      val head = json["head"]?.jsonPrimitive?.contentOrNull
      val hdrRepo = json["repo"]?.jsonPrimitive?.contentOrNull

      if (status == "no_content") {
        return DownloadResult(204, null, "No new commits", head = head, repo = hdrRepo)
      }

      val b64data = json["data"]?.jsonPrimitive?.contentOrNull
        ?: return DownloadResult(500, null, "Missing data in response", head = head, repo = hdrRepo)

      outFile.writeBytes(JavaBase64.getDecoder().decode(b64data))
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
      val url = URL("${baseUrl.trimEnd('/')}/api/documents/preview")
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
    insecureTls: Boolean,
    branch: String? = null
  ): PreviewPullDetailsResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/documents/preview-details")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }

      val sinceJson = if (!since.isNullOrBlank()) "\"$since\"" else "null"
      val branchJson = if (!branch.isNullOrBlank()) "\"$branch\"" else "null"
      val payload = """{"repo":"$repo","since":$sinceJson,"branch":$branchJson}"""
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

  // ───────────────────────────────────────────────────────────────────────
  // /api/deps/* — Gradle dependency sync (encrypted blob postbox)
  // ───────────────────────────────────────────────────────────────────────

  data class DepsItem(val id: String, val size: Long, val mtime: Long)
  data class DepsListResult(val code: Int, val items: List<DepsItem>, val message: String)
  data class DepsUploadResult(val code: Int, val id: String?, val size: Long, val message: String)

  private fun parseDepsList(body: String): List<DepsItem> {
    val items = mutableListOf<DepsItem>()
    val itemRe = Regex(
      """\{\s*"id"\s*:\s*"([^"]+)"\s*,\s*"size"\s*:\s*(\d+)\s*,\s*"mtime"\s*:\s*(\d+)\s*\}"""
    )
    for (m in itemRe.findAll(body)) {
      items.add(DepsItem(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[3].toLong()))
    }
    return items
  }

  private fun multipartUpload(
    baseUrl: String,
    apiKey: String,
    insecureTls: Boolean,
    path: String,
    fields: Map<String, String>,
    fileFieldName: String,
    fileName: String,
    fileBytes: ByteArray
  ): HttpResult {
    return try {
      val boundary = "----FormBoundary${UUID.randomUUID()}"
      val url = URL("${baseUrl.trimEnd('/')}$path")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 60_000
      conn.readTimeout = 600_000  // big archives may take a while
      conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")

      conn.outputStream.use { os ->
        val writer = OutputStreamWriter(os, StandardCharsets.UTF_8)
        for ((name, value) in fields) {
          writer.write("--$boundary\r\n")
          writer.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
          writer.write(value); writer.write("\r\n"); writer.flush()
        }
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"$fileFieldName\"; filename=\"$fileName\"\r\n")
        writer.write("Content-Type: application/octet-stream\r\n\r\n")
        writer.flush()
        os.write(fileBytes); os.flush()
        writer.write("\r\n--$boundary--\r\n"); writer.flush()
      }
      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  /** Dome side: post the encrypted manifest. */
  fun depsRequest(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean,
    encryptedManifest: ByteArray
  ): DepsUploadResult {
    val res = multipartUpload(
      baseUrl, apiKey, insecureTls, "/api/deps/request",
      fields = mapOf("repo" to repo),
      fileFieldName = "attachment",
      fileName = "manifest.bin",
      fileBytes = encryptedManifest
    )
    if (res.code !in 200..299) return DepsUploadResult(res.code, null, 0, res.body.take(500))
    val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(res.body)?.groupValues?.getOrNull(1)
    val size = Regex(""""size"\s*:\s*(\d+)""").find(res.body)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
    return DepsUploadResult(res.code, id, size, "OK")
  }

  /** Work side: list pending requests. */
  fun depsPending(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean
  ): DepsListResult = depsList(baseUrl, apiKey, repo, insecureTls, path = "/api/deps/pending")

  /** Dome side: list ready responses. */
  fun depsResponses(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean
  ): DepsListResult = depsList(baseUrl, apiKey, repo, insecureTls, path = "/api/deps/responses")

  private fun depsList(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean, path: String
  ): DepsListResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}$path?repo=${java.net.URLEncoder.encode(repo, "UTF-8")}")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) return DepsListResult(code, emptyList(), body.take(500))
      DepsListResult(code, parseDepsList(body), "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      DepsListResult(0, emptyList(), "${e.type}: ${e.message}")
    }
  }

  /** Download a manifest blob (work side) or response blob (dome side). */
  fun depsDownload(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean,
    id: String, kind: DepsKind, outFile: File,
    onProgress: ((read: Long, total: Long) -> Unit)? = null
  ): DownloadResult {
    val pathBase = if (kind == DepsKind.MANIFEST) "/api/deps/manifest" else "/api/deps/fetch"
    return try {
      val url = URL(
        "${baseUrl.trimEnd('/')}$pathBase?repo=${java.net.URLEncoder.encode(repo, "UTF-8")}" +
          "&id=${java.net.URLEncoder.encode(id, "UTF-8")}"
      )
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 60_000
      conn.readTimeout = 600_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      if (code !in 200..299) {
        return DownloadResult(code, null, HttpClient.readBody(conn).take(500))
      }
      val total = conn.contentLengthLong
      conn.inputStream.use { input ->
        outFile.outputStream().use { out ->
          val buf = ByteArray(64 * 1024)
          var read = 0L
          var lastReport = 0L
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            read += n
            if (onProgress != null && read - lastReport >= 200 * 1024) {
              onProgress(read, total)
              lastReport = read
            }
          }
          onProgress?.invoke(read, total)
        }
      }
      DownloadResult(code, outFile, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      DownloadResult(0, null, "${e.type}: ${e.message}")
    }
  }

  enum class DepsKind { MANIFEST, RESPONSE }

  /** Work side: upload encrypted archive in response to a pending manifest. */
  fun depsRespond(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean,
    requestId: String, encryptedArchive: ByteArray
  ): DepsUploadResult {
    val res = multipartUpload(
      baseUrl, apiKey, insecureTls, "/api/deps/respond",
      fields = mapOf("repo" to repo, "request_id" to requestId),
      fileFieldName = "attachment",
      fileName = "archive.bin",
      fileBytes = encryptedArchive
    )
    if (res.code !in 200..299) return DepsUploadResult(res.code, null, 0, res.body.take(500))
    val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(res.body)?.groupValues?.getOrNull(1)
    val size = Regex(""""size"\s*:\s*(\d+)""").find(res.body)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
    return DepsUploadResult(res.code, id, size, "OK")
  }

  /** Dome side: confirm response applied; server will delete it. */
  fun depsAck(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean, id: String
  ): HttpResult {
    return try {
      val url = URL(
        "${baseUrl.trimEnd('/')}/api/deps/ack?repo=${java.net.URLEncoder.encode(repo, "UTF-8")}" +
          "&id=${java.net.URLEncoder.encode(id, "UTF-8")}"
      )
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "DELETE"
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }
}
