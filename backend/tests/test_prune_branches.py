"""
Honest tests for POST /api/documents/prune-branches.

Drives the REAL endpoint against a REAL bare+workspace repo created by
RepoManager. The scratch repo is built with plain git commands and contains:
  - master            (base + HEAD)
  - merged-branch     (commit, then merged into master → ancestor of base)
  - unmerged-branch   (fresh commit, not merged, recent date)
  - old-branch        (fresh commit, not merged, ancient GIT_COMMITTER_DATE)

Contract under test:
  candidates = merged-into-any-existing-base OR older-than-older_days,
               MINUS keep, MINUS HEAD, MINUS anything leaving <1 branch.
  apply=false → dry-run (nothing deleted); apply=true → delete candidates.
"""
import json
import os
import subprocess
import time
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from tests import _harness
from tests.conftest import envelope_post, parse_envelope

PASSWORD = "test-prune-branches-pw"

OLD_DATE = "2001-01-01T00:00:00"


def _run_git(cwd: Path, *args: str, env: dict | None = None) -> subprocess.CompletedProcess:
    full_env = os.environ.copy()
    if env:
        full_env.update(env)
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True,
                          text=True, env=full_env)
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


def _make_client(tmp_path: Path, monkeypatch) -> tuple[TestClient, RepoManager]:
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@example.com"}}),
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
    return TestClient(app), rm


def _make_repo_with_history(tmp_path: Path, monkeypatch) -> tuple[TestClient, str, Path, RepoManager]:
    """Create a backend repo whose bare has master/merged/unmerged/old branches."""
    client, rm = _make_client(tmp_path, monkeypatch)
    repo = f"prunebr-{int(time.time() * 1000)}"
    created = client.post("/api/documents/collection", json={"name": repo})
    assert created.status_code == 200, created.text

    bare = rm._get_bare_path(repo)

    work = tmp_path / "scratch"
    work.mkdir()
    _run_git(work, "init")
    _run_git(work, "config", "user.email", "w@example.com")
    _run_git(work, "config", "user.name", "W")

    # master: base branch
    _run_git(work, "checkout", "-B", "master")
    (work / "f.txt").write_text("init\n", encoding="utf-8")
    _run_git(work, "add", "f.txt")
    _run_git(work, "commit", "-m", "master: init")

    # merged-branch: commit, then merge into master
    _run_git(work, "checkout", "-b", "merged-branch")
    (work / "merged.txt").write_text("merged\n", encoding="utf-8")
    _run_git(work, "add", "merged.txt")
    _run_git(work, "commit", "-m", "merged-branch: work")
    _run_git(work, "checkout", "master")
    _run_git(work, "merge", "--no-edit", "--no-ff", "merged-branch")

    # unmerged-branch: fresh commit, never merged
    _run_git(work, "checkout", "-b", "unmerged-branch")
    (work / "unmerged.txt").write_text("unmerged\n", encoding="utf-8")
    _run_git(work, "add", "unmerged.txt")
    _run_git(work, "commit", "-m", "unmerged-branch: work")

    # old-branch: fresh commit, never merged, ancient committer date
    _run_git(work, "checkout", "-b", "old-branch")
    (work / "old.txt").write_text("old\n", encoding="utf-8")
    _run_git(work, "add", "old.txt")
    _run_git(work, "commit", "-m", "old-branch: work",
             env={"GIT_COMMITTER_DATE": OLD_DATE, "GIT_AUTHOR_DATE": OLD_DATE})

    for b in ("master", "merged-branch", "unmerged-branch", "old-branch"):
        _run_git(work, "push", "--force", str(bare), f"refs/heads/{b}:refs/heads/{b}")

    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/master")
    return client, repo, bare, rm


def _prune(client, repo: str, **payload):
    body = {"repo": repo, "bases": ["master"], "older_days": 0, "keep": [], "apply": False}
    body.update(payload)
    res = envelope_post(client, "/api/documents/prune-branches", body, PASSWORD)
    assert res.status_code == 200, res.text
    return parse_envelope(res.json(), PASSWORD)


# ── dry-run ──────────────────────────────────────────────────────────────────

def test_dry_run_lists_merged_and_old_but_not_unmerged_head_or_keep(tmp_path, monkeypatch):
    client, repo, bare, _ = _make_repo_with_history(tmp_path, monkeypatch)

    inner = _prune(client, repo, older_days=365)

    assert inner["success"] is True
    assert inner["apply"] is False
    assert set(inner["candidates"]) == {"merged-branch", "old-branch"}
    assert inner["pruned"] == []
    assert "unmerged-branch" not in inner["candidates"]
    assert "master" not in inner["candidates"]  # HEAD + keep
    assert "master" in inner["protected"]
    assert "dry-run" in inner["message"]

    # Nothing was deleted
    assert _bare_branches(bare) == {"master", "merged-branch", "unmerged-branch", "old-branch"}


def test_dry_run_without_older_days_ignores_old_branch(tmp_path, monkeypatch):
    """older_days=0 disables the age rule — only merged branches are candidates."""
    client, repo, bare, _ = _make_repo_with_history(tmp_path, monkeypatch)

    inner = _prune(client, repo)  # older_days=0

    assert set(inner["candidates"]) == {"merged-branch"}
    assert _bare_branches(bare) == {"master", "merged-branch", "unmerged-branch", "old-branch"}


# ── apply ────────────────────────────────────────────────────────────────────

