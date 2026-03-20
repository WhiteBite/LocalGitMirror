package localgitmirror.idea.sync.v2

import com.intellij.openapi.project.Project
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.sync.SyncStateStore
import localgitmirror.idea.workkit.WorkKit
import java.io.File

interface MirrorPort {
  fun ensureRepoExists(baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean): MirrorApi.HttpResult
  fun capabilities(baseUrl: String, apiKey: String, insecureTls: Boolean): MirrorApi.CapabilitiesResult
  fun passwordProbe(baseUrl: String, apiKey: String, insecureTls: Boolean): MirrorApi.ProbeResult
  fun hasCommits(baseUrl: String, apiKey: String, repo: String, commits: List<String>, insecureTls: Boolean): MirrorApi.HttpResult
  fun applyKnown(baseUrl: String, apiKey: String, repo: String, commit: String, insecureTls: Boolean): MirrorApi.HttpResult
  fun uploadAndApply(baseUrl: String, apiKey: String, repo: String, dumpFile: File, insecureTls: Boolean): MirrorApi.HttpResult
}

object DefaultMirrorPort : MirrorPort {
  override fun ensureRepoExists(baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean): MirrorApi.HttpResult {
    return MirrorApi.ensureRepoExists(baseUrl, apiKey, repo, insecureTls)
  }

  override fun capabilities(baseUrl: String, apiKey: String, insecureTls: Boolean): MirrorApi.CapabilitiesResult {
    return MirrorApi.capabilities(baseUrl, apiKey, insecureTls)
  }

  override fun passwordProbe(baseUrl: String, apiKey: String, insecureTls: Boolean): MirrorApi.ProbeResult {
    return MirrorApi.passwordProbe(baseUrl, apiKey, insecureTls)
  }

  override fun hasCommits(baseUrl: String, apiKey: String, repo: String, commits: List<String>, insecureTls: Boolean): MirrorApi.HttpResult {
    return MirrorApi.hasCommits(baseUrl, apiKey, repo, commits, insecureTls)
  }

  override fun applyKnown(baseUrl: String, apiKey: String, repo: String, commit: String, insecureTls: Boolean): MirrorApi.HttpResult {
    return MirrorApi.applyKnown(baseUrl, apiKey, repo, commit, insecureTls)
  }

  override fun uploadAndApply(baseUrl: String, apiKey: String, repo: String, dumpFile: File, insecureTls: Boolean): MirrorApi.HttpResult {
    return MirrorApi.uploadAndApply(baseUrl, apiKey, repo, dumpFile, insecureTls)
  }
}

interface GitPort {
  fun isCleanWorkTree(project: Project, projectDir: File): Boolean
  fun headHash(project: Project, projectDir: File): String?
  fun currentBranch(project: Project, projectDir: File): String?
  fun isAncestor(project: Project, projectDir: File, ancestor: String, descendant: String): Boolean
  fun recentCommits(project: Project, projectDir: File, limit: Int): List<GitLocal.CommitSummary>
}

object DefaultGitPort : GitPort {
  override fun isCleanWorkTree(project: Project, projectDir: File): Boolean = GitLocal.isCleanWorkTree(project, projectDir)
  override fun headHash(project: Project, projectDir: File): String? = GitLocal.headHash(project, projectDir)
  override fun currentBranch(project: Project, projectDir: File): String? = GitLocal.currentBranch(project, projectDir)
  override fun isAncestor(project: Project, projectDir: File, ancestor: String, descendant: String): Boolean {
    return GitLocal.isAncestor(project, projectDir, ancestor, descendant)
  }
  override fun recentCommits(project: Project, projectDir: File, limit: Int): List<GitLocal.CommitSummary> {
    return GitLocal.recentCommits(project, projectDir, limit)
  }
}

interface WorkKitPort {
  fun runBackupWorkStealth(
    workDir: File,
    password: String,
    repoName: String,
    baseCommit: String?
  ): WorkKit.Result
  fun findLatestDump(projectDir: File, repoName: String): File?
}

object DefaultWorkKitPort : WorkKitPort {
  override fun runBackupWorkStealth(workDir: File, password: String, repoName: String, baseCommit: String?): WorkKit.Result {
    return WorkKit.runBackupWorkStealth(
      workDir = workDir,
      password = password,
      repoName = repoName,
      baseCommit = baseCommit
    )
  }

  override fun findLatestDump(projectDir: File, repoName: String): File? = WorkKit.findLatestDump(projectDir, repoName)
}

interface SyncStatePort {
  fun readLastSent(projectDir: File): String?
  fun readLastByBranch(projectDir: File): Map<String, String>
  fun updateAfterSend(projectDir: File, branch: String, head: String)
  fun migrateLegacyIfPresent(projectDir: File)
  fun cleanupOldDumps(projectDir: File)
}

object DefaultSyncStatePort : SyncStatePort {
  override fun readLastSent(projectDir: File): String? = SyncStateStore.readLastSent(projectDir)
  override fun readLastByBranch(projectDir: File): Map<String, String> = SyncStateStore.readLastByBranch(projectDir)
  override fun updateAfterSend(projectDir: File, branch: String, head: String) = SyncStateStore.updateAfterSend(projectDir, branch, head)
  override fun migrateLegacyIfPresent(projectDir: File) = SyncStateStore.migrateLegacyIfPresent(projectDir)
  override fun cleanupOldDumps(projectDir: File) = SyncStateStore.cleanupOldDumps(projectDir)
}

interface RepoResolverPort {
  fun resolve(project: Project, projectDir: File, configuredRepo: String): RepoResolution
}

object DefaultRepoResolverPort : RepoResolverPort {
  override fun resolve(project: Project, projectDir: File, configuredRepo: String): RepoResolution {
    return RepoResolver.resolve(project, projectDir, configuredRepo)
  }
}
