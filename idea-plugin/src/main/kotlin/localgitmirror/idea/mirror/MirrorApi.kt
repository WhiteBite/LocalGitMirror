package localgitmirror.idea.mirror

import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64 as JavaBase64
import java.util.UUID

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import localgitmirror.idea.net.HttpClient
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.workkit.EnvelopeCrypto
import localgitmirror.idea.workkit.HybridCrypto
import com.intellij.openapi.components.service

object MirrorApi {
  data class HttpResult(val code: Int, val body: String)

  // ─────────────────────── protocol v3 (hybrid ECIES) ───────────────────────
  //
  // When a server public key is pinned (MirrorSettingsService.serverPubKeyB64),
  // every call uses an ephemeral X25519 key to seal the envelope (and, on
  // upload, the bundle) to that key — no SYNC_PASSWORD is needed and recorded
  // traffic stays secret even if this machine is later fully read. When no key
  // is pinned, everything transparently falls back to the legacy password
  // envelope, so old setups keep working unchanged.

  /** Pinned server public key (32 raw bytes), or null => legacy password mode. */
  private fun pinnedServerPub(): ByteArray? = try {
    val b64 = service<MirrorSettingsService>().state.serverPubKeyB64
    if (b64.isBlank()) null else HybridCrypto.decodeServerPub(b64)
  } catch (_: Throwable) {
    null
  }

  /** True when a server public key is pinned — i.e. v3 mode is active. */
  fun isV3Pinned(): Boolean = pinnedServerPub() != null

  /** Per-call codec: hybrid session when pinned, else password envelope. */
  private class Codec(val session: HybridCrypto.Session?, val password: String) {
    val epkB64: String? get() = session?.epkB64

    fun sealEnvelope(obj: JsonObject): String =
      session?.sealEnvelope(obj.toString()) ?: EnvelopeCrypto.encryptJson(obj, password)

    fun openEnvelopeStr(e: String): String =
      session?.openEnvelope(e) ?: EnvelopeCrypto.decrypt(e, password)

    fun openEnvelopeJson(e: String): JsonObject =
      session?.let { Json.parseToJsonElement(it.openEnvelope(e)).jsonObject }
        ?: EnvelopeCrypto.decryptJson(e, password)

    /**
     * Turn the response "d" field into the bytes to write to disk.
     * Hybrid: open the sealed bundle → raw git bundle (plaintext). Password:
     * pass the encrypted dump through unchanged (downstream decrypts it).
     */
    fun bundleToDisk(rawD: ByteArray): ByteArray = session?.openBundle(rawD) ?: rawD

    fun wipe() = session?.wipe()
  }

  private fun beginCall(password: String): Codec {
    val pub = pinnedServerPub()
    return Codec(if (pub != null) HybridCrypto.Session.create(pub) else null, password)
  }

  /** JSON request body: {"epk":"..","e":".."} in hybrid mode, else {"e":".."}. */
  private fun envelopeBody(codec: Codec, e: String): String {
    val epk = codec.epkB64
    return if (epk != null) "{\"epk\":\"$epk\",\"e\":\"$e\"}" else "{\"e\":\"$e\"}"
  }

