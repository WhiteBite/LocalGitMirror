package localgitmirror.idea.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import localgitmirror.idea.sync.v2.RepoResolver
import localgitmirror.idea.sync.v2.RepoSource

class RepoResolverTest {

  @Test
  fun `uses configured repo when valid`() {
    val r = RepoResolver.resolveByNames(
      projectName = "Onyx Platform",
      directoryName = "onyx-platform",
      configuredRepo = "my_CUSTOM repo"
    )

    assertEquals(RepoSource.SETTINGS, r.source)
    assertEquals("my_custom-repo", r.sanitized)
    assertNull(r.error)
  }

  @Test
  fun `fails when configured repo sanitizes to blank`() {
    val r = RepoResolver.resolveByNames(
      projectName = "Onyx Platform",
      directoryName = "onyx-platform",
      configuredRepo = "%%%%"
    )

    assertEquals(RepoSource.SETTINGS, r.source)
    assertEquals("", r.sanitized)
    assertNotNull(r.error)
  }

  @Test
  fun `falls back to project then directory then default`() {
    val fromProject = RepoResolver.resolveByNames(
      projectName = "Onyx Platform",
      directoryName = "workspace",
      configuredRepo = ""
    )
    assertEquals(RepoSource.PROJECT_NAME, fromProject.source)
    assertEquals("onyx-platform", fromProject.sanitized)

    val fromDir = RepoResolver.resolveByNames(
      projectName = "%%%%",
      directoryName = "ws_name",
      configuredRepo = ""
    )
    assertEquals(RepoSource.DIRECTORY_NAME, fromDir.source)
    assertEquals("ws_name", fromDir.sanitized)

    val fallback = RepoResolver.resolveByNames(
      projectName = "%%%%",
      directoryName = "%%%%",
      configuredRepo = ""
    )
    assertEquals(RepoSource.DEFAULT, fallback.source)
    assertEquals("default", fallback.sanitized)
  }

  // ── repoNameFromRemoteUrl — the cross-machine stability fix (A2) ──
  // The whole point: the SAME remote URL yields the SAME slug, regardless of
  // how the repo was cloned (https/ssh/scp) or where it lives on disk.

  @Test
  fun `remote url https with git suffix yields repo slug`() {
    assertEquals("repo", RepoResolver.repoNameFromRemoteUrl("https://nexus.local/group/repo.git"))
  }

  @Test
  fun `remote url https without git suffix`() {
    assertEquals("repo", RepoResolver.repoNameFromRemoteUrl("https://nexus.local/group/repo"))
  }

  @Test
  fun `remote url https with port does not confuse port for path`() {
    assertEquals("repo", RepoResolver.repoNameFromRemoteUrl("https://nexus.local:8443/group/sub/repo.git"))
  }

  @Test
  fun `remote url scp form git at host`() {
    assertEquals("repo", RepoResolver.repoNameFromRemoteUrl("git@github.com:group/repo.git"))
  }

  @Test
  fun `remote url ssh scheme with port`() {
    assertEquals("repo", RepoResolver.repoNameFromRemoteUrl("ssh://git@host:22/group/repo.git"))
  }

  @Test
  fun `remote url trailing slash still yields last segment`() {
    assertEquals("repo", RepoResolver.repoNameFromRemoteUrl("https://host/group/repo/"))
  }

  @Test
  fun `remote url deep group path uses last segment only`() {
    assertEquals("myrepo", RepoResolver.repoNameFromRemoteUrl("https://gitlab.local/a/b/c/d/myrepo.git"))
  }

  @Test
  fun `remote url with dashes lowercased`() {
    assertEquals("my-repo", RepoResolver.repoNameFromRemoteUrl("https://host/group/My-Repo.git"))
  }

  @Test
  fun `remote url underscores preserved`() {
    assertEquals("my_repo", RepoResolver.repoNameFromRemoteUrl("https://host/group/My_Repo.git"))
  }

  @Test
  fun `blank remote url yields empty`() {
    assertEquals("", RepoResolver.repoNameFromRemoteUrl(""))
    assertEquals("", RepoResolver.repoNameFromRemoteUrl("   "))
  }

  @Test
  fun `same repo cloned differently yields identical slug on both machines`() {
    // DOME cloned via https, WORK laptop cloned via ssh — same logical repo.
    val dome = RepoResolver.repoNameFromRemoteUrl("https://nexus.local/eaes/onyx-platform.git")
    val work = RepoResolver.repoNameFromRemoteUrl("git@nexus.local:eaes/onyx-platform.git")
    assertEquals(dome, work)
    assertEquals("onyx-platform", dome)
  }

