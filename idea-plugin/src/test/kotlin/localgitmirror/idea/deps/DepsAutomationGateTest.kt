package localgitmirror.idea.deps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Truth table for [DepsAutomationGate]: "auto" follows the machine role,
 * "on" forces enabled, "off" and any unknown value disable (fail-safe).
 */
class DepsAutomationGateTest {

  @Test
  fun `auto enables automation on home machine`() {
    assertTrue(DepsAutomationGate.enabled("auto", roleIsHome = true))
  }

  @Test
  fun `auto disables automation on work machine`() {
    assertFalse(DepsAutomationGate.enabled("auto", roleIsHome = false))
  }

  @Test
  fun `on forces automation on home machine`() {
    assertTrue(DepsAutomationGate.enabled("on", roleIsHome = true))
  }

  @Test
  fun `on forces automation on work machine`() {
    assertTrue(DepsAutomationGate.enabled("on", roleIsHome = false))
  }

  @Test
  fun `off forces automation off on home machine`() {
    assertFalse(DepsAutomationGate.enabled("off", roleIsHome = true))
  }

  @Test
  fun `off forces automation off on work machine`() {
    assertFalse(DepsAutomationGate.enabled("off", roleIsHome = false))
  }

  @Test
  fun `empty mode disables automation`() {
    assertFalse(DepsAutomationGate.enabled("", roleIsHome = true))
  }

  @Test
  fun `unknown mode disables automation`() {
    assertFalse(DepsAutomationGate.enabled("garbage", roleIsHome = false))
  }

  // machineRole handling in RoleDetector lowercases before matching; the gate mirrors that
  @Test
  fun `mode matching is case insensitive`() {
    assertTrue(DepsAutomationGate.enabled("AUTO", roleIsHome = true))
    assertTrue(DepsAutomationGate.enabled("On", roleIsHome = false))
    assertFalse(DepsAutomationGate.enabled("OFF", roleIsHome = true))
  }

  @Test
  fun `tracker stays idle while the gate is closed at project open`() {
    val tracker = DepsAutomationGateTracker()
    assertEquals(DepsAutomationGateTracker.Action.IDLE, tracker.pass(false))
    assertEquals(DepsAutomationGateTracker.Action.IDLE, tracker.pass(false))
  }

  @Test
  fun `tracker initializes the poller on the first pass after the gate reopens`() {
    val tracker = DepsAutomationGateTracker()
    assertEquals(DepsAutomationGateTracker.Action.IDLE, tracker.pass(false))
    assertEquals(DepsAutomationGateTracker.Action.INITIALIZE, tracker.pass(true))
    assertEquals(DepsAutomationGateTracker.Action.POLL, tracker.pass(true))
  }

  @Test
  fun `tracker initializes exactly once across gate transitions`() {
    val tracker = DepsAutomationGateTracker()
    assertEquals(DepsAutomationGateTracker.Action.INITIALIZE, tracker.pass(true))
    assertEquals(DepsAutomationGateTracker.Action.POLL, tracker.pass(true))
    assertEquals(DepsAutomationGateTracker.Action.IDLE, tracker.pass(false))
    assertEquals(DepsAutomationGateTracker.Action.POLL, tracker.pass(true))
  }
}
