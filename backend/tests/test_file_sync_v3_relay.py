"""Protocol v3 relay for the file postbox: crypto failure modes, fail-closed."""
import base64
import json
import os
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core import hybrid_crypto as hc
from app.core.repo_manager import RepoManager
from app.routers import file_sync as file_sync_router_mod


@pytest.fixture(autouse=True)
def _reset_relay_globals():
    yield
    file_sync_router_mod.server_private_key = None
    file_sync_router_mod.relay_key = None


def _make_client(tmp_path: Path, relay_key: bytes | None = os.urandom(32)):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
        encoding="utf-8",
    )
    rm = RepoManager(storage)
    file_sync_router_mod.repo_manager = rm
    file_sync_router_mod.system_logger = None
    file_sync_router_mod.server_private_key = X25519PrivateKey.generate()
    file_sync_router_mod.relay_key = relay_key
    app = FastAPI()
    app.include_router(file_sync_router_mod.router)
    return TestClient(app)


class _ClientSession:
    """Mimics HybridCrypto.Session: one ephemeral bound to one API call."""

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


def _server_pub() -> bytes:
    return hc.public_bytes(file_sync_router_mod.server_private_key)


def _upload(client, repo: str, blob: bytes, k: str, meta: str):
    return client.post(
        "/api/documents/attachment-upload",
        data={"rid": repo, "k": k, "meta": meta},
        files={"attachment": ("file.lgm", blob, "application/octet-stream")},
    )


def _seal_meta(session: _ClientSession, path: str, plain_size: int,
               aad: bytes = hc.RELAY_AAD_POSTBOX) -> str:
    payload = json.dumps({"path": path, "plain_size": plain_size}).encode("utf-8")
    return base64.b64encode(session.seal(payload, aad)).decode("ascii")


def _stored_blob(repo: str, item_id: str) -> bytes:
    return (file_sync_router_mod._repo_dir(repo) / f"{item_id}.bin").read_bytes()


def _get(client, item_id: str, epk: str = ""):
    headers = {"X-LGM-Epk": epk} if epk else {}
    return client.get(
        "/api/documents/attachment-get",
        params={"rid": "onyx", "id": item_id},
        headers=headers,
    )


def test_v3_upload_get_round_trip(tmp_path):
    client = _make_client(tmp_path)
    plaintext = b"# MR !42\n\nreply body" * 64
    session = _ClientSession(_server_pub())
    sealed = session.seal(plaintext, hc.RELAY_AAD_POSTBOX)

    r = _upload(client, "onyx", sealed, session.epk_b64(),
                _seal_meta(session, "mr-replies/mr-!42.md", len(plaintext)))
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]
    assert r.json()["size"] == len(sealed)

    assert _stored_blob("onyx", item_id)[0] == 0x04

    reader = _ClientSession(_server_pub())
    got = _get(client, item_id, reader.epk_b64())
    assert got.status_code == 200, got.text
    assert got.headers["content-type"].startswith("application/octet-stream")
    assert reader.open(got.content, hc.RELAY_AAD_POSTBOX) == plaintext


def test_v3_upload_with_wrong_aad_fails_closed(tmp_path):
    client = _make_client(tmp_path)
    session = _ClientSession(_server_pub())

    sealed_for_deps = session.seal(b"payload", hc.RELAY_AAD_DEPS_REQ)
    r = _upload(client, "onyx", sealed_for_deps, session.epk_b64(),
                _seal_meta(session, "a/b.md", 1))
    assert r.status_code == 400


def test_v3_upload_tampered_blob_fails_closed(tmp_path):
    client = _make_client(tmp_path)
    session = _ClientSession(_server_pub())

    sealed = bytearray(session.seal(b"payload", hc.RELAY_AAD_POSTBOX))
    sealed[-1] ^= 0xFF
    r = _upload(client, "onyx", bytes(sealed), session.epk_b64(),
                _seal_meta(session, "a/b.md", 1))
    assert r.status_code == 400


def test_v3_upload_tampered_meta_fails_closed(tmp_path):
    client = _make_client(tmp_path)
    session = _ClientSession(_server_pub())
    sealed = bytearray(session.seal(
        json.dumps({"path": "a/b.md", "plain_size": 1}).encode("utf-8"), hc.RELAY_AAD_POSTBOX))
    sealed[-1] ^= 0xFF

    r = _upload(client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
                session.epk_b64(), base64.b64encode(bytes(sealed)).decode("ascii"))
    assert r.status_code == 400
    assert client.get("/api/documents/attachment-list",
                      params={"rid": "onyx"}).json()["items"] == []


def test_v3_upload_meta_with_wrong_aad_fails_closed(tmp_path):
    client = _make_client(tmp_path)
    session = _ClientSession(_server_pub())

    r = _upload(client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
                session.epk_b64(), _seal_meta(session, "a/b.md", 1, aad=hc.RELAY_AAD_DEPS_REQ))
    assert r.status_code == 400


def test_sealed_meta_with_garbage_fails_closed(tmp_path):
    client = _make_client(tmp_path)
    session = _ClientSession(_server_pub())

    r = _upload(client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
                session.epk_b64(), "!!!not-base64!!!")
    assert r.status_code == 400


def test_v3_write_without_relay_key_returns_503(tmp_path):
    client = _make_client(tmp_path, relay_key=None)
    session = _ClientSession(_server_pub())

    r = _upload(client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
                session.epk_b64(), _seal_meta(session, "a/b.md", 1))
    assert r.status_code == 503
    assert "relay key" in r.json()["detail"]


def test_get_without_epk_returns_400(tmp_path):
    client = _make_client(tmp_path)
    session = _ClientSession(_server_pub())

    r = _upload(client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
                session.epk_b64(), _seal_meta(session, "a/b.md", 1))
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]

    got = _get(client, item_id)
    assert got.status_code == 400


def test_get_without_server_key_returns_503(tmp_path):
    client = _make_client(tmp_path)
    session = _ClientSession(_server_pub())

    r = _upload(client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
                session.epk_b64(), _seal_meta(session, "a/b.md", 1))
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]

    reader_epk = _ClientSession(_server_pub()).epk_b64()
    file_sync_router_mod.server_private_key = None
    got = _get(client, item_id, reader_epk)
    assert got.status_code == 503


def test_get_on_legacy_blob_fails_closed_400(tmp_path):
    client = _make_client(tmp_path)
    repo_dir = file_sync_router_mod._repo_dir("onyx")
    (repo_dir / "legacyblob.bin").write_bytes(b"\x01" + bytes(64))
    (repo_dir / "legacyblob.json").write_text(
        json.dumps({"path": "x/legacy", "plain_size": 0}), encoding="utf-8"
    )

    got = _get(client, "legacyblob", _ClientSession(_server_pub()).epk_b64())
    assert got.status_code == 400


def test_list_items_have_no_path_enc(tmp_path):
    client = _make_client(tmp_path)
    session = _ClientSession(_server_pub())

    r = _upload(client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
                session.epk_b64(), _seal_meta(session, "mr-notes/mr-!7.md", 99))
    assert r.status_code == 200, r.text

    item = client.get("/api/documents/attachment-list",
                      params={"rid": "onyx"}).json()["items"][0]
    assert item["path"] == "mr-notes/mr-!7.md"
    assert item["plain_size"] == 99
    assert "path_enc" not in item
