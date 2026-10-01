"""Tests for /api/documents/attachment-* — v3-only relay postbox contract."""
import base64
import json
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from fastapi.testclient import TestClient

from app.core import hybrid_crypto as hc
from app.core.repo_manager import RepoManager
from app.routers import file_sync as file_sync_router_mod
from tests import _harness


@pytest.fixture(autouse=True)
def _reset_relay_globals():
    yield
    file_sync_router_mod.server_private_key = None
    file_sync_router_mod.relay_key = None


class _ClientSession:
    """One ephemeral X25519 keypair bound to one API call."""

    def __init__(self, server_pub: bytes):
        self._eph = X25519PrivateKey.generate()
        self.epk = self._eph.public_key().public_bytes_raw()
        self._shared = self._eph.exchange(X25519PublicKey.from_public_bytes(server_pub))

    def epk_b64(self) -> str:
        return base64.urlsafe_b64encode(self.epk).decode().rstrip("=")

    def seal(self, plaintext: bytes, aad: bytes) -> bytes:
        return hc.relay_seal(self._shared, self.epk, plaintext, aad)

    def open(self, blob: bytes, aad: bytes) -> bytes:
        return hc.relay_open(self._shared, self.epk, blob, aad, resp=True)


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
    file_sync_router_mod.server_private_key = X25519PrivateKey.generate()
    file_sync_router_mod.relay_key = b"\x07" * 32
    app.include_router(file_sync_router_mod.router)
    return TestClient(app), storage


def _server_pub() -> bytes:
    return hc.public_bytes(file_sync_router_mod.server_private_key)


def _seal_meta(session: _ClientSession, path: str, plain_size: int) -> str:
    payload = json.dumps({"path": path, "plain_size": plain_size}).encode("utf-8")
    return base64.b64encode(session.seal(payload, hc.RELAY_AAD_POSTBOX)).decode("ascii")


def _upload(client, repo: str, blob: bytes, k: str, meta: str):
    return client.post(
        "/api/documents/attachment-upload",
        data={"rid": repo, "k": k, "meta": meta},
        files={"attachment": ("file.lgm", blob, "application/octet-stream")},
    )


def test_file_sync_v3_lifecycle(tmp_path: Path):
    client, _ = _make_client(tmp_path)
    plaintext = b"ENCRYPTED-FILE-CONTAINER" * 1024
    session = _ClientSession(_server_pub())
    sealed = session.seal(plaintext, hc.RELAY_AAD_POSTBOX)

    uploaded = _upload(
        client, "onyx", sealed, session.epk_b64(),
        _seal_meta(session, "docs/big-model.bin", 123456),
    )
    assert uploaded.status_code == 200, uploaded.text
    body = uploaded.json()
    item_id = body["id"]
    assert body["path"] == "docs/big-model.bin"
    assert body["size"] == len(sealed)

    listed = client.get("/api/documents/attachment-list", params={"rid": "onyx"})
    assert listed.status_code == 200
    items = listed.json()["items"]
    assert [it["id"] for it in items] == [item_id]
    assert items[0]["path"] == "docs/big-model.bin"
    assert items[0]["plain_size"] == 123456
    assert "path_enc" not in items[0]

    reader = _ClientSession(_server_pub())
    downloaded = client.get(
        "/api/documents/attachment-get",
        params={"rid": "onyx", "id": item_id},
        headers={"X-LGM-Epk": reader.epk_b64()},
    )
    assert downloaded.status_code == 200
    assert reader.open(downloaded.content, hc.RELAY_AAD_POSTBOX) == plaintext

    ack = client.delete("/api/documents/attachment-ack", params={"rid": "onyx", "id": item_id})
    assert ack.status_code == 200
    assert ack.json()["deleted"] is True
    assert client.get("/api/documents/attachment-list", params={"rid": "onyx"}).json()["items"] == []


def test_file_sync_upload_requires_k_and_meta(tmp_path: Path):
    client, _ = _make_client(tmp_path)
    session = _ClientSession(_server_pub())
    sealed = session.seal(b"payload", hc.RELAY_AAD_POSTBOX)
    meta = _seal_meta(session, "a/b.md", 1)

    missing_k = _upload(client, "onyx", sealed, "", meta)
    assert missing_k.status_code == 400

    missing_meta = _upload(client, "onyx", sealed, session.epk_b64(), "")
    assert missing_meta.status_code == 400
    assert client.get("/api/documents/attachment-list", params={"rid": "onyx"}).json()["items"] == []


def test_file_sync_rejects_bad_repo_path_and_id(tmp_path: Path):
    client, _ = _make_client(tmp_path)
    for bad_repo in ["../etc", "foo/bar", "x\\y", "", "."]:
        resp = client.get("/api/documents/attachment-list", params={"rid": bad_repo})
        assert resp.status_code == 400, bad_repo

    session = _ClientSession(_server_pub())
    for bad_path in ["../secret.bin", "/abs/file", "a/../../b", "", "."]:
        resp = _upload(
            client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
            session.epk_b64(), _seal_meta(session, bad_path, 1),
        )
        assert resp.status_code == 400, bad_path

    for bad_id in ["../x", "a/b", "", "x" * 100]:
        resp = client.get("/api/documents/attachment-get", params={"rid": "onyx", "id": bad_id})
        assert resp.status_code == 400, bad_id


def test_file_sync_rejects_empty_payload(tmp_path: Path):
    client, _ = _make_client(tmp_path)
    session = _ClientSession(_server_pub())
    resp = _upload(
        client, "onyx", b"", session.epk_b64(), _seal_meta(session, "a.bin", 0),
    )
    assert resp.status_code == 400


def test_file_sync_list_via_x_doc_ref_header(tmp_path: Path):
    client, _ = _make_client(tmp_path)
    session = _ClientSession(_server_pub())
    _upload(
        client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
        session.epk_b64(), _seal_meta(session, "docs/file.bin", 123),
    )

    resp = client.get("/api/documents/attachment-list", headers={"X-Doc-Ref": "onyx"})
    assert resp.status_code == 200, resp.text
    assert len(resp.json()["items"]) == 1

    resp = client.get("/api/documents/attachment-list")
    assert resp.status_code == 400
