"""apply-known fast path: one batched existence check, one push for all
branches. Locks the per-branch fork/push restructure of /api/documents/link:
cat-file -e per hash and one push per refspec regressed the endpoint on
multi-branch syncs."""
import json
import subprocess
import time
from pathlib import Path

from fastapi.testclient import TestClient

import app.routers.sync as sync_mod
from app.core import git_bundle
from app.core.git_bundle import _git as real_git
from app.core.repo_lock import repo_lock
from app.core.repo_manager import RepoManager
from tests import _harness
from tests.conftest import envelope_post, parse_envelope

PASSWORD = "ak-batch-pw"


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} failed: {proc.stderr}")
    return proc


def _git_rc(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)


def _make_client(tmp_path: Path, monkeypatch):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)

    rm = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=rm,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    return TestClient(app), storage, rm


def _spy_git(monkeypatch):
    calls: list[tuple[str, tuple]] = []

    def spy(cwd, *args, timeout=600):
        calls.append((str(cwd), args))
        return real_git(cwd, *args, timeout=timeout)

    monkeypatch.setattr(sync_mod, "_git", spy)
    return calls


def _create_repo(client, storage, rm, repo_name: str) -> tuple[Path, Path]:
    assert client.post("/api/documents/collection", json={"name": repo_name}).status_code == 200
    return storage / repo_name, rm._get_bare_path(repo_name)


def _commit_file(ws: Path, branch: str, filename: str, content: str) -> str:
    _run_git(ws, "checkout", "-B", branch)
    (ws / filename).write_text(content, encoding="utf-8")
    _run_git(ws, "add", filename)
    _run_git(ws, "commit", "-m", f"{branch}: {filename}")
    return _run_git(ws, "rev-parse", "HEAD").stdout.strip()


def test_apply_known_lands_all_branches_with_single_push(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"ak-batch-{int(time.time() * 1000)}"
    ws, bare = _create_repo(client, storage, rm, repo)

    main_hash = _commit_file(ws, "main", "a.txt", "a\n")
    feat_a = _commit_file(ws, "feat-a", "b.txt", "b\n")
    feat_b = _commit_file(ws, "feat-b", "c.txt", "c\n")
    _run_git(ws, "checkout", "main")

    calls = _spy_git(monkeypatch)
    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": main_hash,
        "branches": {"main": main_hash, "feat-a": feat_a, "feat-b": feat_b},
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)

    assert inner["success"] is True, inner
    assert set(inner) == {"success", "repo", "commit", "branches", "message"}
    assert inner["branches"] == ["main", "feat-a", "feat-b"]

    pushes = [c for c in calls if c[1][:1] == ("push",)]
    assert len(pushes) == 1, f"expected a single push, got {pushes}"
    assert pushes[0][1][:3] == ("push", "--force", str(bare))
    assert pushes[0][1][3:] == (
        "refs/lgm/ak/main:refs/heads/main",
        "refs/lgm/ak/feat-a:refs/heads/feat-a",
        "refs/lgm/ak/feat-b:refs/heads/feat-b",
    )
    assert [c for c in calls if "cat-file" in c[1]] == []

    for branch, expected in (("main", main_hash), ("feat-a", feat_a), ("feat-b", feat_b)):
        assert _run_git(bare, "rev-parse", f"refs/heads/{branch}").stdout.strip() == expected
        assert _run_git(ws, "rev-parse", f"refs/heads/{branch}").stdout.strip() == expected


def test_apply_known_excludes_branch_with_unknown_hash(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"ak-ghost-{int(time.time() * 1000)}"
    ws, bare = _create_repo(client, storage, rm, repo)

    main_hash = _commit_file(ws, "main", "a.txt", "a\n")
    ghost_hash = "f" * 40

    calls = _spy_git(monkeypatch)
    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": main_hash,
        "branches": {"main": main_hash, "ghost": ghost_hash},
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)

    assert inner["success"] is True, inner
    assert inner["branches"] == ["main"]

    pushes = [c for c in calls if c[1][:1] == ("push",)]
    assert len(pushes) == 1
    assert pushes[0][1][3:] == ("refs/lgm/ak/main:refs/heads/main",)
    fetches = [c for c in calls if c[1][:1] == ("fetch",)]
    assert len(fetches) == 1, "missing hash must trigger the bare-fetch fallback"

    assert _git_rc(bare, "rev-parse", "--verify", "refs/heads/ghost").returncode != 0
    assert _git_rc(ws, "rev-parse", "--verify", "refs/heads/ghost").returncode != 0


def test_apply_known_rejected_ref_is_not_counted_as_pushed(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"ak-reject-{int(time.time() * 1000)}"
    ws, bare = _create_repo(client, storage, rm, repo)

    old_main = _commit_file(ws, "main", "a.txt", "a\n")
    newer = _commit_file(ws, "pusher", "b.txt", "b\n")
    _run_git(ws, "checkout", "main")
    _run_git(ws, "push", "--force", str(bare), "refs/heads/pusher:refs/heads/main")
    _run_git(bare, "config", "receive.denyNonFastForwards", "true")
    feat = _commit_file(ws, "feat", "c.txt", "c\n")
    _run_git(ws, "checkout", "main")

    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": old_main,
        "branches": {"main": old_main, "feat": feat},
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)

    assert inner["success"] is True, inner
    assert inner["branches"] == ["feat"], "non-fast-forward main must not be counted pushed"
    assert _run_git(bare, "rev-parse", "refs/heads/main").stdout.strip() == newer
    assert _run_git(bare, "rev-parse", "refs/heads/feat").stdout.strip() == feat


def test_apply_known_holds_repo_lock_during_mutation(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"ak-lock-{int(time.time() * 1000)}"
    ws, bare = _create_repo(client, storage, rm, repo)
    main_hash = _commit_file(ws, "main", "a.txt", "a\n")

    monkeypatch.setattr(sync_mod, "_post_apply_maintenance", lambda *a: None)
    lock_seen: list[tuple[tuple, bool]] = []

    def spy(cwd, *args, timeout=600):
        acquired = repo_lock(repo).acquire(blocking=False)
        if acquired:
            repo_lock(repo).release()
        lock_seen.append((args, not acquired))
        return real_git(cwd, *args, timeout=timeout)

    monkeypatch.setattr(sync_mod, "_git", spy)
    monkeypatch.setattr(git_bundle, "_git", spy)

    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": main_hash,
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner["success"] is True, inner

    assert lock_seen, "expected git calls during apply-known"
    mutations = [
        entry for entry in lock_seen
        if entry[0][:1] in (("update-ref",), ("push",), ("fetch",), ("reset",), ("checkout",), ("clean",))
    ]
    assert mutations, "expected mutation git calls during apply-known"
    assert all(held for _, held in lock_seen), "git ran outside the repo lock during apply-known"

    lock = repo_lock(repo)
    assert lock.acquire(blocking=False) is True, "lock must be released after apply-known"
    lock.release()
