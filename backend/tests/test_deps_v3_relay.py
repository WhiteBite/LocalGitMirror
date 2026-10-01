"""
Protocol v3 relay for the deps flow: the server terminates relay crypto.

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
from app.routers import deps as deps_router_mod

PASSWORD = "v3-relay-test-password"


@pytest.fixture(autouse=True)
def _reset_relay_globals():
    yield
    deps_router_mod.server_private_key = None
    deps_router_mod.relay_key = None


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
    deps_router_mod.repo_manager = rm
    deps_router_mod.system_logger = None
    deps_router_mod.server_private_key = X25519PrivateKey.generate()
    deps_router_mod.relay_key = relay_key
    app = FastAPI()
    app.include_router(deps_router_mod.router)
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
    return hc.public_bytes(deps_router_mod.server_private_key)


def _submit(client, repo: str, blob: bytes, k: str = ""):
    return client.post(
        "/api/documents/submit",
        data={"rid": repo, "k": k},
        files={"attachment": ("m.bin", blob, "application/octet-stream")},
    )


def _fulfill(client, repo: str, request_id: str, blob: bytes, k: str = ""):
    return client.post(
        "/api/documents/fulfill",
        data={"rid": repo, "request_id": request_id, "k": k},
        files={"attachment": ("a.bin", blob, "application/octet-stream")},
    )


def _stored_request(repo: str, item_id: str) -> bytes:
    return (deps_router_mod._requests_dir(repo) / f"{item_id}.bin").read_bytes()


def _stored_response(repo: str, item_id: str) -> bytes:
    return (deps_router_mod._responses_dir(repo) / f"{item_id}.bin").read_bytes()


def test_v3_submit_queue_item_round_trip(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    manifest = b'{"version":3,"missing":[]}'
    session = _ClientSession(_server_pub())
    sealed = session.seal(manifest, hc.RELAY_AAD_DEPS_REQ)

    r = _submit(client, "onyx", sealed, k=session.epk_b64())
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]
    assert r.json()["size"] == len(sealed)

    assert _stored_request("onyx", item_id)[0] == 0x04

    reader = _ClientSession(_server_pub())
    got = client.get(
        "/api/documents/queue-item",
        params={"rid": "onyx", "id": item_id},
        headers={"x-lgm-epk": reader.epk_b64()},
    )
    assert got.status_code == 200, got.text
    assert got.headers["content-type"].startswith("application/octet-stream")
    assert reader.open(got.content, hc.RELAY_AAD_DEPS_REQ) == manifest


def test_v3_fulfill_ready_item_round_trip(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    zip_bytes = b"PK\x03\x04" + bytes(range(256))

    writer = _ClientSession(_server_pub())
    r = _submit(client, "onyx", writer.seal(b"manifest", hc.RELAY_AAD_DEPS_REQ), k=writer.epk_b64())
    assert r.status_code == 200, r.text
    request_id = r.json()["id"]

    session = _ClientSession(_server_pub())
    rf = _fulfill(client, "onyx", request_id, session.seal(zip_bytes, hc.RELAY_AAD_DEPS_RESP), k=session.epk_b64())
    assert rf.status_code == 200, rf.text
    response_id = rf.json()["id"]

    assert _stored_response("onyx", response_id)[0] == 0x04
    assert not (deps_router_mod._requests_dir("onyx") / f"{request_id}.bin").exists()

    reader = _ClientSession(_server_pub())
    got = client.get(
        "/api/documents/ready-item",
        params={"rid": "onyx", "id": response_id},
        headers={"X-LGM-Epk": reader.epk_b64()},
    )
    assert got.status_code == 200, got.text
    assert reader.open(got.content, hc.RELAY_AAD_DEPS_RESP) == zip_bytes


def test_legacy_submit_normalized_at_rest_and_readable_by_v3_reader(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    manifest = b"legacy-manifest-payload"
    legacy = encrypt_bundle_bytes(manifest, PASSWORD)
    assert legacy[0] == 0x01

    r = _submit(client, "onyx", legacy)
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]

    assert _stored_request("onyx", item_id)[0] == 0x04

    reader = _ClientSession(_server_pub())
    got = client.get(
        "/api/documents/queue-item",
        params={"rid": "onyx", "id": item_id},
        headers={"X-LGM-Epk": reader.epk_b64()},
    )
    assert got.status_code == 200, got.text
    assert reader.open(got.content, hc.RELAY_AAD_DEPS_REQ) == manifest


def test_v3_fulfill_readable_by_legacy_password_reader(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    zip_bytes = b"response-zip-content"
    session = _ClientSession(_server_pub())

    rf = _fulfill(client, "onyx", "deadbeef", session.seal(zip_bytes, hc.RELAY_AAD_DEPS_RESP), k=session.epk_b64())
    assert rf.status_code == 200, rf.text
    response_id = rf.json()["id"]

    got = client.get("/api/documents/ready-item", params={"rid": "onyx", "id": response_id})
    assert got.status_code == 200, got.text
    assert decrypt_dump_bytes(got.content, PASSWORD) == zip_bytes


def test_v3_submit_with_wrong_aad_fails_closed(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    session = _ClientSession(_server_pub())

    sealed_for_resp = session.seal(b"payload", hc.RELAY_AAD_DEPS_RESP)
    r = _submit(client, "onyx", sealed_for_resp, k=session.epk_b64())
    assert r.status_code == 400


def test_v3_submit_tampered_blob_fails_closed(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD)
    session = _ClientSession(_server_pub())

    sealed = bytearray(session.seal(b"payload", hc.RELAY_AAD_DEPS_REQ))
    sealed[-1] ^= 0xFF
    r = _submit(client, "onyx", bytes(sealed), k=session.epk_b64())
    assert r.status_code == 400


def test_submit_without_password_or_k_returns_400(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, None)
    r = _submit(client, "onyx", b"\x01" + bytes(64))
    assert r.status_code == 400


def test_epk_on_legacy_blob_without_password_returns_409(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, None)
    req_dir = deps_router_mod._requests_dir("onyx")
    (req_dir / "legacyblob.bin").write_bytes(b"\x01" + bytes(64))

    got = client.get(
        "/api/documents/queue-item",
        params={"rid": "onyx", "id": "legacyblob"},
        headers={"X-LGM-Epk": _ClientSession(_server_pub()).epk_b64()},
    )
    assert got.status_code == 409


def test_legacy_write_without_relay_key_stored_unchanged(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD, relay_key=None)
    legacy = encrypt_bundle_bytes(b"manifest", PASSWORD)

    r = _submit(client, "onyx", legacy)
    assert r.status_code == 200, r.text
    item_id = r.json()["id"]

    assert _stored_request("onyx", item_id) == legacy

    got = client.get("/api/documents/queue-item", params={"rid": "onyx", "id": item_id})
    assert got.status_code == 200, got.text
    assert decrypt_dump_bytes(got.content, PASSWORD) == b"manifest"


def test_v3_write_without_relay_key_returns_503(tmp_path, monkeypatch):
    client = _make_client(tmp_path, monkeypatch, PASSWORD, relay_key=None)
    session = _ClientSession(_server_pub())

    r = _submit(client, "onyx", session.seal(b"manifest", hc.RELAY_AAD_DEPS_REQ), k=session.epk_b64())
    assert r.status_code == 503
    assert "relay key" in r.json()["detail"]
