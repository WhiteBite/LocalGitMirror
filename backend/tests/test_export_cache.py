"""
Honest tests for the server-side export bundle cache (D3).

These drive the REAL /api/documents/export endpoint against REAL bare +
workspace repos created by RepoManager, then decrypt the returned dump and
inspect the embedded git bundle. That way they catch genuine correctness bugs
(stale cache after a moved tip, unbounded growth, corruption handling) rather
than re-asserting mocks.
"""
import base64
import json
import subprocess
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.bundle_crypto import decrypt_dump_to_bundle
from app.core.repo_manager import RepoManager
from app.routers import sync as sync_module
from tests import _harness
from tests.conftest import envelope_form_post, parse_envelope

PASSWORD = "export-cache-test-pw"


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(
            f"git {' '.join(args)} failed\ncwd={cwd}\nexit={proc.returncode}\n"
            f"stdout={proc.stdout}\nstderr={proc.stderr}"
        )
    return proc


def _build_client(storage: Path):
    repo_manager = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=repo_manager,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    return TestClient(app), repo_manager


def _make_repo(tmp_path: Path):
    """Create a backend repo with a 'trunk' branch that lives ONLY in the bare.

    The export endpoint prefers the workspace as its source when the workspace
    has the requested branch. The workspace 'master' created by /repos/create
    never moves in these tests, so we drive everything through a bare-only
    'trunk' branch — that makes the bare the export source and lets HEAD move
    as we push, which is exactly what the cache key must track.
    """
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@example.com"}}),
        encoding="utf-8",
    )
    client, rm = _build_client(storage)
    repo = f"expcache-{int(time.time()*1000)}"
    created = client.post("/api/repos/create", json={"name": repo})
    assert created.status_code == 200, created.text

    bare = rm._get_bare_path(repo)

    work = tmp_path / "scratch"
    work.mkdir()
    _run_git(work, "init")
    _run_git(work, "config", "user.email", "w@example.com")
    _run_git(work, "config", "user.name", "W")
    _run_git(work, "checkout", "-B", "trunk")
    (work / "f.txt").write_text("init\n", encoding="utf-8")
    _run_git(work, "add", "f.txt")
    _run_git(work, "commit", "-m", "trunk: init")
    _run_git(work, "push", "--force", str(bare), "refs/heads/trunk:refs/heads/trunk")
    # Point the bare HEAD at trunk so `rev-parse HEAD` tracks the trunk tip.
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/trunk")

    return client, repo, bare, work, rm, storage


def _push_commit(work: Path, bare: Path, msg: str) -> str:
    (work / "f.txt").write_text(msg + "\n", encoding="utf-8")
    _run_git(work, "add", "f.txt")
    _run_git(work, "commit", "-m", msg)
    sha = _run_git(work, "rev-parse", "HEAD").stdout.strip()
    _run_git(work, "push", "--force", str(bare), "refs/heads/trunk:refs/heads/trunk")
    return sha


def _export(client: TestClient, repo: str, **extra) -> dict:
    """Send an envelope export request and return the decrypted inner dict + raw bundle."""
    payload = {"repo": repo, **extra}
    resp = envelope_form_post(client, "/api/documents/export", payload, PASSWORD)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    inner = parse_envelope(body, PASSWORD)
    # Attach bundle data at top-level for backwards-compat with existing tests
    inner["data"] = body.get("d")
    return inner


def _bundle_refs(dump_b64: str, tmp_path: Path, name: str) -> tuple[dict, Path]:
    """Decrypt a base64 dump and return (refname->sha mapping, bundle path)."""
    dump_path = tmp_path / f"{name}.dmp"
    dump_path.write_bytes(base64.b64decode(dump_b64))
    bundle_path = tmp_path / f"{name}.bundle"
    decrypt_dump_to_bundle(dump_path, bundle_path, PASSWORD)
    proc = subprocess.run(
        ["git", "bundle", "list-heads", str(bundle_path)],
        cwd=str(tmp_path), capture_output=True, text=True,
    )
    assert proc.returncode == 0, f"list-heads failed: {proc.stderr}"
    refs = {}
    for line in proc.stdout.splitlines():
        parts = line.strip().split()
        if len(parts) >= 2:
            refs[parts[1]] = parts[0]
    return refs, bundle_path


