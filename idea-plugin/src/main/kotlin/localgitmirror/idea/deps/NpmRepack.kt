package localgitmirror.idea.deps

import java.io.File

internal object NpmRepack {
  /**
   * DOME side. Feed each received tarball into npm's own cache via
   * `npm cache add <file>`, so a later `npm install` resolves them offline
   * without any .npmrc surgery. Best-effort: if npm isn't on PATH we just
   * report that and leave the tarballs in the mirror folder for manual use.
   */
  fun postInstall(installedRelativePaths: List<String>): String {
    val mirror = NpmCache.npmOfflineMirror()
    val tarballs = installedRelativePaths
      .filter { it.endsWith(".tgz") }
      .map { File(mirror, it) }
      .filter { it.isFile }
    if (tarballs.isEmpty()) return ""

    val npm = npmCommand() ?: return "npm не найден в PATH — тарболы в ${mirror.absolutePath}, поставь вручную (npm install --offline)"
    var ok = 0
    var fail = 0
    for (tb in tarballs) {
      try {
        val proc = ProcessBuilder(npm + listOf("cache", "add", tb.absolutePath))
          .redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readText() }  // drain
        if (proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0) ok++ else fail++
      } catch (_: Exception) { fail++ }
    }
    return "npm cache add: $ok ok" + (if (fail > 0) ", $fail ошибок (см. ${mirror.absolutePath})" else "")
  }

  fun npmCommand(): List<String>? {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    // On Windows npm is a .cmd shim and must be run via cmd /c.
    return if (isWindows) listOf("cmd", "/c", "npm") else listOf("npm")
  }

  /** Run `npm pack <pkgDir>` in [tempDir]; returns the produced .tgz (in tempDir). */
  fun repackWithNpm(npm: List<String>, pkgDir: File, tempDir: File): File? {
    return try {
      val proc = ProcessBuilder(npm + listOf("pack", pkgDir.absolutePath))
        .directory(tempDir)
        .redirectErrorStream(true).start()
      proc.inputStream.bufferedReader().use { it.readText() }
      if (!proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
        proc.destroyForcibly()
        return null
      }
      if (proc.exitValue() != 0) return null
      tempDir.listFiles { f -> f.isFile && f.extension == "tgz" }
        ?.maxByOrNull { it.lastModified() }
    } catch (_: Throwable) { null }
  }

  /** Read "name@version" from a package.json, or null. */
  fun readPackageNameVersion(packageJson: File): String? {
    return try {
      val text = packageJson.readText(Charsets.UTF_8)
      val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
      val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
      if (name != null && version != null) "$name@$version" else null
    } catch (_: Throwable) { null }
  }
}
