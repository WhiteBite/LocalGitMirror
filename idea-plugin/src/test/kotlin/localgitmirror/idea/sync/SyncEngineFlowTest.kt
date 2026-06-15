package localgitmirror.idea.sync

import com.intellij.openapi.project.Project
import localgitmirror.idea.git.GitLocal
import localgitmirror.idea.mirror.MirrorApi
import localgitmirror.idea.sync.v2.GitPort
import localgitmirror.idea.sync.v2.MirrorPort
import localgitmirror.idea.sync.v2.RepoResolution
import localgitmirror.idea.sync.v2.RepoResolverPort
import localgitmirror.idea.sync.v2.RepoSource
import localgitmirror.idea.sync.v2.SettingsSnapshot
import localgitmirror.idea.sync.v2.SyncEngine
import localgitmirror.idea.sync.v2.SyncStatePort
import localgitmirror.idea.sync.v2.WorkKitPort
import localgitmirror.idea.workkit.WorkKit
import localgitmirror.idea.workkit.NativeStealthDump
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SyncEngineFlowTest {

  @Test
  fun `fails fast on invalid configured repo`() {
    val mirror = FakeMirrorPort()
    val git = FakeGitPort()
    val work = FakeWorkKitPort()
    val state = FakeStatePort()
    val resolver = object : RepoResolverPort {
      override fun resolve(project: Project, projectDir: File, configuredRepo: String): RepoResolution {
        return RepoResolution(RepoSource.SETTINGS, configuredRepo, "", error = "invalid configured repo")
      }
    }

    val engine = SyncEngine(mirror = mirror, git = git, workKit = work, state = state, resolver = resolver)
    val projectDir = createTempDir(prefix = "lgm-engine-invalid-")
    try {
      val res = engine.runFullSyncWithSnapshot(project = dummyProject(), projectDir = projectDir, snapshot = defaultSnapshot())
      assertEquals(false, res.step.ok)
      assertEquals("invalid configured repo", res.step.message)
      assertNull(res.repo)
      assertEquals(0, mirror.applyKnownCalls)
      assertEquals(0, mirror.uploadCalls)
    } finally {
      projectDir.deleteRecursively()
    }
  }

  @Test
  fun `pointer-only path skips dump and upload`() {
    val mirror = FakeMirrorPort(
      hasCommitsBody = """{"known":["abc1234"]}""",
      applyKnownResult = MirrorApi.HttpResult(200, "ok")
    )
    val git = FakeGitPort(head = "abc1234")
    val work = FakeWorkKitPort()
    val state = FakeStatePort()
    val resolver = fixedResolver("onyx-platform")

    val engine = SyncEngine(mirror = mirror, git = git, workKit = work, state = state, resolver = resolver)
    val projectDir = createTempDir(prefix = "lgm-engine-pointer-")
    try {
      val res = engine.runFullSyncWithSnapshot(project = dummyProject(), projectDir = projectDir, snapshot = defaultSnapshot())
      assertEquals(true, res.step.ok)
      assertEquals("onyx-platform", res.repo)
      assertNotNull(res.http)
      assertEquals(1, mirror.applyKnownCalls)
      assertEquals(0, work.runBackupCalls)
      assertEquals(0, mirror.uploadCalls)
      assertEquals(1, state.updateCalls)
    } finally {
      projectDir.deleteRecursively()
    }
  }

  @Test
  fun `offline mode generates dump and skips upload`() {
    val mirror = FakeMirrorPort(hasCommitsBody = """{"known":[]}""")
    val git = FakeGitPort(head = "abc1234")
    val work = FakeWorkKitPort(createDump = true)
    val state = FakeStatePort()
    val resolver = fixedResolver("onyx-platform")

    val engine = SyncEngine(mirror = mirror, git = git, workKit = work, state = state, resolver = resolver)
    val projectDir = createTempDir(prefix = "lgm-engine-offline-")
    try {
      val snapshot = defaultSnapshot().copy(offlineGenerateOnly = true)
      val res = engine.runFullSyncWithSnapshot(project = dummyProject(), projectDir = projectDir, snapshot = snapshot)
      assertEquals(true, res.step.ok)
      assertEquals("onyx-platform", res.repo)
      assertNotNull(res.dump)
      assertEquals(1, work.runBackupCalls)
      assertEquals(0, mirror.uploadCalls)
    } finally {
      projectDir.deleteRecursively()
    }
  }

  @Test
  fun `no-op incremental does not fail sync`() {
    val mirror = FakeMirrorPort(hasCommitsBody = """{"known":[]}""")
    val git = FakeGitPort(head = "abc1234")
    val work = FakeWorkKitPort(createDump = false, noChanges = true)
    val state = FakeStatePort()
    val resolver = fixedResolver("onyx-platform-v1")

    val engine = SyncEngine(mirror = mirror, git = git, workKit = work, state = state, resolver = resolver)
    val projectDir = createTempDir(prefix = "lgm-engine-noop-")
    try {
      val res = engine.runFullSyncWithSnapshot(project = dummyProject(), projectDir = projectDir, snapshot = defaultSnapshot().copy(repoConfigured = "onyx-platform-v1"))
      assertEquals(true, res.step.ok)
      assertEquals("No new changes to sync; skipped upload", res.step.message)
      assertNull(res.dump)
      assertEquals(0, mirror.uploadCalls)
      assertEquals(1, state.updateCalls)
    } finally {
      projectDir.deleteRecursively()
    }
  }

  @Test
  fun `fails when generation succeeds but no dump discoverable`() {
    val mirror = FakeMirrorPort(hasCommitsBody = """{"known":[]}""")
    val git = FakeGitPort(head = "abc1234")
    val work = FakeWorkKitPort(createDump = false)
    val state = FakeStatePort()
    val resolver = fixedResolver("onyx-platform-v1")

    val engine = SyncEngine(mirror = mirror, git = git, workKit = work, state = state, resolver = resolver)
    val projectDir = createTempDir(prefix = "lgm-engine-missing-dump-")
    try {
      val res = engine.runFullSyncWithSnapshot(project = dummyProject(), projectDir = projectDir, snapshot = defaultSnapshot().copy(repoConfigured = "onyx-platform-v1"))
      assertEquals(false, res.step.ok)
      assertEquals("No sync package found after generation", res.step.message)
      assertNull(res.dump)
      assertEquals(0, mirror.uploadCalls)
    } finally {
      projectDir.deleteRecursively()
    }
  }

  @Test
  fun `findLatestDump uses sync file path from generator output`() {
    val engine = SyncEngine(workKit = FakeWorkKitPort(createDump = false))
    val projectDir = createTempDir(prefix = "lgm-engine-output-path-")
    try {
      val dumpDir = File(projectDir, ".git/lgm")
      dumpDir.mkdirs()
      val dump = File(dumpDir, "cache_dirname_20260313_1200.bin")
      dump.writeText("x")

      val genOut = """
        [+] Sync package ready
        File: ${dump.absolutePath} (1 bytes)
      """.trimIndent()

      val (step, found) = engine.findLatestDump(projectDir, "onyx-platform-v1", generationOutput = genOut)
      assertEquals(true, step.ok)
      assertEquals(dump.absolutePath, found?.absolutePath)
    } finally {
      projectDir.deleteRecursively()
    }
  }

  private fun defaultSnapshot(): SettingsSnapshot {
    return SettingsSnapshot(
      baseUrl = "https://localhost",
      repoConfigured = "onyx-platform",
      mirrorInsecureTls = true,
      offlineGenerateOnly = false,
      mirrorApiKey = "k",
      syncPassword = "p"
    )
  }

  private fun fixedResolver(repo: String): RepoResolverPort {
    return object : RepoResolverPort {
      override fun resolve(project: Project, projectDir: File, configuredRepo: String): RepoResolution {
        return RepoResolution(RepoSource.SETTINGS, configuredRepo, repo)
      }
    }
  }

  private fun dummyProject(): Project {
    @Suppress("UNCHECKED_CAST")
    return java.lang.reflect.Proxy.newProxyInstance(
      Project::class.java.classLoader,
      arrayOf(Project::class.java)
    ) { _, method, _ ->
      when (method.name) {
        "getName" -> "dummy"
        "isDisposed" -> false
        else -> null
      }
    } as Project
  }

  private class FakeMirrorPort(
    private val hasCommitsBody: String = """{"known":[]}""",
    private val applyKnownResult: MirrorApi.HttpResult = MirrorApi.HttpResult(404, "missing")
  ) : MirrorPort {
    var applyKnownCalls: Int = 0
    var uploadCalls: Int = 0

    override fun ensureRepoExists(baseUrl: String, apiKey: String, repo: String, insecureTls: Boolean, projectDir: File?): MirrorApi.HttpResult {
      return MirrorApi.HttpResult(200, "ok")
    }

    override fun capabilities(baseUrl: String, apiKey: String, insecureTls: Boolean): MirrorApi.CapabilitiesResult {
      return MirrorApi.CapabilitiesResult(200, "ok", apiVersion = 1, protocolVersion = 1, preflight = true, dryRun = true, passwordProbe = true)
    }

    override fun passwordProbe(baseUrl: String, apiKey: String, insecureTls: Boolean): MirrorApi.ProbeResult {
      val dump = NativeStealthDump.encryptBundleBytes("LGM-PROBE\n".toByteArray(), password = "p")
      return MirrorApi.ProbeResult(200, dump, "OK")
    }

    override fun hasCommits(baseUrl: String, apiKey: String, repo: String, commits: List<String>, insecureTls: Boolean): MirrorApi.HttpResult {
      return MirrorApi.HttpResult(200, hasCommitsBody)
    }

    override fun applyKnown(baseUrl: String, apiKey: String, repo: String, commit: String, branches: Map<String, String>, insecureTls: Boolean): MirrorApi.HttpResult {
      applyKnownCalls += 1
      return applyKnownResult
    }

    override fun uploadAndApply(baseUrl: String, apiKey: String, repo: String, dumpFile: File, insecureTls: Boolean, projectDir: File?): MirrorApi.HttpResult {
      uploadCalls += 1
      return MirrorApi.HttpResult(200, """{"success":true}""")
    }
  }

  private class FakeGitPort(private val head: String = "abc1234") : GitPort {
    override fun isCleanWorkTree(project: Project, projectDir: File): Boolean = true
    override fun headHash(project: Project, projectDir: File): String? = head
    override fun currentBranch(project: Project, projectDir: File): String? = "main"
    override fun isAncestor(project: Project, projectDir: File, ancestor: String, descendant: String): Boolean = true
    override fun recentCommits(project: Project, projectDir: File, limit: Int): List<GitLocal.CommitSummary> {
      return listOf(GitLocal.CommitSummary(hash = head, subject = "msg"))
    }
    override fun branchHash(project: Project, projectDir: File, branchName: String): String? = head
  }

  private class FakeWorkKitPort(private val createDump: Boolean = false, private val noChanges: Boolean = false) : WorkKitPort {
    var runBackupCalls: Int = 0

    override fun runBackupWorkStealth(workDir: File, password: String, repoName: String, excludeBases: List<String>, additionalBranches: List<String>, negotiationUsed: Boolean): WorkKit.Result {
      runBackupCalls += 1
      if (noChanges) {
        return WorkKit.Result(1, "", "No new changes to sync")
      }
      if (createDump) {
        val tmp = File(workDir, ".git/lgm")
        if (!tmp.exists()) tmp.mkdirs()
        File(tmp, "cache_${repoName}_20260313_0315.bin").writeText("x")
      }
      return WorkKit.Result(0, "ok", "")
    }

    override fun findLatestDump(projectDir: File, repoName: String): File? {
      val tmp = File(projectDir, ".git/lgm")
      if (!tmp.exists()) return null
      return tmp.listFiles { f -> f.name.startsWith("cache_${repoName}_") && f.name.endsWith(".bin") }?.maxByOrNull { it.lastModified() }
    }
  }

  private class FakeStatePort : SyncStatePort {
    var updateCalls: Int = 0

    override fun readLastSent(projectDir: File): String? = null
    override fun readLastByBranch(projectDir: File): Map<String, String> = emptyMap()
    override fun updateAfterSend(projectDir: File, branch: String, head: String) {
      updateCalls += 1
    }
    override fun migrateLegacyIfPresent(projectDir: File) {}
    override fun cleanupOldSyncFiles(projectDir: File) {}
  }
}
