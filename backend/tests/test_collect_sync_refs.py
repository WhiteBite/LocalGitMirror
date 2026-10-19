"""
Regression: _collect_sync_refs must report the NEWEST branch tip.

The workspace IS the home machine's checkout; the bare repo only advances on
incoming sends. When home commits locally, the workspace tip is newer than
the bare tip — the old bare-wins merge reported a STALE sha to
lgm_branches / the dashboard (observed live on phonyx agent_mode).
"""
import os
import subprocess
from pathlib import Path

from app.routers.sync import _collect_sync_refs

OLD_DATE = "2026-01-01T00:00:00+00:00"
NEW_DATE = "2026-06-01T00:00:00+00:00"


def _run_git(cwd: Path, *args: str, env: dict = None) -> str:
    full_env = {**os.environ, **env} if env else None
    proc = subprocess.run(
        ["git", *args], cwd=str(cwd), capture_output=True, text=True, env=full_env
    )
    if proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} failed: {proc.stderr}")
    return proc.stdout.strip()


def _commit(repo: Path, filename: str, content: str, message: str, date: str) -> str:
    (repo / filename).write_text(content)
    _run_git(repo, "add", ".")
    dates = {"GIT_COMMITTER_DATE": date, "GIT_AUTHOR_DATE": date}
    _run_git(repo, "commit", "-m", message, env=dates)
    return _run_git(repo, "rev-parse", "HEAD")


def _build_repos(tmp_path: Path):
    """Return (workspace, bare, shas) where bare sits at C0 and the workspace
    checkout advanced master to C1 (C1 descends from C0)."""
    seed = tmp_path / "seed"
    seed.mkdir()
    _run_git(seed, "init", "-b", "master")
    _run_git(seed, "config", "user.email", "t@test.com")
    _run_git(seed, "config", "user.name", "Tester")
    c0 = _commit(seed, "a.txt", "c0\n", "c0", OLD_DATE)

    bare = tmp_path / "bare.git"
    _run_git(tmp_path, "clone", "--bare", str(seed), str(bare))

    workspace = tmp_path / "ws"
    _run_git(tmp_path, "clone", str(bare), str(workspace))
    _run_git(workspace, "config", "user.email", "t@test.com")
    _run_git(workspace, "config", "user.name", "Tester")
    c1 = _commit(workspace, "a.txt", "c1\n", "c1", NEW_DATE)

    return workspace, bare, c0, c1


def test_same_branch_reports_newer_workspace_tip(tmp_path: Path):
    workspace, bare, c0, c1 = _build_repos(tmp_path)

    refs, _head, _head_branch = _collect_sync_refs(workspace, bare)

    assert refs["master"]["sha"] == c1, (
        f"newer workspace tip must win over stale bare tip {c0}"
    )


def test_branch_only_in_bare_is_reported(tmp_path: Path):
    workspace, bare, c0, _c1 = _build_repos(tmp_path)
    _run_git(bare, "branch", "bare_only", c0)

    refs, _head, _head_branch = _collect_sync_refs(workspace, bare)

    assert refs["bare_only"]["sha"] == c0


def test_branch_only_in_workspace_is_reported(tmp_path: Path):
    workspace, bare, _c0, c1 = _build_repos(tmp_path)
    _run_git(workspace, "branch", "ws_only", c1)

    refs, _head, _head_branch = _collect_sync_refs(workspace, bare)

    assert refs["ws_only"]["sha"] == c1


def test_head_and_is_head_come_from_checked_out_workspace(tmp_path: Path):
    workspace, bare, _c0, c1 = _build_repos(tmp_path)
    _run_git(bare, "branch", "bare_only")

    refs, head, _head_branch = _collect_sync_refs(workspace, bare)

    assert head == c1
    assert refs["master"]["is_head"] is True
    assert refs["bare_only"]["is_head"] is False
