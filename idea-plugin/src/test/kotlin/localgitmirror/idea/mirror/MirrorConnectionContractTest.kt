package localgitmirror.idea.mirror

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MirrorConnectionContractTest {

  @Test
  fun `connection check uses authenticated health endpoint instead of retired session endpoint`() {
    assertEquals(
      "https://mirror.example/api/health",
      MirrorConnectionContract.authenticatedHealthUrl("https://mirror.example/")
    )
  }

  @Test
  fun `connection check requires the API key before making an authenticated request`() {
    assertEquals(
      "Укажите API key из консоли Mirror-сервера.",
      MirrorConnectionContract.missingConfigurationMessage("https://mirror.example", "")
    )
    assertNull(MirrorConnectionContract.missingConfigurationMessage("https://mirror.example", "key"))
  }
}
