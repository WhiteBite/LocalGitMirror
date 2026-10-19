"""set_storage_path must not silently re-root the running server (S7).

The endpoint persists the new storage path for the next restart and says
so explicitly; every router keeps serving from the old path until then.
"""

import json
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from app.core.shared_manager import SharedManager
from app.routers import files as files_mod
from app.routers import repos as repos_mod
from app.routers import shared as shared_mod
from app.routers import sync as sync_mod
from tests import _harness


def test_set_storage_path_requires_restart_and_keeps_old_path(tmp_path: Path, monkeypatch):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@example.com"}}),
        encoding="utf-8",
    )
    repo_manager = RepoManager(storage)
    shared_manager = SharedManager(storage)
    app = _harness.build_app(
        repo_manager=repo_manager,
        shared_manager=shared_manager,
        git_handler=None,
        git_workspace=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    client = TestClient(app)
    env_file = tmp_path / ".env"
    monkeypatch.setattr(repos_mod, "_ENV_PATH", env_file, raising=False)

    created = client.post("/api/documents/collection", json={"name": "stable"})
    assert created.status_code == 200, created.text
    selected = client.post("/api/repos/select", json={"repo": "stable"})
    assert selected.status_code == 200, selected.text

    new_path = tmp_path / "new_storage"
    resp = client.post("/api/config/storage", json={"path": str(new_path)})

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body.get("success") is True, body
    assert body.get("restart_required") is True, body
    assert body.get("active_storage_path") == str(storage), body

    assert repos_mod.repo_manager is repo_manager
    assert files_mod.repo_manager is repo_manager
    assert sync_mod.repo_manager is repo_manager
    assert shared_mod.shared_manager is shared_manager

    saved = client.post("/api/file/save", json={"path": "probe.txt", "content": "old"})
    assert saved.status_code == 200, saved.text
    assert (storage / "stable" / "probe.txt").exists()
    assert not (new_path / "stable" / "probe.txt").exists()

    assert env_file.exists()
    assert f"STORAGE_PATH={new_path}" in env_file.read_text(encoding="utf-8")
