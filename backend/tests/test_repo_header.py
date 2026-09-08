"""Tests for the X-Doc-Ref header alternative to ?rid= on GET/DELETE endpoints.

Order of precedence: query param > X-Doc-Ref header > 400.
Same validation as ?rid= applies to the header value.
"""
import json
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from app.routers import deps as deps_router_mod
from app.routers import file_sync as file_sync_router_mod
from tests import _harness


def _make_client(tmp_path: Path):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
        encoding="utf-8",
    )
    rm = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=rm,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    deps_router_mod.repo_manager = rm
    deps_router_mod.system_logger = None
    file_sync_router_mod.repo_manager = rm
    file_sync_router_mod.system_logger = None
    app.include_router(deps_router_mod.router)
    app.include_router(file_sync_router_mod.router)
    return TestClient(app)


# ── deps: X-Doc-Ref header on /api/documents/queue ───────────────────────────

def test_deps_queue_via_header(tmp_path: Path):
    client = _make_client(tmp_path)
    resp = client.get("/api/documents/queue", headers={"X-Doc-Ref": "onyx"})
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["repo"] == "onyx"
    assert body["items"] == []


def test_deps_queue_query_param_still_works(tmp_path: Path):
    client = _make_client(tmp_path)
    resp = client.get("/api/documents/queue", params={"rid": "onyx"})
    assert resp.status_code == 200, resp.text
    assert resp.json()["repo"] == "onyx"


def test_deps_queue_missing_both_returns_400(tmp_path: Path):
    client = _make_client(tmp_path)
    resp = client.get("/api/documents/queue")
    assert resp.status_code == 400


def test_deps_queue_query_param_takes_precedence(tmp_path: Path):
    client = _make_client(tmp_path)
    resp = client.get(
        "/api/documents/queue",
        params={"rid": "from-query"},
        headers={"X-Doc-Ref": "from-header"},
    )
    assert resp.status_code == 200
    assert resp.json()["repo"] == "from-query"


# ── file-sync: X-Doc-Ref header on /api/documents/attachment-list ────────────

def test_file_sync_list_via_header(tmp_path: Path):
    client = _make_client(tmp_path)
    resp = client.get("/api/documents/attachment-list", headers={"X-Doc-Ref": "onyx"})
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["repo"] == "onyx"
    assert body["items"] == []


def test_file_sync_list_query_param_still_works(tmp_path: Path):
    client = _make_client(tmp_path)
    resp = client.get("/api/documents/attachment-list", params={"rid": "onyx"})
    assert resp.status_code == 200, resp.text
    assert resp.json()["repo"] == "onyx"


def test_file_sync_list_missing_both_returns_400(tmp_path: Path):
    client = _make_client(tmp_path)
    resp = client.get("/api/documents/attachment-list")
    assert resp.status_code == 400
