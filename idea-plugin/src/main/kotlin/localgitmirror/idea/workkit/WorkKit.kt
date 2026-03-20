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
    baseCommit: String? = null,
    timeoutSeconds: Long = 300,
    kitDir: File? = null
  ): Result {
    return try {
      val resolvedRepo = repoName?.trim().takeUnless { it.isNullOrBlank() } ?: workDir.name
      val bundle = NativeBundleBuilder.createBundle(workDir, baseCommit = baseCommit)
      val dumpFile = NativeBundleBuilder.makeDumpFile(workDir, resolvedRepo)

      val bundleBytes = bundle.bundleFile.readBytes()
      val dumpBytes = NativeStealthDump.encryptBundleBytes(bundleBytes, password)
      dumpFile.writeBytes(dumpBytes)
      try {
        bundle.bundleFile.delete()
      } catch (_: Exception) {
      }

      val stdout = buildString {
        appendLine("[+] SUCCESS: Memory dump generated")
        appendLine("Mode: ${bundle.mode}")
        appendLine("File: ${dumpFile.absolutePath} (${dumpFile.length()} bytes)")
      }.trim()
      Result(0, stdout, "")
    } catch (t: Throwable) {
      Result(1, "", t.message ?: "Native stealth dump failed")
    }
  }

  fun dumpDir(projectDir: File): File = File(projectDir, ".localgitmirror/tmp")

  fun findLatestDump(projectDir: File, repoName: String): File? {
    val dir = dumpDir(projectDir)
    if (!dir.exists()) return null
    val dumps = dir.listFiles { f ->
      f.isFile && f.name.startsWith("dump_${repoName}_") && f.name.endsWith(".dmp")
    } ?: return null
    return dumps.maxByOrNull { it.lastModified() }
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
