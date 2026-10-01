import sys
from pathlib import Path

import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.main import get_api_key
from app.routers import auth as auth_router
from app.routers import sync as api_router
from app.routers import system as system_router


def test_capabilities_endpoint_shape():
    app = FastAPI()
    app.include_router(api_router.router)
    app.include_router(auth_router.router)
    client = TestClient(app)

    res = client.get("/api/health")
    assert res.status_code == 200, res.text
    body = res.json()

    assert body["apiVersion"] == 1
    assert body["server"]["name"] == "DocCache"
    assert isinstance(body["server"]["version"], str)
    assert body["sync"]["protocolVersion"] == 1
    assert body["sync"]["features"]["preflight"] is True
    assert body["sync"]["features"]["dryRun"] is True
    assert "no-op" in body["sync"]["modes"]


def _protected_app() -> FastAPI:
    app = FastAPI()
    app.include_router(system_router.router, dependencies=[Depends(get_api_key)])
    return app


def _scoped_client(app: FastAPI, client_addr: tuple) -> TestClient:
    async def scoped(scope, receive, send):
        if scope["type"] in ("http", "websocket"):
            scope = dict(scope, client=client_addr)
        await app(scope, receive, send)

    return TestClient(scoped)


@pytest.fixture()
def system_env(monkeypatch, tmp_path):
    monkeypatch.setenv("API_KEY", "cap-test-key")
    monkeypatch.setenv("SYNC_PASSWORD", "cap-test-password")
    saved_config = system_router.config
    saved_logger = system_router.system_logger
    saved_repo = system_router.repo_manager
    system_router.config = {"web_port": 8443, "git_port": 8444, "storage_path": str(tmp_path)}
    system_router.system_logger = None
    system_router.repo_manager = None
    yield
    system_router.config = saved_config
    system_router.system_logger = saved_logger
    system_router.repo_manager = saved_repo


def test_api_key_unset_rejects_protected_routes(monkeypatch):
    monkeypatch.delenv("API_KEY", raising=False)
    client = TestClient(_protected_app())

    res = client.get("/api/status")
    assert res.status_code == 503, res.text
    assert "API_KEY" in res.json()["detail"]


def test_wrong_api_key_hides_endpoint(system_env):
    client = TestClient(_protected_app())

    res = client.get("/api/status", headers={"X-Session-ID": "wrong"})
    assert res.status_code == 404, res.text


def test_log_endpoints_require_auth(system_env):
    client = TestClient(_protected_app())
    auth = {"X-Session-ID": "cap-test-key"}

    assert client.get("/api/logs").status_code == 404
    assert client.delete("/api/logs").status_code == 404
    assert client.get("/api/logs/stats").status_code == 404

    assert client.get("/api/logs", headers=auth).status_code == 200
    assert client.delete("/api/logs", headers=auth).status_code == 200
    assert client.get("/api/logs/stats", headers=auth).status_code == 200


def test_ws_token_denied_when_api_key_unset(monkeypatch):
    from app.routers.websocket import _validate_ws_token

    monkeypatch.delenv("API_KEY", raising=False)
    assert _validate_ws_token(None) is False


def test_connection_info_serves_secrets_to_loopback(system_env):
    client = _scoped_client(_protected_app(), ("127.0.0.1", 50000))

    res = client.get("/api/connection-info", headers={"X-Session-ID": "cap-test-key"})
    assert res.status_code == 200, res.text
    body = res.json()

    assert body["api_key"] == "cap-test-key"
    assert body["sync_password"] == "cap-test-password"
    assert "api_key_set" not in body
    assert "sync_password_set" not in body
    assert "mirrorApiKey=cap-test-key" in body["config_line"]
    assert "syncPassword=cap-test-password" in body["config_line"]
    assert body["default_repo"] == "default"
    assert body["web_port"] == 8443


def test_connection_info_hides_secrets_from_remote(system_env):
    client = _scoped_client(_protected_app(), ("203.0.113.5", 50000))

    res = client.get("/api/connection-info", headers={"X-Session-ID": "cap-test-key"})
    assert res.status_code == 200, res.text
    body = res.json()

    assert "api_key" not in body
    assert "sync_password" not in body
    assert body["api_key_set"] is True
    assert body["sync_password_set"] is True
    assert "cap-test-key" not in body["config_line"]
    assert "cap-test-password" not in body["config_line"]
    assert "mirrorApiKey=\n" in body["config_line"]
    assert "syncPassword=\n" in body["config_line"]
    assert body["default_repo"] == "default"
    assert body["web_port"] == 8443


def test_connection_info_reports_unset_password_as_false(system_env, monkeypatch):
    monkeypatch.delenv("SYNC_PASSWORD", raising=False)
    client = _scoped_client(_protected_app(), ("203.0.113.5", 50000))

    res = client.get("/api/connection-info", headers={"X-Session-ID": "cap-test-key"})
    assert res.status_code == 200, res.text
    body = res.json()

    assert body["api_key_set"] is True
    assert body["sync_password_set"] is False
