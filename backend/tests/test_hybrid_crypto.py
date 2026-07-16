"""Tests for hybrid_crypto (protocol v3, ECIES) and its wiring into sync.py.

Covers:
  * key persistence (load/create, raw 32 bytes)
  * fingerprint determinism
  * full ECIES round-trip for envelope + bundle, both directions
  * direction/purpose key separation (req key != resp key)
  * tamper + wrong-key rejection
  * epk base64 decoding (standard and url-safe, padded or not)
  * end-to-end router round-trip on /api/documents/check using a simulated
    client (mirrors exactly what the Kotlin plugin will do)

The simulated client in `FakeClient` is the cross-language reference: the
Kotlin HybridCrypto must produce/consume bytes identically.
"""

import base64
import json

import pytest
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from fastapi.testclient import TestClient

from app.core import hybrid_crypto as hc
from app.core.repo_manager import RepoManager
from app.routers import sync as sync_mod
from tests import _harness


# ───────────────────────── simulated work-PC client ─────────────────────────


class FakeClient:
    """Mirrors the plugin's per-call crypto: one ephemeral, derive both ways."""

    def __init__(self, server_pub: bytes):
        self._eph = X25519PrivateKey.generate()
        self.epk = self._eph.public_key().public_bytes_raw()
        self._shared = self._eph.exchange(X25519PublicKey.from_public_bytes(server_pub))

    @property
    def epk_b64(self) -> str:
        return base64.urlsafe_b64encode(self.epk).decode("ascii")

    def seal_env(self, payload: dict) -> str:
        key = hc._derive(self._shared, self.epk, hc.INFO_ENV_REQ)
        pt = json.dumps(payload, separators=(",", ":")).encode()
        return base64.b64encode(hc._seal(key, pt)).decode("ascii")

    def open_env(self, b64: str) -> dict:
        key = hc._derive(self._shared, self.epk, hc.INFO_ENV_RESP)
        return json.loads(hc._open(key, base64.b64decode(b64)).decode())

    def seal_bundle(self, data: bytes) -> bytes:
        key = hc._derive(self._shared, self.epk, hc.INFO_BUNDLE_REQ)
        return hc._seal(key, data)

    def open_bundle(self, blob: bytes) -> bytes:
        key = hc._derive(self._shared, self.epk, hc.INFO_BUNDLE_RESP)
        return hc._open(key, blob)


# ─────────────────────────── key management ─────────────────────────────────


def test_load_or_create_persists_raw_key(tmp_path):
    path = tmp_path / ".lgm" / "server_x25519.key"
    priv = hc.load_or_create_server_key(path)
    assert path.exists()
    assert len(path.read_bytes()) == 32
    # Reload yields the same public key.
    priv2 = hc.load_or_create_server_key(path)
    assert hc.public_bytes(priv) == hc.public_bytes(priv2)


def test_corrupt_key_rejected(tmp_path):
    path = tmp_path / "k.key"
    path.write_bytes(b"too-short")
    with pytest.raises(ValueError, match="Corrupt server key"):
        hc.load_or_create_server_key(path)


def test_fingerprint_is_deterministic_and_formatted():
    priv = X25519PrivateKey.generate()
    pub = hc.public_bytes(priv)
    fp = hc.fingerprint(pub)
    assert fp == hc.fingerprint(pub)
    # Four groups of four hex chars: "abcd ef01 2345 6789"
    groups = fp.split(" ")
    assert len(groups) == 4 and all(len(g) == 4 for g in groups)


# ───────────────────────────── ECIES core ───────────────────────────────────


def _server_ctx(server_priv, client: FakeClient) -> hc.HybridServerContext:
    return hc.HybridServerContext(server_priv, client.epk)


def test_envelope_round_trip_both_directions():
    server = X25519PrivateKey.generate()
    client = FakeClient(hc.public_bytes(server))
    ctx = _server_ctx(server, client)

    # client -> server
    req = {"repo": "secret-proj", "commits": ["a1b2", "c3d4"]}
    opened = ctx.open_envelope(client.seal_env(req))
    assert opened == req

    # server -> client
    resp = {"success": True, "head": "deadbeef", "refs": {"main": {"sha": "x"}}}
    assert client.open_env(ctx.seal_envelope(resp)) == resp


def test_bundle_round_trip_both_directions():
    server = X25519PrivateKey.generate()
    client = FakeClient(hc.public_bytes(server))
    ctx = _server_ctx(server, client)

    payload = b"PACK\x00\x01" + bytes(range(256)) * 8  # binary-ish bundle bytes
    # upload: client -> server
    assert ctx.open_bundle(client.seal_bundle(payload)) == payload
    # export: server -> client
    assert client.open_bundle(ctx.seal_bundle(payload)) == payload


def test_request_and_response_keys_differ():
    """A captured request key must not decrypt the response (direction separation)."""
    server = X25519PrivateKey.generate()
    client = FakeClient(hc.public_bytes(server))
    shared = client._shared
    epk = client.epk
    k_req = hc._derive(shared, epk, hc.INFO_ENV_REQ)
    k_resp = hc._derive(shared, epk, hc.INFO_ENV_RESP)
    k_breq = hc._derive(shared, epk, hc.INFO_BUNDLE_REQ)
    assert len({k_req, k_resp, k_breq}) == 3


def test_wrong_server_key_fails():
    server = X25519PrivateKey.generate()
    other = X25519PrivateKey.generate()
    client = FakeClient(hc.public_bytes(server))
    blob = client.seal_env({"repo": "x"})
    bad_ctx = hc.HybridServerContext(other, client.epk)
    with pytest.raises(Exception):
        bad_ctx.open_envelope(blob)


