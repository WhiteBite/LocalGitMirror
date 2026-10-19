"""Protocol v3 upload: the bundle key bound in the async handler must reach
the worker thread that runs the apply, regardless of whether the executor
copies the request context (plain run_in_executor does not)."""
import asyncio
import base64
import functools
import json
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core import hybrid_crypto as hc
from app.core import sync_envelope
from app.routers import sync as sync_mod
from tests.conftest import envelope_form_post, parse_envelope


async def _ctx_dropping_threadpool(func, *args):
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, functools.partial(func, *args))


class _ClientSession:
    """One ephemeral bound to one API call, like the plugin's HybridCrypto."""

    def __init__(self, server_pub: bytes):
        self._eph = X25519PrivateKey.generate()
        self.epk = self._eph.public_key().public_bytes_raw()
        self._shared = self._eph.exchange(X25519PublicKey.from_public_bytes(server_pub))

    def epk_b64(self) -> str:
        return base64.urlsafe_b64encode(self.epk).decode().rstrip("=")

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


def _make_client(tmp_path: Path, monkeypatch) -> TestClient:
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
        encoding="utf-8",
    )

    class _RM:
        def get_repos(self):
            return ["proj"]

        def _get_workspace_path(self, _r):
            return tmp_path

        def _get_bare_path(self, _r):
            return tmp_path

    app = FastAPI()
    app.include_router(sync_mod.router)
    monkeypatch.setattr(sync_mod, "repo_manager", _RM(), raising=False)
    monkeypatch.setattr(sync_envelope, "server_private_key", X25519PrivateKey.generate())
    return TestClient(app)


def test_v3_bundle_key_survives_context_dropping_threadpool(tmp_path, monkeypatch):
    monkeypatch.delenv("SYNC_PASSWORD", raising=False)
    client = _make_client(tmp_path, monkeypatch)
    monkeypatch.setattr(sync_mod, "run_in_threadpool", _ctx_dropping_threadpool)

    server_pub = hc.public_bytes(sync_envelope.server_private_key)
    env_sess = _ClientSession(server_pub)
    bundle_sess = _ClientSession(server_pub)
    original = b"BUNDLEDATA" + bytes(range(200))
    sealed = bundle_sess.seal_bundle(original)

    captured = {}

    def _fake_apply(**kwargs):
        ctx = sync_envelope._hybrid_bundle_ctx.get()
        assert ctx is not None, "v3 bundle key lost on the way to the worker thread"
        captured["plaintext"] = ctx.open_bundle(kwargs["dump_path"].read_bytes())
        return {"success": True, "repo": kwargs["repo_name"],
                "attachment": kwargs["dump_filename"]}

    monkeypatch.setattr(sync_mod, "_apply_dump_to_repo_and_sync_bare", _fake_apply)

    resp = client.post(
        "/api/documents/upload",
        data={"e": env_sess.seal_env({"repo": "proj"}),
              "k": env_sess.epk_b64(), "kb": bundle_sess.epk_b64()},
        files={"attachment": ("document.bin", sealed, "application/octet-stream")},
    )
    assert resp.status_code == 200, resp.text
    assert captured["plaintext"] == original
    assert env_sess.open_env(resp.json()["e"])["success"] is True


def test_legacy_upload_without_kb_binds_no_bundle_ctx(tmp_path, monkeypatch):
    monkeypatch.setenv("SYNC_PASSWORD", "legacy-pw")
    client = _make_client(tmp_path, monkeypatch)
    monkeypatch.setattr(sync_mod, "run_in_threadpool", _ctx_dropping_threadpool)

    seen = {}

    def _fake_apply(**kwargs):
        seen["ctx"] = sync_envelope._hybrid_bundle_ctx.get()
        return {"success": True, "repo": kwargs["repo_name"]}

    monkeypatch.setattr(sync_mod, "_apply_dump_to_repo_and_sync_bare", _fake_apply)

    resp = envelope_form_post(
        client, "/api/documents/upload", {"repo": "proj"}, "legacy-pw",
        files={"attachment": ("document.bin", b"stub", "application/octet-stream")},
    )
    assert resp.status_code == 200, resp.text
    assert parse_envelope(resp.json(), "legacy-pw")["success"] is True
    assert seen["ctx"] is None, "legacy uploads must not see a stale v3 bundle key"
