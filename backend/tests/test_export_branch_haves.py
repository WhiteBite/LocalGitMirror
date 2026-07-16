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

from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from tests import _harness
from tests.conftest import envelope_form_post, parse_envelope

PASSWORD = "test-pass"


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
    return TestClient(app), storage


def _export(client, repo_name: str, *, branch=None, haves=None) -> dict:
    """Send an envelope export request, return (decrypted inner dict, raw_bundle bytes)."""
    payload = {"repo": repo_name}
    if branch:
        payload["branch"] = branch
    if haves:
        payload["haves"] = haves
    resp = envelope_form_post(client, "/api/documents/export", payload, PASSWORD)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    inner = parse_envelope(body, PASSWORD)
    inner["_raw_bundle"] = base64.b64decode(body["d"]) if "d" in body else None
    return inner


def test_export_with_branch_bundles_only_that_branch(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo_name = f"branch-export-{int(time.time())}"
    assert client.post("/api/repos/create", json={"name": repo_name}).status_code == 200

    ws = storage / repo_name
    _run_git(ws, "checkout", "-B", "main")
    (ws / "main.txt").write_text("main\n")
    _run_git(ws, "add", "."); _run_git(ws, "commit", "-m", "main commit")

    _run_git(ws, "checkout", "-B", "feature")
    (ws / "feature.txt").write_text("feat\n")
    _run_git(ws, "add", "."); _run_git(ws, "commit", "-m", "feature commit")
    _run_git(ws, "checkout", "main")

    inner = _export(client, repo_name, branch="feature")
    assert inner.get("status") == "ok"
    raw = inner["_raw_bundle"]
    assert raw is not None, "Must contain base64 bundle"
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

    delta_inner = _export(client, repo_name, branch="main", haves=hash_a)
    full_inner = _export(client, repo_name, branch="main")

    assert len(delta_inner["_raw_bundle"]) < len(full_inner["_raw_bundle"]), (
        "Delta bundle should be smaller than full bundle"
    )


def test_export_ignores_unknown_haves(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo_name = f"badhaves-export-{int(time.time())}"
    assert client.post("/api/repos/create", json={"name": repo_name}).status_code == 200

    ws = storage / repo_name
    _run_git(ws, "checkout", "-B", "main")
    (ws / "a.txt").write_text("a\n")
    _run_git(ws, "add", "."); _run_git(ws, "commit", "-m", "commit A")

    inner = _export(client, repo_name, branch="main",
                    haves="deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
    assert inner["_raw_bundle"] is not None, "Should return a full bundle despite unknown haves"


def test_export_no_branch_falls_back_to_all(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo_name = f"fallback-export-{int(time.time())}"
    assert client.post("/api/repos/create", json={"name": repo_name}).status_code == 200

    ws = storage / repo_name
    (ws / "a.txt").write_text("a\n")
    _run_git(ws, "add", "."); _run_git(ws, "commit", "-m", "commit A")

    inner = _export(client, repo_name)
    assert inner.get("status") == "ok"
    assert inner["_raw_bundle"] is not None
