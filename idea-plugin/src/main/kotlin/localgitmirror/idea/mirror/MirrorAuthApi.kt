package localgitmirror.idea.mirror

import java.net.URL

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.net.HttpClient
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.workkit.HybridCrypto
import com.intellij.openapi.components.service

object MirrorAuthApi {
  data class CapabilitiesResult(
    val code: Int,
    val body: String,
    val apiVersion: Int?,
    val protocolVersion: Int?,
    val preflight: Boolean,
    val dryRun: Boolean,
    val passwordProbe: Boolean,
    // v3: server has a hybrid key — pinned clients skip the password probe.
    val v3Key: Boolean = false,
  )

  data class ProbeResult(
    val code: Int,
    val bytes: ByteArray?,
    val message: String
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

      // TOFU: pin the server's v3 key on the first healthy connect; activates hybrid mode.
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
}
