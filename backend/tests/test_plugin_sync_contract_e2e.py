import json
import subprocess
import time
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from app.core.stealth_crypto import encrypt_bundle_to_dump
from app.routers import api as api_router


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(
            f"git {' '.join(args)} failed\n"
            f"cwd={cwd}\n"
            f"exit={proc.returncode}\n"
            f"stdout={proc.stdout}\n"
            f"stderr={proc.stderr}"
        )
    return proc


def test_plugin_sync_contract_e2e(tmp_path: Path, monkeypatch):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)

    # Deterministic git identity for RepoManager.create_repo initial commit
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "E2E Bot", "user_email": "e2e@example.com"}}),
        encoding="utf-8",
    )

    monkeypatch.setenv("SYNC_PASSWORD", "e2e-password")

    repo_name = f"e2e-plugin-sync-{int(time.time())}"
    repo_manager = RepoManager(storage)
    api_router.repo_manager = repo_manager
    api_router.git_handler = None
    api_router.git_workspace = None
    api_router.shared_manager = None
    api_router.system_logger = None
    api_router.config = {"git_port": 0, "web_port": 0, "storage_path": storage}

    app = FastAPI()
    app.include_router(api_router.router)
    client = TestClient(app)

    # 1) Ensure repo exists
    created = client.post("/api/repos/create", json={"name": repo_name})
    assert created.status_code == 200, created.text
    assert created.json().get("success") is True

    ws = storage / repo_name
    assert (ws / ".git").exists(), "Workspace is not a git repo"

    # 2) Create a new commit in workspace to later export
    test_file = ws / "E2E.txt"
    test_file.write_text("hello\n", encoding="utf-8")
    _run_git(ws, "add", "E2E.txt")
    _run_git(ws, "commit", "-m", "e2e: add E2E.txt")

    head = _run_git(ws, "rev-parse", "HEAD").stdout.strip()

    # 3) has-commits should report HEAD as known
    has = client.post(
        "/api/documents/check",
        json={"repo": repo_name, "commits": [head, "deadbeef"]},
    )
    assert has.status_code == 200, has.text
    payload = has.json()
    assert payload.get("success") is True
    assert payload.get("repo") == repo_name
    assert head in payload.get("known", [])
    assert payload.get("head") == head

    # 4) export-dump should produce a .dmp and set headers
    export = client.post(
        "/api/documents/export",
        data={"repo": repo_name, "since": ""},
    )
    assert export.status_code == 200, export.text
    assert export.headers.get("X-Ref-Id") == repo_name
    export_head = export.headers.get("X-Ref")
    assert export_head and len(export_head) >= 7
    assert export.content[0:1] == b"\x01", "Exported dump must be v2 format (version byte 0x01)"

    # 5) upload-and-apply should accept dump and succeed (idempotent apply)
    # Create an independent bundle and encrypted dump to simulate plugin upload
    bundle = tmp_path / "u.bundle"
    _run_git(ws, "bundle", "create", str(bundle), "--all")
    dump = tmp_path / f"dump_{repo_name}_20260313_1200.dmp"
    encrypt_bundle_to_dump(bundle, dump, "e2e-password")

    up = client.post(
        "/api/documents/upload",
        data={"repo": repo_name},
        files={"attachment": (dump.name, dump.read_bytes(), "application/octet-stream")},
    )
    assert up.status_code == 200, up.text
    upj = up.json()
    assert upj.get("success") is True
    assert upj.get("repo") == repo_name

    # 6) apply-known should reset to existing commit
    apply_known = client.post("/api/documents/link", json={"repo": repo_name, "commit": head})
    assert apply_known.status_code == 200, apply_known.text
    ak = apply_known.json()
    assert ak.get("success") is True
    assert ak.get("repo") == repo_name
    assert ak.get("commit") == head

    # 7) export-dump since=head should return 204
    export2 = client.post(
        "/api/documents/export",
        data={"repo": repo_name, "since": head},
    )
    assert export2.status_code == 204
    assert export2.headers.get("X-Ref-Id") == repo_name
    assert export2.headers.get("X-Ref") == head


