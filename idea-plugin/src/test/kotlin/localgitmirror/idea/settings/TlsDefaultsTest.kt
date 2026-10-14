package localgitmirror.idea.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class TlsDefaultsTest {

  @Test
  fun `fresh legacy state migrates to pinning with verified tls`() {
    assertEquals(
      TlsDefaults.TlsMigration(pinEnabled = true, insecure = false, migrated = true),
      TlsDefaults.migrate(pinEnabled = false, insecure = true, migrated = false)
    )
  }

  @Test
  fun `already pinned legacy state keeps pinning and drops insecure`() {
    assertEquals(
      TlsDefaults.TlsMigration(pinEnabled = true, insecure = false, migrated = true),
      TlsDefaults.migrate(pinEnabled = true, insecure = true, migrated = false)
    )
  }

  @Test
  fun `migrated state with insecure re-enabled stays unchanged`() {
    assertEquals(
      TlsDefaults.TlsMigration(pinEnabled = true, insecure = true, migrated = true),
      TlsDefaults.migrate(pinEnabled = true, insecure = true, migrated = true)
    )
  }

  @Test
  fun `migration is idempotent`() {
    val inputs = listOf(
      Triple(false, true, false),
      Triple(true, true, false),
      Triple(false, false, false),
      Triple(true, false, true),
      Triple(false, true, true),
    )
    for ((pin, insecure, migrated) in inputs) {
      val once = TlsDefaults.migrate(pinEnabled = pin, insecure = insecure, migrated = migrated)
      val twice = TlsDefaults.migrate(
        pinEnabled = once.pinEnabled,
        insecure = once.insecure,
        migrated = once.migrated
      )
      assertEquals(once, twice)
    }
  }
}
