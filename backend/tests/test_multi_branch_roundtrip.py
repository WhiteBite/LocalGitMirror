"""
E2E test: Multi-branch sync roundtrip.

Scenario:
  1) Backend creates repo with initial commit on 'master'
  2) "Work" machine has 'master' + 'feature-xyz' branches with different commits
  3) Work machine creates an encrypted bundle with BOTH branches
  4) Work uploads the bundle via upload-and-apply
  5) Backend workspace should have BOTH branches
  6) Backend bare repo should have BOTH branches
  7) Home machine calls export-dump → receives bundle with BOTH branches
  8) Home machine applies the bundle → gets BOTH branches locally
"""
import json
import subprocess
import time
import tempfile
from pathlib import Path

import pytest

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from app.core.stealth_crypto import encrypt_bundle_to_dump, decrypt_dump_to_bundle
from app.routers import api as api_router


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(
        ["git", *args], cwd=str(cwd), capture_output=True, text=True
    )
    if proc.returncode != 0:
        raise AssertionError(
            f"git {' '.join(args)} failed\n"
            f"cwd={cwd}\nexit={proc.returncode}\n"
            f"stdout={proc.stdout}\nstderr={proc.stderr}"
        )
    return proc


def _git_branches(cwd: Path) -> set:
    """Return set of local branch names."""
    proc = subprocess.run(
        ["git", "for-each-ref", "--format=%(refname:short)", "refs/heads"],
        cwd=str(cwd), capture_output=True, text=True
    )
    return {b.strip() for b in proc.stdout.splitlines() if b.strip()}


def _build_client(storage: Path) -> TestClient:
    repo_manager = RepoManager(storage)
    api_router.repo_manager = repo_manager
    api_router.git_handler = None
    api_router.git_workspace = None
    api_router.shared_manager = None
    api_router.system_logger = None
    api_router.config = {"git_port": 0, "web_port": 0, "storage_path": storage}

    app = FastAPI()
    app.include_router(api_router.router)
    return TestClient(app)


