"""Tests for /api/buffer — disk-backed cross-machine clipboard with pins and
client-encrypted hints (hint_enc). The server terminates crypto, so every
put goes through the legacy password bundle path (v3 relay is covered by
test_buffer_v3_relay.py)."""
import base64
import json
import time
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.bundle_crypto import decrypt_dump_bytes, encrypt_bundle_bytes
from app.routers import buffer

PASSWORD = "buf-legacy-test-pw"


@pytest.fixture
def buf(tmp_path: Path, monkeypatch):
    """Isolated buffer module state rooted in a tmp dir."""
    monkeypatch.setattr(buffer, "storage_dir", tmp_path / "buffer")
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)
    buffer._items = {}
    buffer._loaded = False
    buffer.server_private_key = None
    buffer.relay_key = None
    yield buffer
    buffer._items = {}
    buffer._loaded = False
    buffer.server_private_key = None
    buffer.relay_key = None


@pytest.fixture
def client(buf):
    app = FastAPI()
    app.include_router(buf.router)
    return TestClient(app)


def _put(client, content: bytes, **kw) -> dict:
    body = {
        "ciphertext_b64": base64.b64encode(encrypt_bundle_bytes(content, PASSWORD)).decode(),
        **kw,
    }
    resp = client.post("/api/buffer", json=body)
    assert resp.status_code == 200, resp.text
    return resp.json()


def _get_plain(client, item_id: str) -> bytes:
    resp = client.get(f"/api/buffer/{item_id}")
    assert resp.status_code == 200, resp.text
    return decrypt_dump_bytes(resp.content, PASSWORD)


def test_put_list_get_delete_roundtrip(client):
    entry = _put(client, b"opaque-ciphertext", hint_enc="ZW5jLWhpbnQ=")
    items = client.get("/api/buffer").json()["items"]
    assert [i["id"] for i in items] == [entry["id"]]
    assert items[0]["hint_enc"] == "ZW5jLWhpbnQ="
    assert items[0]["hint"] == ""
    assert items[0]["pinned"] is False

    assert _get_plain(client, entry["id"]) == b"opaque-ciphertext"

    assert client.delete(f"/api/buffer/{entry['id']}").status_code == 204
    assert client.get("/api/buffer").json()["items"] == []
    assert client.get(f"/api/buffer/{entry['id']}").status_code == 404


def test_legacy_plaintext_hint_still_accepted(client):
    entry = _put(client, b"ct", hint="first line")
    items = client.get("/api/buffer").json()["items"]
    assert items[0]["hint"] == "first line"
    assert items[0]["hint_enc"] == ""


def test_hint_enc_suppresses_plaintext_hint(client, buf):
    # A paranoid client sends both; the plaintext one must not be stored.
    _put(client, b"ct", hint="secret first line", hint_enc="ZW5j")
    item = client.get("/api/buffer").json()["items"][0]
    assert item["hint"] == ""
    assert item["hint_enc"] == "ZW5j"
    meta = json.loads((buf.storage_dir / f"{item['id']}.json").read_text())
    assert "secret" not in json.dumps(meta)


def test_entries_survive_reload(client, buf):
    entry = _put(client, b"persisted", hint_enc="aW5j")
    # Simulate a server restart: drop the in-memory index.
    buf._items = {}
    buf._loaded = False
    items = client.get("/api/buffer").json()["items"]
    assert [i["id"] for i in items] == [entry["id"]]
    assert _get_plain(client, entry["id"]) == b"persisted"


def test_pinned_survives_ttl_and_eviction(client, buf, monkeypatch):
    pinned = _put(client, b"keep", pinned=True)
    old = _put(client, b"old")
    # Age the plain entry beyond the TTL.
    stale = time.time() - 25 * 3600
    buf._items[old["id"]]["ts"] = stale
    meta = json.loads((buf.storage_dir / f"{old['id']}.json").read_text())
    meta["ts"] = stale
    (buf.storage_dir / f"{old['id']}.json").write_text(json.dumps(meta))

    items = client.get("/api/buffer").json()["items"]
    assert [i["id"] for i in items] == [pinned["id"]]

    # Eviction at capacity spares pinned entries.
    monkeypatch.setattr(buf, "MAX_ITEMS", 2)
    _put(client, b"n1")
    _put(client, b"n2")
    ids = [i["id"] for i in client.get("/api/buffer").json()["items"]]
    assert pinned["id"] in ids and len(ids) == 2


def test_pin_toggle(client, buf):
    entry = _put(client, b"ct")
    resp = client.post(f"/api/buffer/{entry['id']}/pin", json={"pinned": True})
    assert resp.json()["pinned"] is True
    item = client.get("/api/buffer").json()["items"][0]
    assert item["pinned"] is True
    # Pin state persists across reloads.
    buf._items = {}
    buf._loaded = False
    assert client.get("/api/buffer").json()["items"][0]["pinned"] is True


def test_clear_removes_everything_including_pinned(client):
    _put(client, b"a", pinned=True)
    _put(client, b"b")
    assert client.delete("/api/buffer").status_code == 204
    assert client.get("/api/buffer").json()["items"] == []


def test_oversize_rejected(client, buf, monkeypatch):
    monkeypatch.setattr(buf, "MAX_SIZE", 4)
    resp = client.post("/api/buffer", json={
        "ciphertext_b64": base64.b64encode(b"12345").decode()})
    assert resp.status_code == 413
