"""Regression coverage for serving the current IDEA plugin ZIP."""

import os
import threading
import time
from types import SimpleNamespace

from fastapi.testclient import TestClient

from app.main import app
from app.routers import plugin

AUTH = {"X-Session-ID": "plugin-info-test-key"}
BUILD_THREAD_NAME = "lgm-plugin-build"


def test_newest_zip_prefers_semantic_version_over_file_mtime(tmp_path, monkeypatch):
    old_version = tmp_path / "localgitmirror-idea-plugin-0.88.0.zip"
    new_version = tmp_path / "localgitmirror-idea-plugin-0.92.0.zip"
    ignored = tmp_path / "notes-999.0.0.zip"
    old_version.write_bytes(b"old")
    new_version.write_bytes(b"new")
    ignored.write_bytes(b"ignored")

    # Simulate an old artifact copied later than the actual current build.
    os.utime(old_version, (2_000_000_000, 2_000_000_000))
    os.utime(new_version, (1_000_000_000, 1_000_000_000))
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(tmp_path))

    assert plugin._newest_zip() == new_version
    assert plugin._parse_version(new_version.name) == "0.92.0"


def _arm_background_build(monkeypatch, tmp_path, stale: bool):
    """Empty (or stale) dist dir plus a fake gradle build that blocks until
    released, so a request that waits for the build is detectable as a hang.

    Records which thread resolved the gradle command and how many builds ran.
    """
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(tmp_path))
    monkeypatch.setenv("API_KEY", "plugin-info-test-key")
    monkeypatch.setenv("SYNC_PASSWORD", "pw")
    monkeypatch.setattr(plugin, "_expected_version", lambda: (0, 999, 0))
    if stale:
        (tmp_path / "localgitmirror-idea-plugin-0.998.0.zip").write_bytes(b"stale-zip")

    calls = {"gradle_threads": [], "runs": 0}
    entered = threading.Event()
    release = threading.Event()

    def fake_gradle_command():
        calls["gradle_threads"].append(threading.current_thread().name)
        return ["fake-gradle"]

    def fake_run(cmd, **kwargs):
        calls["runs"] += 1
        entered.set()
        release.wait(timeout=30)
        (tmp_path / "localgitmirror-idea-plugin-0.999.0.zip").write_bytes(b"fresh-zip")
        return SimpleNamespace(returncode=0, stdout="", stderr="")

    monkeypatch.setattr(plugin, "_gradle_command", fake_gradle_command)
    monkeypatch.setattr(plugin.subprocess, "run", fake_run)
    return calls, entered, release


def _finish_fake_build(release):
    release.set()
    thread = getattr(plugin, "_BUILD_THREAD", None)
    if thread is not None:
        thread.join(timeout=10)


def _decrypt_info(response):
    from app.core.envelope_crypto import decrypt_envelope
    return decrypt_envelope(response.json()["e"], "pw")


def test_get_without_prebuilt_zip_returns_building_without_blocking(tmp_path, monkeypatch):
    calls, entered, release = _arm_background_build(monkeypatch, tmp_path, stale=False)
    try:
        client = TestClient(app)
        started = time.monotonic()
        r = client.get("/api/plugin/info", params={"enc": "1"}, headers=AUTH)
        elapsed = time.monotonic() - started

        assert elapsed < 5, f"/api/plugin/info blocked {elapsed:.1f}s — gradle ran inside the request"
        assert r.status_code == 200, r.text
        meta = _decrypt_info(r)
        assert meta["available"] is False
        assert meta["building"] is True

        assert entered.wait(timeout=5), "background build never started"
        assert set(calls["gradle_threads"]) == {BUILD_THREAD_NAME}

        _finish_fake_build(release)
        r2 = client.get("/api/plugin/info", params={"enc": "1"}, headers=AUTH)
        meta2 = _decrypt_info(r2)
        assert meta2["available"] is True
        assert meta2["version"] == "0.999.0"
        assert meta2["building"] is False
    finally:
        _finish_fake_build(release)


def test_second_get_while_build_in_flight_starts_no_second_build(tmp_path, monkeypatch):
    calls, entered, release = _arm_background_build(monkeypatch, tmp_path, stale=False)
    try:
        client = TestClient(app)
        r1 = client.get("/api/plugin/info", params={"enc": "1"}, headers=AUTH)
        assert r1.status_code == 200, r1.text
        assert _decrypt_info(r1)["building"] is True
        assert entered.wait(timeout=5), "background build never started"

        r2 = client.get("/api/plugin/info", params={"enc": "1"}, headers=AUTH)
        meta2 = _decrypt_info(r2)
        assert meta2["building"] is True
        assert calls["runs"] == 1
        assert calls["gradle_threads"] == [BUILD_THREAD_NAME]
    finally:
        _finish_fake_build(release)


def test_stale_zip_served_while_background_build_in_flight(tmp_path, monkeypatch):
    calls, entered, release = _arm_background_build(monkeypatch, tmp_path, stale=True)
    try:
        client = TestClient(app)
        r = client.get("/api/plugin/info", params={"enc": "1"}, headers=AUTH)
        assert r.status_code == 200, r.text
        meta = _decrypt_info(r)
        assert meta["available"] is True
        assert meta["version"] == "0.998.0"
        assert meta["building"] is True

        blob = client.get("/api/plugin/latest", params={"enc": "1"}, headers=AUTH)
        assert blob.status_code == 200
        from app.core.bundle_crypto import decrypt_dump_bytes
        assert decrypt_dump_bytes(blob.content, "pw") == b"stale-zip"
    finally:
        _finish_fake_build(release)
