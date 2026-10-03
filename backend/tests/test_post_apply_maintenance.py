"""_post_apply_maintenance: commit-graph write, 24h throttle stamp, repack
above the pack-count threshold. Spy assertions filter by cwd so stray
maintenance daemon threads from other tests cannot pollute them."""

import subprocess
from pathlib import Path

from app.core import git_bundle
from app.core.git_bundle import _git as real_git


class _StorageOnlyManager:
    def __init__(self, storage: Path):
        self.storage_path = storage


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} failed: {proc.stderr}")
    return proc


def _build_repo(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    _run_git(path, "init")
    _run_git(path, "config", "user.email", "maint@test.com")
    _run_git(path, "config", "user.name", "Maint")
    (path / "f.txt").write_text("v1\n", encoding="utf-8")
    _run_git(path, "add", "f.txt")
    _run_git(path, "commit", "-m", "first")
    return path


def _stamp(storage: Path, repo: str) -> Path:
    return storage / ".lgm" / "maintenance" / f"{repo}.stamp"


def _spy_git(monkeypatch) -> list[tuple[str, tuple]]:
    calls: list[tuple[str, tuple]] = []

    def spy(cwd, *args, timeout=600):
        calls.append((str(cwd), args))
        return real_git(cwd, *args, timeout=timeout)

    monkeypatch.setattr(git_bundle, "_git", spy)
    return calls


def _ws_calls(calls: list[tuple[str, tuple]], ws: Path) -> list[tuple]:
    return [args for cwd, args in calls if cwd == str(ws)]


def test_maintenance_writes_commit_graph_and_stamp(tmp_path, monkeypatch):
    ws = _build_repo(tmp_path / "ws")
    storage = tmp_path / "storage"
    storage.mkdir()
    monkeypatch.setattr(git_bundle, "repo_manager", _StorageOnlyManager(storage))

    git_bundle._post_apply_maintenance("maint-a", ws, None)

    assert (ws / ".git" / "objects" / "info" / "commit-graph").exists()
    assert _stamp(storage, "maint-a").exists()


def test_maintenance_throttled_within_interval(tmp_path, monkeypatch):
    ws = _build_repo(tmp_path / "ws")
    storage = tmp_path / "storage"
    storage.mkdir()
    monkeypatch.setattr(git_bundle, "repo_manager", _StorageOnlyManager(storage))
    git_bundle._post_apply_maintenance("maint-b", ws, None)
    stamp = _stamp(storage, "maint-b")
    assert stamp.exists()
    mtime_before = stamp.stat().st_mtime

    calls = _spy_git(monkeypatch)
    git_bundle._post_apply_maintenance("maint-b", ws, None)
    assert _ws_calls(calls, ws) == []
    assert stamp.stat().st_mtime == mtime_before

    monkeypatch.setattr(git_bundle, "_MAINTENANCE_INTERVAL_SECONDS", 0)
    git_bundle._post_apply_maintenance("maint-b", ws, None)
    assert _ws_calls(calls, ws), "stale stamp must let maintenance run again"


def test_maintenance_repacks_when_many_packs(tmp_path, monkeypatch):
    ws = _build_repo(tmp_path / "ws")
    pack_dir = ws / ".git" / "objects" / "pack"
    pack_dir.mkdir(parents=True, exist_ok=True)
    for i in range(11):
        (ws / f"f{i}.txt").write_text(f"v{i}\n", encoding="utf-8")
        _run_git(ws, "add", ".")
        _run_git(ws, "commit", "-m", f"c{i}")
        _run_git(ws, "repack")
    assert len(list(pack_dir.glob("*.pack"))) > 10

    storage = tmp_path / "storage"
    storage.mkdir()
    monkeypatch.setattr(git_bundle, "repo_manager", _StorageOnlyManager(storage))

    calls = _spy_git(monkeypatch)
    git_bundle._post_apply_maintenance("maint-c", ws, None)

    assert ("repack", "-adq") in _ws_calls(calls, ws)
    assert len(list(pack_dir.glob("*.pack"))) == 1
    assert _stamp(storage, "maint-c").exists()


def test_maintenance_skips_without_repo_manager(tmp_path, monkeypatch):
    ws = _build_repo(tmp_path / "ws")
    monkeypatch.setattr(git_bundle, "repo_manager", None)

    calls = _spy_git(monkeypatch)
    git_bundle._post_apply_maintenance("maint-none", ws, None)

    assert _ws_calls(calls, ws) == []
    assert not (ws / ".git" / "objects" / "info" / "commit-graph").exists()
