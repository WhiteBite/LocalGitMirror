"""Tests for /api/documents/attachment-* encrypted file postbox (legacy password mode)."""
import json
from pathlib import Path
from typing import Optional

from fastapi.testclient import TestClient

from app.core.bundle_crypto import decrypt_dump_bytes, encrypt_bundle_bytes
from app.core.repo_manager import RepoManager
from app.routers import file_sync as file_sync_router_mod
from tests import _harness

PASSWORD = "file-sync-test-password"


def _make_client(tmp_path: Path, monkeypatch, password: Optional[str] = PASSWORD):
    if password is None:
        monkeypatch.delenv("SYNC_PASSWORD", raising=False)
    else:
        monkeypatch.setenv("SYNC_PASSWORD", password)
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
    file_sync_router_mod.server_private_key = None
    file_sync_router_mod.relay_key = None
    app.include_router(file_sync_router_mod.router)
    return TestClient(app), storage


def test_file_sync_lifecycle_legacy_password(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    payload = encrypt_bundle_bytes(b"ENCRYPTED-FILE-CONTAINER" * 1024, PASSWORD)

    uploaded = client.post(
        "/api/documents/attachment-upload",
        data={"rid": "onyx", "path": "docs/big-model.bin", "plain_size": "123456"},
        files={"attachment": ("file.lgm", payload, "application/octet-stream")},
    )
    assert uploaded.status_code == 200, uploaded.text
    body = uploaded.json()
    item_id = body["id"]
    assert body["path"] == "docs/big-model.bin"
    assert body["size"] == len(payload)

    listed = client.get("/api/documents/attachment-list", params={"rid": "onyx"})
    assert listed.status_code == 200
    items = listed.json()["items"]
    assert [it["id"] for it in items] == [item_id]
    assert items[0]["path"] == "docs/big-model.bin"
    assert items[0]["plain_size"] == 123456

    downloaded = client.get("/api/documents/attachment-get", params={"rid": "onyx", "id": item_id})
    assert downloaded.status_code == 200
    assert decrypt_dump_bytes(downloaded.content, PASSWORD) == b"ENCRYPTED-FILE-CONTAINER" * 1024

    ack = client.delete("/api/documents/attachment-ack", params={"rid": "onyx", "id": item_id})
    assert ack.status_code == 200
    assert ack.json()["deleted"] is True
    assert client.get("/api/documents/attachment-list", params={"rid": "onyx"}).json()["items"] == []


def test_file_sync_path_enc_passthrough(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    uploaded = client.post(
        "/api/documents/attachment-upload",
        data={"rid": "onyx", "path": "x/9f3ab1", "plain_size": "0",
              "path_enc": "ZW5jY2lwaGVyZWQtcGF0aA=="},
        files={"attachment": ("file.lgm", encrypt_bundle_bytes(b"CT", PASSWORD), "application/octet-stream")},
    )
    assert uploaded.status_code == 200, uploaded.text
    item = client.get("/api/documents/attachment-list",
                      params={"rid": "onyx"}).json()["items"][0]
    assert item["path"] == "x/9f3ab1"
    assert item["path_enc"] == "ZW5jY2lwaGVyZWQtcGF0aA=="


def test_file_sync_rejects_bad_repo_path_and_id(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch, password=None)
    for bad_repo in ["../etc", "foo/bar", "x\\y", "", "."]:
        resp = client.get("/api/documents/attachment-list", params={"rid": bad_repo})
        assert resp.status_code == 400, bad_repo

    for bad_path in ["../secret.bin", "/abs/file", "a/../../b", "", "."]:
        resp = client.post(
            "/api/documents/attachment-upload",
            data={"rid": "onyx", "path": bad_path, "plain_size": "1"},
            files={"attachment": ("x.bin", b"payload", "application/octet-stream")},
        )
        assert resp.status_code == 400, bad_path

    for bad_id in ["../x", "a/b", "", "x" * 100]:
        resp = client.get("/api/documents/attachment-get", params={"rid": "onyx", "id": bad_id})
        assert resp.status_code == 400, bad_id


def test_file_sync_rejects_empty_payload(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch, password=None)
    resp = client.post(
        "/api/documents/attachment-upload",
        data={"rid": "onyx", "path": "a.bin", "plain_size": "0"},
        files={"attachment": ("x.bin", b"", "application/octet-stream")},
    )
    assert resp.status_code == 400


def test_file_sync_sealed_meta_without_v3_key_fails_closed(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    resp = client.post(
        "/api/documents/attachment-upload",
        data={"rid": "onyx", "path": "x/ab", "plain_size": "0", "k": "AAAA", "meta": "AAAA"},
        files={"attachment": ("x.bin", encrypt_bundle_bytes(b"CT", PASSWORD), "application/octet-stream")},
    )
    assert resp.status_code == 400
    assert client.get("/api/documents/attachment-list", params={"rid": "onyx"}).json()["items"] == []


# ─────────────────────────────────────────────────────────────────────────────
# X-Doc-Ref header: alternative to ?rid= query param
# ─────────────────────────────────────────────────────────────────────────────

def test_file_sync_list_via_x_doc_ref_header(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    payload = encrypt_bundle_bytes(b"ENCRYPTED-FILE-CONTAINER" * 1024, PASSWORD)
    client.post(
        "/api/documents/attachment-upload",
        data={"rid": "onyx", "path": "docs/file.bin", "plain_size": "123"},
        files={"attachment": ("file.lgm", payload, "application/octet-stream")},
    )

    resp = client.get("/api/documents/attachment-list", headers={"X-Doc-Ref": "onyx"})
    assert resp.status_code == 200, resp.text
    assert len(resp.json()["items"]) == 1

    resp = client.get("/api/documents/attachment-list")
    assert resp.status_code == 400
