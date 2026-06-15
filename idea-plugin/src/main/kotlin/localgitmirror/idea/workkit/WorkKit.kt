package localgitmirror.idea.workkit

import java.io.File

object WorkKit {
  data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
    fun ok(): Boolean = exitCode == 0
  }

  @Suppress("UNUSED_PARAMETER")
  fun runBackupWorkStealth(
    workDir: File,
    password: String,
    repoName: String? = null,
    excludeBases: List<String> = emptyList(),
    timeoutSeconds: Long = 300,
    kitDir: File? = null,
    additionalBranches: List<String> = emptyList(),
    negotiationUsed: Boolean = false
  ): Result {
    return try {
      val resolvedRepo = repoName?.trim().takeUnless { it.isNullOrBlank() } ?: workDir.name
      val bundle = NativeBundleBuilder.createBundle(workDir, excludeBases = excludeBases, additionalBranches = additionalBranches, negotiationUsed = negotiationUsed)
      val syncFile = NativeBundleBuilder.makeSyncFile(workDir, resolvedRepo)

      val encryptedBytes = NativeStealthDump.encryptBundleBytes(bundle.bundleBytes, password)
      syncFile.writeBytes(encryptedBytes)

      val stdout = buildString {
        appendLine("[+] Sync package ready")
        appendLine("Mode: ${bundle.mode}")
        appendLine("File: ${syncFile.absolutePath} (${syncFile.length()} bytes)")
      }.trim()
      Result(0, stdout, "")
    } catch (t: Throwable) {
      Result(1, "", t.message ?: "Sync export failed")
    }
  }

  fun syncDir(projectDir: File): File {
    val gitDirRes = ProcessBuilder(listOf("git", "rev-parse", "--git-dir"))
      .directory(projectDir).redirectErrorStream(false).start()
    val raw = gitDirRes.inputStream.bufferedReader().readText().trim()
    gitDirRes.waitFor()
    val gitDir = if (File(raw).isAbsolute) File(raw) else File(projectDir, raw)
    return File(gitDir, "lgm")
  }

  fun findLatestDump(projectDir: File, repoName: String): File? {
    val dir = syncDir(projectDir)
    if (!dir.exists()) return null
    val files = dir.listFiles { f ->
      f.isFile && f.name.startsWith("cache_${repoName}_") && f.name.endsWith(".bin")
    } ?: return null
    return files.maxByOrNull { it.lastModified() }
  }

  fun runStealthApply(
    workDir: File,
    password: String,
    dumpFile: File,
    mode: String,
    newBranchName: String? = null,
    timeoutSeconds: Long = 300,
    kitDir: File? = null
  ): Result {
    @Suppress("UNUSED_VARIABLE")
    val _ignoreTimeout = timeoutSeconds
    @Suppress("UNUSED_VARIABLE")
    val _ignoreKitDir = kitDir

    val res = NativeStealthApply.applyDump(
      workDir = workDir,
      password = password,
      dumpFile = dumpFile,
      mode = mode,
      newBranchName = newBranchName
    )
    return Result(res.exitCode, res.stdout, res.stderr)
  }
}
