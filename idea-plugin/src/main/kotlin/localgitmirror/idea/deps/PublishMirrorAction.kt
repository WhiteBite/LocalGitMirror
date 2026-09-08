package localgitmirror.idea.deps

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import localgitmirror.idea.i18n.LocalGitMirrorBundle
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.workkit.BundleCrypto
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ─────────────────────────────────────────────────────────────────────────────
// 4. PublishMirrorAction (WORK): scan local caches for protected corporate
//    artifacts and publish them to the home server's vault
// ─────────────────────────────────────────────────────────────────────────────

class PublishMirrorAction : AnAction() {

  override fun update(e: AnActionEvent) {
    LocalGitMirrorBundle.localizePresentation(e, "LocalGitMirror.MirrorPublish")
    val project = e.project
    if (project == null) { e.presentation.isEnabled = false; return }
    val settings = service<MirrorSettingsService>().state
    val configured = settings.baseUrl.isNotBlank() && SecretsStore.syncPassword.isNotBlank()
    e.presentation.isEnabled = configured
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val settings = service<MirrorSettingsService>().state
    val syncPwd = SecretsStore.syncPassword
    if (settings.baseUrl.isBlank() || syncPwd.isBlank()) {
      notify(project, LocalGitMirrorBundle.message("notify.config.missing"), NotificationType.WARNING)
      return
    }
    val history = service<OperationsHistoryService>()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "DocCache: Опубликовать в кэш", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true

        // 1. Fetch current inventory from vault
        indicator.text = "Запрашиваем инвентарь зеркала…"
        val indexRes = MirrorApi.mirrorIndex(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          insecureTls = settings.mirrorInsecureTls
        )
        if (indexRes.code !in 200..299) {
          notify(project, "Не удалось получить инвентарь зеркала: ${indexRes.body.take(500)}", NotificationType.ERROR)
          history.add("Mirror publish", false, "index failed: ${indexRes.code}")
          return
        }
        val inventory = parseInventory(indexRes.body)

        // 2. Scan Gradle cache via existing scanner
        indicator.text = "Сканируем Gradle-кеш…"
        val gradleArtifacts = DepsScanner.scanAllCandidates()

        // 3. Scan Maven local via existing scanner
        indicator.text = "Сканируем Maven local…"
        val mavenArtifacts = MavenLocalScanner.scan()

        // 4. Filter to protected namespace only
        val allArtifacts = (gradleArtifacts + mavenArtifacts).distinctBy { it.key }
        val protectedArtifacts = allArtifacts.filter { isProtectedGroup(it.group) }

        // 5. Parse wanted coordinates from mirror index and fetch from Nexus
        indicator.text = "Проверяем запрошенные артефакты…"
        val wantedList = parseWantedFromIndex(indexRes.body)
        val nexusFetched = mutableListOf<MirrorEntry>()
        var nexusMissed = 0
        if (wantedList.isNotEmpty()) {
          val nexusBaseUrl = settings.nexusBaseUrl
          val nexusCoords = wantedList.map { w ->
            NexusFetcher.WantedCoordinate(w.group, w.artifact, w.version, w.classifier, w.extension, w.mavenPath, w.reason)
          }
          val fetchResults = NexusFetcher.fetchAll(nexusBaseUrl, nexusCoords)
          for ((mavenPath, result) in fetchResults) {
            if (result.success && result.bytes != null) {
              val tmpFile = NexusFetcher.writeToTemp(result)
              if (tmpFile != null) {
                val wanted = wantedList.first { it.mavenPath == mavenPath }
                val sha256 = sha256Of(tmpFile)
                if (sha256.isNotEmpty()) {
                  nexusFetched.add(MirrorEntry(
                    group = wanted.group,
                    artifact = wanted.artifact,
                    version = wanted.version,
                    classifier = wanted.classifier,
                    extension = wanted.extension,
                    fileName = result.fileName,
                    absolutePath = tmpFile.absolutePath,
                    sha256 = sha256,
                    size = result.bytes.size.toLong()
                  ))
                }
              }
            } else {
              nexusMissed++
            }
          }
        }

        if (protectedArtifacts.isEmpty() && nexusFetched.isEmpty()) {
          notify(project,
            "Не найдено корпоративных артефактов (ru.kryptonite.*) в локальных кешах.",
            NotificationType.INFORMATION)
          history.add("Mirror publish", true, "no protected artifacts found")
          return
        }

        // 6. Parse Maven identity and compute sha256 for cache entries
        indicator.text = "Вычисляем контрольные суммы (${protectedArtifacts.size + nexusFetched.size} артефактов)…"
        val entries = mutableListOf<MirrorEntry>()
        val sha256Digest = MessageDigest.getInstance("SHA-256")

        for (art in protectedArtifacts) {
          val identity = parseMavenIdentity(art.fileName)
          if (identity.artifact.isEmpty()) continue
          val sha256 = sha256Of(File(art.absolutePath), sha256Digest)
          if (sha256.isEmpty()) continue
          entries.add(
            MirrorEntry(
              group = art.group,
              artifact = identity.artifact,
              version = identity.version,
              classifier = identity.classifier,
              extension = identity.extension,
              fileName = art.fileName,
              absolutePath = art.absolutePath,
              sha256 = sha256,
              size = art.size
            )
          )
        }
        // Add Nexus-fetched entries
        entries.addAll(nexusFetched)

        if (entries.isEmpty()) {
          notify(project,
            "Все ${protectedArtifacts.size} корпоративных артефактов имеют нераспознанные имена.",
            NotificationType.WARNING)
          history.add("Mirror publish", false, "all ${protectedArtifacts.size} artifacts unparseable")
          return
        }

        // 7. Build delta: skip artifacts already in inventory with matching sha256
        indicator.text = "Сравниваем с инвентарём зеркала…"
        val newEntries = entries.filter { entry ->
          val path = mavenPath(entry.group, entry.artifact, entry.version, entry.fileName)
          inventory[path] != entry.sha256
        }
        val existedCount = entries.size - newEntries.size

        if (newEntries.isEmpty()) {
          notify(project,
            "Все ${entries.size} корпоративных артефактов уже есть в зеркале.",
            NotificationType.INFORMATION)
          history.add("Mirror publish", true, "all ${entries.size} already in vault (delta=0)")
          return
        }

        // 8-9. Build ZIP with maven layout + manifest.json
        indicator.text = "Упаковываем ${newEntries.size} артефактов…"
        val zipBytes = buildMirrorZip(newEntries)
        val zipSize = zipBytes.size.toLong()

        // 10. Encrypt
        indicator.text = "Шифруем (${humanBytes(zipSize)})…"
        val encrypted = BundleCrypto.encryptBundleBytes(zipBytes, syncPwd)

        // 11. Upload to vault
        indicator.text = "Отправляем (${humanBytes(encrypted.size.toLong())})…"
        val res = MirrorApi.mirrorPublish(
          baseUrl = settings.baseUrl,
          apiKey = SecretsStore.mirrorApiKey,
          insecureTls = settings.mirrorInsecureTls,
          encryptedPublication = encrypted
        )

        if (res.code !in 200..299) {
          notify(project, "Не отправлено (${res.code}): ${res.message}", NotificationType.ERROR)
          history.add("Mirror publish", false, "upload failed: ${res.code} ${res.message}")
          return
        }

        // 12. Show results
        val nexusNewCount = nexusFetched.count { entry ->
          val path = mavenPath(entry.group, entry.artifact, entry.version, entry.fileName)
          inventory[path] != entry.sha256
        }
        val cacheNewCount = newEntries.size - nexusNewCount
        val totalExisted = res.existed + existedCount
        val msg = buildString {
          append("Опубликовано в зеркало: ")
          append("${res.added} добавлено")
          append(" (${cacheNewCount} из кеша, ${nexusNewCount} из Nexus)")
          append(", $totalExisted уже есть")
          if (nexusMissed > 0) append(", $nexusMissed не найдено")
          if (res.conflicts > 0) append(", ${res.conflicts} конфликтов")
          if (res.rejected > 0) append(", ${res.rejected} отклонено")
          append(" (${humanBytes(encrypted.size.toLong())})")
        }
        notify(project, msg, NotificationType.INFORMATION)
        history.add("Mirror publish", true, "added=${res.added} cacheNew=$cacheNewCount nexusNew=$nexusNewCount existed=$totalExisted nexusMissed=$nexusMissed conflicts=${res.conflicts} rejected=${res.rejected}")
      }
    })
  }

  // ─── helpers ───

  internal data class MirrorEntry(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String,
    val extension: String,
    val fileName: String,
    val absolutePath: String,
    val sha256: String,
    val size: Long
  )

  internal data class MavenIdentity(
    val artifact: String,
    val version: String,
    val classifier: String,
    val extension: String
  )

  internal data class WantedCoord(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String,
    val extension: String,
    val mavenPath: String,
    val reason: String
  )

  companion object {
    /** Check whether a group belongs to the protected corporate namespace. */
    fun isProtectedGroup(group: String): Boolean {
      return group == "ru.kryptonite" || group.startsWith("ru.kryptonite.")
    }

    /** Build a maven-layout relative path from coordinates. */
    fun mavenPath(group: String, artifact: String, version: String, fileName: String): String {
      return "${group.replace('.', '/')}/$artifact/$version/$fileName"
    }
  }
}

