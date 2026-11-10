import subprocess
from pathlib import Path

import pytest
from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.crypto import encrypt_bundle
from lgm_core.op_models import Ctx
from lgm_core.ops_git import op_pull


def git(project: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(project), *args], capture_output=True,
        text=True, check=True, timeout=30,
    ).stdout.strip()


@pytest.fixture
def incoming(tmp_path, monkeypatch):
    home = tmp_path / "home"
    work = tmp_path / "work"
    home.mkdir()
    git(home, "init", "-b", "feature")
    git(home, "config", "user.name", "Test")
    git(home, "config", "user.email", "test@example.com")
    (home / "code.txt").write_text("before\n")
    git(home, "add", ".")
    git(home, "commit", "-m", "base")
    git(tmp_path, "clone", str(home), str(work))
    git(work, "config", "user.name", "Test")
    git(work, "config", "user.email", "test@example.com")
    (home / "code.txt").write_text("fixed\n")
    git(home, "commit", "-am", "fix")
    git(home, "branch", "other")
    bundle = tmp_path / "incoming.bundle"
    git(home, "bundle", "create", str(bundle), "--all")
    config = Config(base_url="http://localhost:1", api_key="", sync_password="pw")
    client = MirrorClient(base_url=config.base_url, sync_password="pw")
    dump = encrypt_bundle(bundle.read_bytes(), "pw")
    monkeypatch.setattr(client, "sync_pull", lambda *args, **kwargs: {
        "status": "ok", "head": git(home, "rev-parse", "HEAD"), "dump": dump})
    return Ctx(config=config, client=client), home, work


def test_pull_fast_forwards_current_branch_and_worktree(incoming):
    ctx, home, work = incoming

    result = op_pull(ctx, {"repo": "r", "project": str(work)})

    assert result["fetch_exit"] == 0
    assert git(work, "rev-parse", "HEAD") == git(home, "rev-parse", "HEAD")
    assert (work / "code.txt").read_text() == "fixed\n"
    assert git(work, "status", "--porcelain") == ""
    assert git(work, "rev-parse", "other") == git(home, "rev-parse", "other")


def test_pull_preserves_diverged_current_branch(incoming):
    ctx, home, work = incoming
    (work / "local.txt").write_text("local\n")
    git(work, "add", ".")
    git(work, "commit", "-m", "local")
    before = git(work, "rev-parse", "HEAD")

    with pytest.raises(LgmError):
        op_pull(ctx, {"repo": "r", "project": str(work)})

    assert git(work, "rev-parse", "HEAD") == before
    assert (work / "local.txt").read_text() == "local\n"
    assert (work / "code.txt").read_text() == "before\n"


def test_pull_preserves_dirty_current_worktree(incoming):
    ctx, home, work = incoming
    before = git(work, "rev-parse", "HEAD")
    (work / "code.txt").write_text("unfinished\n")

    with pytest.raises(LgmError):
        op_pull(ctx, {"repo": "r", "project": str(work)})

    assert git(work, "rev-parse", "HEAD") == before
    assert (work / "code.txt").read_text() == "unfinished\n"


def test_pull_preserves_diverged_noncurrent_branch(incoming):
    ctx, home, work = incoming
    git(work, "checkout", "-b", "other")
    (work / "local.txt").write_text("local\n")
    git(work, "add", ".")
    git(work, "commit", "-m", "local")
    before = git(work, "rev-parse", "HEAD")
    git(work, "checkout", "feature")

    with pytest.raises(LgmError):
        op_pull(ctx, {"repo": "r", "project": str(work)})

    assert git(work, "rev-parse", "other") == before
    assert (work / "code.txt").read_text() == "before\n"


def test_pull_initializes_unborn_current_branch(incoming, tmp_path):
    ctx, home, work = incoming
    empty = tmp_path / "empty"
    empty.mkdir()
    git(empty, "init", "-b", "feature")

    op_pull(ctx, {"repo": "r", "project": str(empty)})

    assert git(empty, "rev-parse", "HEAD") == git(home, "rev-parse", "HEAD")
    assert (empty / "code.txt").read_text() == "fixed\n"


def test_mcp_pull_applies_home_commits_to_current_work_branch(incoming, monkeypatch):
    import lgm_mcp

    ctx, home, work = incoming
    monkeypatch.setattr(lgm_mcp.Config, "from_env", lambda: ctx.config)
    monkeypatch.setattr(lgm_mcp, "MirrorClient", lambda **kwargs: ctx.client)

    response = lgm_mcp._handle_request({
        "jsonrpc": "2.0", "id": 1, "method": "tools/call",
        "params": {"name": "pull", "arguments": {"repo": "r", "project": str(work)}},
    })

    assert not response["result"].get("isError")
    assert git(work, "rev-parse", "HEAD") == git(home, "rev-parse", "HEAD")
    assert (work / "code.txt").read_text() == "fixed\n"
