"""
Tests for branch pruning on the Mirror.

Contract since the auto-prune removal: stealth sync endpoints (apply-known,
upload-and-apply) never delete mirror branches from the sender's partial
local view — a sender that never pulled a branch would otherwise prune it.
Pruning is explicit via /documents/prune-branches (dry-run or apply), which
protects the bare HEAD and refuses to empty the repo.
"""
import json
import subprocess
import time
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.bundle_crypto import encrypt_bundle_to_dump
from app.core.repo_manager import RepoManager
from tests import _harness
from tests.conftest import envelope_form_post, envelope_post, parse_envelope

PASSWORD = "test-prune-pw"


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} failed: {proc.stderr}")
    return proc


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


def _bare_branches(bare: Path) -> set:
    proc = subprocess.run(
        ["git", "for-each-ref", "--format=%(refname:short)", "refs/heads"],
        cwd=str(bare), capture_output=True, text=True,
    )
    return {b.strip() for b in proc.stdout.splitlines() if b.strip()}


def _create_repo_with_branches(client, storage, rm, repo_name: str, branches: list[str]):
    """Create repo and push multiple branches into bare."""
    assert client.post("/api/documents/collection", json={"name": repo_name}).status_code == 200

    bare = rm._get_bare_path(repo_name)
    ws = storage / repo_name

    for br in branches:
        _run_git(ws, "checkout", "-B", br)
        (ws / f"{br}.txt").write_text(f"{br}\n")
        _run_git(ws, "add", ".")
        _run_git(ws, "commit", "-m", f"commit on {br}")
        _run_git(ws, "push", "--force", str(bare), f"refs/heads/{br}:refs/heads/{br}")

    _run_git(bare, "symbolic-ref", "HEAD", f"refs/heads/{branches[0]}")
    _run_git(ws, "checkout", branches[0])


# Stealth endpoints never prune

def test_apply_known_does_not_prune_stale_branches(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"prune-ak-{int(time.time())}"
    _create_repo_with_branches(client, storage, rm, repo, ["main", "feature", "old-spike", "dead-code"])

    bare = rm._get_bare_path(repo)
    head = _run_git(bare, "rev-parse", "refs/heads/main").stdout.strip()
    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": head,
        "branches": {"main": head},
        "local_branches": ["main", "feature"],
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner["success"] is True

    remaining = _bare_branches(bare)
    assert {"main", "feature", "old-spike", "dead-code"}.issubset(remaining)
    assert inner.get("pruned") is None


def test_upload_does_not_prune(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"prune-upload-{int(time.time())}"
    _create_repo_with_branches(client, storage, rm, repo, ["main", "old-branch"])

    bare = rm._get_bare_path(repo)
    ws = storage / repo

    bundle_path = tmp_path / "test.bundle"
    _run_git(ws, "bundle", "create", str(bundle_path), "--all")
    dump_path = tmp_path / "test.bin"
    encrypt_bundle_to_dump(bundle_path, dump_path, PASSWORD)

    resp = envelope_form_post(
        client, "/api/documents/upload",
        {"repo": repo, "local_branches": ["main"]},
        PASSWORD,
        files={"attachment": ("document.bin", dump_path.read_bytes(), "application/octet-stream")},
    )
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner.get("success") is True

    assert "old-branch" in _bare_branches(bare)
    assert inner.get("pruned") is None


# Explicit /documents/prune-branches

def _make_merged_repo(client, storage, rm, repo: str):
    """main (HEAD) with a merged-in branch and a live unmerged branch."""
    _create_repo_with_branches(client, storage, rm, repo, ["main"])
    ws = storage / repo
    bare = rm._get_bare_path(repo)

    _run_git(ws, "checkout", "-b", "merged-branch")
    (ws / "merged.txt").write_text("m\n")
    _run_git(ws, "add", ".")
    _run_git(ws, "commit", "-m", "merged work")
    _run_git(ws, "push", "--force", str(bare), "refs/heads/merged-branch:refs/heads/merged-branch")

    _run_git(ws, "checkout", "main")
    _run_git(ws, "merge", "--no-ff", "-m", "merge merged-branch", "merged-branch")
    (ws / "later.txt").write_text("l\n")
    _run_git(ws, "add", ".")
    _run_git(ws, "commit", "-m", "main moves ahead")
    _run_git(ws, "push", "--force", str(bare), "refs/heads/main:refs/heads/main")

    _run_git(ws, "checkout", "-b", "unmerged-branch")
    (ws / "unmerged.txt").write_text("u\n")
    _run_git(ws, "add", ".")
    _run_git(ws, "commit", "-m", "parallel work")
    _run_git(ws, "push", "--force", str(bare), "refs/heads/unmerged-branch:refs/heads/unmerged-branch")
    _run_git(ws, "checkout", "main")
    return bare


def test_manual_prune_dry_run_deletes_nothing(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"prune-dry-{int(time.time())}"
    bare = _make_merged_repo(client, storage, rm, repo)

    resp = envelope_post(client, "/api/documents/prune-branches", {
        "repo": repo, "bases": ["main"], "apply": False,
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner["success"] is True
    assert "merged-branch" in inner.get("candidates", [])

    assert {"main", "merged-branch", "unmerged-branch"} == _bare_branches(bare) - {"master"}


def test_manual_prune_apply_deletes_merged_only(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"prune-apply-{int(time.time())}"
    bare = _make_merged_repo(client, storage, rm, repo)

    resp = envelope_post(client, "/api/documents/prune-branches", {
        "repo": repo, "bases": ["main"], "apply": True,
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner["success"] is True
    assert "merged-branch" in inner.get("pruned", [])

    remaining = _bare_branches(bare)
    assert "merged-branch" not in remaining
    assert "unmerged-branch" in remaining
    assert "main" in remaining


def test_manual_prune_protects_head(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"prune-head-{int(time.time())}"
    bare = _make_merged_repo(client, storage, rm, repo)

    # HEAD is main; even with main as a base and apply on, HEAD survives
    resp = envelope_post(client, "/api/documents/prune-branches", {
        "repo": repo, "bases": ["main", "unmerged-branch"], "apply": True,
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner["success"] is True

    remaining = _bare_branches(bare)
    assert "main" in remaining
