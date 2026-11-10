"""Gate for the identity-stamping class: syncing a workspace must not copy the
last commit's author into it, or one commit by another person silently turns the
whole checkout into that person afterwards."""

import subprocess
from pathlib import Path

import pytest

from app.core.repo_manager import RepoManager


def _git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)


def _local_identity(path: Path) -> tuple[str, str]:
    name = _git(path, "config", "--local", "--get", "user.name").stdout.strip()
    email = _git(path, "config", "--local", "--get", "user.email").stdout.strip()
    return name, email


@pytest.fixture
def neutral_git(monkeypatch, tmp_path):
    empty = tmp_path / "empty-gitconfig"
    empty.write_text("", encoding="utf-8")
    monkeypatch.setenv("GIT_CONFIG_GLOBAL", str(empty))
    monkeypatch.setenv("GIT_CONFIG_SYSTEM", str(empty))
    monkeypatch.setenv("GIT_CONFIG_NOSYSTEM", "1")


def test_identity_is_not_copied_from_last_commit(neutral_git, tmp_path):
    workspace = tmp_path / "ws"
    workspace.mkdir()
    _git(workspace, "init")
    (workspace / "f.txt").write_text("v1\n", encoding="utf-8")
    _git(workspace, "add", "f.txt")
    _git(workspace, "-c", "user.name=Someone Else", "-c", "user.email=someone@example.com",
         "commit", "-m", "made by someone else")

    assert _local_identity(workspace) == ("", "")

    RepoManager(tmp_path / "storage")._apply_configured_identity(workspace)

    assert _local_identity(workspace) == ("", "")


def test_identity_is_applied_from_explicit_setting(neutral_git, tmp_path):
    workspace = tmp_path / "ws2"
    workspace.mkdir()
    _git(workspace, "init")
    storage = tmp_path / "storage2"
    (storage / ".lgm").mkdir(parents=True)
    (storage / ".lgm" / "settings.json").write_text(
        '{"git": {"user_name": "Bot", "user_email": "bot@test.com"}}', encoding="utf-8")

    RepoManager(storage)._apply_configured_identity(workspace)

    assert _local_identity(workspace) == ("Bot", "bot@test.com")
