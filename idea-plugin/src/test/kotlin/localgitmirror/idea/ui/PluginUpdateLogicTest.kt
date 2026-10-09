package localgitmirror.idea.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginUpdateLogicTest {

  @Test
  fun sameVersionIsUpToDate() {
    assertEquals(PluginUpdateLogic.Decision.UpToDate, PluginUpdateLogic.decide("v0.738.0", "0.738.0"))
  }

  @Test
  fun higherRemoteIsUpdate() {
    val d = PluginUpdateLogic.decide("v0.738.0", "0.741.0")
    assertTrue(d is PluginUpdateLogic.Decision.Update)
    assertEquals("0.738.0", (d as PluginUpdateLogic.Decision.Update).current)
    assertEquals("0.741.0", d.remote)
  }

  @Test
  fun lowerRemoteIsUpToDate() {
    assertEquals(PluginUpdateLogic.Decision.UpToDate, PluginUpdateLogic.decide("0.741.0", "0.738.0"))
  }

  @Test
  fun numericNotLexicographic() {
    assertTrue(PluginUpdateLogic.compareVersions("0.10.0", "0.9.0") > 0)
    assertTrue(PluginUpdateLogic.compareVersions("0.9.0", "0.10.0") < 0)
    assertEquals(0, PluginUpdateLogic.compareVersions("1.2.3", "1.2.3"))
  }

  @Test
  fun missingPiecesAreUnknown() {
    assertEquals(PluginUpdateLogic.Decision.Unknown, PluginUpdateLogic.decide(null, "1.0"))
    assertEquals(PluginUpdateLogic.Decision.Unknown, PluginUpdateLogic.decide("v?", "1.0"))
    assertEquals(PluginUpdateLogic.Decision.Unknown, PluginUpdateLogic.decide("v1.0", null))
    assertEquals(PluginUpdateLogic.Decision.Unknown, PluginUpdateLogic.decide("", ""))
  }

  @Test
  fun differentSegmentCountsStillCompare() {
    assertEquals(0, PluginUpdateLogic.compareVersions("0.741", "0.741.0"))
    assertTrue(PluginUpdateLogic.compareVersions("0.741.0.1", "0.741.0") > 0)
  }
}