def test_multi_branch_roundtrip_work_to_home(tmp_path: Path, monkeypatch):
    """
    Full flow:
      Work creates bundle with master + feature-xyz →
      Uploads to backend →
      Backend stores BOTH branches →
      Home pulls export-dump →
      Home decrypts and verifies BOTH branches are in the bundle
    """
    password = "e2e-sync-password"
    monkeypatch.setenv("SYNC_PASSWORD", password)

    # ── Setup backend storage ──────────────────────────────
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "E2E Bot", "user_email": "e2e@example.com"}}),
        encoding="utf-8",
    )

    repo_name = f"e2e-multibranch-{int(time.time())}"
    client = _build_client(storage)

    # Create repo on backend
    created = client.post("/api/repos/create", json={"name": repo_name})
    assert created.status_code == 200, created.text
    assert created.json().get("success") is True, created.text

    workspace = storage / repo_name
    bare = storage / f"{repo_name}.git"
    assert workspace.exists(), "Workspace not created"
    assert bare.exists(), "Bare repo not created"

    # ── Step 1: Simulate "work" machine ────────────────────
    work = tmp_path / "work_machine"
    work.mkdir()
    _run_git(work, "init")
    _run_git(work, "config", "user.email", "work@example.com")
    _run_git(work, "config", "user.name", "Work User")
    _run_git(work, "checkout", "-B", "master")

    # Initial commit on master
    (work / "readme.txt").write_text("initial\n", encoding="utf-8")
    _run_git(work, "add", "readme.txt")
    _run_git(work, "commit", "-m", "master: initial commit")
    master_hash = _run_git(work, "rev-parse", "HEAD").stdout.strip()

    # Create feature branch with additional commit
    _run_git(work, "checkout", "-b", "feature-xyz")
    (work / "feature.txt").write_text("feature work\n", encoding="utf-8")
    _run_git(work, "add", "feature.txt")
    _run_git(work, "commit", "-m", "feature-xyz: add feature work")
    feature_hash = _run_git(work, "rev-parse", "HEAD").stdout.strip()
    assert feature_hash != master_hash, "Feature should have different hash from master"

    # Create bundle with BOTH branches (feature-xyz is current, master is additional)
    bundle_path = tmp_path / "work_bundle.bundle"
    _run_git(work, "bundle", "create", str(bundle_path), "feature-xyz", "master")

    # Verify bundle has both branches
    list_proc = _run_git(work, "bundle", "list-heads", str(bundle_path))
    bundle_output = list_proc.stdout
    assert "refs/heads/feature-xyz" in bundle_output, f"Bundle missing feature-xyz: {bundle_output}"
    assert "refs/heads/master" in bundle_output, f"Bundle missing master: {bundle_output}"

    # Encrypt the bundle into a dump file
    dump_path = tmp_path / f"dump_{repo_name}_test.dmp"
    encrypt_bundle_to_dump(bundle_path, dump_path, password)

    # ── Step 2: Upload to backend via upload-and-apply ─────
    upload_res = client.post(
        "/api/documents/upload",
        data={"repo": repo_name},
        files={"attachment": (dump_path.name, dump_path.read_bytes(), "application/octet-stream")},
    )
    assert upload_res.status_code == 200, upload_res.text
    body = upload_res.json()
    assert body.get("success") is True, f"Upload failed: {body}"

    # ── Step 3: Verify backend workspace has BOTH branches ──
    ws_branches = _git_branches(workspace)
    assert "master" in ws_branches, f"Backend workspace missing 'master'. Branches: {ws_branches}"
    assert "feature-xyz" in ws_branches, f"Backend workspace missing 'feature-xyz'. Branches: {ws_branches}"

    # Verify hashes match
    ws_master = _run_git(workspace, "rev-parse", "refs/heads/master").stdout.strip()
    ws_feature = _run_git(workspace, "rev-parse", "refs/heads/feature-xyz").stdout.strip()
    assert ws_master == master_hash, f"master hash mismatch: {ws_master} != {master_hash}"
    assert ws_feature == feature_hash, f"feature-xyz hash mismatch: {ws_feature} != {feature_hash}"

    # ── Step 4: Verify backend bare repo has BOTH branches ──
    bare_branches_proc = subprocess.run(
        ["git", "for-each-ref", "--format=%(refname:short)", "refs/heads"],
        cwd=str(bare), capture_output=True, text=True
    )
    bare_branches = {b.strip() for b in bare_branches_proc.stdout.splitlines() if b.strip()}
    assert "master" in bare_branches, f"Bare repo missing 'master'. Branches: {bare_branches}"
    assert "feature-xyz" in bare_branches, f"Bare repo missing 'feature-xyz'. Branches: {bare_branches}"

    # ── Step 5: Home machine calls export-dump ──────────────
    export_res = client.post(
        "/api/documents/export",
        data={"repo": repo_name},
    )
    assert export_res.status_code == 200, f"Export failed: {export_res.status_code} {export_res.text}"

    exported_dump = tmp_path / "home_exported.dmp"
    exported_dump.write_bytes(export_res.content)

    # Decrypt the export dump to a bundle
    exported_bundle = tmp_path / "home_exported.bundle"
    decrypt_dump_to_bundle(exported_dump, exported_bundle, password)

    # Verify exported bundle has BOTH branches
    home_list = subprocess.run(
        ["git", "bundle", "list-heads", str(exported_bundle)],
        cwd=str(tmp_path), capture_output=True, text=True
    )
    assert home_list.returncode == 0, f"bundle list-heads failed: {home_list.stderr}"
    export_output = home_list.stdout
    assert "refs/heads/master" in export_output, (
        f"Exported bundle missing 'master'. Bundle refs:\n{export_output}"
    )
    assert "refs/heads/feature-xyz" in export_output, (
        f"Exported bundle missing 'feature-xyz'. Bundle refs:\n{export_output}"
    )

    # ── Step 6: Home machine applies the bundle ─────────────
    home = tmp_path / "home_machine"
    home.mkdir()
    _run_git(home, "init")
    _run_git(home, "config", "user.email", "home@example.com")
    _run_git(home, "config", "user.name", "Home User")

    # Need an initial commit to be able to detach HEAD
    (home / ".gitkeep").write_text("", encoding="utf-8")
    _run_git(home, "add", ".gitkeep")
    _run_git(home, "commit", "-m", "init")

    # Detach HEAD so fetch can update all branch refs (same as backend does)
    _run_git(home, "checkout", "--detach")

    # Fetch all branches from the exported bundle
    _run_git(home, "fetch", str(exported_bundle), "+refs/heads/*:refs/heads/*")

    home_branches = _git_branches(home)
    assert "master" in home_branches, f"Home missing 'master' after apply. Branches: {home_branches}"
    assert "feature-xyz" in home_branches, f"Home missing 'feature-xyz' after apply. Branches: {home_branches}"

    # Verify hashes
    home_master = _run_git(home, "rev-parse", "refs/heads/master").stdout.strip()
    home_feature = _run_git(home, "rev-parse", "refs/heads/feature-xyz").stdout.strip()
    assert home_master == master_hash, f"Home master mismatch: {home_master} != {master_hash}"
    assert home_feature == feature_hash, f"Home feature mismatch: {home_feature} != {feature_hash}"

    # checkout feature and verify files
    _run_git(home, "checkout", "feature-xyz")
    assert (home / "feature.txt").exists(), "feature.txt missing after checkout feature-xyz"
    assert (home / "readme.txt").exists(), "readme.txt missing after checkout feature-xyz"
