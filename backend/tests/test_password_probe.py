"""E2E test for password-probe handshake.

Covers the full handshake flow that was broken by the stealth hardening
(SYNC-PROBE vs LGM-PROBE content mismatch, v1 vs v2 format).

Checks:
  1. /api/health returns passwordProbe=true
  2. /api/auth/verify returns v1 (LGMSTRL1 magic) with LGM-PROBE content
  3. Decryption with correct password succeeds
  4. Decryption with wrong password raises
  5. No revealing X-Sync-Probe header is present
"""
import sys
import struct
import tempfile
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.core.stealth_crypto import decrypt_dump_to_bundle, MAGIC
from app.routers import api as api_router
import pytest


def _make_client(monkeypatch, password: str = "test-probe-pw") -> TestClient:
    monkeypatch.setenv("SYNC_PASSWORD", password)
    app = FastAPI()
    app.include_router(api_router.router)
    return TestClient(app)


def test_capabilities_reports_password_probe(monkeypatch):
    client = _make_client(monkeypatch)
    res = client.get("/api/health")
    assert res.status_code == 200
    body = res.json()
    assert body.get("sync", {}).get("features", {}).get("passwordProbe") is True


def test_probe_returns_v1_format_with_magic(monkeypatch):
    """Probe must use v1 format (LGMSTRL1 magic) for backward compat with old plugins."""
    client = _make_client(monkeypatch)
    res = client.get("/api/auth/verify")
    assert res.status_code == 200

    payload = res.content

    # Must start with MAGIC (8 bytes)
    assert payload[:8] == MAGIC, f"Expected LGMSTRL1 magic, got {payload[:8]}"

    # Verify v1 structure: MAGIC(8) + SALT(16) + NONCE(12) + LEN(8) + CIPHERTEXT
    assert len(payload) > 8 + 16 + 12 + 8, "Payload too short for v1 format"


def test_probe_no_revealing_header(monkeypatch):
    """No revealing X-Sync-Probe header should be present."""
    client = _make_client(monkeypatch)
    res = client.get("/api/auth/verify")
    assert res.status_code == 200
    assert res.headers.get("X-Sync-Probe") is None, "Revealing X-Sync-Probe header must not be present"


def test_probe_decrypt_correct_password(monkeypatch):
    """Full roundtrip: probe → decrypt → content must be 'LGM-PROBE'."""
    password = "correct-password"
    client = _make_client(monkeypatch, password=password)
    res = client.get("/api/auth/verify")
    assert res.status_code == 200

    with tempfile.TemporaryDirectory(prefix="probe-test-") as td:
        td_path = Path(td)
        dump = td_path / "probe.bin"
        out = td_path / "probe.out"
        dump.write_bytes(res.content)
        decrypt_dump_to_bundle(dump, out, password)

        content = out.read_bytes()
        assert content == b"LGM-PROBE\n", f"Expected 'LGM-PROBE\\n', got {content!r}"


def test_probe_decrypt_wrong_password_fails(monkeypatch):
    """Decryption with wrong password must raise (AES-GCM tag check)."""
    client = _make_client(monkeypatch, password="correct")
    res = client.get("/api/auth/verify")
    assert res.status_code == 200

    with tempfile.TemporaryDirectory(prefix="probe-test-") as td:
        td_path = Path(td)
        dump = td_path / "probe.bin"
        out = td_path / "probe.out"
        dump.write_bytes(res.content)
        with pytest.raises(Exception):
            decrypt_dump_to_bundle(dump, out, "wrong-password")


def test_probe_not_available_without_password(monkeypatch):
    """Probe endpoint should fail when SYNC_PASSWORD is not set."""
    monkeypatch.delenv("SYNC_PASSWORD", raising=False)
    app = FastAPI()
    app.include_router(api_router.router)
    client = TestClient(app)

    res = client.get("/api/auth/verify")
    assert res.status_code == 500


def test_e2e_full_handshake_flow(monkeypatch):
    """
    Full plugin handshake simulation:
      capabilities → password-probe → decrypt → validate content
    This is the exact flow that was broken by the stealth hardening changes.
    """
    password = "e2e-handshake"
    client = _make_client(monkeypatch, password=password)

    # Step 1: Capabilities
    caps = client.get("/api/health")
    assert caps.status_code == 200
    caps_body = caps.json()
    assert caps_body.get("sync", {}).get("features", {}).get("passwordProbe") is True
    assert caps_body.get("apiVersion") == 1
    assert caps_body.get("sync", {}).get("protocolVersion") == 1

    # Step 2: Get probe
    probe = client.get("/api/auth/verify")
    assert probe.status_code == 200
    assert probe.headers.get("X-Sync-Probe") is None, "Revealing header must not be present"

    payload = probe.content
    assert payload[:8] == MAGIC, "Probe must use v1 format"

    # Step 3: Decrypt (simulates NativeStealthDump.decryptDumpBytes on plugin side)
    with tempfile.TemporaryDirectory(prefix="e2e-hs-") as td:
        td_path = Path(td)
        dump_file = td_path / "probe.bin"
        out_file = td_path / "probe.out"
        dump_file.write_bytes(payload)
        decrypt_dump_to_bundle(dump_file, out_file, password)

        content = out_file.read_text(encoding="utf-8").strip()

    # Step 4: Validate — plugin checks for "LGM-PROBE" OR "SYNC-PROBE"
    assert content in ("LGM-PROBE", "SYNC-PROBE"), f"Unexpected probe content: {content!r}"
    # Current backend must send "LGM-PROBE" for backward compat
    assert content == "LGM-PROBE", "Backend probe must send LGM-PROBE for old plugin compat"