  @Test
  fun `resolve prefers git remote over differing project names`() {
    // This is the actual bug: project.name differs between machines
    // (IntelliJ "default" vs directory "onyx-platform"), but the remote URL
    // is identical → both must resolve to the same Mirror key.
    val machineA = RepoResolver.resolveByNames(
      projectName = "default",
      directoryName = "checkout-a",
      configuredRepo = "",
      remoteUrl = "https://nexus.local/eaes/onyx-platform.git"
    )
    val machineB = RepoResolver.resolveByNames(
      projectName = "onyx-platform",
      directoryName = "checkout-b",
      configuredRepo = "",
      remoteUrl = "git@nexus.local:eaes/onyx-platform.git"
    )
    assertEquals(RepoSource.GIT_REMOTE, machineA.source)
    assertEquals(RepoSource.GIT_REMOTE, machineB.source)
    assertEquals(machineA.sanitized, machineB.sanitized)
    assertEquals("onyx-platform", machineA.sanitized)
  }

  @Test
  fun `configured repo still wins over git remote`() {
    val r = RepoResolver.resolveByNames(
      projectName = "default",
      directoryName = "checkout",
      configuredRepo = "explicit-name",
      remoteUrl = "https://nexus.local/eaes/onyx-platform.git"
    )
    assertEquals(RepoSource.SETTINGS, r.source)
    assertEquals("explicit-name", r.sanitized)
  }

  @Test
  fun `falls through to project name when no remote`() {
    val r = RepoResolver.resolveByNames(
      projectName = "Onyx Platform",
      directoryName = "checkout",
      configuredRepo = "",
      remoteUrl = ""
    )
    assertEquals(RepoSource.PROJECT_NAME, r.source)
    assertEquals("onyx-platform", r.sanitized)
  }

  // ── pinned repo (.git/config localgitmirror.repo) — the drift fix ──
  // Once a strong identity is pinned, it must win over the weak project/dir
  // fallbacks AND survive a transient remote-read failure, so the Mirror key
  // never silently drifts (the onyx-platform -> phonyx bug).

  @Test
  fun `pinned wins over project name when settings and remote are empty`() {
    val r = RepoResolver.resolveByNames(
      projectName = "phonyx",          // IntelliJ project name differs from folder
      directoryName = "onyx-platform",
      configuredRepo = "",
      remoteUrl = "",                  // remote momentarily unreadable
      pinnedRepo = "onyx-platform"
    )
    assertEquals(RepoSource.PINNED, r.source)
    assertEquals("onyx-platform", r.sanitized)
  }

  @Test
  fun `pinned wins over a differing remote for stability`() {
    val r = RepoResolver.resolveByNames(
      projectName = "whatever",
      directoryName = "whatever",
      configuredRepo = "",
      remoteUrl = "https://nexus.local/eaes/some-fork.git",
      pinnedRepo = "onyx-platform"
    )
    assertEquals(RepoSource.PINNED, r.source)
    assertEquals("onyx-platform", r.sanitized)
  }

  @Test
  fun `configured settings still win over pinned`() {
    val r = RepoResolver.resolveByNames(
      projectName = "phonyx",
      directoryName = "onyx-platform",
      configuredRepo = "explicit-name",
      remoteUrl = "",
      pinnedRepo = "onyx-platform"
    )
    assertEquals(RepoSource.SETTINGS, r.source)
    assertEquals("explicit-name", r.sanitized)
  }

  @Test
  fun `blank pinned is ignored and remote is used`() {
    val r = RepoResolver.resolveByNames(
      projectName = "phonyx",
      directoryName = "checkout",
      configuredRepo = "",
      remoteUrl = "https://nexus.local/eaes/onyx-platform.git",
      pinnedRepo = "   "
    )
    assertEquals(RepoSource.GIT_REMOTE, r.source)
    assertEquals("onyx-platform", r.sanitized)
  }

  @Test
  fun `pinned value is sanitized`() {
    val r = RepoResolver.resolveByNames(
      projectName = "x",
      directoryName = "y",
      configuredRepo = "",
      remoteUrl = "",
      pinnedRepo = "Onyx Platform"
    )
    assertEquals(RepoSource.PINNED, r.source)
    assertEquals("onyx-platform", r.sanitized)
  }
}
