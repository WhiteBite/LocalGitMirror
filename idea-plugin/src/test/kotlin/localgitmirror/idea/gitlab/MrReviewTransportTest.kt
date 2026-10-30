package localgitmirror.idea.gitlab

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.mirror.MirrorCrypto
import localgitmirror.idea.mirror.MirrorPostboxApi
import localgitmirror.idea.workkit.HybridCrypto
import org.junit.jupiter.api.io.TempDir

class MrReviewTransportTest {
  @TempDir lateinit var tmp: File

  private lateinit var fixtureServer: FixturePostboxServer
  private lateinit var postbox: MrReviewPostbox
  private lateinit var bundle: File
  private lateinit var ready: MrReviewSnapshotSync.Ready
  private val bundleBytes = ByteArray(2048) { (it * 13).toByte() }

  @BeforeTest
  fun up() {
    fixtureServer = FixturePostboxServer(NOW)
    fixtureServer.start()
    postbox = fixtureServer.postbox()
    bundle = File(tmp, "code.bundle")
    bundle.writeBytes(bundleBytes)
    val snapshot = MrReviewProtocol.verifySnapshot(fixtureSnapshot("snapshot_fork_mr"))
    ready = MrReviewSnapshotSync.Ready(
      manifestJson = MrReviewProtocol.canonicalJson(snapshot),
      bundle = bundle,
      snapshotId = MrReviewProtocol.snapshotDigests(snapshot).getValue("snapshot_id"),
      codeId = MrReviewProtocol.snapshotDigests(snapshot).getValue("code_id"),
      versionFingerprint = MrReviewProtocol.snapshotDigests(snapshot).getValue("version_fingerprint"),
    )
  }

  @AfterTest
  fun down() {
    fixtureServer.stop()
  }

  @Test
  fun `transfer uploads the bundle before the envelope over the v3 wire`() {
    val result = MrReviewTransport.transfer(postbox, ready, nowEpochSeconds = NOW)

    val transferred = assertIs<MrReviewTransport.Transferred>(result)
    assertEquals("uploaded", transferred.bundleAction)
    assertEquals(MrReviewProtocol.codeArtifactPath(ready.codeId), transferred.bundlePath)
    assertEquals(MrReviewProtocol.snapshotArtifactPath(ready.snapshotId), transferred.manifestPath)
    assertEquals(sha256Hex(bundleBytes), transferred.bundleSha256)
    assertEquals(bundleBytes.size.toLong(), transferred.bundleSize)
    assertEquals(
      listOf(transferred.bundlePath, transferred.manifestPath),
      fixtureServer.uploadLog,
    )
    assertEquals(setOf("rid", "k", "meta"), fixtureServer.uploadFieldKeys)
    val envelope = MrReviewProtocol.verifyTransferEnvelope(
      Json.parseToJsonElement(
        String(fixtureServer.storedBytes(transferred.manifestPath), Charsets.UTF_8)).jsonObject)
    assertEquals(ready.snapshotId, envelope.getValue("snapshot_id").jsonPrimitive.content)
    val code = envelope.getValue("code").jsonObject
    assertEquals(transferred.bundleSha256, code.getValue("bundle_sha256").jsonPrimitive.content)
    assertEquals(bundleBytes.size.toLong(), code.getValue("bundle_size").jsonPrimitive.content.toLong())
  }

  @Test
  fun `second transfer dedups the bundle and uploads metadata only`() {
    MrReviewTransport.transfer(postbox, ready, nowEpochSeconds = NOW)
    val uploadsAfterFirst = fixtureServer.uploadLog.size

    val result = MrReviewTransport.transfer(postbox, ready, nowEpochSeconds = NOW + 60)

    val transferred = assertIs<MrReviewTransport.Transferred>(result)
    assertEquals("deduplicated", transferred.bundleAction)
    assertEquals(uploadsAfterFirst + 1, fixtureServer.uploadLog.size)
    assertEquals(transferred.manifestPath, fixtureServer.uploadLog.last())
    assertEquals(1, fixtureServer.items.count { it.path == transferred.bundlePath })
  }

