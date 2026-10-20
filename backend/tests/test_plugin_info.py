"""Regression: /api/plugin/info must answer 200 with size/built_at/sha256.

Added after a refactor shipped `stat.mtime` (AttributeError -> 500) and broke
the dashboard plugin card and the IDE self-update flow.
"""

from fastapi.testclient import TestClient

# Import BEFORE monkeypatching: app.main loads .env at import time, so
# API_KEY must be set after the import (per-test) to a known value.
from app.main import app

AUTH = {"X-Session-ID": "plugin-info-test-key"}


def test_plugin_info_returns_metadata_and_sha256(tmp_path, monkeypatch):
    # Point the dist dir at a fake archive whose version is high enough that
    # _ensure_current_zip() serves it as-is instead of launching gradle.
    zip_path = tmp_path / "localgitmirror-idea-plugin-0.999.0.zip"
    zip_path.write_bytes(b"fake-zip-content")
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(tmp_path))
    monkeypatch.setenv("API_KEY", "plugin-info-test-key")
    monkeypatch.setenv("SYNC_PASSWORD", "pw")

    client = TestClient(app)  # no context manager: skips lifespan/ports
    r = client.get("/api/plugin/info", params={"enc": "1"}, headers=AUTH)

    assert r.status_code == 200, r.text
    data = _decrypt_info(r)
    assert data["available"] is True
    assert data["filename"] == zip_path.name
    assert data["size"] == len(b"fake-zip-content")
    assert "built_at" in data and data["built_at"]
    assert len(data["sha256"]) == 64


def test_plugin_latest_streams_the_archive(tmp_path, monkeypatch):
    zip_path = tmp_path / "localgitmirror-idea-plugin-0.999.0.zip"
    payload = b"fake-zip-content-2"
    zip_path.write_bytes(payload)
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(tmp_path))
    monkeypatch.setenv("API_KEY", "plugin-info-test-key")
    monkeypatch.setenv("SYNC_PASSWORD", "pw")

    client = TestClient(app)
    r = client.get("/api/plugin/latest", params={"enc": "1"}, headers=AUTH)

    assert r.status_code == 200
    from app.core.bundle_crypto import decrypt_dump_bytes
    assert decrypt_dump_bytes(r.content, "pw") == payload


def _enc_env(monkeypatch, tmp_path, payload: bytes):
    zip_path = tmp_path / "localgitmirror-idea-plugin-0.999.0.zip"
    zip_path.write_bytes(payload)
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(tmp_path))
    monkeypatch.setenv("SYNC_PASSWORD", "pw")
    monkeypatch.setenv("API_KEY", "plugin-info-test-key")


def _decrypt_info(response):
    from app.core.envelope_crypto import decrypt_envelope
    return decrypt_envelope(response.json()["e"], "pw")


def test_plugin_info_enc_hides_metadata(tmp_path, monkeypatch):
    _enc_env(monkeypatch, tmp_path, b"fake-zip-content")
    r = TestClient(app).get("/api/plugin/info", params={"enc": "1"}, headers=AUTH)
    assert r.status_code == 200, r.text
    data = r.json()
    assert set(data) == {"e"}  # no filename/version leaks in the JSON

    meta = _decrypt_info(r)
    assert meta["filename"] == "localgitmirror-idea-plugin-0.999.0.zip"
    assert meta["version"] == "0.999.0"
    assert len(meta["sha256"]) == 64


def test_plugin_latest_enc_ships_ciphertext(tmp_path, monkeypatch):
    payload = b"PK\x03\x04" + b"\x00" * 64
    _enc_env(monkeypatch, tmp_path, payload)
    r = TestClient(app).get("/api/plugin/latest", params={"enc": "1"}, headers=AUTH)
    assert r.status_code == 200
    assert r.content[:2] != b"PK"  # bundle v2 noise, not a zip
    assert r.content[0] == 0x01

    from app.core.bundle_crypto import decrypt_dump_bytes
    assert decrypt_dump_bytes(r.content, "pw") == payload


def test_plugin_enc_requires_password(tmp_path, monkeypatch):
    zip_path = tmp_path / "localgitmirror-idea-plugin-0.999.0.zip"
    zip_path.write_bytes(b"x")
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(tmp_path))
    monkeypatch.delenv("SYNC_PASSWORD", raising=False)
    monkeypatch.setenv("API_KEY", "plugin-info-test-key")
    r = TestClient(app).get("/api/plugin/latest", params={"enc": "1"}, headers=AUTH)
    assert r.status_code == 503


