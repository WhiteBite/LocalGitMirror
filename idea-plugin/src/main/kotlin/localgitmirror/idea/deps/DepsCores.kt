package localgitmirror.idea.deps

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.workkit.BundleCrypto
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// Shared helpers (moved from DepsActions.kt so the cores are self-contained)
// ─────────────────────────────────────────────────────────────────────────────

internal fun humanBytes(b: Long): String {
  if (b < 1024) return "$b B"
  val units = arrayOf("KB", "MB", "GB", "TB")
  var v = b.toDouble() / 1024
  var i = 0
  while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
  return java.text.DecimalFormat("0.#").format(v) + " " + units[i]
}

internal fun resolveRepoName(project: Project): String {
  val dir = project.basePath?.let { File(it) } ?: File(".")
  return localgitmirror.idea.sync.v2.RepoResolver
    .resolve(project, dir, "")
    .sanitized
    .ifBlank { project.name }
}

internal fun projectJdkHome(project: Project): String? = try {
  com.intellij.openapi.roots.ProjectRootManager.getInstance(project).projectSdk?.homePath
} catch (_: Throwable) { null }

/**
 * STEALTH cleanup: earlier versions wrote `.lgm-deps-debug.txt`,
 * `.lgm-last-sent-deps.txt` and `.lgm-last-deps.txt` into the project root.
 * Those leak corporate package names into the git working tree. Remove any
 * that linger. Best-effort; never throws.
 */
