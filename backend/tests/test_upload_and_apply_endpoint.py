import sys
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

# Ensure `app` package is importable when tests are launched from repo root.
BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.core.bundle_crypto import MAGIC
from app.routers import api as api_router


class _FakeRepoManager:
    def __init__(self, repos):
        self._repos = list(repos)

    def get_repos(self):
        return list(self._repos)

    def _get_workspace_path(self, _repo):
        p = Path.cwd()
        p.mkdir(parents=True, exist_ok=True)
        return p

    def _get_bare_path(self, _repo):
        p = Path.cwd() / "tmp-test-bare.git"
        p.mkdir(parents=True, exist_ok=True)
        return p


def _build_client(monkeypatch, repos, captured_apply_kwargs):
    app = FastAPI()
    app.include_router(api_router.router)

    monkeypatch.setattr(api_router, "repo_manager", _FakeRepoManager(repos), raising=False)
    monkeypatch.setenv("SYNC_PASSWORD", "test-password")

    def _fake_apply_dump_to_repo_and_sync_bare(**kwargs):
        captured_apply_kwargs.update(kwargs)
        return {
            "success": True,
            "repo": kwargs["repo_name"],
            "attachment": kwargs["dump_filename"],
            "commit": "abc123 Test commit",
            "message": "Sync applied successfully",
        }

    monkeypatch.setattr(api_router, "_apply_dump_to_repo_and_sync_bare", _fake_apply_dump_to_repo_and_sync_bare)

    return TestClient(app)