def test_plugin_info_enc0_refused_typed(tmp_path, monkeypatch):
    zip_path = tmp_path / "localgitmirror-idea-plugin-0.999.0.zip"
    zip_path.write_bytes(b"fake-zip-content")
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(tmp_path))
    monkeypatch.setenv("API_KEY", "plugin-info-test-key")

    r = TestClient(app).get("/api/plugin/info", params={"enc": "0"}, headers=AUTH)

    assert r.status_code == 400, r.text
    detail = r.json()["detail"]
    assert detail["code"] == "enc_required"
    assert "sync password" in detail["message"].lower()
    assert "localgitmirror" not in r.text  # metadata must not leak either


def test_plugin_info_without_enc_flag_refused_typed(tmp_path, monkeypatch):
    zip_path = tmp_path / "localgitmirror-idea-plugin-0.999.0.zip"
    zip_path.write_bytes(b"fake-zip-content")
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(tmp_path))
    monkeypatch.setenv("API_KEY", "plugin-info-test-key")

    r = TestClient(app).get("/api/plugin/info", headers=AUTH)

    assert r.status_code == 400, r.text
    assert r.json()["detail"]["code"] == "enc_required"


def test_plugin_latest_enc0_refused_enc1_still_ships_archive(tmp_path, monkeypatch):
    payload = b"PK\x03\x04" + b"\x00" * 64
    _enc_env(monkeypatch, tmp_path, payload)
    client = TestClient(app)

    plain = client.get("/api/plugin/latest", params={"enc": "0"}, headers=AUTH)

    assert plain.status_code == 400, plain.text
    assert plain.json()["detail"]["code"] == "enc_required"
    assert plain.content[:2] != b"PK"  # never the raw archive bytes

    sealed = client.get("/api/plugin/latest", params={"enc": "1"}, headers=AUTH)
    assert sealed.status_code == 200
    from app.core.bundle_crypto import decrypt_dump_bytes
    assert decrypt_dump_bytes(sealed.content, "pw") == payload


def _client_from(addr: tuple) -> TestClient:
    """TestClient whose peer address is forced to ``addr``."""
    async def scoped(scope, receive, send):
        if scope["type"] in ("http", "websocket"):
            scope = dict(scope, client=addr)
        await app(scope, receive, send)
    return TestClient(scoped)


def test_plaintext_allowed_loopback_hosts_only():
    # "testclient" is the FastAPI TestClient default peer, not loopback — the enc-refusal tests above stay valid.
    from app.routers import plugin
    assert plugin._plaintext_allowed("127.0.0.1")
    assert plugin._plaintext_allowed("::1")
    assert not plugin._plaintext_allowed("testclient")
    assert not plugin._plaintext_allowed("192.168.1.50")
    assert not plugin._plaintext_allowed("8.8.8.8")


def test_plugin_info_plaintext_refused_for_remote_addr(tmp_path, monkeypatch):
    _enc_env(monkeypatch, tmp_path, b"fake-zip-content")
    client = _client_from(("192.168.1.50", 50000))

    r = client.get("/api/plugin/info", headers=AUTH)

    assert r.status_code == 400, r.text
    assert r.json()["detail"]["code"] == "enc_required"
    assert "localgitmirror" not in r.text


def test_plugin_info_plaintext_for_loopback_client(tmp_path, monkeypatch):
    _enc_env(monkeypatch, tmp_path, b"fake-zip-content")
    client = _client_from(("127.0.0.1", 50000))

    plain = client.get("/api/plugin/info", headers=AUTH)

    assert plain.status_code == 200, plain.text
    data = plain.json()
    assert "e" not in data
    assert data["version"] == "0.999.0"
    assert data["filename"] == "localgitmirror-idea-plugin-0.999.0.zip"

    sealed = client.get("/api/plugin/info", params={"enc": "1"}, headers=AUTH)
    assert sealed.status_code == 200
    assert set(sealed.json()) == {"e"}


def test_plugin_latest_plaintext_for_loopback_client(tmp_path, monkeypatch):
    payload = b"PK\x03\x04" + b"\x00" * 64
    _enc_env(monkeypatch, tmp_path, payload)
    client = _client_from(("127.0.0.1", 50000))

    plain = client.get("/api/plugin/latest", headers=AUTH)

    assert plain.status_code == 200, plain.text
    assert plain.content == payload

    sealed = client.get("/api/plugin/latest", params={"enc": "1"}, headers=AUTH)
    assert sealed.status_code == 200
    assert sealed.content[:2] != b"PK"
