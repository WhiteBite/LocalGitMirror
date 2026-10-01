"""
Protocol v3 relay for the clipboard buffer: the server terminates relay crypto.

Uploads sealed with the client's ephemeral key (JSON field "k") are decrypted
on write and stored at rest under the relay key (0x04 || nonce || AES-GCM);
reads re-seal per reader — the "X-LGM-Epk" header selects the v3 relay,
absence falls back to the legacy password bundle.
"""
import base64
import json
import os
import time
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
from app.routers import buffer as buffer_router_mod

PASSWORD = "v3-buffer-test-password"


@pytest.fixture(autouse=True)
def _reset_relay_globals():
    yield
    buffer_router_mod.server_private_key = None
    buffer_router_mod.relay_key = None


def _make_client(tmp_path: Path, monkeypatch, password: Optional[str], relay_key: Optional[bytes] = os.urandom(32)):
    if password is None:
        monkeypatch.delenv("SYNC_PASSWORD", raising=False)
    else:
        monkeypatch.setenv("SYNC_PASSWORD", password)
    monkeypatch.setattr(buffer_router_mod, "storage_dir", tmp_path / "buffer")
    buffer_router_mod._items = {}
    buffer_router_mod._loaded = False
    buffer_router_mod.server_private_key = X25519PrivateKey.generate()
    buffer_router_mod.relay_key = relay_key
    app = FastAPI()
    app.include_router(buffer_router_mod.router)
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
    return hc.public_bytes(buffer_router_mod.server_private_key)


def _put(client, blob: bytes, k: str = "", **kw):
    body = {"ciphertext_b64": base64.b64encode(blob).decode(), "k": k, **kw}
    return client.post("/api/buffer", json=body)


def _stored_blob(item_id: str) -> bytes:
    return (buffer_router_mod.storage_dir / f"{item_id}.bin").read_bytes()


def test_v3_put_get_round_trip(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    plaintext = "буферный текст" * 128
    session = _ClientSession(_server_pub())
    sealed = session.seal(plaintext.encode("utf-8"), hc.RELAY_AAD_BUFFER)

    r = _put(client, sealed, k=session.epk_b64(), hint_enc="aGk=")
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]
    assert r.json()["ts"] > 0

    assert _stored_blob(item_id)[0] == 0x04

    reader = _ClientSession(_server_pub())
    got = client.get(f"/api/buffer/{item_id}", headers={"X-LGM-Epk": reader.epk_b64()})
    assert got.status_code == 200, got.text
    assert got.headers["content-type"].startswith("application/octet-stream")
    assert reader.open(got.content, hc.RELAY_AAD_BUFFER) == plaintext.encode("utf-8")


def test_legacy_put_normalized_at_rest_and_readable_by_v3_reader(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    plaintext = b"legacy-buffer-payload"
    legacy = encrypt_bundle_bytes(plaintext, PASSWORD)
    assert legacy[0] == 0x01

    r = _put(client, legacy)
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]

    assert _stored_blob(item_id)[0] == 0x04

    reader = _ClientSession(_server_pub())
    got = client.get(f"/api/buffer/{item_id}", headers={"X-LGM-Epk": reader.epk_b64()})
    assert got.status_code == 200, got.text
    assert reader.open(got.content, hc.RELAY_AAD_BUFFER) == plaintext


def test_v3_put_readable_by_legacy_password_reader(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    plaintext = b"buffer-clipboard-content"
    session = _ClientSession(_server_pub())

    r = _put(client, session.seal(plaintext, hc.RELAY_AAD_BUFFER), k=session.epk_b64())
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]

    got = client.get(f"/api/buffer/{item_id}")
    assert got.status_code == 200, got.text
    assert decrypt_dump_bytes(got.content, PASSWORD) == plaintext


def test_v3_put_with_wrong_aad_fails_closed(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    session = _ClientSession(_server_pub())

    sealed_for_postbox = session.seal(b"payload", hc.RELAY_AAD_POSTBOX)
    r = _put(client, sealed_for_postbox, k=session.epk_b64())
    assert r.status_code == 400


def test_v3_put_tampered_blob_fails_closed(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    session = _ClientSession(_server_pub())

    sealed = bytearray(session.seal(b"payload", hc.RELAY_AAD_BUFFER))
    sealed[-1] ^= 0xFF
    r = _put(client, bytes(sealed), k=session.epk_b64())
    assert r.status_code == 400


def test_put_without_password_or_k_returns_400(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, None)
    r = _put(client, b"\x01" + bytes(64))
    assert r.status_code == 400


def test_epk_on_legacy_blob_without_password_returns_409(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, None)
    (buffer_router_mod.storage_dir).mkdir(parents=True, exist_ok=True)
    (buffer_router_mod.storage_dir / "legacyblob.bin").write_bytes(b"\x01" + bytes(64))
    (buffer_router_mod.storage_dir / "legacyblob.json").write_text(
        json.dumps({
            "id": "legacyblob", "ts": time.time(), "hint": "", "hint_enc": "",
            "pinned": False, "size": 65,
        }),
        encoding="utf-8",
    )

    got = client.get(
        f"/api/buffer/legacyblob",
        headers={"X-LGM-Epk": _ClientSession(_server_pub()).epk_b64()},
    )
    assert got.status_code == 409


def test_legacy_write_without_relay_key_stored_unchanged(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD, relay_key=None)
    legacy = encrypt_bundle_bytes(b"payload", PASSWORD)

    r = _put(client, legacy)
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]

    assert _stored_blob(item_id) == legacy

    got = client.get(f"/api/buffer/{item_id}")
    assert got.status_code == 200, got.text
    assert decrypt_dump_bytes(got.content, PASSWORD) == b"payload"


def test_v3_write_without_relay_key_returns_503(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD, relay_key=None)
    session = _ClientSession(_server_pub())

    r = _put(client, session.seal(b"payload", hc.RELAY_AAD_BUFFER), k=session.epk_b64())
    assert r.status_code == 503
    assert "relay key" in r.json()["detail"]


def test_v3_entry_metadata_endpoints_unchanged(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    plaintext = b"metadata-payload"
    session = _ClientSession(_server_pub())

    r = _put(client, session.seal(plaintext, hc.RELAY_AAD_BUFFER), k=session.epk_b64(), hint_enc="aGludA==")
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]

    items = client.get("/api/buffer").json()["items"]
    assert [i["id"] for i in items] == [item_id]
    assert items[0]["hint_enc"] == "aGludA=="
    assert items[0]["hint"] == ""
    assert items[0]["pinned"] is False
    assert items[0]["size"] > 0

    pin = client.post(f"/api/buffer/{item_id}/pin", json={"pinned": True})
    assert pin.status_code == 200
    assert pin.json()["pinned"] is True
    assert client.get("/api/buffer").json()["items"][0]["pinned"] is True

    assert client.delete(f"/api/buffer/{item_id}").status_code == 204
    assert client.get("/api/buffer").json()["items"] == []
