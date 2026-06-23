"""
Tests that /api/documents/list and /api/documents/export see branches
that were pushed directly to the BARE repo (not just the workspace).

This is the bug: pushing a branch via `git push` lands it in <repo>.git
(bare), but the old sync_refs only looked at the workspace checkout, so
freshly pushed branches were invisible to the plugin's pull dialog.
"""
import json
import subprocess
import time
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from tests import _harness


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
    assert client.post("/api/repos/create", json={"name": repo_name}).status_code == 200

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
    resp = client.get("/api/documents/list", params={"repo": repo_name})
    assert resp.status_code == 200, resp.text
    refs = resp.json().get("refs", {})
    assert "confluence_mcp" in refs, (
        f"Branch pushed to bare must appear in /documents/list. Got: {list(refs.keys())}"
    )


def test_export_bundles_branch_living_only_in_bare(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo_name = f"bare-export-{int(time.time())}"
    assert client.post("/api/repos/create", json={"name": repo_name}).status_code == 200

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
    resp = client.post(
        "/api/documents/export",
        data={"repo": repo_name, "branch": "confluence_mcp"},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json().get("status") == "ok"
    assert resp.json().get("data"), "Must return a bundle for the bare-only branch"