def test_apply_deletes_exactly_candidates(tmp_path, monkeypatch):
    client, repo, bare, _ = _make_repo_with_history(tmp_path, monkeypatch)

    inner = _prune(client, repo, older_days=365, apply=True)

    assert inner["success"] is True
    assert inner["apply"] is True
    assert set(inner["pruned"]) == {"merged-branch", "old-branch"}
    assert set(inner["candidates"]) == set(inner["pruned"])
    assert _bare_branches(bare) == {"master", "unmerged-branch"}
    assert "Pruned 2" in inner["message"]


def test_apply_with_no_candidates_deletes_nothing(tmp_path, monkeypatch):
    client, repo, bare, _ = _make_repo_with_history(tmp_path, monkeypatch)

    inner = _prune(client, repo, apply=True)  # older_days=0 → only merged-branch

    assert set(inner["pruned"]) == {"merged-branch"}
    assert _bare_branches(bare) == {"master", "unmerged-branch", "old-branch"}

    # Second run: nothing left to prune
    inner2 = _prune(client, repo, apply=True)
    assert inner2["candidates"] == []
    assert inner2["pruned"] == []
    assert "Nothing to prune" in inner2["message"]


# ── guards ───────────────────────────────────────────────────────────────────

def test_head_branch_is_protected_even_when_it_is_a_base(tmp_path, monkeypatch):
    """master is HEAD and trivially an ancestor of itself (base) — still safe."""
    client, repo, bare, _ = _make_repo_with_history(tmp_path, monkeypatch)

    inner = _prune(client, repo, older_days=365, apply=True)

    assert "master" not in inner["candidates"]
    assert "master" not in inner["pruned"]
    assert "master" in _bare_branches(bare)
    assert "master" in inner["protected"]


def test_keep_list_protects_merged_branch(tmp_path, monkeypatch):
    client, repo, bare, _ = _make_repo_with_history(tmp_path, monkeypatch)

    inner = _prune(client, repo, keep=["merged-branch"], apply=True)

    assert "merged-branch" not in inner["candidates"]
    assert "merged-branch" not in inner["pruned"]
    assert "merged-branch" in inner["protected"]
    assert "merged-branch" in _bare_branches(bare)


def test_refuses_to_delete_last_branch(tmp_path, monkeypatch):
    """Single branch that would be a candidate (HEAD points elsewhere) → guard."""
    client, rm = _make_client(tmp_path, monkeypatch)
    repo = f"prunelast-{int(time.time() * 1000)}"
    assert client.post("/api/documents/collection", json={"name": repo}).status_code == 200
    bare = rm._get_bare_path(repo)

    # Remove every branch the auto-create made, push exactly one, and leave
    # bare HEAD pointing at a (now nonexistent) master.
    for b in sorted(_bare_branches(bare)):
        _run_git(bare, "update-ref", "-d", f"refs/heads/{b}")
    work = tmp_path / "scratch-last"
    work.mkdir()
    _run_git(work, "init")
    _run_git(work, "config", "user.email", "w@example.com")
    _run_git(work, "config", "user.name", "W")
    _run_git(work, "checkout", "-B", "feature")
    (work / "f.txt").write_text("x\n", encoding="utf-8")
    _run_git(work, "add", "f.txt")
    _run_git(work, "commit", "-m", "feature: init")
    _run_git(work, "push", "--force", str(bare), "refs/heads/feature:refs/heads/feature")
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/master")  # dangling on purpose

    assert _bare_branches(bare) == {"feature"}

    # feature is an ancestor of itself (base=feature) → would be a candidate,
    # but deleting it would leave zero branches → guard must fire.
    inner = _prune(client, repo, bases=["feature"], apply=True)

    assert inner["candidates"] == []
    assert inner["pruned"] == []
    assert "no branches" in inner["message"]
    assert _bare_branches(bare) == {"feature"}


# ── validation & edge cases ──────────────────────────────────────────────────

def test_nonexistent_bases_are_ignored(tmp_path, monkeypatch):
    client, repo, bare, _ = _make_repo_with_history(tmp_path, monkeypatch)

    inner = _prune(client, repo, bases=["no-such-base", "master"], older_days=365)

    assert set(inner["candidates"]) == {"merged-branch", "old-branch"}


def test_unknown_repo_404(tmp_path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    res = envelope_post(client, "/api/documents/prune-branches",
                        {"repo": "no-such-repo", "bases": ["master"]}, PASSWORD)
    assert res.status_code == 404, res.text


def test_invalid_branch_name_in_keep_or_bases_400(tmp_path, monkeypatch):
    client, repo, _, _ = _make_repo_with_history(tmp_path, monkeypatch)
    for bad_field, bad in (("keep", ["bad name"]), ("bases", ["../escape"]),
                           ("keep", ["with~tilde"])):
        res = envelope_post(client, "/api/documents/prune-branches",
                            {"repo": repo, "bases": ["master"], bad_field: bad}, PASSWORD)
        assert res.status_code == 400, (
            f"expected 400 for {bad_field}={bad!r}, got {res.status_code}: {res.text}"
        )


def test_default_keep_applies_when_keep_empty(tmp_path, monkeypatch):
    """Empty keep → default [master, develop, plan_fix]; master survives apply."""
    client, repo, bare, _ = _make_repo_with_history(tmp_path, monkeypatch)
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/unmerged-branch")

    inner = _prune(client, repo, older_days=365, apply=True)

    # master is no longer HEAD, but the default keep list protects it.
    assert "master" not in inner["pruned"]
    assert "master" in inner["protected"]
    assert "master" in _bare_branches(bare)
    # HEAD is protected too
    assert "unmerged-branch" not in inner["pruned"]
    assert "unmerged-branch" in inner["protected"]
