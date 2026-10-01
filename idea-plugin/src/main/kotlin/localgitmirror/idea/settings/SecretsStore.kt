package localgitmirror.idea.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager

object SecretsStore {
  private const val SUBSYSTEM = "LocalGitMirror"

  data class Snapshot(val mirrorApiKey: String, val syncPassword: String, val gitlabToken: String)

  private val emptySnapshot = Snapshot("", "", "")

  @Volatile
  private var cache: Snapshot? = null

  /**
   * EDT-safe reads: the background-warmed snapshot. Cold cache returns blank
   * values on the EDT (PasswordSafe is too slow for update()/constructors);
   * off the EDT it falls back to direct reads so background callers always
   * see fresh values.
   */
  val cached: Snapshot
    get() = cache ?: if (ApplicationManager.getApplication()?.isDispatchThread == true) emptySnapshot else readAll()

  private fun attr(key: String): CredentialAttributes =
    CredentialAttributes(generateServiceName(SUBSYSTEM, key))

  private fun get(key: String): String {
    val c = PasswordSafe.instance.get(attr(key))
    return c?.getPasswordAsString().orEmpty()
  }

  private fun set(key: String, value: String) {
    val trimmed = value.trim()
    if (trimmed.isBlank()) {
      PasswordSafe.instance.set(attr(key), null)
    } else {
      PasswordSafe.instance.set(attr(key), Credentials("", trimmed))
    }
  }

  private fun readAll(): Snapshot = Snapshot(get("mirror.apiKey"), get("mirror.syncPassword"), get("gitlab.token"))

  /** (Re)load all secrets into the EDT-safe cache; runs PasswordSafe, so never call it on the EDT. */
  fun warmCache() {
    cache = readAll()
  }

  fun warmCacheAsync(onComplete: (() -> Unit)? = null) {
    ApplicationManager.getApplication().executeOnPooledThread {
      runCatching { warmCache() }
      onComplete?.invoke()
    }
  }

  private fun mutateCache(transform: (Snapshot) -> Snapshot) {
    val cur = cache ?: run { warmCacheAsync(); return }
    cache = transform(cur)
  }

  var mirrorApiKey: String
    get() = get("mirror.apiKey")
    set(value) {
      set("mirror.apiKey", value)
      mutateCache { it.copy(mirrorApiKey = value.trim()) }
    }

  var syncPassword: String
    get() = get("mirror.syncPassword")
    set(value) {
      set("mirror.syncPassword", value)
      mutateCache { it.copy(syncPassword = value.trim()) }
    }

  /** GitLab personal access token (MR transfer). Never stored in plain State. */
  var gitlabToken: String
    get() = get("gitlab.token")
    set(value) {
      set("gitlab.token", value)
      mutateCache { it.copy(gitlabToken = value.trim()) }
    }
}
