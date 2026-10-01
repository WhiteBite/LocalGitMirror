package localgitmirror.idea.sync.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.mirror.HttpResult
import localgitmirror.idea.sync.v2.SyncEngine.StepResult
import java.io.File

@Suppress("HttpCallOnEdt")
internal fun SyncEngine.uploadAndApply(settings: SettingsSnapshot, repoName: String, dump: File, projectDir: File? = null, localBranches: List<String> = emptyList()): Pair<StepResult, HttpResult> {
  val res = mirror.uploadAndApply(
    baseUrl = settings.baseUrl,
    apiKey = settings.mirrorApiKey,
    repo = repoName,
    dumpFile = dump,
    syncPassword = settings.syncPassword,
    insecureTls = settings.mirrorInsecureTls,
    projectDir = projectDir,
    localBranches = localBranches
  )
  if (res.code !in 200..299) {
    return StepResult(false, "Cache error HTTP ${res.code}", res.body.take(500)) to res
  }

  val success = parseJsonSuccess(res.body)
  if (success == false) {
    return StepResult(false, "Cache rejected sync", res.body.take(500)) to res
  }

  return StepResult(true, "Upload-and-apply success", res.body.take(500)) to res
}

internal fun SyncEngine.parseJsonSuccess(body: String): Boolean? {
  return try {
    val json = Json.parseToJsonElement(body).jsonObject
    json["success"]?.jsonPrimitive?.booleanOrNull
  } catch (_: Exception) {
    val m = Regex("\"success\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE).find(body)
    val v = m?.groupValues?.getOrNull(1)?.lowercase()
    when (v) {
      "true" -> true
      "false" -> false
      else -> null
    }
  }
}
