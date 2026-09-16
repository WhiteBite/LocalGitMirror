"""Regression: /api/plugin/info must answer 200 with size/built_at/sha256.

Added after a refactor shipped `stat.mtime` (AttributeError -> 500) and broke
the dashboard plugin card and the IDE self-update flow.
"""

from fastapi.testclient import TestClient

# Import BEFORE monkeypatching: app.main loads .env at import time, so
# API_KEY must be removed after the import (per-test) to keep auth open.
from app.main import app


def test_plugin_info_returns_metadata_and_sha256(tmp_path, monkeypatch):
    # Point the dist dir at a fake archive whose version is high enough that
    # _ensure_current_zip() serves it as-is instead of launching gradle.
    zip_path = tmp_path / "localgitmirror-idea-plugin-0.999.0.zip"
    zip_path.write_bytes(b"fake-zip-content")
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(tmp_path))
    monkeypatch.delenv("API_KEY", raising=False)

    client = TestClient(app)  # no context manager: skips lifespan/ports
    r = client.get("/api/plugin/info")

    assert r.status_code == 200, r.text
    data = r.json()
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
    monkeypatch.delenv("API_KEY", raising=False)

    client = TestClient(app)
    r = client.get("/api/plugin/latest")

    assert r.status_code == 200
    assert r.content == payload


def _enc_env(monkeypatch, tmp_path, payload: bytes):
    zip_path = tmp_path / "localgitmirror-idea-plugin-0.999.0.zip"
    zip_path.write_bytes(payload)
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(tmp_path))
    monkeypatch.setenv("SYNC_PASSWORD", "pw")
    monkeypatch.delenv("API_KEY", raising=False)


def test_plugin_info_enc_hides_metadata(tmp_path, monkeypatch):
    _enc_env(monkeypatch, tmp_path, b"fake-zip-content")
    r = TestClient(app).get("/api/plugin/info", params={"enc": "1"})
    assert r.status_code == 200, r.text
    data = r.json()
    assert set(data) == {"e"}  # no filename/version leaks in the JSON

    from app.core.envelope_crypto import decrypt_envelope
    meta = decrypt_envelope(data["e"], "pw")
    assert meta["filename"] == "localgitmirror-idea-plugin-0.999.0.zip"
    assert meta["version"] == "0.999.0"
    assert len(meta["sha256"]) == 64


def test_plugin_latest_enc_ships_ciphertext(tmp_path, monkeypatch):
    payload = b"PK\x03\x04" + b"\x00" * 64
    _enc_env(monkeypatch, tmp_path, payload)
    r = TestClient(app).get("/api/plugin/latest", params={"enc": "1"})
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
    monkeypatch.delenv("API_KEY", raising=False)
    r = TestClient(app).get("/api/plugin/latest", params={"enc": "1"})
    assert r.status_code == 503
