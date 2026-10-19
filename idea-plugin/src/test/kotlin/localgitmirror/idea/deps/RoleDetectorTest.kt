package localgitmirror.idea.deps

import localgitmirror.idea.settings.MirrorSettingsService
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RoleDetector must tell the truth: a real HOME/WORK answer when detection
 * succeeds, UNDETERMINED when it cannot — never a silent WORK fallback that
 * hides the whole deps loop on the home machine.
 */
class RoleDetectorTest {

  private fun state(baseUrl: String, machineRole: String = "auto") =
    MirrorSettingsService.State(baseUrl = baseUrl, machineRole = machineRole)

  @Test
  fun `localhost url detects HOME`() {
    assertEquals(MachineRole.HOME, RoleDetector.detect(state("https://localhost")))
  }

  @Test
  fun `loopback ip detects HOME`() {
    assertEquals(MachineRole.HOME, RoleDetector.detect(state("https://127.0.0.1")))
  }

  @Test
  fun `foreign host detects WORK`() {
    assertEquals(MachineRole.WORK, RoleDetector.detect(state("https://192.0.2.10")))
  }

  @Test
  fun `blank baseUrl is UNDETERMINED not silently WORK`() {
    assertEquals(MachineRole.UNDETERMINED, RoleDetector.detect(state("")))
  }

  @Test
  fun `unparseable url is UNDETERMINED`() {
    assertEquals(MachineRole.UNDETERMINED, RoleDetector.detect(state("https://[broken")))
  }

  @Test
  fun `explicit home override wins over failed detection`() {
    assertEquals(MachineRole.HOME, RoleDetector.detect(state("", machineRole = "home")))
  }

  @Test
  fun `explicit work override wins over localhost`() {
    assertEquals(MachineRole.WORK, RoleDetector.detect(state("https://localhost", machineRole = "work")))
  }

  @Test
  fun `override matching is case-insensitive`() {
    assertEquals(MachineRole.HOME, RoleDetector.detect(state("", machineRole = "HOME")))
    assertEquals(MachineRole.WORK, RoleDetector.detect(state("https://localhost", machineRole = "Work")))
  }

  @Test
  fun `auto mode falls through to url detection`() {
    assertEquals(MachineRole.HOME, RoleDetector.detect(state("https://localhost", machineRole = "auto")))
  }
}
