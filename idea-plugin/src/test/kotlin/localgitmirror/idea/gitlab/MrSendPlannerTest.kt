package localgitmirror.idea.gitlab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFailsWith

class MrSendPlannerTest {

  private class Scenario(
    val name: String,
    val mirrorRefs: Map<String, String>,
    val localTips: Map<String, String>,
    val existingShas: Set<String>,
    val branches: List<String>,
    val newCommitCount: Int,
    val sent: List<String>,
    val skipped: List<String>,
    val excludeShas: List<String>,
  )

  private fun fixtureFile(): File {
    var dir: File? = File(System.getProperty("user.dir", "."))
    while (dir != null) {
      val candidate = File(dir, "tests/fixtures/mr_send_scenarios.json")
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    throw IllegalStateException("tests/fixtures/mr_send_scenarios.json not found above the working directory")
  }

  private fun scenarios(): List<Scenario> =
    Json.parseToJsonElement(fixtureFile().readText()).jsonArray.map { el ->
      val o = el.jsonObject
      val exp = o.getValue("expected").jsonObject
      Scenario(
        name = o.getValue("name").jsonPrimitive.content,
        mirrorRefs = o.getValue("mirror_refs").jsonObject.entries.associate { (k, v) ->
          k to (v.jsonObject["sha"]?.jsonPrimitive?.content ?: "")
        },
        localTips = o.getValue("local_tips").jsonObject.entries.associate { (k, v) ->
          k to v.jsonPrimitive.content
        },
        existingShas = o.getValue("existing_shas").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        branches = o.getValue("branches").jsonArray.map { it.jsonPrimitive.content },
        newCommitCount = o.getValue("new_commit_count").jsonPrimitive.int,
        sent = exp.getValue("sent").jsonArray.map { it.jsonPrimitive.content },
        skipped = exp.getValue("skipped").jsonArray.map { it.jsonPrimitive.content },
        excludeShas = exp.getValue("exclude_shas").jsonArray.map { it.jsonPrimitive.content },
      )
    }

  @Test
  fun `shared fixture scenarios match the python mr_send contract`() {
    for (s in scenarios()) {
      val plan = MrSendPlanner.planSend(
        s.mirrorRefs, s.localTips, s.existingShas, s.branches, s.newCommitCount,
      )
      assertEquals(s.sent, plan.sent, "[${s.name}] sent")
      assertEquals(s.skipped, plan.skipped, "[${s.name}] skipped")
      assertEquals(s.excludeShas, plan.excludeShas, "[${s.name}] exclude_shas")
    }
  }

  @Test
  fun `branch without a local tip fails resolution`() {
    assertFailsWith<IllegalArgumentException> {
      MrSendPlanner.planSend(emptyMap(), emptyMap(), emptySet(), listOf("b1"), 1)
    }
  }
}