  @Test
  fun `an aged bundle is re-uploaded for TTL refresh before the envelope`() {
    MrReviewTransport.transfer(postbox, ready, nowEpochSeconds = NOW)
    val uploadsAfterFirst = fixtureServer.uploadLog.size
    for (item in fixtureServer.items) {
      if (item.path == MrReviewProtocol.codeArtifactPath(ready.codeId)) item.mtime = 0
    }

    val result = MrReviewTransport.transfer(
      postbox, ready, nowEpochSeconds = MrReviewTransport.TTL_REFRESH_SECONDS + 1)

    val transferred = assertIs<MrReviewTransport.Transferred>(result)
    assertEquals("ttl-refreshed", transferred.bundleAction)
    assertEquals(2, fixtureServer.items.count { it.path == transferred.bundlePath })
    assertEquals(
      listOf(transferred.bundlePath, transferred.manifestPath),
      fixtureServer.uploadLog.subList(uploadsAfterFirst, fixtureServer.uploadLog.size),
    )
  }

  @Test
  fun `size drift between transport and bundle re-uploads`() {
    MrReviewTransport.transfer(postbox, ready, nowEpochSeconds = NOW)
    for (item in fixtureServer.items) {
      if (item.path == MrReviewProtocol.codeArtifactPath(ready.codeId)) item.plainSize += 1
    }

    val result = MrReviewTransport.transfer(postbox, ready, nowEpochSeconds = NOW + 60)

    val transferred = assertIs<MrReviewTransport.Transferred>(result)
    assertEquals("uploaded", transferred.bundleAction)
    assertEquals(2, fixtureServer.items.count { it.path == transferred.bundlePath })
  }

  @Test
  fun `notes-only transfer skips without a code artifact`() {
    val result = MrReviewTransport.transfer(
      postbox, ready, nowEpochSeconds = NOW, allowCodeUpload = false)

    val skipped = assertIs<MrReviewTransport.Skipped>(result)
    assertEquals(ready.snapshotId, skipped.snapshotId)
    assertEquals(ready.codeId, skipped.codeId)
    assertTrue(skipped.limitations.isNotEmpty(), skipped.limitations.toString())
    assertTrue("not available" in skipped.limitations.first(), skipped.limitations.first())
    assertEquals(emptyList(), fixtureServer.uploadLog)
  }

  @Test
  fun `notes-only transfer refreshes metadata when the code artifact is available`() {
    MrReviewTransport.transfer(postbox, ready, nowEpochSeconds = NOW)
    val uploadsAfterFull = fixtureServer.uploadLog.size

    val result = MrReviewTransport.transfer(
      postbox, ready, nowEpochSeconds = NOW + 60, allowCodeUpload = false)

    val transferred = assertIs<MrReviewTransport.Transferred>(result)
    assertEquals("deduplicated", transferred.bundleAction)
    assertEquals(listOf(transferred.manifestPath),
      fixtureServer.uploadLog.subList(uploadsAfterFull, fixtureServer.uploadLog.size))
  }

  @Test
  fun `notes-only transfer reports an aged bundle limitation without re-upload`() {
    MrReviewTransport.transfer(postbox, ready, nowEpochSeconds = NOW)
    for (item in fixtureServer.items) {
      if (item.path == MrReviewProtocol.codeArtifactPath(ready.codeId)) item.mtime = 0
    }
    val uploadsAfterFull = fixtureServer.uploadLog.size

    val result = MrReviewTransport.transfer(
      postbox, ready, nowEpochSeconds = MrReviewTransport.TTL_REFRESH_SECONDS + 1,
      allowCodeUpload = false)

    val transferred = assertIs<MrReviewTransport.Transferred>(result)
    assertEquals("deduplicated", transferred.bundleAction)
    assertTrue(
      transferred.limitations.any { "expiry" in it },
      transferred.limitations.toString(),
    )
    assertEquals(listOf(transferred.manifestPath),
      fixtureServer.uploadLog.subList(uploadsAfterFull, fixtureServer.uploadLog.size))
  }

