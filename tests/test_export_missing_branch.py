import subprocess
import sys
from pathlib import Path

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient

_REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_REPO_ROOT / "backend"))

from app.core.envelope_crypto import encrypt_envelope  # noqa: E402
from app.routers import sync  # noqa: E402


def _init_repo(path: Path, branch: str):
    def run(*args):
        subprocess.run(["git", *args], cwd=str(path), check=True,
                       capture_output=True, text=True)
    run("init", "-b", branch, ".")
    run("config", "user.email", "t@t")
    run("config", "user.name", "t")
    (path / "f.txt").write_text("hello")
    run("add", ".")
    run("commit", "-m", "c1")


@pytest.fixture()
def repo(tmp_path, monkeypatch):
    ws = tmp_path / "ws"
    ws.mkdir()
    _init_repo(ws, "main")
    return ws


def test_existing_branch_bundle_unchanged(repo, tmp_path):
    bundle = tmp_path / "out.bundle"
    proc = sync._build_export_bundle(repo, bundle, None, "main", None)
    assert proc.returncode == 0
    assert bundle.exists()


def test_missing_branch_raises_named_error(repo, tmp_path):
    bundle = tmp_path / "out.bundle"
    with pytest.raises(sync.BranchNotFoundError) as exc:
        sync._build_export_bundle(repo, bundle, None, "ghost", None)
    assert "branch 'ghost' not found on mirror" in str(exc.value)


def test_no_branch_falls_back_to_all(repo, tmp_path):
    bundle = tmp_path / "out.bundle"
    proc = sync._build_export_bundle(repo, bundle, None, None, None)
    assert proc.returncode == 0


def test_retry_without_exclusions_logs_warning(repo, tmp_path, monkeypatch):
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=str(repo),
                          check=True, capture_output=True, text=True).stdout.strip()
    bundle = tmp_path / "out.bundle"
    warnings = []

    class _Logger:
        def info(self, *a, **k):
            pass

        def error(self, *a, **k):
            pass

        def warning(self, msg, details=None):
            warnings.append((msg, details))

    real_git = sync._git
    calls = {"bundle": 0}

    def flaky_git(cwd, *args, **kw):
        if args and args[0] == "bundle":
            calls["bundle"] += 1
            if calls["bundle"] == 1:
                return subprocess.CompletedProcess(
                    ["git", *args], returncode=128, stdout="",
                    stderr="fatal: some transient bundle failure\n")
        return real_git(cwd, *args, **kw)

    monkeypatch.setattr(sync, "system_logger", _Logger())
    monkeypatch.setattr(sync, "_git", flaky_git)
    proc = sync._build_export_bundle(repo, bundle, None, "main", head)
    assert proc.returncode == 0
    assert any("exclusions" in m for m, _ in warnings)


class _RM:
    def __init__(self, ws):
        self._ws = ws

    def get_repos(self):
        return ["demo"]

    def _get_workspace_path(self, name):
        return self._ws

    def _get_bare_path(self, name):
        return self._ws.parent / "missing-bare"


@pytest.fixture()
def client(repo, monkeypatch):
    monkeypatch.setenv("SYNC_PASSWORD", "pw")
    monkeypatch.setattr(sync, "repo_manager", _RM(repo))
    app = FastAPI()
    app.include_router(sync.router)
    return TestClient(app)


def _post_export(client, params):
    e = encrypt_envelope(params, "pw")
    return client.post("/api/documents/export", data={"e": e})


def test_endpoint_404_names_missing_branch(client):
    resp = _post_export(client, {"repo": "demo", "branch": "ghost"})
    assert resp.status_code == 404
    assert "branch 'ghost' not found on mirror" in resp.json()["detail"]


def test_endpoint_ok_for_existing_branch(client):
    resp = _post_export(client, {"repo": "demo", "branch": "main"})
    assert resp.status_code == 200
    body = resp.json()
    assert "e" in body and "d" in body
