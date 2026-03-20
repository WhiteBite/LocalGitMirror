import sys
import tempfile
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.core.stealth_crypto import decrypt_dump_to_bundle
from app.routers import api as api_router


def test_password_probe_roundtrip(monkeypatch):
    monkeypatch.setenv("SYNC_PASSWORD", "probe-password")

    app = FastAPI()
    app.include_router(api_router.router)
    client = TestClient(app)

    res = client.get("/api/sync/password-probe")
    assert res.status_code == 200, res.text
    assert res.headers.get("X-LGM-Probe") == "1"
    payload = res.content
    assert payload.startswith(b"LGMSTRL1")

    with tempfile.TemporaryDirectory(prefix="lgm-probe-test-") as td:
        td_path = Path(td)
        dump = td_path / "probe.dmp"
        out = td_path / "probe.bin"
        dump.write_bytes(payload)
        decrypt_dump_to_bundle(dump, out, "probe-password")
        assert out.read_bytes() == b"LGM-PROBE\n"
