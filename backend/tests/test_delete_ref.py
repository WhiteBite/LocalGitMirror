"""
Honest tests for branch management on the Mirror:
  - POST /api/documents/delete-ref
  - GET  /api/documents/list   (enriched: sha / updated / is_head)

These drive the REAL endpoint against a REAL bare+workspace repo created by
RepoManager, so they catch actual behavioural bugs (guard logic, ref removal,
metadata shape) rather than re-asserting mocks.
"""
import json
import subprocess
import time
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from tests import _harness


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(
            f"git {' '.join(args)} failed\ncwd={cwd}\nexit={proc.returncode}\n"
            f"stdout={proc.stdout}\nstderr={proc.stderr}"
        )
    return proc


def _bare_branches(bare: Path) -> set:
    proc = subprocess.run(
        ["git", "for-each-ref", "--format=%(refname:short)", "refs/heads"],
        cwd=str(bare), capture_output=True, text=True,
    )
    return {b.strip() for b in proc.stdout.splitlines() if b.strip()}


def _build_client(storage: Path) -> TestClient:
    repo_manager = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=repo_manager,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    return TestClient(app), repo_manager


def _make_repo_with_branches(tmp_path: Path, branches: list[str]) -> tuple[TestClient, str, Path, Path, RepoManager]:
    """Create a backend repo and push the given branches into its bare repo."""
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@example.com"}}),
        encoding="utf-8",
    )
    client, rm = _build_client(storage)
    repo = f"delref-{int(time.time()*1000)}"
    created = client.post("/api/repos/create", json={"name": repo})
    assert created.status_code == 200, created.text

    bare = rm._get_bare_path(repo)

    # Build branches in a scratch repo and push them into the bare.
    work = tmp_path / "scratch"
    work.mkdir()
    _run_git(work, "init")
    _run_git(work, "config", "user.email", "w@example.com")
    _run_git(work, "config", "user.name", "W")
    first = branches[0]
    _run_git(work, "checkout", "-B", first)
    (work / "f.txt").write_text("init\n", encoding="utf-8")
    _run_git(work, "add", "f.txt")
    _run_git(work, "commit", "-m", f"{first}: init")
    for b in branches[1:]:
        _run_git(work, "checkout", "-b", b)
        (work / f"{b}.txt").write_text(f"{b}\n", encoding="utf-8")
        _run_git(work, "add", f"{b}.txt")
        _run_git(work, "commit", "-m", f"{b}: work")
    for b in branches:
        _run_git(work, "push", "--force", str(bare), f"refs/heads/{b}:refs/heads/{b}")

    return client, repo, bare, work, rm


# ── /documents/list enrichment ──────────────────────────────────────────────

def test_list_returns_sha_updated_and_is_head(tmp_path):
    client, repo, bare, _, _ = _make_repo_with_branches(tmp_path, ["master", "feature"])
    # Point bare HEAD at master so is_head is deterministic.
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/master")

    res = client.get("/api/documents/list", params={"repo": repo})
    assert res.status_code == 200, res.text
    body = res.json()
    refs = body["refs"]

    assert set(refs.keys()) == {"master", "feature"}
    for name, info in refs.items():
        assert isinstance(info, dict), f"ref '{name}' must be an object, got {info!r}"
        assert len(info["sha"]) == 40, f"sha should be full hash: {info}"
        assert info["updated"], f"updated date must be present: {info}"
        assert "is_head" in info

    assert refs["master"]["is_head"] is True
    assert refs["feature"]["is_head"] is False


# ── delete-ref: happy path ───────────────────────────────────────────────────

def test_delete_ref_removes_branch_from_bare(tmp_path):
    client, repo, bare, _, _ = _make_repo_with_branches(tmp_path, ["master", "stale"])
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/master")

    assert "stale" in _bare_branches(bare)
    res = client.post("/api/documents/delete-ref", json={"repo": repo, "branch": "stale"})
    assert res.status_code == 200, res.text
    assert res.json()["success"] is True
    assert "stale" not in _bare_branches(bare)
    assert "master" in _bare_branches(bare)


# ── delete-ref: guards ───────────────────────────────────────────────────────

def test_delete_ref_refuses_head_branch(tmp_path):
    client, repo, bare, _, _ = _make_repo_with_branches(tmp_path, ["master", "feature"])
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/master")

    res = client.post("/api/documents/delete-ref", json={"repo": repo, "branch": "master"})
    assert res.status_code == 409, res.text
    # master must still be there
    assert "master" in _bare_branches(bare)


def test_delete_ref_refuses_last_branch(tmp_path):
    client, repo, bare, _, _ = _make_repo_with_branches(tmp_path, ["only"])
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/only")

    res = client.post("/api/documents/delete-ref", json={"repo": repo, "branch": "only"})
    assert res.status_code == 409, res.text
    assert "only" in _bare_branches(bare)


def test_delete_ref_unknown_branch_404(tmp_path):
    client, repo, bare, _, _ = _make_repo_with_branches(tmp_path, ["master", "feature"])
    res = client.post("/api/documents/delete-ref", json={"repo": repo, "branch": "does-not-exist"})
    assert res.status_code == 404, res.text


def test_delete_ref_invalid_branch_name_400(tmp_path):
    client, repo, bare, _, _ = _make_repo_with_branches(tmp_path, ["master", "feature"])
    for bad in ["../escape", "bad name", "with~tilde", ".."]:
        res = client.post("/api/documents/delete-ref", json={"repo": repo, "branch": bad})
        assert res.status_code == 400, f"expected 400 for {bad!r}, got {res.status_code}: {res.text}"


def test_delete_ref_unknown_repo_404(tmp_path):
    client, repo, bare, _, _ = _make_repo_with_branches(tmp_path, ["master", "feature"])
    res = client.post("/api/documents/delete-ref", json={"repo": "no-such-repo", "branch": "feature"})
    assert res.status_code == 404, res.text


def test_delete_ref_then_list_no_longer_shows_it(tmp_path):
    """End-to-end: the deleted branch disappears from /documents/list."""
    client, repo, bare, _, _ = _make_repo_with_branches(tmp_path, ["master", "feature", "old"])
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/master")

    before = client.get("/api/documents/list", params={"repo": repo}).json()["refs"]
    assert "old" in before

    res = client.post("/api/documents/delete-ref", json={"repo": repo, "branch": "old"})
    assert res.status_code == 200, res.text

    after = client.get("/api/documents/list", params={"repo": repo}).json()["refs"]
    assert "old" not in after
    assert set(after.keys()) == {"master", "feature"}