// ─── private functions (file-level, shared with DepsActions.kt's notify/humanBytes) ───

private fun notify(project: Project, msg: String, type: NotificationType) {
  NotificationGroupManager.getInstance()
    .getNotificationGroup("DocCache")
    .createNotification(msg, type)
    .notify(project)
}

/**
 * Parse Maven identity from a file name.
 * Format: `<artifact>-<version>[-<classifier>].<extension>`
 * The version is the first hyphen-separated part that starts with a digit,
 * followed by consecutive version-like parts (pre-release qualifiers like
 * `beta`, `rc1`, `SNAPSHOT`). Everything after the version is the classifier.
 */
private val mavenVersionQualifier = Regex("^(alpha|beta|rc|snapshot|m|cr|final|release|ga)\\d*$", RegexOption.IGNORE_CASE)

internal fun parseMavenIdentity(fileName: String): PublishMirrorAction.MavenIdentity {
  // Extension is after the last dot, but ONLY if what follows is not purely numeric
  // (to avoid treating "foo-1.0" as having extension "0").
  val dotIdx = fileName.lastIndexOf('.')
  val extension: String
  val base: String
  if (dotIdx >= 0) {
    val afterDot = fileName.substring(dotIdx + 1)
    if (afterDot.all { it.isDigit() }) {
      // "foo-1.0" — the dot is part of the version, not an extension separator
      extension = ""
      base = fileName
    } else {
      extension = afterDot
      base = fileName.substring(0, dotIdx)
    }
  } else {
    extension = ""
    base = fileName
  }

  val parts = base.split('-')
  val versionStart = parts.indexOfFirst { it.isNotEmpty() && it[0].isDigit() }
  if (versionStart < 0) {
    return PublishMirrorAction.MavenIdentity("", "", "", extension)
  }

  val artifact = parts.subList(0, versionStart).joinToString("-")

  // collect consecutive version-like parts: starts with digit, or is a known qualifier
  val versionEnd = (versionStart until parts.size).firstOrNull { i ->
    val part = parts[i]
    !(part.isNotEmpty() && (part[0].isDigit() || part.matches(mavenVersionQualifier)))
  } ?: parts.size

  val version = parts.subList(versionStart, versionEnd).joinToString("-")
  val classifier = if (versionEnd < parts.size) {
    parts.subList(versionEnd, parts.size).joinToString("-")
  } else {
    ""
  }

  return PublishMirrorAction.MavenIdentity(artifact, version, classifier, extension)
}

