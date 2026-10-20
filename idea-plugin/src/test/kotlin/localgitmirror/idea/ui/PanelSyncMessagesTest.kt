package localgitmirror.idea.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelSyncMessagesTest {

  @Test
  fun branchSendSuccessNamesBranchAndRepo() {
    val text = PanelSyncMessages.sendBranchOk("feature-x", "myrepo")
    assertTrue(text.contains("feature-x"), text)
    assertTrue(text.contains("myrepo"), text)
  }

  @Test
  fun pushAsSuccessNamesBranchAndRepo() {
    val text = PanelSyncMessages.pushAsOk("task-123", "myrepo")
    assertTrue(text.contains("task-123"), text)
    assertTrue(text.contains("myrepo"), text)
  }

  @Test
  fun sendCurrentTargetIgnoresTheListSelection() {
    assertEquals(
      "master",
      PanelSyncMessages.sendCurrentTarget(BranchChoice("feature-x", isLocal = true), "master")
    )
    assertEquals(
      "master",
      PanelSyncMessages.sendCurrentTarget(BranchChoice("cache-only", isLocal = false), "master")
    )
    assertEquals("master", PanelSyncMessages.sendCurrentTarget(null, "master"))
  }

  @Test
  fun workspaceDirtyRefusalMapsToDedicatedMessageWithPaths() {
    val body = """{"success":false,"repo":"myrepo","code":"workspace_dirty","paths":["src/a.py","docs/b.md"],"message":"Refusing to apply onto a dirty workspace"}"""
    val text = PanelSyncMessages.sendFailureText("myrepo", "Cache rejected sync", body)
    assertTrue(text.contains("src/a.py"), text)
    assertTrue(text.contains("docs/b.md"), text)
    assertFalse(text.contains("Cache rejected sync"), text)
  }

  @Test
  fun workspaceDirtySurvivesATruncatedBody() {
    val body = """{"success":false,"repo":"myrepo","code":"workspace_dirty","paths":["src/a.py","docs/"""
    val text = PanelSyncMessages.sendFailureText("myrepo", "Cache rejected sync", body)
    assertTrue(text.contains("src/a.py"), text)
    assertFalse(text.contains("Cache rejected sync"), text)
  }

  @Test
  fun genericFailureKeepsItsFormat() {
    assertEquals(
      "repo='myrepo' Cache error HTTP 500. boom",
      PanelSyncMessages.sendFailureText("myrepo", "Cache error HTTP 500", "boom")
    )
  }
}
