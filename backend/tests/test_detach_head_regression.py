"""Regression tests: stealth sync must never leave the workspace HEAD detached.

Bug class (recurred on both machines): upload-and-apply and apply-known used
`git checkout --detach` on the storage workspace and re-attached afterwards.
Two defects made the detach stick:

  A) apply-known computed `preferred = primary_branch or ...` where
     primary_branch is the literal string "HEAD" on a detached workspace —
     truthy, so `checkout -f HEAD` re-detached forever (self-sustaining).
  B) upload-and-apply returned early on fetch/prerequisite errors BEFORE the
     re-attach step, leaving the workspace detached.

Invariants these tests lock:
  * After any /api/documents/link (apply-known) call — success OR failure —
    the workspace HEAD is attached to a branch.
  * After any /api/documents/upload (upload-and-apply) call — success OR
    failure — the workspace HEAD is attached to a branch.
  * A workspace that is ALREADY detached gets healed back onto a branch.
  * The working tree always matches the attached branch tip.
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

PASSWORD = "detach-regression-pw"


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} failed: {proc.stderr}")
    return proc


def _git_rc(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)


def _head_branch(cwd: Path) -> str:
    """Attached branch name, or "" when HEAD is detached."""
    proc = _git_rc(cwd, "symbolic-ref", "--short", "-q", "HEAD")
    return proc.stdout.strip() if proc.returncode == 0 else ""


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


def _create_repo(client, storage, rm, repo_name: str) -> Path:
    assert client.post("/api/documents/collection", json={"name": repo_name}).status_code == 200
    ws = storage / repo_name
    assert ws.exists()
    return ws


def _commit_file(ws: Path, branch: str, filename: str, content: str) -> str:
    _run_git(ws, "checkout", "-B", branch)
    (ws / filename).write_text(content, encoding="utf-8")
    _run_git(ws, "add", filename)
    _run_git(ws, "commit", "-m", f"{branch}: {filename}")
    return _run_git(ws, "rev-parse", "HEAD").stdout.strip()


# Defect A: apply-known must heal an already-detached workspace HEAD

def test_apply_known_heals_detached_workspace_head(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"detach-ak-{int(time.time() * 1000)}"
    ws = _create_repo(client, storage, rm, repo)
    bare = rm._get_bare_path(repo)

    main_hash = _commit_file(ws, "main", "a.txt", "a\n")
    _run_git(ws, "push", "--force", str(bare), "refs/heads/main:refs/heads/main")
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/main")

    # Simulate the stuck state this bug class leaves behind.
    _run_git(ws, "checkout", "--detach")
    assert _head_branch(ws) == "", "precondition: workspace must start detached"

    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": main_hash,
        "branches": {"main": main_hash},
    }, PASSWORD)
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner["success"] is True, inner

    healed = _head_branch(ws)
    assert healed == "main", f"workspace HEAD still detached after apply-known (branch={healed!r})"
    assert _run_git(ws, "rev-parse", "HEAD").stdout.strip() == main_hash


def test_apply_known_keeps_head_attached_and_updates_worktree(tmp_path, monkeypatch):
    """Pointer move of the CURRENT branch: HEAD stays attached, tree follows."""
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"detach-ak2-{int(time.time() * 1000)}"
    ws = _create_repo(client, storage, rm, repo)
    bare = rm._get_bare_path(repo)

    _commit_file(ws, "main", "a.txt", "a\n")
    # Sender's newer commit — create it on a side ref so main stays behind.
    new_hash = _commit_file(ws, "incoming-work", "b.txt", "b\n")
    _run_git(ws, "checkout", "main")
    _run_git(ws, "push", "--force", str(bare), "refs/heads/main:refs/heads/main")
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/main")

    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": new_hash,
        "branches": {"main": new_hash},
    }, PASSWORD)
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner["success"] is True, inner

    assert _head_branch(ws) == "main", "HEAD must stay attached to main"
    assert _run_git(ws, "rev-parse", "refs/heads/main").stdout.strip() == new_hash
    assert (ws / "b.txt").read_text(encoding="utf-8") == "b\n", "working tree must follow the new tip"
    assert _run_git(bare, "rev-parse", "refs/heads/main").stdout.strip() == new_hash


# Defect B: upload-and-apply failure paths must not leave HEAD detached

def test_upload_apply_prerequisite_failure_leaves_head_attached(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"detach-ua-{int(time.time() * 1000)}"
    ws = _create_repo(client, storage, rm, repo)

    attached_before = _head_branch(ws)
    assert attached_before != "", "precondition: fresh workspace must be attached"

    # Work machine with history the server has never seen; bundle with a
    # prerequisite commit absent on the server → fetch must fail.
    work = tmp_path / "work"
    work.mkdir()
    _run_git(work, "init")
    _run_git(work, "config", "user.email", "work@example.com")
    _run_git(work, "config", "user.name", "Work User")
    _run_git(work, "checkout", "-B", "feature")
    (work / "base.txt").write_text("base\n", encoding="utf-8")
    _run_git(work, "add", "base.txt")
    _run_git(work, "commit", "-m", "base (prerequisite)")
    base_hash = _run_git(work, "rev-parse", "HEAD").stdout.strip()
    (work / "tip.txt").write_text("tip\n", encoding="utf-8")
    _run_git(work, "add", "tip.txt")
    _run_git(work, "commit", "-m", "tip")

    bundle = tmp_path / "prereq.bundle"
    _run_git(work, "bundle", "create", str(bundle), f"^{base_hash}", "feature")

    dump = tmp_path / f"dump_{repo}_prereq.dmp"
    encrypt_bundle_to_dump(bundle, dump, PASSWORD)

    resp = envelope_form_post(
        client, "/api/documents/upload", {"repo": repo}, PASSWORD,
        files={"attachment": (dump.name, dump.read_bytes(), "application/octet-stream")},
    )
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner.get("success") is False, f"expected prerequisite failure, got {inner}"

    assert _head_branch(ws) == attached_before, (
        f"workspace HEAD detached by failed upload-and-apply "
        f"(was {attached_before!r}, now {_head_branch(ws)!r})"
    )


def test_upload_apply_success_attaches_preferred_branch_and_updates_tree(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"detach-ua2-{int(time.time() * 1000)}"
    ws = _create_repo(client, storage, rm, repo)
    bare = rm._get_bare_path(repo)

    # Work machine: master + feature (feature is the sender's current branch,
    # listed first in the bundle → preferred).
    work = tmp_path / "work2"
    work.mkdir()
    _run_git(work, "init")
    _run_git(work, "config", "user.email", "work@example.com")
    _run_git(work, "config", "user.name", "Work User")
    _run_git(work, "checkout", "-B", "master")
    (work / "readme.txt").write_text("initial\n", encoding="utf-8")
    _run_git(work, "add", "readme.txt")
    _run_git(work, "commit", "-m", "master initial")
    master_hash = _run_git(work, "rev-parse", "HEAD").stdout.strip()
    _run_git(work, "checkout", "-b", "feature-xyz")
    (work / "feature.txt").write_text("feature work\n", encoding="utf-8")
    _run_git(work, "add", "feature.txt")
    _run_git(work, "commit", "-m", "feature work")
    feature_hash = _run_git(work, "rev-parse", "HEAD").stdout.strip()

    bundle = tmp_path / "work2.bundle"
    _run_git(work, "bundle", "create", str(bundle), "feature-xyz", "master")
    dump = tmp_path / f"dump_{repo}_ok.dmp"
    encrypt_bundle_to_dump(bundle, dump, PASSWORD)

    resp = envelope_form_post(
        client, "/api/documents/upload", {"repo": repo}, PASSWORD,
        files={"attachment": (dump.name, dump.read_bytes(), "application/octet-stream")},
    )
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner.get("success") is True, inner

    attached = _head_branch(ws)
    assert attached != "", "workspace HEAD detached after successful upload-and-apply"
    assert set(inner.get("branches", [])) >= {"master", "feature-xyz"}

    # Both refs landed in workspace and bare.
    assert _run_git(ws, "rev-parse", "refs/heads/feature-xyz").stdout.strip() == feature_hash
    assert _run_git(ws, "rev-parse", "refs/heads/master").stdout.strip() == master_hash
    assert _run_git(bare, "rev-parse", "refs/heads/feature-xyz").stdout.strip() == feature_hash
    assert _run_git(bare, "rev-parse", "refs/heads/master").stdout.strip() == master_hash

    # Working tree matches the attached branch tip.
    tree_hash = _run_git(ws, "rev-parse", "HEAD").stdout.strip()
    assert tree_hash == _run_git(ws, "rev-parse", f"refs/heads/{attached}").stdout.strip()
    status = _git_rc(ws, "status", "--porcelain").stdout.strip()
    assert status == "", f"workspace dirty after apply: {status}"
