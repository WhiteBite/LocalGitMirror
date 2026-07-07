"""Tests for /api/file-sync opaque encrypted file postbox."""
import json
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from app.routers import file_sync as file_sync_router_mod
from tests import _harness


def _make_client(tmp_path: Path):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
        encoding="utf-8",
    )
    rm = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=rm,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    file_sync_router_mod.repo_manager = rm
    file_sync_router_mod.system_logger = None
    app.include_router(file_sync_router_mod.router)
    return TestClient(app), storage


def test_file_sync_lifecycle_is_opaque(tmp_path: Path):
    client, _ = _make_client(tmp_path)
    payload = b"ENCRYPTED-FILE-CONTAINER" * 1024

    uploaded = client.post(
        "/api/file-sync/upload",
        data={"repo": "onyx", "path": "docs/big-model.bin", "plain_size": "123456"},
        files={"attachment": ("file.lgm", payload, "application/octet-stream")},
    )
    assert uploaded.status_code == 200, uploaded.text
    body = uploaded.json()
    item_id = body["id"]
    assert body["path"] == "docs/big-model.bin"
    assert body["size"] == len(payload)

    listed = client.get("/api/file-sync/list", params={"repo": "onyx"})
    assert listed.status_code == 200
    items = listed.json()["items"]
    assert [it["id"] for it in items] == [item_id]
    assert items[0]["path"] == "docs/big-model.bin"
    assert items[0]["plain_size"] == 123456

    downloaded = client.get("/api/file-sync/download", params={"repo": "onyx", "id": item_id})
    assert downloaded.status_code == 200
    assert downloaded.content == payload

    ack = client.delete("/api/file-sync/ack", params={"repo": "onyx", "id": item_id})
    assert ack.status_code == 200
    assert ack.json()["deleted"] is True
    assert client.get("/api/file-sync/list", params={"repo": "onyx"}).json()["items"] == []


def test_file_sync_rejects_bad_repo_path_and_id(tmp_path: Path):
    client, _ = _make_client(tmp_path)
    for bad_repo in ["../etc", "foo/bar", "x\\y", "", "."]:
        resp = client.get("/api/file-sync/list", params={"repo": bad_repo})
        assert resp.status_code == 400, bad_repo

    for bad_path in ["../secret.bin", "/abs/file", "a/../../b", "", "."]:
        resp = client.post(
            "/api/file-sync/upload",
            data={"repo": "onyx", "path": bad_path, "plain_size": "1"},
            files={"attachment": ("x.bin", b"payload", "application/octet-stream")},
        )
        assert resp.status_code == 400, bad_path

    for bad_id in ["../x", "a/b", "", "x" * 100]:
        resp = client.get("/api/file-sync/download", params={"repo": "onyx", "id": bad_id})
        assert resp.status_code == 400, bad_id


def test_file_sync_rejects_empty_payload(tmp_path: Path):
    client, _ = _make_client(tmp_path)
    resp = client.post(
        "/api/file-sync/upload",
        data={"repo": "onyx", "path": "a.bin", "plain_size": "0"},
        files={"attachment": ("x.bin", b"", "application/octet-stream")},
    )
    assert resp.status_code == 400
