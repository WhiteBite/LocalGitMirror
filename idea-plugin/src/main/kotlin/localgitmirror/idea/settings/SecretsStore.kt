package localgitmirror.idea.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

object SecretsStore {
  private const val SUBSYSTEM = "LocalGitMirror"

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

  var mirrorApiKey: String
    get() = get("mirror.apiKey")
    set(value) = set("mirror.apiKey", value)

  var syncPassword: String
    get() = get("mirror.syncPassword")
    set(value) = set("mirror.syncPassword", value)

  var gitLabToken: String
    get() = get("gitlab.token")
    set(value) = set("gitlab.token", value)
}
