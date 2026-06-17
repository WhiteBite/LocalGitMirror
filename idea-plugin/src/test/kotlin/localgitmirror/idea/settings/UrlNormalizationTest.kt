package localgitmirror.idea.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the URL normalization logic applied in MirrorSettingsConfigurable.apply().
 * The logic is extracted here as a pure function to keep tests simple.
 */
class UrlNormalizationTest {

  /** Mirrors the normalization logic in MirrorSettingsConfigurable.apply() */
  private fun normalizeUrl(input: String): String {
    val url = input.trim()
    return when {
      url.isBlank() -> url
      url.startsWith("http://") || url.startsWith("https://") -> url.trimEnd('/')
      else -> "https://${url.trimEnd('/')}"
    }
  }

  @Test
  fun `blank URL stays blank`() {
    assertEquals("", normalizeUrl(""))
    assertEquals("", normalizeUrl("   "))
  }

  @Test
  fun `bare IP gets https scheme added`() {
    assertEquals("https://192.168.1.50", normalizeUrl("192.168.1.50"))
  }

  @Test
  fun `bare IP with port gets https scheme`() {
    assertEquals("https://192.168.1.50:443", normalizeUrl("192.168.1.50:443"))
  }

  @Test
  fun `bare hostname gets https scheme`() {
    assertEquals("https://mymirror.local", normalizeUrl("mymirror.local"))
  }

  @Test
  fun `https URL is kept as-is`() {
    assertEquals("https://192.168.1.50", normalizeUrl("https://192.168.1.50"))
  }

  @Test
  fun `https URL with port is kept`() {
    assertEquals("https://192.168.1.50:8443", normalizeUrl("https://192.168.1.50:8443"))
  }

  @Test
  fun `http URL is kept without upgrading to https`() {
    // http is allowed — user made the choice explicitly
    assertEquals("http://192.168.1.50", normalizeUrl("http://192.168.1.50"))
  }

  @Test
  fun `trailing slash is stripped from https URL`() {
    assertEquals("https://192.168.1.50", normalizeUrl("https://192.168.1.50/"))
  }

  @Test
  fun `trailing slash is stripped from bare URL`() {
    assertEquals("https://192.168.1.50", normalizeUrl("192.168.1.50/"))
  }

  @Test
  fun `multiple trailing slashes stripped`() {
    assertEquals("https://192.168.1.50", normalizeUrl("192.168.1.50///"))
  }

  @Test
  fun `leading and trailing whitespace stripped`() {
    assertEquals("https://192.168.1.50", normalizeUrl("  192.168.1.50  "))
  }
}
