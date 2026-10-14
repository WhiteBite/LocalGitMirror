package localgitmirror.idea.settings

object TlsDefaults {

  data class TlsMigration(
    val pinEnabled: Boolean,
    val insecure: Boolean,
    val migrated: Boolean
  )

  fun migrate(pinEnabled: Boolean, insecure: Boolean, migrated: Boolean): TlsMigration =
    if (migrated) {
      TlsMigration(pinEnabled, insecure, migrated)
    } else {
      TlsMigration(pinEnabled = true, insecure = false, migrated = true)
    }
}