internal fun sweepLegacyDepsFiles(projectDir: File) {
  listOf(".lgm-deps-debug.txt", ".lgm-last-sent-deps.txt", ".lgm-last-deps.txt").forEach {
    runCatching { File(projectDir, it).takeIf { f -> f.exists() }?.delete() }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// DepsRequester — DOME side: detect missing corporate deps and send a request
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Extracted core of [RequestDepsAction]. Detects which gradle/npm dependencies
 * the project needs but cannot resolve locally, builds an encrypted manifest,
 * and uploads it to the Mirror server via [MirrorApi.depsRequest].
 *
 * The caller is responsible for:
 *  - checking that baseUrl/syncPassword are configured
 *  - resolving the repo name
 *  - checking the pending list (skip if a request already exists)
 *  - notifications and history
 */
object DepsRequester {

  data class Result(
    val success: Boolean,
    val message: String,
    val missingCount: Int,
    val requestId: String?,
    val ecosystem: String
  )

  fun request(
    project: Project,
    settings: MirrorSettingsService.State,
    syncPwd: String,
    repo: String,
    indicator: ProgressIndicator? = null
  ): Result {
    val projectDir = project.basePath?.let { File(it) } ?: File(".")
    val jdkHome = projectJdkHome(project)

    indicator?.isIndeterminate = true
    sweepLegacyDepsFiles(projectDir)

    val ecosystems = DepsEcosystems.detect(projectDir)
    if (ecosystems.isEmpty()) {
      return Result(
        success = false,
        message = "Не найден ни gradle, ни npm проект в ${projectDir.name}.",
        missingCount = 0,
        requestId = null,
        ecosystem = ""
      )
    }

    val allMissing = mutableListOf<DepCoordinate>()
    val logs = StringBuilder()
    for (eco in ecosystems) {
      indicator?.text = "Определяем недостающие ${eco.id}-зависимости…"
      val r = eco.resolveMissing(projectDir, jdkHome)
      logs.appendLine("[${eco.id}] ok=${r.ok} missing=${r.missing.size} (${r.durationMs}ms)")
      if (!r.ok && r.missing.isEmpty()) {
        logs.appendLine("  ! ${r.log.takeLast(300)}")
      }
      allMissing.addAll(r.missing)
    }
    val missing = allMissing.distinctBy { it.key }

    DepsDiagnostics.enabled = settings.depsDiagnosticsEnabled
    DepsDiagnostics.verbose = settings.depsDiagnosticsVerbose
    DepsDiagnostics.event("request: ecosystems=${ecosystems.joinToString(",") { it.id }} missing=${missing.size}")
    for (line in logs.lineSequence()) if (line.isNotBlank()) DepsDiagnostics.event(line.trim())
    DepsDiagnostics.detail("Missing coordinates") { missing.map { "${it.ecosystem}  ${it.label}" } }

    if (missing.isEmpty()) {
      return Result(
        success = true,
        message = "Всё резолвится локально — запрашивать нечего.",
        missingCount = 0,
        requestId = null,
        ecosystem = ecosystems.joinToString(",") { it.id }
      )
    }

    val manifest = DepsRequestManifest(
      version = 3,
      requester = System.getProperty("user.name") ?: "dome",
      project = repo,
      ecosystem = ecosystems.joinToString(",") { it.id },
      missing = missing,
      present = ecosystems.flatMap { it.enumeratePresent() }
    )
    val encrypted = BundleCrypto.encryptBundleBytes(DepsRequestManifest.toJsonBytes(manifest), syncPwd)

    indicator?.text = "Отправляем запрос (${missing.size} координат)…"
    val res = MirrorApi.depsRequest(
      baseUrl = settings.baseUrl,
      apiKey = SecretsStore.mirrorApiKey,
      repo = repo,
      insecureTls = settings.mirrorInsecureTls,
      encryptedManifest = encrypted
    )
    if (res.code !in 200..299 || res.id == null) {
      return Result(
        success = false,
        message = "Запрос не отправлен (${res.code}): ${res.message}",
        missingCount = missing.size,
        requestId = null,
        ecosystem = manifest.ecosystem
      )
    }

    return Result(
      success = true,
      message = "Запрошено ${missing.size} недостающих зависимостей (${manifest.ecosystem}).\n" +
        "На рабочей машине нажми «Выдать запрошенные зависимости».",
      missingCount = missing.size,
      requestId = res.id,
      ecosystem = manifest.ecosystem
    )
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// DepsResponder — WORK side: collect requested coords from cache, ship them
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Extracted core of [RespondDepsAction]. Decrypts the manifest, scans local
 * caches for the requested coordinates, packs them into a ZIP, encrypts it,
 * and uploads the response via [MirrorApi.depsRespond].
 *
 * The caller is responsible for:
 *  - listing pending requests and downloading the manifest blob
 *  - notifications and history
 *  - updating [RespondDepsAction.lastKnownPendingCount]
 *
 * @param manifestBlob  the encrypted manifest bytes (downloaded from the server)
 * @param requestId     the server-assigned request ID (needed for depsRespond)
 */
object DepsResponder {

  data class Result(
    val success: Boolean,
    val message: String,
    val sentCount: Int,
    val notFoundCount: Int,
    val bytes: Long
  )

  fun respond(
    project: Project,
    settings: MirrorSettingsService.State,
    syncPwd: String,
    repo: String,
    requestId: String,
    manifestBlob: ByteArray,
    indicator: ProgressIndicator? = null
  ): Result {
    indicator?.isIndeterminate = true
    project.basePath?.let { sweepLegacyDepsFiles(File(it)) }

    val manifest = try {
      DepsRequestManifest.fromJsonBytes(BundleCrypto.decryptDumpBytes(manifestBlob, syncPwd))
    } catch (t: Throwable) {
      return Result(
        success = false,
        message = "Ошибка расшифровки запроса: ${t.message ?: t::class.simpleName}",
        sentCount = 0, notFoundCount = 0, bytes = 0
      )
    }

    if (manifest.version < 3 || manifest.missing.isEmpty()) {
      val detail = "v=${manifest.version} missing=${manifest.missing.size} eco='${manifest.ecosystem}'"
      val hint = if (manifest.version < 3)
        "Обнови плагин на домашней машине и повтори «Запросить»."
      else
        "Домашняя машина решила что ничего не нужно. Открой Help → Show Log, найди '[deps] request:' — там будет причина."
      return Result(
        success = false,
        message = "Запрос пуст или в старом формате ($detail). $hint",
        sentCount = 0, notFoundCount = 0, bytes = 0
      )
    }

    indicator?.text = "Ищем ${manifest.missing.size} координат в локальном кеше…"
    val workProjectDir = project.basePath?.let { File(it) }
    GradleEcosystem.collectProjectDir = workProjectDir
    val presentIndex = manifest.presentIndex()
    val byEco = manifest.missing.groupBy { it.ecosystem }
    val entries = mutableListOf<DepFileEntry>()
    val notFound = mutableListOf<DepCoordinate>()
    for ((ecoId, coords) in byEco) {
      val eco = DepsEcosystems.byId(ecoId)
      if (eco == null) { notFound.addAll(coords); continue }
      entries.addAll(eco.collect(coords, presentIndex) { notFound.add(it) })
    }
    GradleEcosystem.collectProjectDir = null

    val nexusBaseUrl = settings.nexusBaseUrl
    var nexusRecovered = 0
    var pkgMgrGradle = 0
    var pkgMgrNpm = 0
    val tempFiles = mutableListOf<File>()

    if (notFound.isNotEmpty() && workProjectDir != null) {
      val gradleMissing = notFound.filter { it.ecosystem == "gradle" }
      val npmMissing = notFound.filter { it.ecosystem == "npm" }
      if (gradleMissing.isNotEmpty()) {
        pkgMgrGradle = PkgManagerFetch.fetchGradle(workProjectDir, gradleMissing, indicator)
      }
      if (npmMissing.isNotEmpty()) {
        pkgMgrNpm = PkgManagerFetch.fetchNpm(workProjectDir, npmMissing, indicator)
      }
      if (pkgMgrGradle > 0 || pkgMgrNpm > 0) {
        val reNotFound = mutableListOf<DepCoordinate>()
        GradleEcosystem.collectProjectDir = workProjectDir
        for ((ecoId, coords) in notFound.groupBy { it.ecosystem }) {
          val eco = DepsEcosystems.byId(ecoId)
          if (eco == null) { reNotFound.addAll(coords); continue }
          entries.addAll(eco.collect(coords, presentIndex) { reNotFound.add(it) })
        }
        GradleEcosystem.collectProjectDir = null
        notFound.clear()
        notFound.addAll(reNotFound)
      }
    }

    if (notFound.isNotEmpty()) {
      val gradleMissing = notFound.filter { it.ecosystem == "gradle" }
      val nonGradle = notFound.filter { it.ecosystem != "gradle" }
      if (nexusBaseUrl.isNotBlank() && gradleMissing.isNotEmpty()) {
        val cap = minOf(50, gradleMissing.size)
        indicator?.text = "Пробуем Nexus для $cap отсутствующих артефактов…"
        val stillMissing = mutableListOf<DepCoordinate>()
        for (coord in gradleMissing.take(cap)) {
          val ok = runCatching { fetchCoordFromNexus(coord, nexusBaseUrl, entries, tempFiles) }
            .getOrDefault(false)
          if (ok) nexusRecovered++ else stillMissing.add(coord)
        }
        stillMissing.addAll(gradleMissing.drop(cap))
        notFound.clear()
        notFound.addAll(nonGradle)
        notFound.addAll(stillMissing)
      }
    }

    try {
      if (entries.isEmpty()) {
        // Diagnostics only; the caller (action) does the detailed history logging
        // by scanning cache roots itself, keeping the core free of UI concerns.
        val gradleEnv = System.getenv("GRADLE_USER_HOME") ?: "(не задана)"
        DepsDiagnostics.event("respond: 0 found. GRADLE_USER_HOME=$gradleEnv" +
          (if (nexusBaseUrl.isNotBlank()) " nexusTried=${notFound.size}" else ""))
        DepsDiagnostics.detail("Requested (not found)") { manifest.missing.map { "${it.ecosystem}  ${it.label}" } }

        val hint = if (nexusBaseUrl.isBlank() && notFound.isNotEmpty())
          "\nЗадайте Nexus URL в настройках DocCache, чтобы доставать отсутствующие в кэше артефакты напрямую."
        else ""
        return Result(
          success = false,
          message = "Ни одна из ${manifest.missing.size} зависимостей не найдена в кеше.$hint",
          sentCount = 0,
          notFoundCount = manifest.missing.size,
          bytes = 0
        )
      }

      // Pack with ecosystem-prefixed entry names so the dome can route them.
      val prefixed = entries.map {
        it.copy(relativePath = "${it.coordinate.ecosystem}/${it.relativePath}")
      }.toMutableList()
      // Ship the project's npm lockfile (if present) as a meta entry.
      workProjectDir?.let { dir ->
        val lock = File(dir, "package-lock.json")
        if (lock.isFile) {
          prefixed.add(DepFileEntry(
            coordinate = DepCoordinate("npm", "", "package-lock.json", ""),
            absolutePath = lock.absolutePath,
            relativePath = "__meta__/package-lock.json",
            size = lock.length()
          ))
        }
      }
      val diffSize = prefixed.sumOf { it.size }
      indicator?.text = "Упаковываем ${prefixed.size} файлов (${humanBytes(diffSize)})…"
      val zipBytes = DepsBundler.packEntries(prefixed)
      val encrypted = BundleCrypto.encryptBundleBytes(zipBytes, syncPwd)

      indicator?.text = "Отправляем (${humanBytes(encrypted.size.toLong())})…"
      val res = MirrorApi.depsRespond(
        baseUrl = settings.baseUrl,
        apiKey = SecretsStore.mirrorApiKey,
        repo = repo,
        insecureTls = settings.mirrorInsecureTls,
        requestId = requestId,
        encryptedArchive = encrypted
      )
      if (res.code !in 200..299 || res.id == null) {
        return Result(
          success = false,
          message = "Не отправлено (${res.code}): ${res.message}",
          sentCount = 0, notFoundCount = 0, bytes = 0
        )
      }

      val foundCoords = entries.map { it.coordinate.key }.toSet()
      val notFoundUnique = notFound.distinctBy { it.key }
      DepsDiagnostics.enabled = settings.depsDiagnosticsEnabled
      DepsDiagnostics.verbose = settings.depsDiagnosticsVerbose
      DepsDiagnostics.event("respond: shipped=${foundCoords.size} notFound=${notFoundUnique.size} pkgMgrGradle=$pkgMgrGradle pkgMgrNpm=$pkgMgrNpm nexusRecovered=$nexusRecovered bytes=$diffSize")
      DepsDiagnostics.detail("Shipped coordinates") {
        entries.map { it.coordinate.label }.distinct().sorted()
      }
      if (notFoundUnique.isNotEmpty()) {
        DepsDiagnostics.detail("Requested but NOT in local cache") {
          notFoundUnique.map { "${it.ecosystem}  ${it.label}" }
        }
      }

      val warn = if (notFoundUnique.isNotEmpty()) " (не найдено ${notFoundUnique.size})" else ""
      val pkgMgrInfo = if (pkgMgrGradle + pkgMgrNpm > 0) ", докачано пакетными менеджерами: ${pkgMgrGradle + pkgMgrNpm}" else ""
      val nexusInfo = if (nexusRecovered > 0) ", восстановлено через Nexus: $nexusRecovered" else ""
      val hint = if (nexusBaseUrl.isBlank() && notFoundUnique.isNotEmpty())
        "\nЗадайте Nexus URL в настройках DocCache, чтобы доставать отсутствующие в кэше артефакты напрямую."
      else ""
      return Result(
        success = true,
        message = "Отправлено ${humanBytes(diffSize)} — ${foundCoords.size} зависимостей$warn$pkgMgrInfo$nexusInfo.$hint",
        sentCount = foundCoords.size,
        notFoundCount = notFoundUnique.size,
        bytes = diffSize
      )
    } finally {
      tempFiles.forEach { runCatching { it.delete() } }
    }
  }

  private fun fetchCoordFromNexus(
    coord: DepCoordinate,
    nexusBaseUrl: String,
    entries: MutableList<DepFileEntry>,
    tempFiles: MutableList<File>
  ): Boolean {
    val main = NexusFetcher.fetch(nexusBaseUrl, coord.group, coord.name, coord.version, coord.classifier, "jar")
    val pom = NexusFetcher.fetch(nexusBaseUrl, coord.group, coord.name, coord.version, "", "pom")
    val module = NexusFetcher.fetch(nexusBaseUrl, coord.group, coord.name, coord.version, "", "module")
    var any = false
    for (r in listOf(main, pom, module)) {
      if (!r.success || r.bytes == null) continue
      val tmp = NexusFetcher.writeToTemp(r) ?: continue
      tempFiles.add(tmp)
      entries.add(DepFileEntry(
        coordinate = coord,
        absolutePath = tmp.absolutePath,
        relativePath = MavenLocalScanner.mavenLocalRelativePath(coord.group, coord.name, coord.version, r.fileName),
        size = r.bytes.size.toLong()
      ))
      any = true
    }
    return any
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// DepsApplier — DOME side: decrypt and unpack received deps into local caches
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Extracted core of [ApplyDepsAction]. Decrypts the response archive, unpacks
 * it into the local gradle/npm cache roots, runs post-install steps (npm cache
 * add, lockfile rewriting), and returns a result.
 *
 * The caller is responsible for:
 *  - listing responses and downloading the response blob
 *  - calling [MirrorApi.depsAck] after a successful apply
 *  - showing the "run npm/yarn install?" dialog (action only)
 *  - notifications and history
 *
 * @param responseBlob  the encrypted response bytes (downloaded from the server)
 */
object DepsApplier {

  data class Result(
    val success: Boolean,
    val message: String,
    val installed: Int,
    val skipped: Int,
    val invalid: Int,
    val bytes: Long,
    val npmInstalled: Boolean = false,
    val postStatus: String = "",
    val lockMsg: String = "",
    val suggestNpmInstall: Boolean = false,
    val suggestYarnInstall: Boolean = false
  )

  fun apply(
    project: Project,
    settings: MirrorSettingsService.State,
    syncPwd: String,
    responseBlob: ByteArray,
    indicator: ProgressIndicator? = null
  ): Result {
    indicator?.isIndeterminate = true
    indicator?.text = "Расшифровка и распаковка…"

    val unpackResult = try {
      val decrypted = BundleCrypto.decryptDumpBytes(responseBlob, syncPwd)
      val applyProjectDir = project.basePath?.let { File(it) }
      GradleEcosystem.collectProjectDir = applyProjectDir
      try {
        DepsBundler.unpackRouted(decrypted) { ecoId ->
          val eco = DepsEcosystems.byId(ecoId) ?: return@unpackRouted null
          eco.cacheRoot()
        }
      } finally {
        GradleEcosystem.collectProjectDir = null
      }
    } catch (t: Throwable) {
      return Result(
        success = false,
        message = "Ошибка применения: ${t.message ?: t::class.simpleName}",
        installed = 0, skipped = 0, invalid = 0, bytes = 0
      )
    }

    // Ensure ~/.gradle/init.d/lgm-mavenlocal-fallback.gradle exists so
    // mavenLocal() is automatically declared on every gradle build.
    runCatching { GradleEcosystem.ensureMavenLocalInitScript() }
      .onFailure { DepsDiagnostics.event("apply: init-script write failed: ${it.message}") }

    // Best-effort per-ecosystem post-install (e.g. npm cache add).
    val postStatus = StringBuilder()
    runCatching {
      val npmMirrorRoot = NpmEcosystem.cacheRoot()
      if (npmMirrorRoot.isDirectory) {
        val tgzRel = npmMirrorRoot.walkTopDown()
          .filter { it.isFile && it.extension == "tgz" }
          .map { it.relativeTo(npmMirrorRoot).path.replace('\\', '/') }
          .toList()
        if (tgzRel.isNotEmpty()) {
          val s = NpmEcosystem.postInstall(tgzRel)
          if (s.isNotBlank()) postStatus.append(s)
        }
      }
    }

    val npmMirror = NpmEcosystem.cacheRoot()
    val npmInstalled = unpackResult.installedEntries.any { it.endsWith(".tgz") }

    // npm lockfile (shipped as __meta__/package-lock.json): rewrite resolved
    // URLs corporate-registry -> npmjs, write it into the project.
    var lockMsg = ""
    val applyProj = project.basePath?.let { File(it) }
    val isYarnProj = applyProj != null && File(applyProj, "yarn.lock").isFile
    val lockBytes = unpackResult.meta["package-lock.json"]
    var suggestNpm = false
    var suggestYarn = false

    if (isYarnProj && applyProj != null) {
      val ymir = NpmEcosystem.yarnOfflineMirror()
      val cnt = runCatching { NpmEcosystem.buildYarnMirror(NpmEcosystem.cacheRoot(), ymir) }.getOrDefault(0)
      runCatching { NpmEcosystem.setGlobalYarnMirror(ymir) }
      lockMsg = "yarn: offline-mirror ($cnt пакет(ов)) в глобальном ~/.yarnrc; yarn.lock не тронут."
      suggestYarn = true
    } else if (lockBytes != null && applyProj != null && File(applyProj, "package.json").isFile) {
      val nrw = runCatching {
        val (rewritten, n) = NpmEcosystem.rewriteLockToNpmjs(String(lockBytes, Charsets.UTF_8), applyProj)
        File(applyProj, "package-lock.json").writeText(rewritten, Charsets.UTF_8)
        n
      }.getOrNull()
      if (nrw != null) {
        lockMsg = "package-lock.json применён ($nrw ссылок → npmjs)."
        suggestNpm = true
      } else {
        lockMsg = "package-lock.json получен, но записать не удалось."
      }
    }

    DepsDiagnostics.enabled = settings.depsDiagnosticsEnabled
    DepsDiagnostics.verbose = settings.depsDiagnosticsVerbose
    DepsDiagnostics.event("apply: installed=${unpackResult.installed} skipped=${unpackResult.skipped} invalid=${unpackResult.invalid} bytes=${unpackResult.totalBytes}")
    DepsDiagnostics.detail("Installed") { unpackResult.installedEntries }
    if (unpackResult.skippedEntries.isNotEmpty()) {
      DepsDiagnostics.detail("Already present") { unpackResult.skippedEntries }
    }

    val msg = buildString {
      append("Применено: ${unpackResult.installed} установлено, ${unpackResult.skipped} уже было")
      if (unpackResult.invalid > 0) append(", ${unpackResult.invalid} отклонено")
      append(" (${humanBytes(unpackResult.totalBytes)}).")
      if (npmInstalled) {
        appendLine(); appendLine()
        if (postStatus.isNotEmpty()) {
          append("npm: $postStatus")
        } else {
          append("npm-тарболы: ${npmMirror.absolutePath}\nставь из этой папки (npm install --offline).")
        }
      }
      if (lockMsg.isNotEmpty()) {
        appendLine(); appendLine(); append(lockMsg)
      }
    }

    return Result(
      success = true,
      message = msg,
      installed = unpackResult.installed,
      skipped = unpackResult.skipped,
      invalid = unpackResult.invalid,
      bytes = unpackResult.totalBytes,
      npmInstalled = npmInstalled,
      postStatus = postStatus.toString(),
      lockMsg = lockMsg,
      suggestNpmInstall = suggestNpm,
      suggestYarnInstall = suggestYarn
    )
  }
}