  @Test
  fun `oversized bundle fails as review-package-too-large`() {
    val result = MrReviewTransport.transfer(
      postbox, ready, nowEpochSeconds = NOW, maxCodeArtifactBytes = 16)

    val failed = assertIs<MrReviewTransport.TransferFailed>(result)
    assertEquals("review-package-too-large", failed.code)
    assertTrue("bytes" in failed.message, failed.message)
    assertEquals(emptyList(), fixtureServer.uploadLog)
  }

  @Test
  fun `envelope parity fixture matches the Python canonical digest`() {
    val parity = Json.parseToJsonElement(
      File(fixtureDir(), "mr_review_transfer/envelope_parity.json").readText()).jsonObject
    val params = parity.getValue("params").jsonObject
    val snapshot = fixtureSnapshot(parity.getValue("snapshot_fixture").jsonPrimitive.content)
    val envelope = MrReviewProtocol.buildTransferEnvelope(
      snapshot,
      params.getValue("bundle_sha256").jsonPrimitive.content,
      params.getValue("bundle_size").jsonPrimitive.content.toLong(),
      params.getValue("prepared_at").jsonPrimitive.content,
      params.getValue("limitations").jsonArray.map { it.jsonPrimitive.content },
    )
    assertEquals(
      parity.getValue("expected_canonical_sha256").jsonPrimitive.content,
      MrReviewProtocol.sha256Hex(MrReviewProtocol.canonicalJson(envelope)),
    )
  }

  private fun fixtureDir(): File {
    var dir: File? = File(System.getProperty("user.dir", "."))
    while (dir != null) {
      val candidate = File(dir, "tests/fixtures")
      if (candidate.isDirectory) return candidate
      dir = dir.parentFile
    }
    throw IllegalStateException("tests/fixtures not found above the working directory")
  }

  private fun fixtureSnapshot(name: String) =
    MrReviewProtocol.verifySnapshot(
      Json.parseToJsonElement(File(fixtureDir(), "mr_review_protocol/$name.json").readText())
        .jsonObject.getValue("object").jsonObject)

  private fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { b -> "%02x".format(b) }

  private companion object {
    const val NOW = 1_000_000_000L
    val INFO_RELAY_REQ = "lgm/v3/relay/req".toByteArray(Charsets.US_ASCII)
  }

  /** In-memory postbox behind a real HTTP v3 wire: multipart upload with
   * rid/k/meta, relay-sealed attachment, attachment-list inventory. */
  private class FixturePostboxServer(private val mtimeBase: Long) {
    data class StoredItem(var id: String, var path: String, var plainSize: Long, var mtime: Long)

    val items = mutableListOf<StoredItem>()
    val uploadLog = mutableListOf<String>()
    val uploadFieldKeys = mutableSetOf<String>()
    private val stored = mutableMapOf<String, ByteArray>()
    private val serverKeyPair = newX25519()
    private val serverPub = HybridCrypto.publicKeyToRaw(serverKeyPair.public as XECPublicKey)
    private lateinit var server: HttpServer
    private var nextId = 0
    private var mtimeClock = mtimeBase

    fun start() {
      server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/api/documents/attachment-upload") { ex ->
        try {
          val (fields, files) = parseMultipart(ex.requestBody.readBytes())
          uploadFieldKeys.addAll(fields.keys)
          val epk = HybridCrypto.decodeServerPub(fields.getValue("k"))
          val key = HybridCrypto.hkdf(serverShared(serverKeyPair, epk), epk, INFO_RELAY_REQ)
          val meta = Json.parseToJsonElement(
            String(openGcmAad(key, java.util.Base64.getDecoder().decode(fields.getValue("meta")),
              HybridCrypto.RELAY_AAD_POSTBOX))).jsonObject
          val path = meta.getValue("path").jsonPrimitive.content
          val plainSize = meta.getValue("plain_size").jsonPrimitive.content.toLong()
          val plain = openGcmAad(key, files.first(), HybridCrypto.RELAY_AAD_POSTBOX)
          nextId += 1
          val id = "i$nextId"
          mtimeClock += 1
          items.add(StoredItem(id, path, plainSize, mtimeClock))
          stored[id] = plain
          uploadLog.add(path)
          val resp = """{"success":true,"id":"$id","path":"$path","size":$plainSize}"""
          respond(ex, 200, resp)
        } finally {
          ex.close()
        }
      }
      server.createContext("/api/documents/attachment-list") { ex ->
        try {
          val body = items.joinToString(",", prefix = """{"items":[""", postfix = "]}") { item ->
            """{"id":"${item.id}","path":"${item.path}","size":${item.plainSize},""" +
              """"plain_size":${item.plainSize},"mtime":${item.mtime}}"""
          }
          respond(ex, 200, body)
        } finally {
          ex.close()
        }
      }
      server.start()
    }

