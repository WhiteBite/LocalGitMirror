package localgitmirror.idea.gitlab

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import localgitmirror.idea.mirror.MirrorCrypto
import localgitmirror.idea.mirror.MirrorPostboxApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import java.io.File

/** code = 0 marks a local failure (no HTTP round trip happened). */
sealed interface MrUploadResult {
  val ok: Boolean get() = this is Ok

  data object Ok : MrUploadResult
  data class Failed(val code: Int, val reason: String) : MrUploadResult
}

/** Postbox transport for agent review artifacts, shared by the upload action, the review dialog and status reports. */
object MrRepliesTransport {

  fun uploadMarkdown(project: Project, iid: Int, markdown: String): Boolean =
    uploadMarkdownResult(project, iid, markdown).ok

  fun uploadStatus(project: Project, iid: Int, markdown: String): Boolean =
    uploadStatusResult(project, iid, markdown).ok

  fun upload(project: Project, displayPath: String, markdown: String): Boolean =
    uploadResult(project, displayPath, markdown).ok

  /** Encrypt an arbitrary file (plugin zip, artifacts) and put it into the repo postbox under [displayPath]. */
  fun uploadFile(project: Project, displayPath: String, file: File): Boolean =
    uploadFileResult(project, displayPath, file).ok

  fun uploadMarkdownResult(project: Project, iid: Int, markdown: String): MrUploadResult =
    uploadResult(project, "mr-replies/mr-!$iid.md", markdown)

  fun uploadStatusResult(project: Project, iid: Int, markdown: String): MrUploadResult =
    uploadResult(project, "mr-replies-status/mr-!$iid.md", markdown)

  fun uploadResult(project: Project, displayPath: String, markdown: String): MrUploadResult {
    return try {
      val plain = File.createTempFile("lgm-upload-", ".md")
      try {
        plain.writeText(markdown, Charsets.UTF_8)
        uploadFileResult(project, displayPath, plain)
      } finally {
        runCatching { plain.delete() }
      }
    } catch (e: Throwable) {
      MrUploadResult.Failed(0, "upload: ${e.message ?: "error"}")
    }
  }

  fun uploadFileResult(project: Project, displayPath: String, file: File): MrUploadResult {
    val settings = service<MirrorSettingsService>().state
    val base = project.basePath ?: return MrUploadResult.Failed(0, "project base directory missing")
    val repo = runCatching {
      project.getService(SyncFacadeService::class.java).resolveRepo(File(base), settings).sanitized
    }.getOrDefault("")
    if (repo.isBlank()) return MrUploadResult.Failed(0, "repo not resolved")
    if (!file.isFile) return MrUploadResult.Failed(0, "not a file: ${file.path}")

    return try {
      val encrypted = File.createTempFile("lgm-upload-", ".bin")
      try {
        val sealed = MirrorCrypto.sealPostboxPayload(file.readBytes(), displayPath, file.length())
        encrypted.writeBytes(sealed.bytes)
        val up = MirrorPostboxApi.fileSyncUpload(
          settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls,
          sealed.epkB64, sealed.meta, encrypted,
        )
        if (up.code in 200..299) MrUploadResult.Ok
        else MrUploadResult.Failed(up.code, "HTTP ${up.code}: ${up.message}")
      } finally {
        runCatching { encrypted.delete() }
      }
    } catch (e: Throwable) {
      MrUploadResult.Failed(0, "upload: ${e.message ?: "error"}")
    }
  }
}
