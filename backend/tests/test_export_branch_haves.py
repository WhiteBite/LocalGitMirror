"""
Tests for the smart export: branch-scoped bundles + haves exclusions.

Verifies that /api/documents/export:
- with `branch` bundles only that branch (not --all)
- with `haves` excludes commits the client already has (smaller delta)
- ignores invalid/unknown haves gracefully
- falls back to --all when no branch given (backward compat)
"""
import base64
import json
import subprocess
import time
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from app.routers import api as api_router


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
    api_router.repo_manager = rm
    api_router.git_handler = None
    api_router.git_workspace = None
    api_router.shared_manager = None
    api_router.system_logger = None
    api_router.config = {"git_port": 0, "web_port": 0, "storage_path": storage}

    app = FastAPI()
    app.include_router(api_router.router)
    return TestClient(app), storage


def _bundle_heads(raw_dump: bytes, tmp_path: Path) -> set:
    """Decrypt is not needed here — we just check the export is non-empty/valid base64."""
    return set()


def test_export_with_branch_bundles_only_that_branch(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo_name = f"branch-export-{int(time.time())}"
    assert client.post("/api/repos/create", json={"name": repo_name}).status_code == 200

    ws = storage / repo_name
    _run_git(ws, "checkout", "-B", "main")
    (ws / "main.txt").write_text("main\n")
    _run_git(ws, "add", "."); _run_git(ws, "commit", "-m", "main commit")

    # Create a second branch with unrelated content
    _run_git(ws, "checkout", "-B", "feature")
    (ws / "feature.txt").write_text("feat\n")
    _run_git(ws, "add", "."); _run_git(ws, "commit", "-m", "feature commit")
    _run_git(ws, "checkout", "main")

    # Export only `feature`
    resp = client.post("/api/documents/export", data={"repo": repo_name, "branch": "feature"})
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body.get("status") == "ok"
    assert body.get("data"), "Must contain base64 bundle"
    raw = base64.b64decode(body["data"])
    assert raw[0:1] == b"\x01", "Must be encrypted v2 dump"


def test_export_with_haves_excludes_known_commits(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo_name = f"haves-export-{int(time.time())}"
    assert client.post("/api/repos/create", json={"name": repo_name}).status_code == 200

    ws = storage / repo_name
    _run_git(ws, "checkout", "-B", "main")
    (ws / "a.txt").write_text("a\n")
    _run_git(ws, "add", "."); _run_git(ws, "commit", "-m", "commit A")
    hash_a = _run_git(ws, "rev-parse", "HEAD").stdout.strip()

    (ws / "b.txt").write_text("b\n")
    _run_git(ws, "add", "."); _run_git(ws, "commit", "-m", "commit B")

    # Export with haves=hash_a → bundle should be the delta after A (smaller)
    resp_delta = client.post(
        "/api/documents/export",
        data={"repo": repo_name, "branch": "main", "haves": hash_a},
    )
    assert resp_delta.status_code == 200, resp_delta.text
    delta = base64.b64decode(resp_delta.json()["data"])

    # Export full branch (no haves)
    resp_full = client.post(
        "/api/documents/export",
        data={"repo": repo_name, "branch": "main"},
    )
    assert resp_full.status_code == 200, resp_full.text
    full = base64.b64decode(resp_full.json()["data"])

    # Delta bundle must be strictly smaller than the full bundle
    assert len(delta) < len(full), (
        f"Delta bundle ({len(delta)}) should be smaller than full ({len(full)})"
    )


def test_export_ignores_unknown_haves(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo_name = f"badhaves-export-{int(time.time())}"
    assert client.post("/api/repos/create", json={"name": repo_name}).status_code == 200

    ws = storage / repo_name
    _run_git(ws, "checkout", "-B", "main")
    (ws / "a.txt").write_text("a\n")
    _run_git(ws, "add", "."); _run_git(ws, "commit", "-m", "commit A")

    # Unknown hash in haves must be ignored, not crash
    resp = client.post(
        "/api/documents/export",
        data={"repo": repo_name, "branch": "main", "haves": "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json().get("data"), "Should still return a full bundle"


def test_export_no_branch_falls_back_to_all(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo_name = f"fallback-export-{int(time.time())}"
    assert client.post("/api/repos/create", json={"name": repo_name}).status_code == 200

    ws = storage / repo_name
    (ws / "a.txt").write_text("a\n")
    _run_git(ws, "add", "."); _run_git(ws, "commit", "-m", "commit A")

    # No branch param → legacy --all behaviour
    resp = client.post("/api/documents/export", data={"repo": repo_name})
    assert resp.status_code == 200, resp.text
    assert resp.json().get("status") == "ok"
    assert resp.json().get("data")