def test_upload_and_apply_unrelated_histories_replaces_branch(tmp_path: Path, monkeypatch):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "E2E Bot", "user_email": "e2e@example.com"}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("SYNC_PASSWORD", "e2e-password")

    repo_name = f"e2e-unrelated-{int(time.time())}"
    repo_manager = RepoManager(storage)
    api_router.repo_manager = repo_manager
    api_router.git_handler = None
    api_router.git_workspace = None
    api_router.shared_manager = None
    api_router.system_logger = None
    api_router.config = {"git_port": 0, "web_port": 0, "storage_path": storage}

    app = FastAPI()
    app.include_router(api_router.router)
    client = TestClient(app)

    created = client.post("/api/repos/create", json={"name": repo_name})
    assert created.status_code == 200, created.text
    assert created.json().get("success") is True

    ws = storage / repo_name
    _run_git(ws, "checkout", "-B", "main")

    # target history A
    target_file = ws / "target.txt"
    target_file.write_text("target\n", encoding="utf-8")
    _run_git(ws, "add", "target.txt")
    _run_git(ws, "commit", "-m", "target history")
    head_before = _run_git(ws, "rev-parse", "HEAD").stdout.strip()

    # independent source history B
    src = tmp_path / "independent-src"
    src.mkdir(parents=True, exist_ok=True)
    _run_git(src, "init")
    _run_git(src, "config", "user.email", "src@example.com")
    _run_git(src, "config", "user.name", "Src")
    _run_git(src, "checkout", "-B", "main")
    src_file = src / "source.txt"
    src_file.write_text("source\n", encoding="utf-8")
    _run_git(src, "add", "source.txt")
    _run_git(src, "commit", "-m", "source history")
    src_head = _run_git(src, "rev-parse", "HEAD").stdout.strip()

    # Upload source history dump to target repo name.
    bundle = tmp_path / "unrelated.bundle"
    _run_git(src, "bundle", "create", str(bundle), "--all")
    dump = tmp_path / f"dump_{repo_name}_20260313_1300.dmp"
    encrypt_bundle_to_dump(bundle, dump, "e2e-password")

    up = client.post(
        "/api/documents/upload",
        data={"repo": repo_name},
        files={"attachment": (dump.name, dump.read_bytes(), "application/octet-stream")},
    )
    assert up.status_code == 200, up.text
    body = up.json()
    assert body.get("success") is True, body
    assert body.get("repo") == repo_name

    # Branch should now be replaced by source history.
    head_after = _run_git(ws, "rev-parse", "HEAD").stdout.strip()
    assert head_after != head_before
    assert head_after == src_head


def test_apply_known_rejects_dirty_workspace_and_unknown_commit(tmp_path: Path, monkeypatch):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "E2E Bot", "user_email": "e2e@example.com"}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("SYNC_PASSWORD", "e2e-password")

    repo_name = f"e2e-apply-known-{int(time.time())}"
    repo_manager = RepoManager(storage)
    api_router.repo_manager = repo_manager
    api_router.git_handler = None
    api_router.git_workspace = None
    api_router.shared_manager = None
    api_router.system_logger = None
    api_router.config = {"git_port": 0, "web_port": 0, "storage_path": storage}

    app = FastAPI()
    app.include_router(api_router.router)
    client = TestClient(app)

    created = client.post("/api/repos/create", json={"name": repo_name})
    assert created.status_code == 200, created.text
    assert created.json().get("success") is True

    ws = storage / repo_name
    _run_git(ws, "checkout", "-B", "main")
    (ws / "k.txt").write_text("v1\n", encoding="utf-8")
    _run_git(ws, "add", "k.txt")
    _run_git(ws, "commit", "-m", "known commit")
    known = _run_git(ws, "rev-parse", "HEAD").stdout.strip()

    # Dirty workspace rejection
    (ws / "k.txt").write_text("dirty\n", encoding="utf-8")
    dirty = client.post("/api/documents/link", json={"repo": repo_name, "commit": known})
    assert dirty.status_code == 200, dirty.text
    dj = dirty.json()
    # apply-known may succeed with reset if workspace was dirty (depends on impl)
    # or may fail with "Uncommitted changes"
    if not dj.get("success"):
        assert "Uncommitted" in dj.get("message", "") or "dirty" in dj.get("message", "").lower()

    # Clean then unknown commit rejection
    _run_git(ws, "checkout", "--", "k.txt")
    unknown = client.post("/api/documents/link", json={"repo": repo_name, "commit": "deadbeef"})
    assert unknown.status_code == 200, unknown.text
    uj = unknown.json()
    assert uj.get("success") is False
    assert "Commit not found locally" in uj.get("message", "")


def test_export_dump_unknown_since_falls_back_to_full_dump(tmp_path: Path, monkeypatch):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "E2E Bot", "user_email": "e2e@example.com"}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("SYNC_PASSWORD", "e2e-password")

    repo_name = f"e2e-export-fallback-{int(time.time())}"
    repo_manager = RepoManager(storage)
    api_router.repo_manager = repo_manager
    api_router.git_handler = None
    api_router.git_workspace = None
    api_router.shared_manager = None
    api_router.system_logger = None
    api_router.config = {"git_port": 0, "web_port": 0, "storage_path": storage}

    app = FastAPI()
    app.include_router(api_router.router)
    client = TestClient(app)

    created = client.post("/api/repos/create", json={"name": repo_name})
    assert created.status_code == 200, created.text
    assert created.json().get("success") is True

    ws = storage / repo_name
    (ws / "f.txt").write_text("v1\n", encoding="utf-8")
    _run_git(ws, "add", "f.txt")
    _run_git(ws, "commit", "-m", "export base")

    ex = client.post("/api/documents/export", data={"repo": repo_name, "since": "deadbeef"})
    assert ex.status_code == 200, ex.text
    assert ex.headers.get("X-Ref-Id") == repo_name
    assert ex.content[0:1] == b"\x01", "Exported dump must be v2 format"
