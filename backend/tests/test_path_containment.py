"""Path containment for client-supplied paths (S6).

Every endpoint that turns a client string into a filesystem path must keep
the result inside its base directory: /api/file/*, /api/repos/select and
the shared folders manager.
"""

import json
import subprocess
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from app.core.shared_manager import SharedManager
from tests import _harness


def _build_client(tmp_path: Path):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@example.com"}}),
        encoding="utf-8",
    )
    repo_manager = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=repo_manager,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    return repo_manager, TestClient(app), storage


def _make_repo(client: TestClient, name: str):
    created = client.post("/api/documents/collection", json={"name": name})
    assert created.status_code == 200, created.text
    selected = client.post("/api/repos/select", json={"repo": name})
    assert selected.status_code == 200, selected.text


def _commit_count(folder: Path) -> int:
    proc = subprocess.run(
        ["git", "rev-list", "--count", "HEAD"], cwd=str(folder), capture_output=True, text=True
    )
    assert proc.returncode == 0, proc.stderr
    return int(proc.stdout.strip())


def test_file_save_rejects_dotdot_escape(tmp_path: Path):
    repo_manager, client, storage = _build_client(tmp_path)
    _make_repo(client, "contain")

    resp = client.post("/api/file/save", json={"path": "../../escape.txt", "content": "evil"})

    assert resp.status_code in (400, 403), resp.text
    assert not (tmp_path / "escape.txt").exists()


def test_file_save_rejects_absolute_path(tmp_path: Path):
    repo_manager, client, storage = _build_client(tmp_path)
    _make_repo(client, "contain")
    target = tmp_path / "abs-escape.txt"

    resp = client.post("/api/file/save", json={"path": str(target), "content": "evil"})

    assert resp.status_code in (400, 403), resp.text
    assert not target.exists()


def test_file_view_rejects_dotdot_escape(tmp_path: Path):
    repo_manager, client, storage = _build_client(tmp_path)
    _make_repo(client, "contain")
    secret = tmp_path / "secret.txt"
    secret.write_text("top secret", encoding="utf-8")

    resp = client.get("/api/file/view", params={"file": "../../secret.txt"})

    assert resp.status_code in (400, 403), resp.text
    assert "top secret" not in resp.text


def test_file_view_rejects_absolute_path(tmp_path: Path):
    repo_manager, client, storage = _build_client(tmp_path)
    _make_repo(client, "contain")
    secret = tmp_path / "secret.txt"
    secret.write_text("top secret", encoding="utf-8")

    resp = client.get("/api/file/view", params={"file": str(secret)})

    assert resp.status_code in (400, 403), resp.text
    assert "top secret" not in resp.text


def test_repos_select_rejects_dotdot(tmp_path: Path):
    repo_manager, client, storage = _build_client(tmp_path)
    _make_repo(client, "contain")

    resp = client.post("/api/repos/select", json={"repo": "../../x"})

    assert resp.status_code == 400, resp.text
    assert repo_manager.current_repo == "contain"

    saved = client.post("/api/file/save", json={"path": "ok.txt", "content": "ok"})
    assert saved.status_code == 200, saved.text
    assert (storage / "contain" / "ok.txt").exists()
    assert not (tmp_path / "x").exists()


def test_shared_save_rejects_dotdot(tmp_path: Path):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    sm = SharedManager(storage)
    created = sm.create_folder("f")
    assert created["success"], created
    folder_path = sm._get_folder_path("f")
    commits_before = _commit_count(folder_path)

    result = sm.save_file("f", "../escape.txt", b"evil")

    assert result["success"] is False, result
    assert not (sm.shared_root / "escape.txt").exists()
    assert _commit_count(folder_path) == commits_before


def test_shared_delete_rejects_dotdot(tmp_path: Path):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    sm = SharedManager(storage)
    created = sm.create_folder("f")
    assert created["success"], created
    victim = sm.shared_root / "victim.txt"
    victim.write_bytes(b"keep")

    result = sm.delete_file("f", "../victim.txt")

    assert result["success"] is False, result
    assert victim.exists()


def test_shared_save_rejects_folder_escape(tmp_path: Path):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    sm = SharedManager(storage)
    created = sm.create_folder("f")
    assert created["success"], created

    result = sm.save_file("../evil", "x.txt", b"evil")

    assert result["success"] is False, result
    assert not (storage / ".lgm" / "evil").exists()
