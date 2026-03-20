import json
import subprocess
import tempfile
import time
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
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


def _build_client(storage: Path) -> TestClient:
    repo_manager = RepoManager(storage)
    api_router.repo_manager = repo_manager
    api_router.git_handler = None
    api_router.git_workspace = None
    api_router.shared_manager = None
    api_router.system_logger = None
    api_router.config = {"git_port": 0, "web_port": 0, "storage_path": storage}

    app = FastAPI()
    app.include_router(api_router.router)
    return TestClient(app)


def test_upload_and_apply_reports_stale_kit_on_non_native_dump(tmp_path: Path, monkeypatch):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "E2E Bot", "user_email": "e2e@example.com"}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("SYNC_PASSWORD", "test-password")

    client = _build_client(storage)
    repo_name = f"fmt-stale-{int(time.time())}"

    c = client.post("/api/repos/create", json={"name": repo_name})
    assert c.status_code == 200, c.text

    # Non-native payload (not LGMSTRL1 and not 7z archive) should fail deterministically
    fake_dump = b"X" * 1024
    res = client.post(
        "/api/sync/upload-and-apply",
        data={"repo": repo_name},
        files={"dump_file": (f"dump_{repo_name}_20260313_1200.dmp", fake_dump, "application/octet-stream")},
    )

    assert res.status_code == 200, res.text
    body = res.json()
    assert body["success"] is False
    assert body["message"].startswith("Failed to decrypt dump:")
    assert "Unsupported dump format" in body["message"]
    assert "stale/legacy work_kit" in body["message"]


def test_upload_and_apply_reports_password_mismatch_invalidtag(tmp_path: Path, monkeypatch):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "E2E Bot", "user_email": "e2e@example.com"}}),
        encoding="utf-8",
    )

    client = _build_client(storage)
    repo_name = f"fmt-invalidtag-{int(time.time())}"
    c = client.post("/api/repos/create", json={"name": repo_name})
    assert c.status_code == 200, c.text

    ws = storage / repo_name
    f = ws / "pw.txt"
    f.write_text("pw\n", encoding="utf-8")
    _run_git(ws, "add", "pw.txt")
    _run_git(ws, "commit", "-m", "pw source")

    with tempfile.TemporaryDirectory(prefix="lgm-invalidtag-src-") as td:
        td_path = Path(td)
        bundle = td_path / "debug_info.tmp"
        _run_git(ws, "bundle", "create", str(bundle), "--all")

        # Encrypt with one password
        from app.core.stealth_crypto import encrypt_bundle_to_dump

        dump = td_path / f"dump_{repo_name}_20260313_1201.dmp"
        encrypt_bundle_to_dump(bundle, dump, "encrypt-password")

        # Decrypt with different password on server
        monkeypatch.setenv("SYNC_PASSWORD", "decrypt-password")
        up = client.post(
            "/api/sync/upload-and-apply",
            data={"repo": repo_name},
            files={"dump_file": (dump.name, dump.read_bytes(), "application/octet-stream")},
        )
        assert up.status_code == 200, up.text
        body = up.json()
        assert body["success"] is False
        assert "InvalidTag" in body["message"]
        assert "password mismatch" in body["message"]


def test_upload_and_apply_rejects_legacy_7z_dump(tmp_path: Path, monkeypatch):
    seven_zip = Path("C:/Program Files/7-Zip/7z.exe")
    if not seven_zip.exists():
        return

    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "E2E Bot", "user_email": "e2e@example.com"}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("SYNC_PASSWORD", "test-password")

    client = _build_client(storage)
    repo_name = f"fmt-legacy-reject-{int(time.time())}"
    c = client.post("/api/repos/create", json={"name": repo_name})
    assert c.status_code == 200, c.text

    ws = storage / repo_name
    f = ws / "legacy.txt"
    f.write_text("legacy\n", encoding="utf-8")
    _run_git(ws, "add", "legacy.txt")
    _run_git(ws, "commit", "-m", "legacy bundle source")

    with tempfile.TemporaryDirectory(prefix="lgm-legacy-src-") as td:
        td_path = Path(td)
        bundle = td_path / "debug_info.tmp"
        _run_git(ws, "bundle", "create", str(bundle), "--all")

        legacy_dump = td_path / f"dump_{repo_name}_20260313_1200.dmp"
        pack = subprocess.run(
            [str(seven_zip), "a", "-t7z", "-ptest-password", "-mhe=on", "-mx=1", str(legacy_dump), str(bundle)],
            capture_output=True,
            text=True,
        )
        if pack.returncode != 0:
            raise AssertionError(f"7z pack failed: {pack.stdout}\n{pack.stderr}")

        assert not legacy_dump.read_bytes().startswith(b"LGMSTRL1")

        up = client.post(
            "/api/sync/upload-and-apply",
            data={"repo": repo_name},
            files={"dump_file": (legacy_dump.name, legacy_dump.read_bytes(), "application/octet-stream")},
        )
        assert up.status_code == 200, up.text
        body = up.json()
        assert body["success"] is False
        assert "Unsupported dump format" in body["message"]
