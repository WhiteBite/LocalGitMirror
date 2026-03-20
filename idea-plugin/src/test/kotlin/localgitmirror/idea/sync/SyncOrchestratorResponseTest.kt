package localgitmirror.idea.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import localgitmirror.idea.sync.v2.SyncEngine

class SyncOrchestratorResponseTest {
  private val engine = SyncEngine()

  @Test
  fun `parseJsonSuccess returns true`() {
    val body = """{"success":true,"message":"ok"}"""
    assertEquals(true, engine.parseJsonSuccess(body))
  }

  @Test
  fun `parseJsonSuccess returns false`() {
    val body = """{"success":false,"message":"failed"}"""
    assertEquals(false, engine.parseJsonSuccess(body))
  }

  @Test
  fun `parseJsonSuccess handles formatted json boolean`() {
    val body = """
      {
        "success": true,
        "message": "ok"
      }
    """.trimIndent()
    assertEquals(true, engine.parseJsonSuccess(body))
  }

  @Test
  fun `parseJsonSuccess returns null when field missing`() {
    val body = """{"message":"ok"}"""
    assertNull(engine.parseJsonSuccess(body))
  }
}
