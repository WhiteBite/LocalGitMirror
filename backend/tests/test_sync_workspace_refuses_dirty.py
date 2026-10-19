"""Gate for the sync_workspace data-loss class: the workspace IS the home
machine's real checkout, so a tracked modification must be refused with the
typed workspace_dirty error and never reset, while a clean workspace still
syncs from the bare exactly as before."""

import subprocess
from pathlib import Path

from app.core.repo_manager import RepoManager


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} failed: {proc.stderr}")
    return proc


def _build_repo(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    _run_git(path, "init")
    _run_git(path, "config", "user.email", "ws@test.com")
    _run_git(path, "config", "user.name", "WS")
    (path / "f.txt").write_text("v1\n", encoding="utf-8")
    _run_git(path, "add", "f.txt")
    _run_git(path, "commit", "-m", "first")
    return path


def _build_bare(storage: Path, repo: str) -> Path:
    src = _build_repo(storage / "seed")
    _run_git(src, "branch", "-M", "main")
    bare = storage / f"{repo}.git"
    _run_git(src, "init", "--bare", str(bare))
    _run_git(src, "push", str(bare), "main:main")
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/main")
    return bare


def test_sync_workspace_refuses_dirty_tracked_modification(tmp_path):
    storage = tmp_path / "storage"
    ws = _build_repo(storage / "dirty_repo")
    (ws / "f.txt").write_text("operator WIP\n", encoding="utf-8")
    (ws / "notes.local").write_text("untracked user data\n", encoding="utf-8")
    manager = RepoManager(storage)

    result = manager.sync_workspace("dirty_repo")

    assert result["success"] is False
    assert result["code"] == "workspace_dirty"
    assert result["paths"] == ["f.txt"]
    assert (ws / "f.txt").read_text(encoding="utf-8") == "operator WIP\n"
    assert (ws / "notes.local").read_text(encoding="utf-8") == "untracked user data\n"


def test_sync_workspace_clean_still_syncs_from_bare(tmp_path):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    bare = _build_bare(storage, "attach_repo")
    tip = _run_git(bare, "rev-parse", "refs/heads/main").stdout.strip()
    manager = RepoManager(storage)

    result = manager.sync_workspace("attach_repo")

    ws = storage / "attach_repo"
    assert result["success"] is True
    assert (ws / "f.txt").read_text(encoding="utf-8") == "v1\n"
    assert _run_git(ws, "rev-parse", "HEAD").stdout.strip() == tip


def test_sync_workspace_clean_non_bare_resets_to_head(tmp_path):
    storage = tmp_path / "storage"
    ws = _build_repo(storage / "solo_repo")
    manager = RepoManager(storage)

    result = manager.sync_workspace("solo_repo")

    assert result["success"] is True
    assert (ws / "f.txt").read_text(encoding="utf-8") == "v1\n"
