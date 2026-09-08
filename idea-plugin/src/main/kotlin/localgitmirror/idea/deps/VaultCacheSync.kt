package localgitmirror.idea.deps

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.OperationsHistoryService
import localgitmirror.idea.settings.SecretsStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object VaultCacheSync {

  private val running = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

  fun syncInBackground(project: Project, source: String) {
    if (project.isDisposed) return
    val key = project.name + "@" + System.identityHashCode(project)
    if (running.putIfAbsent(key, true) != null) return

    Thread({
      try {
        runSync(project, source)
      } finally {
        running.remove(key)
      }
    }, "doccache-vault-sync").apply { isDaemon = true }.start()
  }

  private fun runSync(project: Project, source: String) {
    val settings = service<MirrorSettingsService>().state
    val syncPwd = SecretsStore.syncPassword
    if (settings.baseUrl.isBlank() || syncPwd.isBlank()) {
      DepsDiagnostics.event("vault-sync: skipped (no config) [$source]")
      return
    }

    val indexRes = MirrorApi.mirrorIndex(
      baseUrl = settings.baseUrl,
      apiKey = SecretsStore.mirrorApiKey,
      insecureTls = settings.mirrorInsecureTls
    )
    if (indexRes.code !in 200..299) {
      DepsDiagnostics.event("vault-sync: index failed code=${indexRes.code} [$source]")
      return
    }

    val inventory = parseInventory(indexRes.body)
    if (inventory.isEmpty()) {
      DepsDiagnostics.event("vault-sync: inventory empty [$source]")
      return
    }

    val cacheRoot = MavenLocalScanner.cacheRoot()
    val restored = AtomicInteger(0)
    val skipped = AtomicInteger(0)
    val failed = AtomicInteger(0)

    val executor = Executors.newFixedThreadPool(4) { r ->
      Thread(r, "doccache-vault-fetch").apply { isDaemon = true }
    }
    try {
      val futures = inventory.keys.map { mavenPath ->
        executor.submit {
          if (project.isDisposed) return@submit
          try {
            val localFile = File(cacheRoot, mavenPath)
            if (localFile.exists() && localFile.length() > 0) {
              skipped.incrementAndGet()
              return@submit
            }
            val res = MirrorApi.vaultM2Fetch(
              baseUrl = settings.baseUrl,
              apiKey = SecretsStore.mirrorApiKey,
              insecureTls = settings.mirrorInsecureTls,
              mavenPath = mavenPath
            )
            if (res.code !in 200..299 || res.bytes == null) {
              failed.incrementAndGet()
              return@submit
            }
            localFile.parentFile?.mkdirs()
            localFile.writeBytes(res.bytes)
            restored.incrementAndGet()
          } catch (_: Throwable) {
            failed.incrementAndGet()
          }
        }
      }
      for (f in futures) runCatching { f.get() }
    } finally {
      executor.shutdown()
    }

    val r = restored.get()
    val s = skipped.get()
    val f = failed.get()
    DepsDiagnostics.event("vault-sync: restored=$r skipped=$s failed=$f [$source]")

    if (r > 0 && !project.isDisposed) {
      service<OperationsHistoryService>().add(
        "Восстановлено из хранилища: $r (пропущено $s)", true,
        "restored=$r skipped=$s failed=$f source=$source"
      )
    }
  }

  private fun parseInventory(body: String): Map<String, String> {
    return try {
      val json = Json.parseToJsonElement(body).jsonObject
      val inv = json["inventory"]?.jsonObject ?: return emptyMap()
      inv.mapValues { (_, v) -> v.jsonPrimitive.content }
    } catch (_: Throwable) {
      emptyMap()
    }
  }
}
