package localgitmirror.idea.workkit

import com.intellij.openapi.components.service
import java.io.File
import localgitmirror.idea.settings.MirrorSettingsService

object WorkKit {
  data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
    fun ok(): Boolean = exitCode == 0
  }

  /** Pinned server public key (32 raw bytes), or null => legacy password mode. */
  private fun pinnedServerPub(): ByteArray? = try {
    val b64 = service<MirrorSettingsService>().state.serverPubKeyB64
    if (b64.isBlank()) null else HybridCrypto.decodeServerPub(b64)
  } catch (_: Throwable) {
    null
  }

  @Suppress("UNUSED_PARAMETER")
  fun createSyncPackage(
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

      val pub = pinnedServerPub()
      val modeNote: String
      if (pub != null) {
        // v3 (hybrid): seal the bundle to the server's static key via a throwaway
        // ephemeral. The dump layout is  0x03 || ephemeralPub[32] || sealed  — the
        // ephemeral PUBLIC key travels in the file, the private key is discarded
        // immediately. No plaintext and no shared password touch the disk, and a
        // later read of this machine cannot decrypt the dump (forward secrecy).
        val session = HybridCrypto.Session.create(pub)
        try {
          val sealed = session.sealBundle(bundle.bundleBytes)
          syncFile.writeBytes(byteArrayOf(0x03) + session.ephemeralPub + sealed)
        } finally {
          session.wipe()
        }
        modeNote = "${bundle.mode} (v3/hybrid)"
      } else {
        val encryptedBytes = BundleCrypto.encryptBundleBytes(bundle.bundleBytes, password)
        syncFile.writeBytes(encryptedBytes)
        modeNote = bundle.mode
      }

      val stdout = buildString {
        appendLine("[+] Sync package ready")
        appendLine("Mode: $modeNote")
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
    return File(gitDir, ".cache")
  }

  fun findLatestDump(projectDir: File, repoName: String): File? {
    val dir = syncDir(projectDir)
    if (!dir.exists()) return null
    val files = dir.listFiles { f ->
      f.isFile && f.name.startsWith(".tmp_")
    } ?: return null
    return files.maxByOrNull { it.lastModified() }
  }

  fun applySyncPackage(
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

    val res = BundleImporter.applyDump(
      workDir = workDir,
      password = password,
      dumpFile = dumpFile,
      mode = mode,
      newBranchName = newBranchName
    )
    return Result(res.exitCode, res.stdout, res.stderr)
  }
}
