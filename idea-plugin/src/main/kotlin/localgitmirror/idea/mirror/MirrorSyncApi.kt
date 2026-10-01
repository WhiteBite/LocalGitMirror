package localgitmirror.idea.mirror

import java.io.File
import java.io.OutputStreamWriter
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64 as JavaBase64
import java.util.UUID

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import localgitmirror.idea.net.HttpClient
import localgitmirror.idea.workkit.HybridCrypto

object MirrorSyncApi {
  data class RefInfo(
    val sha: String,
    val updated: String,   // ISO-8601 committer date, "" if old server
    val isHead: Boolean
  )
  data class RefsResult(val code: Int, val message: String, val head: String?, val refs: Map<String, RefInfo>?)

  fun getRefs(
    baseUrl: String,
    apiKey: String,
    repo: String,
    syncPassword: String,
    insecureTls: Boolean
  ): RefsResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/documents/list")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 15_000
      conn.readTimeout = 15_000
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }

      val codec = MirrorCrypto.beginCall(syncPassword)
      val e = codec.sealEnvelope(buildJsonObject { put("repo", repo) })
      conn.outputStream.use { os ->
        os.write(MirrorCrypto.envelopeBody(codec, e).toByteArray(StandardCharsets.UTF_8))
      }

      val code = conn.responseCode
      val body = HttpClient.readBody(conn)

      if (code in 200..299) {
        val outer = Json.parseToJsonElement(body).jsonObject
        val eField = outer["e"]?.jsonPrimitive?.contentOrNull
          ?: return RefsResult(code, "Missing envelope in response", null, null)
        val inner = codec.openEnvelopeJson(eField)

        val head = inner["head"]?.jsonPrimitive?.contentOrNull
        val refsMap = mutableMapOf<String, RefInfo>()
        val refsEl = inner["refs"]
        if (refsEl != null && refsEl != JsonNull) {
          for ((branch, el) in refsEl.jsonObject.entries) {
            val o = el.jsonObject
            refsMap[branch] = RefInfo(
              sha = o["sha"]?.jsonPrimitive?.contentOrNull ?: "",
              updated = o["updated"]?.jsonPrimitive?.contentOrNull ?: "",
              isHead = o["is_head"]?.jsonPrimitive?.booleanOrNull
                ?: (o["sha"]?.jsonPrimitive?.contentOrNull == head)
            )
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
    syncPassword: String,
    insecureTls: Boolean,
    projectDir: File? = null,
    localBranches: List<String> = emptyList()
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

        val pub = MirrorCrypto.pinnedServerPub()
        // Hybrid dump layout: 0x03 || ephemeralPub[32] || sealed(nonce+ct); peek the 33-byte header.
        val header = dumpFile.inputStream().use { ins ->
          val b = ByteArray(33); val r = ins.read(b); if (r >= 33) b else ByteArray(0)
        }
        val hybrid = pub != null && header.size == 33 && header[0] == 0x03.toByte()
        val bundleEpk =
          if (hybrid) JavaBase64.getUrlEncoder().withoutPadding().encodeToString(header.copyOfRange(1, 33))
          else null
        val codec = MirrorCrypto.Codec(if (hybrid) HybridCrypto.Session.create(pub!!) else null, syncPassword)

        // Envelope the repo name + local branches — hides them from DLP
        val envPayload = buildJsonObject {
          put("repo", repo)
          if (localBranches.isNotEmpty()) {
            put("local_branches", buildJsonArray { localBranches.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
          }
        }
        val e = codec.sealEnvelope(envPayload)
        partHeader("e")
        writer.write(e)
        writer.write("\r\n")
        writer.flush()

        // v3: envelope ephemeral ("k") and bundle ephemeral ("kb").
        codec.epkB64?.let { epk ->
          partHeader("k")
          writer.write(epk)
          writer.write("\r\n")
          writer.flush()
        }
        bundleEpk?.let { kb ->
          partHeader("kb")
          writer.write(kb)
          writer.write("\r\n")
          writer.flush()
        }

        partHeader("attachment", "data.bin", "application/octet-stream")
        if (hybrid) {
          // Stream only the sealed bytes, skipping the 33-byte header.
          dumpFile.inputStream().use { it.skip(33); it.copyTo(os) }
        } else {
          dumpFile.inputStream().use { it.copyTo(os) }
        }
        writer.write("\r\n")
        writer.flush()

        writer.write("--$boundary--\r\n")
        writer.flush()
        codec.wipe()
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
      val url = URL("${baseUrl.trimEnd('/')}/api/documents/collection")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }

      val payload = "{\"rid\":\"${MirrorTransport.rid(repo)}\"}"
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
    syncPassword: String,
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

      val params = buildJsonObject {
        put("repo", repo)
        put("commits", buildJsonArray { commits.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
      }
      val codec = MirrorCrypto.beginCall(syncPassword)
      val e = codec.sealEnvelope(params)
      conn.outputStream.use { os ->
        os.write(MirrorCrypto.envelopeBody(codec, e).toByteArray(StandardCharsets.UTF_8))
      }

      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code in 200..299) {
        val outer = Json.parseToJsonElement(body).jsonObject
        val eField = outer["e"]?.jsonPrimitive?.contentOrNull ?: return HttpResult(code, body)
        val inner = codec.openEnvelopeStr(eField)
        HttpResult(code, inner)
      } else {
        HttpResult(code, body)
      }
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
    syncPassword: String,
    insecureTls: Boolean,
    localBranches: List<String> = emptyList()
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

      val params = buildJsonObject {
        put("repo", repo)
        put("commit", commit)
        if (branches.isNotEmpty()) {
          put("branches", buildJsonObject {
            branches.forEach { (k, v) -> put(k, v) }
          })
        }
        if (localBranches.isNotEmpty()) {
          put("local_branches", buildJsonArray { localBranches.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
      }
      val codec = MirrorCrypto.beginCall(syncPassword)
      val e = codec.sealEnvelope(params)
      conn.outputStream.use { os ->
        os.write(MirrorCrypto.envelopeBody(codec, e).toByteArray(StandardCharsets.UTF_8))
      }

      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code in 200..299) {
        val outer = Json.parseToJsonElement(body).jsonObject
        val eField = outer["e"]?.jsonPrimitive?.contentOrNull ?: return HttpResult(code, body)
        val inner = codec.openEnvelopeStr(eField)
        HttpResult(code, inner)
      } else {
        HttpResult(code, body)
      }
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
    syncPassword: String,
    insecureTls: Boolean,
    outFile: File,
    /** Bundle ONLY this branch on the server (instead of --all). */
    branch: String? = null,
    /** Commit hashes the client already has; server excludes them to send only the delta. */
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

      // All params in a single encrypted envelope — nothing readable by DLP
      val params = buildJsonObject {
        put("repo", repo)
        if (!since.isNullOrBlank()) put("since", since)
        if (!branch.isNullOrBlank()) put("branch", branch)
        if (haves.isNotEmpty()) put("haves", haves.joinToString(","))
      }
      val codec = MirrorCrypto.beginCall(syncPassword)
      val e = codec.sealEnvelope(params)

      conn.outputStream.use { os ->
        val writer = OutputStreamWriter(os, StandardCharsets.UTF_8)
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"e\"\r\n\r\n")
        writer.write(e)
        writer.write("\r\n")
        codec.epkB64?.let { epk ->
          writer.write("--$boundary\r\n")
          writer.write("Content-Disposition: form-data; name=\"k\"\r\n\r\n")
          writer.write(epk)
          writer.write("\r\n")
        }
        writer.write("--$boundary--\r\n")
        writer.flush()
      }

      val code = conn.responseCode
      if (code !in 200..299) {
        val body = HttpClient.readBody(conn)
        return DownloadResult(code, null, body.take(500))
      }

      // Response: {"e": "<encrypted {status,head,repo}>", "d": "<bundle base64>"}
      val body = HttpClient.readBodyWithProgress(conn, onProgress)
      val outer = Json.parseToJsonElement(body).jsonObject

      val eField = outer["e"]?.jsonPrimitive?.contentOrNull
        ?: return DownloadResult(500, null, "Missing envelope in response")
      val inner = codec.openEnvelopeJson(eField)

      val status = inner["status"]?.jsonPrimitive?.contentOrNull ?: ""
      val head = inner["head"]?.jsonPrimitive?.contentOrNull
      val hdrRepo = inner["repo"]?.jsonPrimitive?.contentOrNull

      if (status == "no_content") {
        return DownloadResult(204, null, "No new commits", head = head, repo = hdrRepo)
      }

      val b64data = outer["d"]?.jsonPrimitive?.contentOrNull
        ?: return DownloadResult(500, null, "Missing bundle data in response", head = head, repo = hdrRepo)

      // Hybrid yields the raw bundle; password mode passes the dump through for BundleImporter to decrypt.
      outFile.writeBytes(codec.bundleToDisk(JavaBase64.getDecoder().decode(b64data)))
      codec.wipe()
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
    syncPassword: String,
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

      val params = buildJsonObject {
        put("repo", repo)
        if (!since.isNullOrBlank()) put("since", since)
      }
      val codec = MirrorCrypto.beginCall(syncPassword)
      val e = codec.sealEnvelope(params)
      conn.outputStream.use { os ->
        os.write(MirrorCrypto.envelopeBody(codec, e).toByteArray(StandardCharsets.UTF_8))
      }

      val code = conn.responseCode
      val body = HttpClient.readBody(conn)

      if (code !in 200..299) {
        return PreviewPullResult(code, null, false, "error", body.take(500))
      }

      val outer = Json.parseToJsonElement(body).jsonObject
      val eField = outer["e"]?.jsonPrimitive?.contentOrNull
        ?: return PreviewPullResult(code, null, false, "error", "Missing envelope")
      val inner = codec.openEnvelopeJson(eField)

      val remoteHead = inner["remoteHead"]?.jsonPrimitive?.contentOrNull
      val hasUpdates = inner["hasUpdates"]?.jsonPrimitive?.booleanOrNull ?: false
      val reason = inner["reason"]?.jsonPrimitive?.contentOrNull ?: ""
      PreviewPullResult(code, remoteHead, hasUpdates, reason, "OK")
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
    syncPassword: String,
    insecureTls: Boolean,
    branch: String? = null
  ): PreviewPullDetailsResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/documents/preview-details")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 30_000
      conn.readTimeout = 60_000
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) {
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
      }

      val params = buildJsonObject {
        put("repo", repo)
        if (!since.isNullOrBlank()) put("since", since)
        if (!branch.isNullOrBlank()) put("branch", branch)
      }
      val codec = MirrorCrypto.beginCall(syncPassword)
      val e = codec.sealEnvelope(params)
      conn.outputStream.use { os ->
        os.write(MirrorCrypto.envelopeBody(codec, e).toByteArray(StandardCharsets.UTF_8))
      }

      val code = conn.responseCode
      val body = HttpClient.readBody(conn)

      if (code !in 200..299) {
        return PreviewPullDetailsResult(code, emptyList(), "", body.take(500))
      }

      val outer = Json.parseToJsonElement(body).jsonObject
      val eField = outer["e"]?.jsonPrimitive?.contentOrNull
        ?: return PreviewPullDetailsResult(code, emptyList(), "", "Missing envelope")

      // Parse the commits array via regex from the decrypted JSON string.
      val decryptedBody = codec.openEnvelopeStr(eField)
      val commits = mutableListOf<CommitInfo>()
      val commitRegex = Regex(""""hash"\s*:\s*"([^"]+)"\s*,\s*"message"\s*:\s*"([^"]*)"""")
      commitRegex.findAll(decryptedBody).forEach {
        commits.add(CommitInfo(it.groupValues[1], it.groupValues[2]))
      }

      val inner = Json.parseToJsonElement(decryptedBody).jsonObject
      val diffstat = inner["diffstat"]?.jsonPrimitive?.contentOrNull ?: ""

      PreviewPullDetailsResult(code, commits, diffstat, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      PreviewPullDetailsResult(0, emptyList(), "", "${e.type}: ${e.message}")
    }
  }

  /** Delete a branch on the Mirror server. Requires explicit user confirmation in the caller. */
  fun deleteRef(baseUrl: String, apiKey: String, repo: String, branch: String, syncPassword: String, insecureTls: Boolean): HttpResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/documents/delete-ref")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")

      val params = buildJsonObject { put("repo", repo); put("branch", branch) }
      val codec = MirrorCrypto.beginCall(syncPassword)
      val e = codec.sealEnvelope(params)
      conn.outputStream.use { os ->
        os.write(MirrorCrypto.envelopeBody(codec, e).toByteArray(StandardCharsets.UTF_8))
      }

      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) return HttpResult(code, body)

      val outer = Json.parseToJsonElement(body).jsonObject
      val eField = outer["e"]?.jsonPrimitive?.contentOrNull ?: return HttpResult(code, body)
      val inner = codec.openEnvelopeStr(eField)
      codec.wipe()
      HttpResult(code, inner)
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  /**
   * Result of a prune-branches call. With apply=false only [candidates] is
   * filled (dry-run); with apply=true [pruned] lists the branches the server
   * actually deleted and [protected] the ones it refused to touch (HEAD, last
   * branch, keep-list).
   */
  data class PruneResult(
    val code: Int,
    val success: Boolean,
    val candidates: List<String>,
    val pruned: List<String>,
    val `protected`: List<String>,
    val message: String
  )

  /**
   * Ask the server which branches are already merged into [bases] (and thus
   * prunable). With apply=false the server only computes the candidate list;
   * with apply=true it deletes them.
   */
  fun pruneBranches(
    baseUrl: String,
    apiKey: String,
    repo: String,
    bases: List<String>,
    olderDays: Int,
    keep: List<String>,
    apply: Boolean,
    insecureTls: Boolean,
    syncPassword: String
  ): PruneResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/documents/prune-branches")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 30_000
      conn.readTimeout = 60_000
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")

      val params = buildJsonObject {
        put("repo", repo)
        put("bases", buildJsonArray { bases.forEach { add(JsonPrimitive(it)) } })
        put("older_days", olderDays)
        put("keep", buildJsonArray { keep.forEach { add(JsonPrimitive(it)) } })
        put("apply", apply)
      }
      val codec = MirrorCrypto.beginCall(syncPassword)
      val e = codec.sealEnvelope(params)
      conn.outputStream.use { os ->
        os.write(MirrorCrypto.envelopeBody(codec, e).toByteArray(StandardCharsets.UTF_8))
      }

      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) {
        return PruneResult(code, false, emptyList(), emptyList(), emptyList(), body.take(500))
      }

      val outer = Json.parseToJsonElement(body).jsonObject
      val eField = outer["e"]?.jsonPrimitive?.contentOrNull
        ?: return PruneResult(code, false, emptyList(), emptyList(), emptyList(), "Missing envelope in response")
      val inner = codec.openEnvelopeJson(eField)
      codec.wipe()

      PruneResult(
        code = code,
        success = inner["success"]?.jsonPrimitive?.booleanOrNull ?: false,
        candidates = MirrorTransport.stringList(inner, "candidates"),
        pruned = MirrorTransport.stringList(inner, "pruned"),
        `protected` = MirrorTransport.stringList(inner, "protected"),
        message = inner["message"]?.jsonPrimitive?.contentOrNull ?: ""
      )
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      PruneResult(0, false, emptyList(), emptyList(), emptyList(), "${e.type}: ${e.message}")
    }
  }
}