# ── Test 1: cache hit serves correct, decryptable content ────────────────────

def test_cache_hit_serves_correct_content(tmp_path, monkeypatch):
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)
    client, repo, bare, work, rm, storage = _make_repo(tmp_path)

    first = _export(client, repo, branch="trunk")
    second = _export(client, repo, branch="trunk")

    assert first["status"] == "ok"
    assert second["status"] == "ok"
    assert first["head"] == second["head"]

    refs1, _ = _bundle_refs(first["data"], tmp_path, "first")
    refs2, _ = _bundle_refs(second["data"], tmp_path, "second")

    # Both decrypt to valid bundles containing the same master commit.
    assert "refs/heads/trunk" in refs1
    assert refs1 == refs2

    # Sanity: a cache file was actually written.
    cache_dir = storage / ".lgm" / "_export_cache"
    entries = [p for p in cache_dir.iterdir() if p.is_file() and not p.name.startswith(".tmp_")]
    assert len(entries) == 1, f"expected one cached bundle, found {entries}"


# ── Test 2: cache invalidates when the branch tip moves (CRITICAL) ───────────

def test_cache_invalidates_on_new_commit(tmp_path, monkeypatch):
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)
    client, repo, bare, work, rm, storage = _make_repo(tmp_path)

    first = _export(client, repo, branch="trunk")
    refs1, _ = _bundle_refs(first["data"], tmp_path, "before")
    old_sha = refs1["refs/heads/trunk"]

    # Move HEAD forward with a brand new commit.
    new_sha = _push_commit(work, bare, "second commit")
    assert new_sha != old_sha

    second = _export(client, repo, branch="trunk")
    refs2, _ = _bundle_refs(second["data"], tmp_path, "after")
    new_bundle_sha = refs2["refs/heads/trunk"]

    # The export must reflect the NEW tip — a cache keyed only by repo would
    # wrongly return the old commit here.
    assert second["head"] == new_sha
    assert new_bundle_sha == new_sha
    assert new_bundle_sha != old_sha


# ── Test 3: cache is bounded (LRU cap) ───────────────────────────────────────

def test_cache_is_bounded(tmp_path, monkeypatch):
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)
    client, repo, bare, work, rm, storage = _make_repo(tmp_path)

    cap = sync_module._EXPORT_CACHE_MAX_ENTRIES
    cache_dir = storage / ".lgm" / "_export_cache"

    # Each distinct commit -> distinct head -> distinct cache key.
    for i in range(cap + 5):
        _push_commit(work, bare, f"commit {i}")
        out = _export(client, repo, branch="trunk")
        assert out["status"] == "ok"

    entries = [p for p in cache_dir.iterdir() if p.is_file() and not p.name.startswith(".tmp_")]
    assert len(entries) <= cap, f"cache grew past cap: {len(entries)} > {cap}"


# ── Test 4: corrupt cache falls back gracefully ──────────────────────────────

def test_corrupt_cache_falls_back(tmp_path, monkeypatch):
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)
    client, repo, bare, work, rm, storage = _make_repo(tmp_path)

    # Populate the cache once.
    first = _export(client, repo, branch="trunk")
    refs1, _ = _bundle_refs(first["data"], tmp_path, "warm")

    # Poison every cache entry with garbage bytes.
    cache_dir = storage / ".lgm" / "_export_cache"
    poisoned = 0
    for p in cache_dir.iterdir():
        if p.is_file():
            p.write_bytes(b"this is not a git bundle \x00\x01\x02")
            poisoned += 1
    assert poisoned >= 1, "expected at least one cache file to poison"

    # Export must still succeed by rebuilding from scratch.
    second = _export(client, repo, branch="trunk")
    assert second["status"] == "ok"
    refs2, _ = _bundle_refs(second["data"], tmp_path, "recovered")
    assert refs2.get("refs/heads/trunk") == refs1.get("refs/heads/trunk")

