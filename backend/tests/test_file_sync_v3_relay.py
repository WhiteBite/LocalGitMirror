"""
Protocol v3 relay for the file postbox: the server terminates relay crypto.

Uploads sealed with the client's ephemeral key (form field "k") are decrypted
on write and stored at rest under the relay key (0x04 || nonce || AES-GCM);
reads re-seal per reader — the "X-LGM-Epk" header selects the v3 relay,
absence falls back to the legacy password bundle.
"""
import base64
import json
import os
from pathlib import Path
from typing import Optional

import pytest
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core import hybrid_crypto as hc
from app.core.bundle_crypto import decrypt_dump_bytes, encrypt_bundle_bytes
from app.core.repo_manager import RepoManager
from app.routers import file_sync as file_sync_router_mod

PASSWORD = "v3-postbox-test-password"


@pytest.fixture(autouse=True)
def _reset_relay_globals():
    yield
    file_sync_router_mod.server_private_key = None
    file_sync_router_mod.relay_key = None


def _make_client(tmp_path: Path, monkeypatch, password: Optional[str], relay_key: Optional[bytes] = os.urandom(32)):
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


def _upload(client, repo: str, blob: bytes, k: str = "", path: str = "mr-replies/mr-!42.md",
            plain_size: str = "10", meta: Optional[str] = None, path_enc: str = ""):
    data = {"rid": repo, "path": path, "plain_size": plain_size, "k": k}
    if path_enc:
        data["path_enc"] = path_enc
    if meta is not None:
        data["meta"] = meta
    return client.post(
        "/api/documents/attachment-upload",
        data=data,
        files={"attachment": ("file.lgm", blob, "application/octet-stream")},
    )


def _seal_meta(session: "_ClientSession", path: str, plain_size: int,
               aad: bytes = hc.RELAY_AAD_POSTBOX) -> str:
    payload = json.dumps({"path": path, "plain_size": plain_size}).encode("utf-8")
    return base64.b64encode(session.seal(payload, aad)).decode("ascii")


def _stored_blob(repo: str, item_id: str) -> bytes:
    return (file_sync_router_mod._repo_dir(repo) / f"{item_id}.bin").read_bytes()


def test_v3_upload_get_round_trip(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    plaintext = b"# MR !42\n\nreply body" * 64
    session = _ClientSession(_server_pub())
    sealed = session.seal(plaintext, hc.RELAY_AAD_POSTBOX)

    r = _upload(client, "onyx", sealed, k=session.epk_b64())
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]
    assert r.json()["size"] == len(sealed)

    assert _stored_blob("onyx", item_id)[0] == 0x04

    reader = _ClientSession(_server_pub())
    got = client.get(
        "/api/documents/attachment-get",
        params={"rid": "onyx", "id": item_id},
        headers={"X-LGM-Epk": reader.epk_b64()},
    )
    assert got.status_code == 200, got.text
    assert got.headers["content-type"].startswith("application/octet-stream")
    assert reader.open(got.content, hc.RELAY_AAD_POSTBOX) == plaintext


def test_legacy_upload_normalized_at_rest_and_readable_by_v3_reader(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    plaintext = b"legacy-postbox-payload"
    legacy = encrypt_bundle_bytes(plaintext, PASSWORD)
    assert legacy[0] == 0x01

    r = _upload(client, "onyx", legacy)
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]

    assert _stored_blob("onyx", item_id)[0] == 0x04

    reader = _ClientSession(_server_pub())
    got = client.get(
        "/api/documents/attachment-get",
        params={"rid": "onyx", "id": item_id},
        headers={"X-LGM-Epk": reader.epk_b64()},
    )
    assert got.status_code == 200, got.text
    assert reader.open(got.content, hc.RELAY_AAD_POSTBOX) == plaintext


def test_v3_upload_readable_by_legacy_password_reader(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    plaintext = b"postbox-file-content"
    session = _ClientSession(_server_pub())

    r = _upload(client, "onyx", session.seal(plaintext, hc.RELAY_AAD_POSTBOX), k=session.epk_b64())
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]

    got = client.get("/api/documents/attachment-get", params={"rid": "onyx", "id": item_id})
    assert got.status_code == 200, got.text
    assert decrypt_dump_bytes(got.content, PASSWORD) == plaintext