/** Parse the inventory JSON from the mirror index response into a path→sha256 map. */
private fun parseInventory(body: String): Map<String, String> {
  return try {
    val json = Json.parseToJsonElement(body).jsonObject
    val inv = json["inventory"]?.jsonObject ?: return emptyMap()
    inv.mapValues { (_, v) -> v.jsonPrimitive.content }
  } catch (_: Throwable) {
    emptyMap()
  }
}

/** Compute SHA-256 of a file's bytes. Returns empty string on any I/O failure. */
private fun sha256Of(file: File, digest: MessageDigest = MessageDigest.getInstance("SHA-256")): String {
  return try {
    digest.reset()
    file.inputStream().buffered().use { ins ->
      val buf = ByteArray(8192)
      while (true) {
        val n = ins.read(buf)
        if (n <= 0) break
        digest.update(buf, 0, n)
      }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
  } catch (_: Throwable) {
    ""
  }
}

/** Parse the `wanted` field from the mirror index JSON response. */
private fun parseWantedFromIndex(body: String): List<PublishMirrorAction.WantedCoord> {
  return try {
    val json = Json.parseToJsonElement(body).jsonObject
    val wanted = json["wanted"]?.jsonArray ?: return emptyList()
    wanted.mapNotNull { el ->
      val obj = el.jsonObject
      val mavenPath = obj["maven_path"]?.jsonPrimitive?.content ?: return@mapNotNull null
      val coord = obj["coord"]?.jsonObject
      if (coord != null) {
        PublishMirrorAction.WantedCoord(
          group = coord["group"]?.jsonPrimitive?.content ?: "",
          artifact = coord["artifact"]?.jsonPrimitive?.content ?: "",
          version = coord["version"]?.jsonPrimitive?.content ?: "",
          classifier = coord["classifier"]?.jsonPrimitive?.content ?: "",
          extension = coord["extension"]?.jsonPrimitive?.content ?: "jar",
          mavenPath = mavenPath,
          reason = obj["reason"]?.jsonPrimitive?.content ?: ""
        )
      } else {
        // ponytail: parse from mavenPath when coord is null
        val parsed = parseMavenCoordFromPath(mavenPath) ?: return@mapNotNull null
        PublishMirrorAction.WantedCoord(
          group = parsed.group,
          artifact = parsed.artifact,
          version = parsed.version,
          classifier = parsed.classifier,
          extension = parsed.extension,
          mavenPath = mavenPath,
          reason = obj["reason"]?.jsonPrimitive?.content ?: ""
        )
      }
    }
  } catch (_: Throwable) {
    emptyList()
  }
}

/** Parse group/artifact/version/classifier/extension from a maven-layout path. */
private data class ParsedMavenCoord(
  val group: String,
  val artifact: String,
  val version: String,
  val classifier: String,
  val extension: String
)

private fun parseMavenCoordFromPath(mavenPath: String): ParsedMavenCoord? {
  val segs = mavenPath.split("/")
  if (segs.size < 4) return null
  val fileName = segs.last()
  val version = segs[segs.size - 2]
  val artifact = segs[segs.size - 3]
  val group = segs.subList(0, segs.size - 3).joinToString(".")
  if (group.isEmpty()) return null

  val identity = parseMavenIdentity(fileName)
  return ParsedMavenCoord(group, artifact, version, identity.classifier, identity.extension)
}

/** Build a ZIP with maven-layout entries and a manifest.json at the root. */
private fun buildMirrorZip(entries: List<PublishMirrorAction.MirrorEntry>): ByteArray {
  val baos = ByteArrayOutputStream()
  ZipOutputStream(baos).use { zip ->
    // manifest.json
    zip.putNextEntry(ZipEntry("manifest.json"))
    val manifest = buildJsonObject {
      put("schema", "mirror-v1")
      put("entries", buildJsonArray {
        for (entry in entries) {
          val path = "maven/${PublishMirrorAction.mavenPath(entry.group, entry.artifact, entry.version, entry.fileName)}"
          add(buildJsonObject {
            put("path", path)
            put("sha256", entry.sha256)
          })
        }
      })
    }
    zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
    zip.closeEntry()

    // artifact files
    for (entry in entries) {
      val path = "maven/${PublishMirrorAction.mavenPath(entry.group, entry.artifact, entry.version, entry.fileName)}"
      val file = File(entry.absolutePath)
      if (!file.exists()) continue
      zip.putNextEntry(ZipEntry(path))
      file.inputStream().use { it.copyTo(zip) }
      zip.closeEntry()
    }
  }
  return baos.toByteArray()
}