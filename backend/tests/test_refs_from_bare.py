"""
Tests that /api/documents/list and /api/documents/export see branches
that were pushed directly to the BARE repo (not just the workspace).

This is the bug: pushing a branch via `git push` lands it in <repo>.git
(bare), but the old sync_refs only looked at the workspace checkout, so
freshly pushed branches were invisible to the plugin's pull dialog.
"""
import json
import os
import subprocess
import time
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from tests import _harness
from tests.conftest import envelope_form_post, envelope_post, parse_envelope

OLD_DATE = "2026-01-01T00:00:00+00:00"
NEW_DATE = "2026-06-01T00:00:00+00:00"


def _run_git(cwd: Path, *args: str, env: dict = None) -> subprocess.CompletedProcess:
    full_env = {**os.environ, **env} if env else None
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True, env=full_env)
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
    monkeypatch.setenv("SYNC_PASSWORD", "test-pass")
    rm = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=rm,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    return TestClient(app), storage


def test_list_shows_branch_pushed_only_to_bare(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo_name = f"bare-branch-{int(time.time())}"
    assert client.post("/api/documents/collection", json={"name": repo_name}).status_code == 200

    bare = storage / ".lgm" / "bare" / f"{repo_name}.git"

    # Clone the bare repo to a scratch dir, create a new branch, push it to bare.
    # This simulates a `git push` of a brand-new branch from a work machine.
    scratch = tmp_path / "scratch"
    _run_git(tmp_path, "clone", str(bare), str(scratch))
    _run_git(scratch, "config", "user.email", "w@test.com")
    _run_git(scratch, "config", "user.name", "Worker")
    _run_git(scratch, "checkout", "-B", "confluence_mcp")
    (scratch / "feat.txt").write_text("feature\n")
    _run_git(scratch, "add", ".")
    _run_git(scratch, "commit", "-m", "confluence work")
    _run_git(scratch, "push", "origin", "confluence_mcp")

    # The new branch exists in bare but NOT in the workspace checkout.
    resp = envelope_post(client, "/api/documents/list", {"repo": repo_name}, "test-pass")
    assert resp.status_code == 200, resp.text
    refs = parse_envelope(resp.json(), "test-pass").get("refs", {})
    assert "confluence_mcp" in refs, (
        f"Branch pushed to bare must appear in /documents/list. Got: {list(refs.keys())}"
    )


def test_export_bundles_branch_living_only_in_bare(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo_name = f"bare-export-{int(time.time())}"
    assert client.post("/api/documents/collection", json={"name": repo_name}).status_code == 200

    bare = storage / ".lgm" / "bare" / f"{repo_name}.git"
    scratch = tmp_path / "scratch2"
    _run_git(tmp_path, "clone", str(bare), str(scratch))
    _run_git(scratch, "config", "user.email", "w@test.com")
    _run_git(scratch, "config", "user.name", "Worker")
    _run_git(scratch, "checkout", "-B", "confluence_mcp")
    (scratch / "feat.txt").write_text("feature\n")
    _run_git(scratch, "add", ".")
    _run_git(scratch, "commit", "-m", "confluence work")
    _run_git(scratch, "push", "origin", "confluence_mcp")

    # Export must succeed even though the branch is only in bare
    resp = envelope_form_post(
        client, "/api/documents/export", {"repo": repo_name, "branch": "confluence_mcp"}, "test-pass"
    )
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), "test-pass")
    assert inner.get("status") == "ok"
    assert resp.json().get("d"), "Must return a bundle for the bare-only branch"


def _commit(repo: Path, filename: str, message: str, date: str) -> str:
    (repo / filename).write_text(f"{message}\n", encoding="utf-8")
    _run_git(repo, "add", ".")
    _run_git(repo, "commit", "-m", message,
             env={"GIT_COMMITTER_DATE": date, "GIT_AUTHOR_DATE": date})
    return _run_git(repo, "rev-parse", "HEAD").stdout.strip()


def test_preview_reports_newer_tip_pushed_to_bare(tmp_path: Path, monkeypatch):
    """S3: a branch pushed to bare while the workspace lags behind must make
    /documents/preview report the newer tip, not "no new commits"."""
    client, storage = _make_client(tmp_path, monkeypatch)
    repo_name = f"bare-preview-{int(time.time())}"
    assert client.post("/api/documents/collection", json={"name": repo_name}).status_code == 200

    ws = storage / repo_name
    bare = storage / ".lgm" / "bare" / f"{repo_name}.git"

    _run_git(ws, "config", "user.email", "h@test.com")
    _run_git(ws, "config", "user.name", "Home")
    c0 = _commit(ws, "home.txt", "c0", OLD_DATE)
    _run_git(ws, "push", str(bare), "HEAD")

    # A work machine pushes a newer commit to the bare repo; the workspace
    # never fetches it, so its HEAD stays at c0.
    scratch = tmp_path / "scratch"
    _run_git(tmp_path, "clone", str(bare), str(scratch))
    _run_git(scratch, "config", "user.email", "w@test.com")
    _run_git(scratch, "config", "user.name", "Worker")
    c1 = _commit(scratch, "work.txt", "c1", NEW_DATE)
    _run_git(scratch, "push", "origin", "HEAD")

    resp = envelope_post(client, "/api/documents/preview",
                         {"repo": repo_name, "since": c0}, "test-pass")
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), "test-pass")
    assert inner["hasUpdates"] is True, (
        f"bare tip {c1} is newer than since {c0}; preview must report updates: {inner}"
    )
    assert inner["remoteHead"] == c1, (
        f"preview must report the bare tip {c1}, got: {inner}"
    )
    assert inner["reason"] != "no-new-commits", inner


def test_preview_no_updates_when_mirror_is_at_since(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo_name = f"bare-preview-same-{int(time.time())}"
    assert client.post("/api/documents/collection", json={"name": repo_name}).status_code == 200

    ws = storage / repo_name
    bare = storage / ".lgm" / "bare" / f"{repo_name}.git"

    _run_git(ws, "config", "user.email", "h@test.com")
    _run_git(ws, "config", "user.name", "Home")
    c0 = _commit(ws, "home.txt", "c0", OLD_DATE)
    _run_git(ws, "push", str(bare), "HEAD")

    resp = envelope_post(client, "/api/documents/preview",
                         {"repo": repo_name, "since": c0}, "test-pass")
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), "test-pass")
    assert inner["hasUpdates"] is False, inner
    assert inner["reason"] == "no-new-commits", inner
