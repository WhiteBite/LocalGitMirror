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
