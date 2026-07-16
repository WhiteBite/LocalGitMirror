"""
Tests for auto-prune of stale branches during push.

When the plugin sends `local_branches` in the envelope, the server removes
branches from Mirror that:
  - exist on Mirror bare repo
  - do NOT exist in the client's local_branches list
  - are NOT the current HEAD of the bare repo

Safety guards:
  - HEAD is never pruned
  - If pruning would leave zero branches, nothing is pruned
  - Without `local_branches` in params, no prune happens (backward compat)
"""
import json
import subprocess
import time
from pathlib import Path

from fastapi.testclient import TestClient

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
    assert client.post("/api/repos/create", json={"name": repo_name}).status_code == 200

    # Use a scratch dir to create branches and push to bare
    bare = rm._get_bare_path(repo_name)
    ws = storage / repo_name

    for br in branches:
        _run_git(ws, "checkout", "-B", br)
        (ws / f"{br}.txt").write_text(f"{br}\n")
        _run_git(ws, "add", ".")
        _run_git(ws, "commit", "-m", f"commit on {br}")
        # Push to bare
        _run_git(ws, "push", "--force", str(bare), f"refs/heads/{br}:refs/heads/{br}")

    # Set HEAD to first branch
    _run_git(bare, "symbolic-ref", "HEAD", f"refs/heads/{branches[0]}")
    _run_git(ws, "checkout", branches[0])


# ─────────────────────────────────────────────────────────────────────────────
# Tests via /documents/link (apply-known) — the pointer-only path
# ─────────────────────────────────────────────────────────────────────────────

def test_prune_removes_stale_branches_on_apply_known(tmp_path, monkeypatch):
    """Branches not in local_branches get pruned after apply-known."""
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"prune-ak-{int(time.time())}"
    _create_repo_with_branches(client, storage, rm, repo, ["main", "feature", "old-spike", "dead-code"])

    bare = rm._get_bare_path(repo)
    # /repos/create also creates a 'master' branch; our branches are pushed on top
    assert {"main", "feature", "old-spike", "dead-code"}.issubset(_bare_branches(bare))

    # Client says it only has main + feature locally
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

    # old-spike and dead-code should be pruned; master too (it's not in local_branches and not HEAD)
    pruned = set(inner.get("pruned", []))
    assert "old-spike" in pruned
    assert "dead-code" in pruned
    remaining = _bare_branches(bare)
    assert "main" in remaining
    assert "feature" in remaining
    assert "old-spike" not in remaining
    assert "dead-code" not in remaining


def test_prune_never_removes_head(tmp_path, monkeypatch):
    """HEAD branch is always protected even if not in local_branches."""
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"prune-head-{int(time.time())}"
    _create_repo_with_branches(client, storage, rm, repo, ["main", "feature"])

    bare = rm._get_bare_path(repo)
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/main")

    # Client says it only has feature — main is HEAD on server
    head = _run_git(bare, "rev-parse", "refs/heads/feature").stdout.strip()
    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": head,
        "branches": {"feature": head},
        "local_branches": ["feature"],
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner["success"] is True

    # main must NOT be pruned (it's HEAD)
    assert "main" not in inner.get("pruned", [])
    assert "main" in _bare_branches(bare)
    assert "feature" in _bare_branches(bare)


def test_prune_does_nothing_without_local_branches(tmp_path, monkeypatch):
    """Backward compat: no local_branches field → no pruning."""
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"prune-nofield-{int(time.time())}"
    _create_repo_with_branches(client, storage, rm, repo, ["main", "stale"])

    bare = rm._get_bare_path(repo)
    head = _run_git(bare, "rev-parse", "refs/heads/main").stdout.strip()

    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": head,
        "branches": {"main": head},
        # no local_branches!
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner["success"] is True
    assert inner.get("pruned") is None or inner.get("pruned") == []

    # stale must still be there
    assert "stale" in _bare_branches(bare)


def test_prune_wont_delete_last_branch(tmp_path, monkeypatch):
    """Safety: if pruning would leave zero branches, skip entirely."""
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"prune-last-{int(time.time())}"
    _create_repo_with_branches(client, storage, rm, repo, ["only"])

    bare = rm._get_bare_path(repo)
    head = _run_git(bare, "rev-parse", "refs/heads/only").stdout.strip()

    # Client has NO branches in local_branches — extreme case
    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": head,
        "branches": {"only": head},
        "local_branches": [],
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner["success"] is True
    # "only" is HEAD so it's protected, but even if it weren't,
    # the "never leave zero branches" guard would save it.
    # Note: /repos/create also creates 'master', which is also protected (not in local_branches
    # but the guard protects HEAD='only', and 'master' is not the only branch).
    # Key assertion: at least "only" still exists.
    assert "only" in _bare_branches(bare)


# ─────────────────────────────────────────────────────────────────────────────
# Tests via /documents/upload — the full bundle path
# ─────────────────────────────────────────────────────────────────────────────

def test_prune_works_on_upload(tmp_path, monkeypatch):
    """Auto-prune also fires after a successful upload-and-apply."""
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"prune-upload-{int(time.time())}"
    _create_repo_with_branches(client, storage, rm, repo, ["main", "old-branch"])

    bare = rm._get_bare_path(repo)
    ws = storage / repo
    assert "old-branch" in _bare_branches(bare)

    # Create a real encrypted dump to upload
    from app.core.bundle_crypto import encrypt_bundle_to_dump
    import tempfile

    # Create a minimal bundle from workspace
    bundle_path = tmp_path / "test.bundle"
    _run_git(ws, "bundle", "create", str(bundle_path), "--all")
    dump_path = tmp_path / "test.bin"
    encrypt_bundle_to_dump(bundle_path, dump_path, PASSWORD)

    # Upload with local_branches = ["main"] only
    resp = envelope_form_post(
        client, "/api/documents/upload",
        {"repo": repo, "local_branches": ["main"]},
        PASSWORD,
        files={"attachment": ("document.bin", dump_path.read_bytes(), "application/octet-stream")},
    )
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner.get("success") is True

    # old-branch should be pruned
    assert "old-branch" in inner.get("pruned", [])
    assert "old-branch" not in _bare_branches(bare)
    assert "main" in _bare_branches(bare)