def test_tamper_detected():
    server = X25519PrivateKey.generate()
    client = FakeClient(hc.public_bytes(server))
    ctx = _server_ctx(server, client)
    b64 = client.seal_env({"repo": "x"})
    raw = bytearray(base64.b64decode(b64))
    raw[-1] ^= 0xFF
    with pytest.raises(Exception):
        ctx.open_envelope(base64.b64encode(bytes(raw)).decode())


@pytest.mark.parametrize("transform", [
    lambda b: base64.b64encode(b).decode(),                         # standard, padded
    lambda b: base64.urlsafe_b64encode(b).decode(),                 # url-safe, padded
    lambda b: base64.urlsafe_b64encode(b).decode().rstrip("="),     # url-safe, unpadded
])
def test_decode_epk_accepts_variants(transform):
    priv = X25519PrivateKey.generate()
    pub = hc.public_bytes(priv)
    assert hc.decode_epk(transform(pub)) == pub


def test_decode_epk_rejects_bad_length():
    with pytest.raises(ValueError):
        hc.decode_epk(base64.b64encode(b"short").decode())


# ─────────────────────── end-to-end through the router ──────────────────────


def _build_client(tmp_path, *, with_server_key: bool):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    rm = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=rm,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    if with_server_key:
        sync_mod.server_private_key = hc.load_or_create_server_key(storage / ".lgm" / "srv.key")
    else:
        sync_mod.server_private_key = None
    return TestClient(app), rm


def test_router_check_v3_round_trip(tmp_path, monkeypatch):
    """A full v3 request/response on /documents/check with NO server password set."""
    monkeypatch.delenv("SYNC_PASSWORD", raising=False)
    client, _ = _build_client(tmp_path, with_server_key=True)

    server_pub = hc.public_bytes(sync_mod.server_private_key)
    fake = FakeClient(server_pub)

    body = {"epk": fake.epk_b64, "e": fake.seal_env({"repo": "ghost-repo", "commits": ["abc"]})}
    resp = client.post("/api/documents/check", json=body)
    assert resp.status_code == 200, resp.text

    inner = fake.open_env(resp.json()["e"])
    assert inner["success"] is True
    assert inner["repo"] == "ghost-repo"
    assert inner["known"] == []


def test_router_legacy_still_works_without_server_key(tmp_path, monkeypatch):
    """Legacy password clients keep working (no epk, server key absent)."""
    monkeypatch.setenv("SYNC_PASSWORD", "legacy-pw")
    client, _ = _build_client(tmp_path, with_server_key=False)

    from app.core.envelope_crypto import decrypt_envelope, encrypt_envelope

    e = encrypt_envelope({"repo": "ghost", "commits": []}, "legacy-pw")
    resp = client.post("/api/documents/check", json={"e": e})
    assert resp.status_code == 200, resp.text
    inner = decrypt_envelope(resp.json()["e"], "legacy-pw")
    assert inner["success"] is True


def test_router_legacy_no_password_returns_503(tmp_path, monkeypatch):
    monkeypatch.delenv("SYNC_PASSWORD", raising=False)
    client, _ = _build_client(tmp_path, with_server_key=False)
    # Legacy client (no epk) against a server with neither password nor key.
    resp = client.post("/api/documents/check", json={"e": "not-decryptable"})
    assert resp.status_code == 503


def test_router_upload_v3_seals_bundle_with_separate_kb(tmp_path, monkeypatch):
    """A v3 upload: envelope sealed with `k`, attachment sealed with `kb`.

    The attachment ephemeral (`kb`) is independent of the envelope ephemeral
    (`k`) — the plugin seals the bundle to disk at creation time with its own
    key. We assert the server binds the bundle context from `kb` and recovers
    the exact plaintext, while never needing a password.
    """
    from fastapi import FastAPI

    monkeypatch.delenv("SYNC_PASSWORD", raising=False)

    class _FakeRepoManager:
        def get_repos(self):
            return ["proj"]

        def _get_workspace_path(self, _r):
            return tmp_path

        def _get_bare_path(self, _r):
            return tmp_path

    app = FastAPI()
    app.include_router(sync_mod.router)
    monkeypatch.setattr(sync_mod, "repo_manager", _FakeRepoManager(), raising=False)
    sync_mod.server_private_key = hc.load_or_create_server_key(tmp_path / "srv.key")
    server_pub = hc.public_bytes(sync_mod.server_private_key)

    captured = {}

    def _fake_apply(**kwargs):
        dump_path = kwargs["dump_path"]
        ctx = sync_mod._hybrid_bundle_ctx.get()
        assert ctx is not None, "bundle context must be bound from kb"
        captured["plaintext"] = ctx.open_bundle(dump_path.read_bytes())
        return {"success": True, "repo": kwargs["repo_name"], "attachment": kwargs["dump_filename"]}

    monkeypatch.setattr(sync_mod, "_apply_dump_to_repo_and_sync_bare", _fake_apply)

    client = TestClient(app)

    env = FakeClient(server_pub)          # envelope ephemeral -> "k"
    bundle = FakeClient(server_pub)       # bundle ephemeral   -> "kb"
    original = b"BUNDLEDATA" + bytes(range(200))
    sealed_attachment = bundle.seal_bundle(original)

    resp = client.post(
        "/api/documents/upload",
        data={"e": env.seal_env({"repo": "proj"}), "k": env.epk_b64, "kb": bundle.epk_b64},
        files={"attachment": ("document.bin", sealed_attachment, "application/octet-stream")},
    )
    assert resp.status_code == 200, resp.text
    assert captured["plaintext"] == original
    # Response envelope is sealed back with the envelope session.
    assert env.open_env(resp.json()["e"])["success"] is True