    fun stop() {
      server.stop(0)
    }

    fun postbox(): MrReviewPostbox = object : MrReviewPostbox {
      private val baseUrl = "http://127.0.0.1:${server.address.port}"

      override fun list(): List<MrReviewPostbox.Item>? {
        val res = MirrorPostboxApi.fileSyncList(baseUrl, "", "test-repo", false)
        if (res.code !in 200..299) return null
        return res.items.map { MrReviewPostbox.Item(it.path, it.plainSize, it.mtime) }
      }

      override fun upload(path: String, bytes: ByteArray): MrReviewPostbox.UploadOutcome {
        val sealed = MirrorCrypto.sealPostboxPayload(bytes, serverPub, path, bytes.size.toLong())
        val encrypted = File.createTempFile("review-postbox-", ".bin")
        return try {
          encrypted.writeBytes(sealed.bytes)
          val res = MirrorPostboxApi.fileSyncUpload(
            baseUrl, "", "test-repo", false, sealed.epkB64, sealed.meta, encrypted)
          if (res.code in 200..299) MrReviewPostbox.UploadOutcome(true, res.code, "OK")
          else MrReviewPostbox.UploadOutcome(false, res.code, res.message)
        } finally {
          runCatching { encrypted.delete() }
        }
      }
    }

    fun storedBytes(path: String): ByteArray {
      val item = items.last { it.path == path }
      return stored.getValue(item.id)
    }

    private fun respond(ex: HttpExchange, code: Int, body: String) {
      val bytes = body.toByteArray(Charsets.UTF_8)
      ex.responseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(code, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }

    private fun parseMultipart(body: ByteArray): Pair<Map<String, String>, List<ByteArray>> {
      val text = String(body, Charsets.ISO_8859_1)
      val boundary = text.substringBefore("\r\n").removePrefix("--")
      val fields = mutableMapOf<String, String>()
      val files = mutableListOf<ByteArray>()
      for (raw in text.split("--$boundary")) {
        val part = raw.removePrefix("\r\n").removeSuffix("\r\n")
        if (part.isBlank() || part == "--") continue
        val headerEnd = part.indexOf("\r\n\r\n")
        if (headerEnd < 0) continue
        val headers = part.substring(0, headerEnd)
        val content = part.substring(headerEnd + 4)
        val name = Regex("name=\"([^\"]+)\"").find(headers)?.groupValues?.getOrNull(1) ?: continue
        if (headers.contains("filename=")) {
          files.add(content.toByteArray(Charsets.ISO_8859_1))
        } else {
          fields[name] = content
        }
      }
      return fields to files
    }

    private fun newX25519(): KeyPair =
      KeyPairGenerator.getInstance("XDH").apply { initialize(NamedParameterSpec.X25519) }.generateKeyPair()

    private fun serverShared(serverKp: KeyPair, epk: ByteArray): ByteArray {
      val ka = KeyAgreement.getInstance("XDH")
      ka.init(serverKp.private)
      ka.doPhase(HybridCrypto.rawToPublicKey(epk), true)
      return ka.generateSecret()
    }

    private fun openGcmAad(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
      val nonce = blob.copyOfRange(0, 12)
      val ct = blob.copyOfRange(12, blob.size)
      val c = Cipher.getInstance("AES/GCM/NoPadding")
      c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
      c.updateAAD(aad)
      return c.doFinal(ct)
    }
  }
}
