package localgitmirror.idea.sync.v2

import com.intellij.openapi.project.Project
import localgitmirror.idea.sync.v2.SyncEngine.StepResult
import java.io.File

internal fun SyncEngine.generateDump(
  project: Project,
  projectDir: File,
  settings: SettingsSnapshot,
  repoName: String,
  excludeBases: List<String> = emptyList(),
  additionalBranches: List<String> = emptyList()
): StepResult {
  // negotiationUsed=true: trust excludeBases, never fall back to stale state
  val kitRes = workKit.createSyncPackage(
    workDir = projectDir,
    password = settings.syncPassword,
    repoName = repoName,
    excludeBases = excludeBases,
    additionalBranches = additionalBranches,
    negotiationUsed = true
  )
  if (!kitRes.ok() && isNoChangesToSync(kitRes)) {
    return StepResult(true, "No new changes to sync", kitRes.stderr.ifBlank { kitRes.stdout })
  }
  if (!kitRes.ok()) {
    return StepResult(false, "Dump generation failed", "exit=${kitRes.exitCode} ${kitRes.stderr}")
  }
  return StepResult(true, "Dump generated", kitRes.stdout)
}

private fun isNoChangesToSync(result: localgitmirror.idea.workkit.WorkKit.Result): Boolean {
  val text = (result.stderr + "\n" + result.stdout).lowercase()
  return text.contains("no new changes to sync")
}

internal fun SyncEngine.findLatestDump(projectDir: File, repoName: String): Pair<StepResult, File?> {
  return findLatestDump(projectDir, repoName, generationOutput = null)
}

internal fun SyncEngine.findLatestDump(projectDir: File, repoName: String, generationOutput: String?): Pair<StepResult, File?> {
  val fromOutput = findSyncFileFromGeneratorOutput(projectDir, generationOutput)
  if (fromOutput != null) {
    return StepResult(true, "Found sync package from generator output", fromOutput.name) to fromOutput
  }

  val dump = workKit.findLatestDump(projectDir, repoName)
  if (dump != null) {
    return StepResult(true, "Found sync package", dump.name) to dump
  }

  val fallback = findLatestAnySyncFile(projectDir)
  if (fallback != null) {
    return StepResult(true, "Found fallback sync package", fallback.name) to fallback
  }

  return StepResult(false, "No sync package found after generation") to null
}

private fun findSyncFileFromGeneratorOutput(projectDir: File, output: String?): File? {
  if (output.isNullOrBlank()) return null

  val m = Regex("(?im)^\\s*File:\\s*(.+?\\.bin)\\b").find(output) ?: return null
  val raw = m.groupValues.getOrNull(1)?.trim()?.trim('"') ?: return null
  if (raw.isBlank()) return null

  val fromRaw = File(raw)
  if (fromRaw.isAbsolute && fromRaw.exists() && fromRaw.isFile) return fromRaw

  val rootCandidate = File(projectDir, raw)
  if (rootCandidate.exists() && rootCandidate.isFile) return rootCandidate

  return null
}

private fun findLatestAnySyncFile(projectDir: File): File? {
  // Resolve .git/.cache/ directory for sync files
  val proc = ProcessBuilder(listOf("git", "rev-parse", "--git-dir"))
    .directory(projectDir).redirectErrorStream(false).start()
  val rawGitDir = proc.inputStream.bufferedReader().readText().trim()
  proc.waitFor()
  val gitDir = if (File(rawGitDir).isAbsolute) File(rawGitDir) else File(projectDir, rawGitDir)
  val syncDir = File(gitDir, ".cache")
  val dirs = listOf(syncDir, projectDir)
  val files = mutableListOf<File>()
  for (dir in dirs) {
    if (!dir.exists() || !dir.isDirectory) continue
    val local = dir.listFiles { f -> f.isFile && (f.name.startsWith(".tmp_") || (f.name.startsWith("cache_") && f.name.endsWith(".bin"))) } ?: continue
    files.addAll(local)
  }
  return files.maxByOrNull { it.lastModified() }
}
