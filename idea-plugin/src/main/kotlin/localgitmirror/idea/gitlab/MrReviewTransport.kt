package localgitmirror.idea.gitlab

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.jsonObject
import localgitmirror.idea.net.Cancellation
import java.io.File
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** Postbox transport seam for review artifacts: list + upload, so the
 * transfer rules (code bundle first, envelope last, dedup, TTL refresh) are
 * testable against a fixture postbox without the IntelliJ services. */
interface MrReviewPostbox {

  data class Item(val path: String, val plainSize: Long, val mtime: Long)

  data class UploadOutcome(val ok: Boolean, val code: Int, val reason: String)

  /** Null when the postbox cannot be listed (transport failure). */
  fun list(): List<Item>?

  fun upload(path: String, bytes: ByteArray): UploadOutcome
}

/** Production postbox: the existing v3 encrypted file postbox through
 * [MrRepliesTransport] and [localgitmirror.idea.mirror.MirrorPostboxApi]. */
class PluginMrReviewPostbox(private val project: Project) : MrReviewPostbox {

  private fun repo(): String {
    val settings = service<localgitmirror.idea.settings.MirrorSettingsService>().state
    val base = project.basePath ?: return ""
    return runCatching {
      project.getService(localgitmirror.idea.sync.v2.SyncFacadeService::class.java)
        .resolveRepo(File(base), settings).sanitized
    }.getOrDefault("")
  }

  override fun list(): List<MrReviewPostbox.Item>? {
    val settings = service<localgitmirror.idea.settings.MirrorSettingsService>().state
    val repo = repo()
    if (repo.isBlank()) return null
    val res = localgitmirror.idea.mirror.MirrorPostboxApi.fileSyncList(
      settings.baseUrl, localgitmirror.idea.settings.SecretsStore.mirrorApiKey,
      repo, settings.mirrorInsecureTls,
    )
    if (res.code !in 200..299) return null
    return res.items.map { MrReviewPostbox.Item(it.path, it.plainSize, it.mtime) }
  }

  override fun upload(path: String, bytes: ByteArray): MrReviewPostbox.UploadOutcome {
    val plain = File.createTempFile("lgm-review-upload-", ".bin")
    return try {
      plain.writeBytes(bytes)
      when (val res = MrRepliesTransport.uploadFileResult(project, path, plain)) {
        is MrUploadResult.Ok -> MrReviewPostbox.UploadOutcome(true, 200, "OK")
        is MrUploadResult.Failed -> MrReviewPostbox.UploadOutcome(false, res.code, res.reason)
      }
    } finally {
      runCatching { plain.delete() }
    }
  }
}

/** Transfers a prepared review snapshot into the postbox: the code bundle
 * goes to mr-review/code/<code_id>.bundle FIRST, the transfer envelope to
 * mr-review/snapshots/<snapshot_id>.json LAST. A transport receipt is not
 * review completion — the result reports exactly what was uploaded. */
object MrReviewTransport {

  // postbox TTL is 7 days; refresh one day before expiry
  const val TTL_REFRESH_SECONDS = 6L * 24 * 3600

  sealed interface Transfer

  data class Transferred(
    val snapshotId: String,
    val codeId: String,
    val versionFingerprint: String,
    val bundleAction: String,
    val bundleSha256: String,
    val bundleSize: Long,
    val bundlePath: String,
    val manifestPath: String,
    val limitations: List<String>,
  ) : Transfer

  data class Skipped(
    val snapshotId: String,
    val codeId: String,
    val limitations: List<String>,
  ) : Transfer

  data class TransferFailed(val code: String?, val message: String) : Transfer

  fun transfer(
    postbox: MrReviewPostbox,
    ready: MrReviewSnapshotSync.Ready,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    allowCodeUpload: Boolean = true,
    maxCodeArtifactBytes: Long = MrReviewProtocol.CODE_ARTIFACT_MAX_BYTES,
  ): Transfer {
    return try {
      transferOrThrow(postbox, ready, nowEpochSeconds, allowCodeUpload, maxCodeArtifactBytes)
    } catch (e: MrReviewProtocolException) {
      TransferFailed(e.code, e.message ?: e.code)
    } catch (t: Throwable) {
      Cancellation.rethrow(t)
      TransferFailed(null, t.message ?: "error")
    }
  }

  private fun transferOrThrow(
    postbox: MrReviewPostbox,
    ready: MrReviewSnapshotSync.Ready,
    nowEpochSeconds: Long,
    allowCodeUpload: Boolean,
    maxCodeArtifactBytes: Long,
  ): Transfer {
    val snapshot = MrReviewProtocol.verifySnapshot(
      MrReviewProtocol.parse(ready.manifestJson).jsonObject)
    val size = ready.bundle.length()
    if (size > maxCodeArtifactBytes) {
      throw MrReviewProtocolException(
        "review-package-too-large",
        "code bundle is $size bytes, above the $maxCodeArtifactBytes limit",
      )
    }
    val bundleBytes = ready.bundle.readBytes()
    val bundleSha256 = sha256Hex(bundleBytes)
    val codePath = MrReviewProtocol.codeArtifactPath(ready.codeId)
    val items = postbox.list()
      ?: return TransferFailed(null, "postbox list failed")
    val existing = items.filter { it.path == codePath }.maxByOrNull { it.mtime }
    val desired = when {
      existing == null -> "upload"
      existing.plainSize != 0L && existing.plainSize != size -> "upload"
      nowEpochSeconds - existing.mtime >= TTL_REFRESH_SECONDS -> "ttl-refresh"
      else -> "deduplicated"
    }

    if (desired == "upload" && !allowCodeUpload) {
      return Skipped(
        snapshotId = ready.snapshotId,
        codeId = ready.codeId,
        limitations = listOf(
          "code artifact $codePath is not available in the postbox; " +
            "run a full sync (not notes-only) to transfer it",
        ),
      )
    }

    val limitations = mutableListOf<String>()
    var action = "deduplicated"
    if (desired == "upload" || desired == "ttl-refresh") {
      if (allowCodeUpload) {
        val up = postbox.upload(codePath, bundleBytes)
        if (!up.ok) {
          return TransferFailed(null, "bundle upload failed: HTTP ${up.code} ${up.reason}")
        }
        action = if (desired == "upload") "uploaded" else "ttl-refreshed"
      } else {
        limitations += "code artifact $codePath approaches postbox expiry and cannot " +
          "be refreshed in a notes-only run"
      }
    }

    val envelope = MrReviewProtocol.buildTransferEnvelope(
      snapshot, bundleSha256, size, nowIso(), limitations)
    val manifestPath = MrReviewProtocol.snapshotArtifactPath(ready.snapshotId)
    val up = postbox.upload(
      manifestPath, MrReviewProtocol.canonicalJson(envelope).toByteArray(Charsets.UTF_8))
    if (!up.ok) {
      return TransferFailed(null, "envelope upload failed: HTTP ${up.code} ${up.reason}")
    }
    return Transferred(
      snapshotId = ready.snapshotId,
      codeId = ready.codeId,
      versionFingerprint = ready.versionFingerprint,
      bundleAction = action,
      bundleSha256 = bundleSha256,
      bundleSize = size,
      bundlePath = codePath,
      manifestPath = manifestPath,
      limitations = limitations,
    )
  }

  private fun nowIso(): String =
    OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { b -> "%02x".format(b) }
}
