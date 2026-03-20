import sys
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.routers import api as api_router


def test_capabilities_endpoint_shape():
    app = FastAPI()
    app.include_router(api_router.router)
    client = TestClient(app)

    res = client.get("/api/capabilities")
    assert res.status_code == 200, res.text
    body = res.json()

    assert body["apiVersion"] == 1
    assert body["server"]["name"] == "LocalGitMirror"
    assert isinstance(body["server"]["version"], str)
    assert body["sync"]["protocolVersion"] == 1
    assert body["sync"]["features"]["preflight"] is True
    assert body["sync"]["features"]["dryRun"] is True
    assert "no-op" in body["sync"]["modes"]
