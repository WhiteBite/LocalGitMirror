"""apply-known must never blind-force: a client-claimed hash that would rewind
a bare branch is rejected, and push success comes from the process exit code
plus the actual ref state, never from parsing git's stderr text."""
import json
import subprocess
import time
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from tests import _harness
from tests.conftest import envelope_post, parse_envelope

PASSWORD = "ak-ff-pw"


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} failed: {proc.stderr}")
    return proc


def _make_client(tmp_path: Path, monkeypatch):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)

    rm = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=rm,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    return TestClient(app), storage, rm


def _create_repo(client, storage, rm, repo_name: str) -> tuple[Path, Path]:
    assert client.post("/api/documents/collection", json={"name": repo_name}).status_code == 200
    return storage / repo_name, rm._get_bare_path(repo_name)


def _commit_file(ws: Path, branch: str, filename: str, content: str) -> str:
    _run_git(ws, "checkout", "-B", branch)
    (ws / filename).write_text(content, encoding="utf-8")
    _run_git(ws, "add", filename)
    _run_git(ws, "commit", "-m", f"{branch}: {filename}")
    return _run_git(ws, "rev-parse", "HEAD").stdout.strip()


def test_apply_known_refuses_to_rewind_bare_branch(tmp_path, monkeypatch):
    """The client claims a hash that is BEHIND the bare branch (another machine
    advanced it): the mirror must keep the newer tip and report the rejection,
    not blind-force the rewind and count it as pushed."""
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"ak-ff-{int(time.time() * 1000)}"
    ws, bare = _create_repo(client, storage, rm, repo)

    c1 = _commit_file(ws, "main", "a.txt", "a\n")
    _run_git(ws, "push", str(bare), "refs/heads/main:refs/heads/main")

    # A second machine advances bare main beyond what the workspace has seen.
    scratch = tmp_path / "scratch"
    _run_git(tmp_path, "clone", "--branch", "main", str(bare), str(scratch))
    _run_git(scratch, "config", "user.email", "w@test.com")
    _run_git(scratch, "config", "user.name", "Worker")
    (scratch / "b.txt").write_text("b\n", encoding="utf-8")
    _run_git(scratch, "add", ".")
    _run_git(scratch, "commit", "-m", "advance main")
    c2 = _run_git(scratch, "rev-parse", "HEAD").stdout.strip()
    _run_git(scratch, "push", "origin", "HEAD:refs/heads/main")

    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": c1,
        "branches": {"main": c1},
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)

    assert "main" not in inner["branches"], (
        f"a rejected (non-fast-forward) ref must not be counted as pushed: {inner}"
    )
    assert inner.get("rejected_branches") == ["main"], (
        f"the rejection must be visible in the response: {inner}"
    )
    assert _run_git(bare, "rev-parse", "refs/heads/main").stdout.strip() == c2, (
        "apply-known must not rewind the bare branch to the client's stale hash"
    )


def test_apply_known_fast_forward_update_lands(tmp_path, monkeypatch):
    """A client hash that descends from the bare tip is a fast-forward and
    must still land normally."""
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"ak-ffok-{int(time.time() * 1000)}"
    ws, bare = _create_repo(client, storage, rm, repo)

    c1 = _commit_file(ws, "main", "a.txt", "a\n")
    _run_git(ws, "push", str(bare), "refs/heads/main:refs/heads/main")
    c2 = _commit_file(ws, "main", "b.txt", "b\n")

    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": c2,
        "branches": {"main": c2},
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)

    assert inner["success"] is True, inner
    assert inner["branches"] == ["main"], inner
    assert _run_git(bare, "rev-parse", "refs/heads/main").stdout.strip() == c2


def test_apply_known_preserves_unrelated_home_branch(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    ws, bare = _create_repo(client, storage, rm, "ak-preserve-home")
    home_tip = _commit_file(ws, "home-work", "a.txt", "home\n")
    target_tip = _commit_file(ws, "target", "a.txt", "target\n")
    _run_git(ws, "checkout", "home-work")

    response = envelope_post(client, "/api/documents/link", {
        "repo": "ak-preserve-home", "commit": target_tip,
        "branches": {"target": target_tip},
    }, PASSWORD)

    assert response.status_code == 200
    assert parse_envelope(response.json(), PASSWORD)["success"] is True
    assert parse_envelope(response.json(), PASSWORD)["branches"] == ["target"]
    assert _run_git(ws, "rev-parse", "HEAD").stdout.strip() == home_tip
    assert (ws / "a.txt").read_text() == "home\n"
    assert _run_git(bare, "rev-parse", "refs/heads/target").stdout.strip() == target_tip
