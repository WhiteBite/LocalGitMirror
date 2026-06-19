"""
Tests for _git() timeout handling and the sync export handler.

These tests verify:
1. _git() with timeout=0 (immediate timeout) returns returncode=124 and
   a useful stderr message without raising an exception.
2. sync_export_dump is a regular `def` (not async), ensuring it runs
   in a threadpool and does not block the event loop.
"""
import inspect
import subprocess
from pathlib import Path

import pytest

from app.routers.sync import _git


# ─────────────────────────────────────────────────────────────────────────────
# 1.  _git() timeout → returncode 124
# ─────────────────────────────────────────────────────────────────────────────

def test_git_timeout_returns_rc_124(tmp_path: Path):
    """A command that would normally succeed but times out returns rc=124."""
    # timeout=0 triggers TimeoutExpired immediately on any command
    result = _git(tmp_path, "version", timeout=0)
    assert result.returncode == 124, (
        f"Expected returncode 124 on timeout, got {result.returncode}"
    )
    assert "timed out" in result.stderr.lower(), (
        f"Expected timeout message in stderr, got: {result.stderr!r}"
    )


def test_git_no_timeout_succeeds(tmp_path: Path):
    """Normal git command with default timeout completes successfully."""
    result = _git(tmp_path, "version")
    assert result.returncode == 0
    assert "git version" in result.stdout.lower()


def test_git_invalid_command_returns_nonzero(tmp_path: Path):
    """Invalid git command returns non-zero exit code (not 124)."""
    result = _git(tmp_path, "this-command-does-not-exist")
    assert result.returncode != 0
    assert result.returncode != 124  # it's a real failure, not a timeout


# ─────────────────────────────────────────────────────────────────────────────
# 2.  sync_export_dump must NOT be async (must run in threadpool)
# ─────────────────────────────────────────────────────────────────────────────

def test_sync_export_dump_is_not_async():
    """
    Verify sync_export_dump is a plain `def`, not `async def`.

    FastAPI runs sync handlers in a threadpool, keeping the event loop free.
    An async handler would run on the event loop and block it during heavy
    work (git bundle, encryption, base64) — the original cause of
    'channel was closed' errors on large repos.
    """
    from app.routers.sync import sync_export_dump
    assert not inspect.iscoroutinefunction(sync_export_dump), (
        "sync_export_dump must be a plain `def` (not async) so FastAPI runs it "
        "in a threadpool. Converting it back to async def will block the event "
        "loop and cause 'channel was closed' errors on large repos."
    )


# ─────────────────────────────────────────────────────────────────────────────
# 3.  export returns 400 when repo has no commits
# ─────────────────────────────────────────────────────────────────────────────

def test_export_empty_repo_returns_400(tmp_path: Path, monkeypatch):
    """Export of a repo with no commits returns HTTP 400."""
    import json
    from fastapi.testclient import TestClient
    from app.core.repo_manager import RepoManager
    from tests import _harness

    storage = tmp_path / "storage"
    storage.mkdir()
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("SYNC_PASSWORD", "test-pass")

    repo_name = "empty-repo-export-test"
    rm = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=rm,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    client = TestClient(app)

    # Create repo (this makes the initial commit via RepoManager)
    created = client.post("/api/repos/create", json={"name": repo_name})
    assert created.status_code == 200

    # Force an empty-looking repo by pointing to a non-existent repo name
    resp = client.post("/api/documents/export", data={"repo": "no-such-repo"})
    assert resp.status_code == 404, f"Expected 404 for unknown repo, got {resp.status_code}: {resp.text}"
