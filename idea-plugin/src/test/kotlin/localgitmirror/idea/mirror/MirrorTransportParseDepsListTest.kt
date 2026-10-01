package localgitmirror.idea.mirror

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MirrorTransportParseDepsListTest {

  @Test
  fun `parses items from the wrapped server response`() {
    val body = """{"success": true, "repo": "demo", "items": [
      {"id": "a1", "size": 10, "mtime": 100},
      {"id": "b2", "size": 20, "mtime": 200}
    ]}"""
    val items = MirrorTransport.parseDepsList(body)
    assertEquals(2, items.size)
    assertEquals(MirrorDepsApi.DepsItem("a1", 10, 100), items[0])
    assertEquals(MirrorDepsApi.DepsItem("b2", 20, 200), items[1])
  }

  @Test
  fun `parses a bare array response`() {
    val body = """[{"id": "x", "size": 1, "mtime": 5}]"""
    val items = MirrorTransport.parseDepsList(body)
    assertEquals(1, items.size)
    assertEquals("x", items[0].id)
  }

  @Test
  fun `skips entries with missing or non-numeric fields`() {
    val body = """{"items": [
      {"id": "ok", "size": 3, "mtime": 7},
      {"size": 3, "mtime": 7},
      {"id": "nosize", "mtime": 7},
      {"id": "badsize", "size": "big", "mtime": 7},
      "not-an-object"
    ]}"""
    val items = MirrorTransport.parseDepsList(body)
    assertEquals(1, items.size)
    assertEquals("ok", items[0].id)
  }

  @Test
  fun `malformed body yields an empty list instead of throwing`() {
    assertTrue(MirrorTransport.parseDepsList("not json at all").isEmpty())
    assertTrue(MirrorTransport.parseDepsList("").isEmpty())
    assertTrue(MirrorTransport.parseDepsList("""{"items": 42}""").isEmpty())
    assertTrue(MirrorTransport.parseDepsList("""{"success": true}""").isEmpty())
  }
}