  data class CapabilitiesResult(
    val code: Int,
    val body: String,
    val apiVersion: Int?,
    val protocolVersion: Int?,
    val preflight: Boolean,
    val dryRun: Boolean,
    val passwordProbe: Boolean,
    // v3: server has a hybrid X25519 key — clients that have pinned it can skip
    // the password probe entirely.
    val v3Key: Boolean = false,
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
      val url = URL(MirrorConnectionContract.authenticatedHealthUrl(baseUrl))
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

      // On a healthy connection, pin the server's v3 public key if not yet
      // pinned (TOFU). This is what activates hybrid mode for subsequent sync.
      if (code in 200..299) {
        runCatching { ensureServerKeyPinned(baseUrl, apiKey, insecureTls) }
      }

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
        passwordProbe = boolField("passwordProbe"),
        v3Key = boolField("v3"),
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

  data class PubKeyResult(val code: Int, val pubB64: String?, val fp: String?, val message: String)

  /** Fetch the server's long-term X25519 public key (protocol v3, GET /api/auth/pubkey). */
  fun fetchServerPubKey(baseUrl: String, apiKey: String, insecureTls: Boolean): PubKeyResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/auth/pubkey")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 15_000
      conn.readTimeout = 15_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) return PubKeyResult(code, null, null, body.take(300))
      val obj = Json.parseToJsonElement(body).jsonObject
      val pub = obj["pub"]?.jsonPrimitive?.contentOrNull
      val fp = obj["fp"]?.jsonPrimitive?.contentOrNull
      PubKeyResult(code, pub, fp, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      PubKeyResult(0, null, null, "${e.type}: ${e.message}")
    }
  }

  /**
   * Trust-on-first-use pinning: when no server key is pinned yet, fetch and
   * store it, switching all subsequent sync to protocol v3 (hybrid). Returns
   * the pinned fingerprint so the caller can surface it for out-of-band
   * verification against the value printed in the server console. Returns null
   * when a key is already pinned or on any failure (stays on password mode).
   *
   * SECURITY: this is TOFU (like SSH known_hosts). An active MITM present on the
   * very first connect could pin its own key; verify the fingerprint once.
   */
  fun ensureServerKeyPinned(baseUrl: String, apiKey: String, insecureTls: Boolean): String? {
    return try {
      val state = service<MirrorSettingsService>().state
      if (state.serverPubKeyB64.isNotBlank()) return null
      val res = fetchServerPubKey(baseUrl, apiKey, insecureTls)
      val pub = res.pubB64 ?: return null
      HybridCrypto.decodeServerPub(pub) // validate 32-byte key before trusting
      state.serverPubKeyB64 = pub
      state.serverPubKeyFp = res.fp ?: ""
      res.fp
    } catch (_: Throwable) {
      null
    }
  }

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

      val codec = beginCall(syncPassword)
      val e = codec.sealEnvelope(buildJsonObject { put("repo", repo) })
      conn.outputStream.use { os ->
        os.write(envelopeBody(codec, e).toByteArray(StandardCharsets.UTF_8))
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

        val pub = pinnedServerPub()
        // Hybrid dump layout: 0x03 || ephemeralPub[32] || sealed(nonce+ct).
        // Peek the 33-byte header to decide mode and recover the bundle ephemeral.
        val header = dumpFile.inputStream().use { ins ->
          val b = ByteArray(33); val r = ins.read(b); if (r >= 33) b else ByteArray(0)
        }
        val hybrid = pub != null && header.size == 33 && header[0] == 0x03.toByte()
        val bundleEpk =
          if (hybrid) JavaBase64.getUrlEncoder().withoutPadding().encodeToString(header.copyOfRange(1, 33))
          else null
        val codec = Codec(if (hybrid) HybridCrypto.Session.create(pub!!) else null, syncPassword)

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

        partHeader("attachment", "document.bin", "application/octet-stream")
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
      val codec = beginCall(syncPassword)
      val e = codec.sealEnvelope(params)
      conn.outputStream.use { os ->
        os.write(envelopeBody(codec, e).toByteArray(StandardCharsets.UTF_8))
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
      val codec = beginCall(syncPassword)
      val e = codec.sealEnvelope(params)
      conn.outputStream.use { os ->
        os.write(envelopeBody(codec, e).toByteArray(StandardCharsets.UTF_8))
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
      val codec = beginCall(syncPassword)
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

      // Hybrid: openBundle yields the raw git bundle (plaintext). Password mode:
      // bundleToDisk passes the encrypted dump through; BundleImporter decrypts.
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
      val codec = beginCall(syncPassword)
      val e = codec.sealEnvelope(params)
      conn.outputStream.use { os ->
        os.write(envelopeBody(codec, e).toByteArray(StandardCharsets.UTF_8))
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
      val codec = beginCall(syncPassword)
      val e = codec.sealEnvelope(params)
      conn.outputStream.use { os ->
        os.write(envelopeBody(codec, e).toByteArray(StandardCharsets.UTF_8))
      }

      val code = conn.responseCode
      val body = HttpClient.readBody(conn)

      if (code !in 200..299) {
        return PreviewPullDetailsResult(code, emptyList(), "", body.take(500))
      }

      val outer = Json.parseToJsonElement(body).jsonObject
      val eField = outer["e"]?.jsonPrimitive?.contentOrNull
        ?: return PreviewPullDetailsResult(code, emptyList(), "", "Missing envelope")

      // Decrypt to plain JSON string, then parse commits array using regex
      // (avoids complex JsonArray navigation — same approach as the original code)
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

  // ───────────────────────────────────────────────────────────────────────
  // /api/deps/* — Gradle dependency sync (encrypted blob postbox)
  // ───────────────────────────────────────────────────────────────────────

data class DepsItem(val id: String, val size: Long, val mtime: Long)
data class DepsListResult(val code: Int, val items: List<DepsItem>, val message: String)
data class DepsUploadResult(val code: Int, val id: String?, val size: Long, val message: String)
data class MirrorPublishResult(val code: Int, val added: Int, val existed: Int, val conflicts: Int, val rejected: Int, val message: String)
  data class FileSyncItem(val id: String, val path: String, val size: Long, val plainSize: Long, val mtime: Long)
  data class FileSyncListResult(val code: Int, val items: List<FileSyncItem>, val message: String)
  data class FileSyncUploadResult(val code: Int, val id: String?, val path: String?, val size: Long, val message: String)

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

  private fun multipartUploadFile(
    baseUrl: String,
    apiKey: String,
    insecureTls: Boolean,
    path: String,
    fields: Map<String, String>,
    fileFieldName: String,
    fileName: String,
    file: File,
    onProgress: ((sent: Long, total: Long) -> Unit)? = null
  ): HttpResult {
    return try {
      val boundary = "----FormBoundary${UUID.randomUUID()}"
      val url = URL("${baseUrl.trimEnd('/')}$path")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 60_000
      conn.readTimeout = 600_000
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
        file.inputStream().use { input ->
          val buf = ByteArray(1024 * 1024)
          val total = file.length()
          var sent = 0L
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            os.write(buf, 0, n)
            sent += n
            onProgress?.invoke(sent, total)
          }
        }
        os.flush()
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

  /** Fetch vault inventory for delta-sync. */
  fun mirrorIndex(
    baseUrl: String, apiKey: String, insecureTls: Boolean
  ): HttpResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/deps/mirror/index")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  /** Fetch vault diagnostics status. */
  fun mirrorStatus(
    baseUrl: String, apiKey: String, insecureTls: Boolean
  ): HttpResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/deps/mirror/status")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  /** Upload encrypted publication to vault. */
  fun mirrorPublish(
    baseUrl: String, apiKey: String, insecureTls: Boolean,
    encryptedPublication: ByteArray
  ): MirrorPublishResult {
    val res = multipartUpload(
      baseUrl, apiKey, insecureTls, "/api/deps/mirror/publish",
      fields = emptyMap(),
      fileFieldName = "attachment",
      fileName = "publication.enc",
      fileBytes = encryptedPublication
    )
    if (res.code !in 200..299) return MirrorPublishResult(res.code, 0, 0, 0, 0, res.body.take(500))
    val body = res.body
    val added = Regex(""""added"\s*:\s*(\d+)""").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val existed = Regex(""""existed"\s*:\s*(\d+)""").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val conflicts = Regex(""""conflicts"\s*:\s*(\d+)""").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val rejected = Regex(""""rejected"\s*:\s*(\d+)""").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    return MirrorPublishResult(res.code, added, existed, conflicts, rejected, "OK")
  }

  // ───────────────────────────────────────────────────────────────────────
  // /api/file-sync/* — repo file postbox (opaque encrypted containers)
  // ───────────────────────────────────────────────────────────────────────

  fun fileSyncUpload(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean,
    relativePath: String, plainSize: Long, encryptedFile: File,
    onProgress: ((sent: Long, total: Long) -> Unit)? = null
  ): FileSyncUploadResult {
    val res = multipartUploadFile(
      baseUrl, apiKey, insecureTls, "/api/file-sync/upload",
      fields = mapOf("repo" to repo, "path" to relativePath, "plain_size" to plainSize.toString()),
      fileFieldName = "attachment",
      fileName = "file.lgm",
      file = encryptedFile,
      onProgress = onProgress
    )
    if (res.code !in 200..299) return FileSyncUploadResult(res.code, null, null, 0, res.body.take(500))
    val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(res.body)?.groupValues?.getOrNull(1)
    val pathValue = Regex(""""path"\s*:\s*"([^"]+)"""").find(res.body)?.groupValues?.getOrNull(1)
    val size = Regex(""""size"\s*:\s*(\d+)""").find(res.body)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0
    return FileSyncUploadResult(res.code, id, pathValue, size, "OK")
  }

  fun fileSyncList(baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean): FileSyncListResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/file-sync/list?repo=${java.net.URLEncoder.encode(repo, "UTF-8")}")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 30_000
      conn.readTimeout = 30_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) return FileSyncListResult(code, emptyList(), body.take(500))
      val root = Json.parseToJsonElement(body).jsonObject
      val items = root["items"]?.jsonArray?.mapNotNull { el ->
        val o = el.jsonObject
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val p = o["path"]?.jsonPrimitive?.contentOrNull ?: ""
        val size = o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val plain = o["plain_size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val mtime = o["mtime"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        FileSyncItem(id, p, size, plain, mtime)
      } ?: emptyList()
      FileSyncListResult(code, items, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      FileSyncListResult(0, emptyList(), "${e.type}: ${e.message}")
    }
  }

  fun fileSyncDownload(
    baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean,
    id: String, outFile: File, onProgress: ((read: Long, total: Long) -> Unit)? = null
  ): DownloadResult {
    return try {
      val url = URL(
        "${baseUrl.trimEnd('/')}/api/file-sync/download?repo=${java.net.URLEncoder.encode(repo, "UTF-8")}" +
          "&id=${java.net.URLEncoder.encode(id, "UTF-8")}" 
      )
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 60_000
      conn.readTimeout = 600_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      if (code !in 200..299) return DownloadResult(code, null, HttpClient.readBody(conn).take(500))
      val total = conn.contentLengthLong
      conn.inputStream.use { input ->
        outFile.outputStream().use { out ->
          val buf = ByteArray(1024 * 1024)
          var read = 0L
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            read += n
            onProgress?.invoke(read, total)
          }
        }
      }
      DownloadResult(code, outFile, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      DownloadResult(0, null, "${e.type}: ${e.message}")
    }
  }

  fun fileSyncAck(baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean, id: String): HttpResult {
    return try {
      val url = URL(
        "${baseUrl.trimEnd('/')}/api/file-sync/ack?repo=${java.net.URLEncoder.encode(repo, "UTF-8")}" +
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
      val e = EnvelopeCrypto.encryptJson(params, syncPassword)
      conn.outputStream.use { it.write("{\"e\":\"$e\"}".toByteArray(StandardCharsets.UTF_8)) }
      HttpResult(conn.responseCode, HttpClient.readBody(conn))
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      HttpResult(0, "${e.type}: ${e.message}")
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // /api/plugin/* — fetch the latest IDEA plugin .zip built by the server
  // ───────────────────────────────────────────────────────────────────────

  data class PluginInfo(
    val code: Int,
    val available: Boolean,
    val version: String?,
    val filename: String?,
    val size: Long,
    val builtAt: String?,
    val message: String,
    /** SHA-256 of the archive for download integrity verification (null on old servers). */
    val sha256: String? = null
  )

  /** Query metadata of the freshest plugin build on the server (auth-gated). */
  fun pluginInfo(baseUrl: String, apiKey: String, insecureTls: Boolean): PluginInfo {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/plugin/info")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 15_000
      conn.readTimeout = 15_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) {
        return PluginInfo(code, false, null, null, 0L, null, body.take(500))
      }
      val root = Json.parseToJsonElement(body).jsonObject
      PluginInfo(
        code = code,
        available = root["available"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true,
        version = root["version"]?.jsonPrimitive?.contentOrNull,
        filename = root["filename"]?.jsonPrimitive?.contentOrNull,
        size = root["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
        builtAt = root["built_at"]?.jsonPrimitive?.contentOrNull,
        message = "OK",
        sha256 = root["sha256"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length == 64 }
      )
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      PluginInfo(0, false, null, null, 0L, null, "${e.type}: ${e.message}")
    }
  }

  /**
   * Stream the newest plugin .zip into [outFile]. Reports progress in the same
   * shape as [exportDump]/[depsDownload] so the caller can drive a progress bar.
   */
  fun pluginDownload(
    baseUrl: String,
    apiKey: String,
    insecureTls: Boolean,
    outFile: File,
    onProgress: ((read: Long, total: Long) -> Unit)? = null
  ): DownloadResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/plugin/latest")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 30_000
      conn.readTimeout = 300_000
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

  // ───────────────────────────────────────────────────────────────────────
  // /api/buffer/* — cross-machine clipboard (E2E-encrypted with SYNC_PASSWORD)
  // ───────────────────────────────────────────────────────────────────────

  data class BufferItem(val id: String, val ts: Double, val size: Long, val hint: String)
  data class BufferListResult(val code: Int, val items: List<BufferItem>, val message: String)
  data class BufferPutResult(val code: Int, val id: String?, val ts: Double, val message: String)

  /**
   * Push a ciphertext blob into the server's clipboard. The body is
   * base64-wrapped JSON so the same code path works on every IDEA we support.
   */
  fun bufferPut(
    baseUrl: String,
    apiKey: String,
    insecureTls: Boolean,
    ciphertext: ByteArray,
    hint: String
  ): BufferPutResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/buffer")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 30_000
      conn.readTimeout = 60_000
      conn.setRequestProperty("Content-Type", "application/json")
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")

      val b64 = JavaBase64.getEncoder().encodeToString(ciphertext)
      val safeHint = hint.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").take(120)
      val payload = """{"ciphertext_b64":"$b64","hint":"$safeHint"}"""
      conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }

      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) return BufferPutResult(code, null, 0.0, body.take(500))
      val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.getOrNull(1)
      val ts = Regex(""""ts"\s*:\s*([0-9.]+)""").find(body)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
      BufferPutResult(code, id, ts, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      BufferPutResult(0, null, 0.0, "${e.type}: ${e.message}")
    }
  }

  /** Fetch metadata for the latest entries (newest first). */
  fun bufferList(baseUrl: String, apiKey: String, insecureTls: Boolean): BufferListResult {
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/api/buffer")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 15_000
      conn.readTimeout = 15_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      val body = HttpClient.readBody(conn)
      if (code !in 200..299) return BufferListResult(code, emptyList(), body.take(500))

      val items = mutableListOf<BufferItem>()
      val root = Json.parseToJsonElement(body).jsonObject
      val arr = root["items"]
      if (arr != null && arr is kotlinx.serialization.json.JsonArray) {
        for (el in arr) {
          val o = el.jsonObject
          items.add(
            BufferItem(
              id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue,
              ts = o["ts"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
              size = o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
              hint = o["hint"]?.jsonPrimitive?.contentOrNull ?: ""
            )
          )
        }
      }
      BufferListResult(code, items, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      BufferListResult(0, emptyList(), "${e.type}: ${e.message}")
    }
  }

  /** Fetch raw ciphertext bytes of a single entry. */
  fun bufferGet(baseUrl: String, apiKey: String, insecureTls: Boolean, id: String): DownloadResult {
    return try {
      val safeId = java.net.URLEncoder.encode(id, "UTF-8")
      val url = URL("${baseUrl.trimEnd('/')}/api/buffer/$safeId")
      val conn = HttpClient.open(url, insecureTls)
      conn.requestMethod = "GET"
      conn.connectTimeout = 30_000
      conn.readTimeout = 60_000
      if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
      val code = conn.responseCode
      if (code !in 200..299) return DownloadResult(code, null, HttpClient.readBody(conn).take(500))
      val bytes = conn.inputStream.use { it.readBytes() }
      val tmp = File.createTempFile("lgm-buf-", ".bin").apply { writeBytes(bytes); deleteOnExit() }
      DownloadResult(code, tmp, "OK")
    } catch (t: Throwable) {
      val e = HttpClient.classifyError(t)
      DownloadResult(0, null, "${e.type}: ${e.message}")
    }
  }
}
