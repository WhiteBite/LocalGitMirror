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

/** Postbox transport for agent review replies, shared by the upload action and the review dialog. */
object MrRepliesTransport {

  fun uploadMarkdown(project: Project, iid: Int, markdown: String): Boolean {
    val settings = service<MirrorSettingsService>().state
    val base = project.basePath ?: return false
    val repo = runCatching {
      project.getService(SyncFacadeService::class.java).resolveRepo(File(base), settings).sanitized
    }.getOrDefault("")
    if (repo.isBlank()) return false

    val plain = File.createTempFile("mr-replies-up-", ".md")
    val encrypted = File.createTempFile("mr-replies-up-", ".bin")
    try {
      plain.writeText(markdown, Charsets.UTF_8)
      RepoFileSyncCrypto.encryptFile(plain, encrypted, SecretsStore.syncPassword, null)
      val pathEnc = ExchangeCrypto.encryptHint("mr-replies/mr-!$iid.md", SecretsStore.syncPassword)
      val up = MirrorApi.fileSyncUpload(
        settings.baseUrl, SecretsStore.mirrorApiKey, repo, settings.mirrorInsecureTls,
        "x/${java.util.UUID.randomUUID().toString().take(8)}", markdown.length.toLong(), encrypted, pathEnc, null,
      )
      return up.code in 200..299
    } catch (_: Throwable) {
      return false
    } finally {
      runCatching { plain.delete() }
      runCatching { encrypted.delete() }
    }
  }
}
