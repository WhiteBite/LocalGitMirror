"""The plaintext git daemon must not auto-start and must not auto-create repos.

Repo creation is only legitimate through POST /api/documents/collection
(RepoManager.create_repo with name validation); the dulwich backend used by
the git:// daemon must stay open-only.
"""
import json
import socket
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

DAEMON_KEY = "no-git-daemon-test-key"
AUTH = {"X-Session-ID": DAEMON_KEY}


def _pick_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _port_accepts_connections(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1.0):
            return True
    except OSError:
        return False


@pytest.fixture
def real_app(tmp_path: Path, monkeypatch):
    """Boot the real app on a known-free git port so the daemon is probeable."""
    storage = tmp_path / "lgm-storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({
            "general": {"storage_path": str(storage)},
            "git": {"user_name": "Bot", "user_email": "bot@test.com"},
        }),
        encoding="utf-8",
    )
    git_port = _pick_free_port()
    monkeypatch.setenv("STORAGE_PATH", str(storage))
    monkeypatch.setenv("WEB_PORT", "0")
    monkeypatch.setenv("GIT_PORT", str(git_port))
    monkeypatch.setenv("API_KEY", DAEMON_KEY)
    monkeypatch.setenv("SYNC_PASSWORD", "test-pwd")

    saved = {m: mod for m, mod in list(sys.modules.items()) if m.startswith("app.")}
    for mod in list(saved):
        del sys.modules[mod]

    from app.main import app
    try:
        with TestClient(app) as client:
            yield client
    finally:
        sys.modules.update(saved)


def _git_handler():
    return sys.modules["app.main"].git_handler


def test_no_git_daemon_listening_after_lifespan(real_app: TestClient):
    handler = _git_handler()
    assert handler is not None
    assert handler.is_running is False
    assert handler._server is None
    assert handler._thread is None
    assert not _port_accepts_connections(handler.port), (
        f"git daemon is listening on port {handler.port} after lifespan startup"
    )


def test_open_repository_does_not_create_unknown_repo(real_app: TestClient):
    from dulwich.errors import NotGitRepository

    handler = _git_handler()
    with pytest.raises(NotGitRepository):
        handler.backend.open_repository(b"unknown-x")

    storage = handler.storage_path
    assert not (storage / ".lgm" / "bare" / "unknown-x.git").exists()
    assert not (storage / "unknown-x.git").exists()
    assert not (storage / "unknown-x").exists()


def test_repo_creation_still_works_via_collection(real_app: TestClient):
    resp = real_app.post(
        "/api/documents/collection",
        json={"name": "created-via-collection"},
        headers=AUTH,
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["success"] is True

    storage = _git_handler().storage_path
    assert (storage / ".lgm" / "bare" / "created-via-collection.git").exists()