def test_v3_upload_with_wrong_aad_fails_closed(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    session = _ClientSession(_server_pub())

    sealed_for_deps = session.seal(b"payload", hc.RELAY_AAD_DEPS_REQ)
    r = _upload(client, "onyx", sealed_for_deps, k=session.epk_b64())
    assert r.status_code == 400


def test_v3_upload_tampered_blob_fails_closed(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    session = _ClientSession(_server_pub())

    sealed = bytearray(session.seal(b"payload", hc.RELAY_AAD_POSTBOX))
    sealed[-1] ^= 0xFF
    r = _upload(client, "onyx", bytes(sealed), k=session.epk_b64())
    assert r.status_code == 400


def test_upload_without_password_or_k_returns_400(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, None)
    r = _upload(client, "onyx", b"\x01" + bytes(64))
    assert r.status_code == 400


def test_epk_on_legacy_blob_without_password_returns_409(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, None)
    repo_dir = file_sync_router_mod._repo_dir("onyx")
    (repo_dir / "legacyblob.bin").write_bytes(b"\x01" + bytes(64))
    (repo_dir / "legacyblob.json").write_text(
        json.dumps({"path": "x/legacy", "plain_size": 0}), encoding="utf-8"
    )

    got = client.get(
        "/api/documents/attachment-get",
        params={"rid": "onyx", "id": "legacyblob"},
        headers={"X-LGM-Epk": _ClientSession(_server_pub()).epk_b64()},
    )
    assert got.status_code == 409


def test_legacy_write_without_relay_key_stored_unchanged(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD, relay_key=None)
    legacy = encrypt_bundle_bytes(b"payload", PASSWORD)

    r = _upload(client, "onyx", legacy)
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]

    assert _stored_blob("onyx", item_id) == legacy

    got = client.get("/api/documents/attachment-get", params={"rid": "onyx", "id": item_id})
    assert got.status_code == 200, got.text
    assert decrypt_dump_bytes(got.content, PASSWORD) == b"payload"


def test_v3_write_without_relay_key_returns_503(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD, relay_key=None)
    session = _ClientSession(_server_pub())

    r = _upload(client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX), k=session.epk_b64())
    assert r.status_code == 503
    assert "relay key" in r.json()["detail"]


def test_v3_upload_with_sealed_meta_stores_real_path_and_size(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    plaintext = b"postbox-file-content"
    session = _ClientSession(_server_pub())

    r = _upload(
        client, "onyx", session.seal(plaintext, hc.RELAY_AAD_POSTBOX),
        k=session.epk_b64(), path="x/9f3ab1", plain_size="0",
        meta=_seal_meta(session, "mr-replies/mr-!42.md", 1234),
    )
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]
    assert r.json()["path"] == "mr-replies/mr-!42.md"

    items = client.get("/api/documents/attachment-list", params={"rid": "onyx"}).json()["items"]
    assert [it["id"] for it in items] == [item_id]
    assert items[0]["path"] == "mr-replies/mr-!42.md"
    assert items[0]["plain_size"] == 1234

    reader = _ClientSession(_server_pub())
    got = client.get(
        "/api/documents/attachment-get",
        params={"rid": "onyx", "id": item_id},
        headers={"X-LGM-Epk": reader.epk_b64()},
    )
    assert got.status_code == 200, got.text
    assert reader.open(got.content, hc.RELAY_AAD_POSTBOX) == plaintext


def test_v3_upload_with_sealed_meta_and_path_enc(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    session = _ClientSession(_server_pub())

    r = _upload(
        client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
        k=session.epk_b64(), path="x/1a2b3c4d", plain_size="0", path_enc="ZW5j",
        meta=_seal_meta(session, "mr-notes/mr-!7.md", 99),
    )
    assert r.status_code == 200, r.text

    item = client.get("/api/documents/attachment-list",
                      params={"rid": "onyx"}).json()["items"][0]
    assert item["path"] == "mr-notes/mr-!7.md"
    assert item["plain_size"] == 99
    assert item["path_enc"] == "ZW5j"


def test_v3_upload_tampered_meta_fails_closed(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    session = _ClientSession(_server_pub())
    sealed = bytearray(session.seal(
        json.dumps({"path": "a/b.md", "plain_size": 1}).encode("utf-8"), hc.RELAY_AAD_POSTBOX))
    sealed[-1] ^= 0xFF

    r = _upload(client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
                k=session.epk_b64(), path="x/ab", plain_size="0",
                meta=base64.b64encode(bytes(sealed)).decode("ascii"))
    assert r.status_code == 400
    assert client.get("/api/documents/attachment-list",
                      params={"rid": "onyx"}).json()["items"] == []


def test_v3_upload_meta_with_wrong_aad_fails_closed(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    session = _ClientSession(_server_pub())

    r = _upload(client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
                k=session.epk_b64(), path="x/ab", plain_size="0",
                meta=_seal_meta(session, "a/b.md", 1, aad=hc.RELAY_AAD_DEPS_REQ))
    assert r.status_code == 400


def test_sealed_meta_without_k_fails_closed(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    session = _ClientSession(_server_pub())
    legacy = encrypt_bundle_bytes(b"payload", PASSWORD)

    r = _upload(client, "onyx", legacy, path="x/ab", plain_size="0",
                meta=_seal_meta(session, "a/b.md", 1))
    assert r.status_code == 400


def test_sealed_meta_with_garbage_fails_closed(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    session = _ClientSession(_server_pub())

    r = _upload(client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
                k=session.epk_b64(), path="x/ab", plain_size="0", meta="!!!not-base64!!!")
    assert r.status_code == 400


def test_v3_upload_without_meta_keeps_cleartext_fields(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    session = _ClientSession(_server_pub())

    r = _upload(client, "onyx", session.seal(b"payload", hc.RELAY_AAD_POSTBOX),
                k=session.epk_b64(), path="docs/file.bin", plain_size="77")
    assert r.status_code == 200, r.text

    item = client.get("/api/documents/attachment-list",
                      params={"rid": "onyx"}).json()["items"][0]
    assert item["path"] == "docs/file.bin"
    assert item["plain_size"] == 77
