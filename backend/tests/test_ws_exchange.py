"""/ws/exchange: buffer mutations broadcast exchange_change push events."""
import asyncio
import base64
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.bundle_crypto import encrypt_bundle_bytes
from app.routers import buffer, websocket

PASSWORD = "ws-exchange-test-pw"
API_KEY = "ws-exchange-test-key"


def _build_app() -> FastAPI:
    @asynccontextmanager
    async def lifespan(app):
        websocket.set_main_loop(asyncio.get_running_loop())
        yield
        websocket.set_main_loop(None)

    app = FastAPI(lifespan=lifespan)
    app.include_router(websocket.router)
    app.include_router(buffer.router)
    return app


def _reset_modules():
    buffer._items = {}
    buffer._loaded = False
    buffer.server_private_key = None
    buffer.relay_key = None
    websocket.exchange_connections.clear()
    websocket.set_main_loop(None)


def test_buffer_put_broadcasts_exchange_change(tmp_path, monkeypatch):
    monkeypatch.setattr(buffer, "storage_dir", tmp_path / "buffer")
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)
    monkeypatch.setenv("API_KEY", API_KEY)
    _reset_modules()
    try:
        with TestClient(_build_app()) as client:
            with client.websocket_connect(f"/ws/exchange?key={API_KEY}") as ws:
                body = {
                    "ciphertext_b64": base64.b64encode(
                        encrypt_bundle_bytes(b"opaque", PASSWORD)).decode(),
                }
                resp = client.post("/api/buffer", json=body)
                assert resp.status_code == 200, resp.text
                data = ws.receive_json()
                assert data["type"] == "exchange_change"
                assert data["kind"] == "buffer_put"
    finally:
        _reset_modules()


def test_notify_exchange_change_without_loop_is_a_noop():
    _reset_modules()
    websocket.notify_exchange_change("buffer_put")
    assert websocket.exchange_connections == []