def test_upload_and_apply_accepts_repo_names_with_underscores(monkeypatch):
    captured = {}
    client = _build_client(monkeypatch, ["upload_apply_smoke_repo"], captured)

    response = client.post(
        "/api/documents/upload",
        data={"repo": "upload_apply_smoke_repo"},
        files={
            "attachment": (
                "dump_upload_apply_smoke_repo_20260306_1200.dmp",
                MAGIC + b"stub-encrypted-content",
                "application/octet-stream",
            )
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["success"] is True
    assert payload["repo"] == "upload_apply_smoke_repo"
    assert payload["attachment"] == "dump_upload_apply_smoke_repo_20260306_1200.dmp"

    # Critical regression check: inferred repo must keep full underscore name.
    assert captured["repo_name"] == "upload_apply_smoke_repo"
    assert captured["dump_filename"] == "dump_upload_apply_smoke_repo_20260306_1200.dmp"


def test_upload_and_apply_rejects_real_repo_mismatch(monkeypatch):
    captured = {}
    client = _build_client(monkeypatch, ["other_repo"], captured)

    response = client.post(
        "/api/documents/upload",
        data={"repo": "other_repo"},
        files={
            "attachment": (
                "dump_upload_apply_smoke_repo_20260306_1200.dmp",
                MAGIC + b"stub-encrypted-content",
                "application/octet-stream",
            )
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["success"] is False
    assert "indicates repo 'upload_apply_smoke_repo'" in payload["message"]

    # Ensure apply helper was not called when mismatch is detected.
    assert captured == {}


def test_pick_bundle_ref_prefers_current_branch(monkeypatch, tmp_path):
    work = tmp_path / "w"
    bundle = tmp_path / "b.bundle"
    work.mkdir(parents=True, exist_ok=True)
    bundle.write_text("x", encoding="utf-8")

    class _P:
        def __init__(self, rc, out):
            self.returncode = rc
            self.stdout = out
            self.stderr = ""

    def _fake_git(_wd, *args):
        assert args[0] == "bundle"
        assert args[1] == "list-heads"
        return _P(0, "abc refs/heads/main\ndef refs/heads/master\n")

    monkeypatch.setattr(api_router, "_git", _fake_git)
    ref = api_router._pick_bundle_ref(work, bundle, preferred_branch="main")
    assert ref == "refs/heads/main"


def test_pick_bundle_ref_falls_back_first_ref(monkeypatch, tmp_path):
    work = tmp_path / "w2"
    bundle = tmp_path / "b2.bundle"
    work.mkdir(parents=True, exist_ok=True)
    bundle.write_text("x", encoding="utf-8")

    class _P:
        def __init__(self, rc, out):
            self.returncode = rc
            self.stdout = out
            self.stderr = ""

    def _fake_git(_wd, *args):
        return _P(0, "111 refs/heads/feature\n222 refs/heads/dev\n")

    monkeypatch.setattr(api_router, "_git", _fake_git)
    ref = api_router._pick_bundle_ref(work, bundle, preferred_branch="master")
    assert ref == "refs/heads/feature"


def test_upload_apply_bootstraps_when_workspace_has_no_branch(monkeypatch, tmp_path):
    repo = "bootstrap_repo"
    workspace = tmp_path / repo
    bare = tmp_path / f"{repo}.git"
    workspace.mkdir(parents=True, exist_ok=True)
    bare.mkdir(parents=True, exist_ok=True)

    class _RM:
        def get_repos(self):
            return [repo]

        def _get_workspace_path(self, _repo):
            return workspace

        def _get_bare_path(self, _repo):
            return bare

        def sync_workspace(self, _repo):
            return {"success": True}

    monkeypatch.setattr(api_router, "repo_manager", _RM(), raising=False)
    monkeypatch.setenv("SYNC_PASSWORD", "pwd")

    dump = tmp_path / "dump_bootstrap_repo_20260313_0000.dmp"
    dump.write_bytes(api_router.MAGIC + b"x" * 64)

    def _fake_decrypt(_dump, out, _pwd):
        out.write_bytes(b"bundle")

    monkeypatch.setattr(api_router, "decrypt_dump_to_bundle", _fake_decrypt)

    calls = []

    class _P:
        def __init__(self, rc=0, out="", err=""):
            self.returncode = rc
            self.stdout = out
            self.stderr = err

    def _fake_git(_wd, *args):
        calls.append(args)
        if args[:2] == ("status", "--porcelain"):
            return _P(0, "")
        if args[:2] == ("bundle", "list-heads"):
            return _P(0, "abc123 refs/heads/master\n")
        if args[:3] == ("rev-parse", "--abbrev-ref", "HEAD"):
            # Simulate unborn/no branch state
            return _P(128, "HEAD\n", "fatal")
        if args[:2] == ("checkout", "--detach"):
            return _P(0, "")
        if args[0] == "fetch":
            return _P(0, "")
        if args[:2] == ("checkout", "-f"):
            return _P(0, "")
        if args[:2] == ("checkout", "-B"):
            return _P(0, "")
        if args[0] == "push":
            return _P(0, "")
        if args[:2] == ("log", "-1"):
            return _P(0, "abc123 Initial\n")
        return _P(0, "")

    monkeypatch.setattr(api_router, "_git", _fake_git)

    res = api_router._apply_dump_to_repo_and_sync_bare(
        dump_path=dump,
        repo_name=repo,
        dump_filename=dump.name,
    )

    assert res["success"] is True
    # Ensure fetch was called with multi-branch refspec
    assert any("refs/heads" in str(c) and "fetch" in str(c) for c in calls)


def test_upload_apply_replaces_branch_on_unrelated_histories(monkeypatch, tmp_path):
    """New flow: fetch all refs, checkout -f, push --force per branch."""
    repo = "unrelated_repo"
    workspace = tmp_path / repo
    bare = tmp_path / f"{repo}.git"
    workspace.mkdir(parents=True, exist_ok=True)
    bare.mkdir(parents=True, exist_ok=True)

    class _RM:
        def get_repos(self):
            return [repo]

        def _get_workspace_path(self, _repo):
            return workspace

        def _get_bare_path(self, _repo):
            return bare

    monkeypatch.setattr(api_router, "repo_manager", _RM(), raising=False)
    monkeypatch.setenv("SYNC_PASSWORD", "pwd")

    dump = tmp_path / "dump_unrelated_repo_20260313_0001.dmp"
    dump.write_bytes(api_router.MAGIC + b"x" * 64)

    def _fake_decrypt(_dump, out, _pwd):
        out.write_bytes(b"bundle")

    monkeypatch.setattr(api_router, "decrypt_dump_to_bundle", _fake_decrypt)

    calls = []

    class _P:
        def __init__(self, rc=0, out="", err=""):
            self.returncode = rc
            self.stdout = out
            self.stderr = err

    def _fake_git(_wd, *args):
        calls.append(args)
        if args[:2] == ("status", "--porcelain"):
            return _P(0, "")
        if args[:2] == ("bundle", "list-heads"):
            return _P(0, "def456 refs/heads/main\nabc789 refs/heads/feature\n")
        if args[:3] == ("rev-parse", "--abbrev-ref", "HEAD"):
            return _P(0, "main\n", "")
        if args[:2] == ("checkout", "--detach"):
            return _P(0, "")
        if args[0] == "fetch":
            return _P(0, "", "")
        if args[:2] == ("checkout", "-f"):
            return _P(0, "", "")
        if args[0] == "push":
            return _P(0, "", "")
        if args[:2] == ("log", "-1"):
            return _P(0, "def456 Replace branch\n", "")
        return _P(0, "", "")

    monkeypatch.setattr(api_router, "_git", _fake_git)

    res = api_router._apply_dump_to_repo_and_sync_bare(
        dump_path=dump,
        repo_name=repo,
        dump_filename=dump.name,
    )

    assert res["success"] is True
    # Should have pushed BOTH branches (main and feature)
    push_calls = [c for c in calls if c[0] == "push"]
    assert len(push_calls) >= 2, f"Expected at least 2 push calls, got {push_calls}"
    pushed_refs = [c[-1] for c in push_calls]
    assert any("main" in r for r in pushed_refs)
    assert any("feature" in r for r in pushed_refs)


def test_apply_dump_proceeds_despite_dirty_workspace(monkeypatch, tmp_path):
    """Dirty workspace should NOT block upload — self-healing is best-effort."""
    repo = "dirty_repo"
    workspace = tmp_path / repo
    bare = tmp_path / f"{repo}.git"
    workspace.mkdir(parents=True, exist_ok=True)
    bare.mkdir(parents=True, exist_ok=True)

    class _RM:
        def get_repos(self):
            return [repo]

        def _get_workspace_path(self, _repo):
            return workspace

        def _get_bare_path(self, _repo):
            return bare

    monkeypatch.setattr(api_router, "repo_manager", _RM(), raising=False)
    monkeypatch.setenv("SYNC_PASSWORD", "pwd")

    dump = tmp_path / "dump_dirty_repo_20260313_0002.dmp"
    dump.write_bytes(api_router.MAGIC + b"x" * 64)

    class _P:
        def __init__(self, rc=0, out="", err=""):
            self.returncode = rc
            self.stdout = out
            self.stderr = err

    def _fake_git(_wd, *args):
        if args[:2] == ("status", "--porcelain"):
            return _P(0, " M README.md\n", "")
        return _P(0, "", "")

    monkeypatch.setattr(api_router, "_git", _fake_git)

    res = api_router._apply_dump_to_repo_and_sync_bare(
        dump_path=dump,
        repo_name=repo,
        dump_filename=dump.name,
    )

    # Upload proceeds past dirty check — fails at decrypt (fake dump), NOT at "Uncommitted changes"
    assert res["success"] is False
    assert "Uncommitted changes" not in res["message"]


def test_apply_dump_force_push_failure_after_unrelated_histories(monkeypatch, tmp_path):
    repo = "force_fail_repo"
    workspace = tmp_path / repo
    bare = tmp_path / f"{repo}.git"
    workspace.mkdir(parents=True, exist_ok=True)
    bare.mkdir(parents=True, exist_ok=True)

    class _RM:
        def get_repos(self):
            return [repo]

        def _get_workspace_path(self, _repo):
            return workspace

        def _get_bare_path(self, _repo):
            return bare

    monkeypatch.setattr(api_router, "repo_manager", _RM(), raising=False)
    monkeypatch.setenv("SYNC_PASSWORD", "pwd")

    dump = tmp_path / "dump_force_fail_repo_20260313_0003.dmp"
    dump.write_bytes(api_router.MAGIC + b"x" * 64)

    def _fake_decrypt(_dump, out, _pwd):
        out.write_bytes(b"bundle")

    monkeypatch.setattr(api_router, "decrypt_dump_to_bundle", _fake_decrypt)

    class _P:
        def __init__(self, rc=0, out="", err=""):
            self.returncode = rc
            self.stdout = out
            self.stderr = err

    def _fake_git(_wd, *args):
        if args[:2] == ("status", "--porcelain"):
            return _P(0, "", "")
        if args[:2] == ("bundle", "list-heads"):
            return _P(0, "def456 refs/heads/main\n")
        if args[:3] == ("rev-parse", "--abbrev-ref", "HEAD"):
            return _P(0, "main\n", "")
        if args[:2] == ("checkout", "--detach"):
            return _P(0, "", "")
        if args[0] == "fetch":
            return _P(0, "", "")
        if args[:2] == ("checkout", "-f"):
            return _P(0, "", "")
        if args[0] == "push":
            return _P(1, "", "fatal: push failed")
        if args[:2] == ("log", "-1"):
            return _P(0, "def456 Replace branch\n", "")
        return _P(0, "", "")

    monkeypatch.setattr(api_router, "_git", _fake_git)

    res = api_router._apply_dump_to_repo_and_sync_bare(
        dump_path=dump,
        repo_name=repo,
        dump_filename=dump.name,
    )

    assert res["success"] is False
    assert "push" in res["message"].lower()
