package localgitmirror.idea.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MirrorSettingsValidationTest {

  @Test
  fun `blank base URL is rejected with the blank reason`() {
    val errors = validateMirrorSettings("   ", "300")
    assertEquals(listOf(MirrorSettingsFieldError("settings.validation.url.blank")), errors)
  }

  @Test
  fun `authority-less URL is rejected as malformed`() {
    val errors = validateMirrorSettings("https://", "300")
    assertEquals(listOf(MirrorSettingsFieldError("settings.validation.url.malformed")), errors)
  }

  @Test
  fun `unparseable URL is rejected as malformed`() {
    val errors = validateMirrorSettings("https://foo bar", "300")
    assertEquals(listOf(MirrorSettingsFieldError("settings.validation.url.malformed")), errors)
  }

  @Test
  fun `out-of-range poll interval is rejected with the bounds`() {
    assertEquals(
      listOf(MirrorSettingsFieldError("settings.validation.pollSec.range", listOf(15, 600))),
      validateMirrorSettings("https://192.168.1.50", "14")
    )
    assertEquals(
      listOf(MirrorSettingsFieldError("settings.validation.pollSec.range", listOf(15, 600))),
      validateMirrorSettings("https://192.168.1.50", "601")
    )
  }

  @Test
  fun `non-numeric poll interval is rejected`() {
    assertEquals(
      listOf(MirrorSettingsFieldError("settings.validation.pollSec.invalid")),
      validateMirrorSettings("https://192.168.1.50", "abc")
    )
  }

  @Test
  fun `valid form is accepted unchanged`() {
    assertTrue(validateMirrorSettings("https://192.168.1.50", "300").isEmpty())
  }

  @Test
  fun `bare host without scheme is valid`() {
    assertTrue(validateMirrorSettings("192.168.1.50", "600").isEmpty())
  }

  @Test
  fun `blank URL and bad poll interval are reported together`() {
    val errors = validateMirrorSettings("", "0")
    assertEquals(2, errors.size)
  }
}
