package localgitmirror.idea.gitlab

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.sync.v2.SyncFacadeService
import localgitmirror.idea.workkit.ExchangeCrypto
import localgitmirror.idea.workkit.RepoFileSyncCrypto
import java.io.File

/** Postbox transport for agent review artifacts, shared by the upload action, the review dialog and status reports. */
object MrRepliesTransport {

  fun uploadMarkdown(project: Project, iid: Int, markdown: String): Boolean =
    upload(project, "mr-replies/mr-!$iid.md", markdown)

  fun uploadStatus(project: Project, iid: Int, markdown: String): Boolean =
    upload(project, "mr-replies-status/mr-!$iid.md", markdown)

  fun upload(project: Project, displayPath: String, markdown: String): Boolean {
    val plain = File.createTempFile("lgm-upload-", ".md")
    return try {
      plain.writeText(markdown, Charsets.UTF_8)
      uploadFile(project, displayPath, plain)
    } catch (_: Throwable) {
      false
    } finally {
      runCatching { plain.delete() }
    }
  }

  /** Encrypt an arbitrary file (plugin zip, artifacts) and put it into the repo postbox under [displayPath]. */
  fun uploadFile(project: Project, displayPath: String, file: File): Boolean {
    val settings = service<MirrorSettingsService>().state
    val base = project.basePath ?: return false
    val repo = runCatching {
      project.getService(SyncFacadeService::class.java).resolveRepo(File(base), settings).sanitized
    }.getOrDefault("")
    if (repo.isBlank() || !file.isFile) return false

    val encrypted = File.createTempFile("lgm-upload-", ".bin")
    try {
      RepoFileSyncCrypto.encryptFile(file, encrypted, SecretsStore.syncPassword, null)
      val pathEnc = ExchangeCrypto.encryptHint(displayPath, SecretsStore.syncPassword)
      val up = MirrorApi.fileSyncUpload(
        settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls,
        "x/${java.util.UUID.randomUUID().toString().take(8)}", file.length(), encrypted, pathEnc, null,
      )
      return up.code in 200..299
    } catch (_: Throwable) {
      return false
    } finally {
      runCatching { encrypted.delete() }
    }
  }
}
