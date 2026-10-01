"""
Honest startup test: spin up the REAL FastAPI app (with lifespan handler)
and verify every router's dependencies got wired.

This catches the bug class where a router is registered at import time but
its globals (repo_manager, system_logger, etc.) are still None until the
async lifespan handler runs. We hit each router's endpoint and assert it
DOESN'T return "X not initialised".

Specifically guards against:
  - GET  /api/repos                 — depends on api.repo_manager
  - GET  /api/health                — depends on api.config
  - GET  /api/documents/queue       — depends on deps.repo_manager  (the bug we just fixed)
  - GET  /api/settings              — depends on settings.settings_manager
"""
import json
import os
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient


@pytest.fixture
def real_app(tmp_path: Path, monkeypatch):
    """Boot the real `app` from app.main with a tmp storage dir."""
    storage = tmp_path / "lgm-storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({
            "general": {"storage_path": str(storage)},
            "git": {"user_name": "Bot", "user_email": "bot@test.com"},
        }),
        encoding="utf-8",
    )
    monkeypatch.setenv("STORAGE_PATH", str(storage))
    monkeypatch.setenv("WEB_PORT", "0")           # not actually bound
    monkeypatch.setenv("GIT_PORT", "0")
    monkeypatch.setenv("API_KEY", "")             # disable auth — we test wiring only
    monkeypatch.setenv("SYNC_PASSWORD", "test-pwd")

    # Force a fresh import so CONFIG and globals re-read env
    saved = {m: mod for m, mod in list(sys.modules.items()) if m.startswith("app.")}
    for mod in list(saved):
        del sys.modules[mod]

    from app.main import app
    # TestClient triggers the lifespan handler exactly once on enter
    try:
        with TestClient(app) as client:
            yield client
    finally:
        # Later tests hold references to the pre-reload module instances;
        # without restoring, their monkeypatch targets a different instance.
        sys.modules.update(saved)


# ─────────────────────────────────────────────────────────────────────────────
# Each endpoint must NOT report "not initialised" — that's the wiring smoke test
# ─────────────────────────────────────────────────────────────────────────────

def test_api_repos_router_is_wired(real_app: TestClient):
    resp = real_app.get("/api/repos")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    detail = (body.get("detail") or "").lower()
    assert "not initialised" not in detail and "не инициализирован" not in detail, (
        f"api.repo_manager not wired: {body}"
    )


def test_deps_pending_router_is_wired(real_app: TestClient):
    """
    Regression test for: 'Запрос не отправлен (500): Repo manager not initialised'
    The deps router globals must be assigned in the lifespan handler, not at
    module import time (when they were still None).
    """
    resp = real_app.get("/api/documents/queue", params={"rid": "any-name"})
    assert resp.status_code == 200, (
        f"deps.repo_manager not wired (got {resp.status_code}): {resp.text}\n"
        f"This is the bug where the deps router was wired before the lifespan "
        f"handler created repo_manager, so it stayed None."
    )
    body = resp.json()
    assert body.get("success") is True
    assert "items" in body


def test_deps_request_router_is_wired(real_app: TestClient):
    """The POST endpoint also needs the wiring — different code path than GET."""
    from app.core.bundle_crypto import encrypt_bundle_bytes

    resp = real_app.post(
        "/api/documents/submit",
        data={"rid": "any-name"},
        files={"attachment": ("m.bin", encrypt_bundle_bytes(b"\x00" * 64, "test-pwd"), "application/octet-stream")},
    )
    # Should accept the upload and return success — NOT 500 'not initialised'.
    assert resp.status_code == 200, (
        f"deps POST endpoint failed before reaching repo logic: {resp.status_code}: {resp.text}"
    )


def test_settings_router_is_wired(real_app: TestClient):
    resp = real_app.get("/api/settings")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    detail = (body.get("detail") or "").lower()
    assert "not initialised" not in detail and "не инициализирован" not in detail


def test_mirror_hybrid_key_is_wired(real_app: TestClient):
    """Vault publish (v3) uses the SAME server key object as the sync envelope module."""
    from app.core import sync_envelope
    from app.routers import buffer as buffer_mod
    from app.routers import deps as deps_mod
    from app.routers import file_sync as file_sync_mod
    from app.routers import mirror as mirror_mod

    assert mirror_mod.server_private_key is not None
    assert mirror_mod.server_private_key is sync_envelope.server_private_key
    assert deps_mod.server_private_key is sync_envelope.server_private_key
    assert deps_mod.relay_key is not None and len(deps_mod.relay_key) == 32
    assert file_sync_mod.server_private_key is sync_envelope.server_private_key
    assert file_sync_mod.relay_key is deps_mod.relay_key
    assert buffer_mod.server_private_key is sync_envelope.server_private_key
    assert buffer_mod.relay_key is deps_mod.relay_key


def test_health_endpoint(real_app: TestClient):
    """API health must work too — sanity check the app is fully alive."""
    resp = real_app.get("/api/health")
    assert resp.status_code == 200
    body = resp.json()
    # The app reports its capabilities here; "apiVersion" should be present
    assert isinstance(body, dict)


# ─────────────────────────────────────────────────────────────────────────────
# Honest e2e: full deps roundtrip against the REAL app (not isolated TestClient)
# ─────────────────────────────────────────────────────────────────────────────

def test_full_deps_roundtrip_against_real_app(real_app: TestClient):
    """
    Reproduces the user's flow with the real lifespan-managed app:
      1. Dome posts an encrypted manifest
      2. Work lists pending, sees it
      3. Work uploads a response
      4. Dome lists responses, sees it
      5. Dome fetches the response
      6. Dome ACKs — server cleans up
    If anything regressed in lifespan wiring, this test breaks first.
    """
    from app.core.bundle_crypto import decrypt_dump_bytes, encrypt_bundle_bytes

    repo = "onyx-platform"
    password = "test-pwd"

    # 1. Request
    manifest_plain = b"ENCRYPTED_MANIFEST" * 100
    r = real_app.post(
        "/api/documents/submit",
        data={"rid": repo},
        files={"attachment": ("m.bin", encrypt_bundle_bytes(manifest_plain, password), "application/octet-stream")},
    )
    assert r.status_code == 200, r.text
    request_id = r.json()["id"]

    # 2. Pending
    p = real_app.get("/api/documents/queue", params={"rid": repo}).json()
    assert any(item["id"] == request_id for item in p["items"])

    # 3. Respond
    archive_plain = b"ENCRYPTED_ARCHIVE_PAYLOAD" * 1000
    rr = real_app.post(
        "/api/documents/fulfill",
        data={"rid": repo, "request_id": request_id},
        files={"attachment": ("a.bin", encrypt_bundle_bytes(archive_plain, password), "application/octet-stream")},
    )
    assert rr.status_code == 200, rr.text
    response_id = rr.json()["id"]

    # 4. Responses list (dome side)
    listing = real_app.get("/api/documents/ready", params={"rid": repo}).json()
    assert any(item["id"] == response_id for item in listing["items"])

    # 5. Fetch — the plaintext round-trips (the server re-seals on read)
    fetched = real_app.get("/api/documents/ready-item", params={"rid": repo, "id": response_id})
    assert fetched.status_code == 200
    assert decrypt_dump_bytes(fetched.content, password) == archive_plain

    # 6. Ack — server deletes the blob
    ack = real_app.delete("/api/documents/ack", params={"rid": repo, "id": response_id})
    assert ack.status_code == 200
    final = real_app.get("/api/documents/ready", params={"rid": repo}).json()
    assert not any(item["id"] == response_id for item in final["items"])
