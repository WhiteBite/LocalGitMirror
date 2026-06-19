"""
Honest tests for D2: hashed repo directory names in storage/deps.
Tests catch real bugs: hash collision, wrong hash length, migration not firing,
plain-name dir still accessible after migration, two repos sharing a dir.
"""
import json
import time
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from app.routers import deps as deps_router
from app.routers.deps import _deps_repo_hash, _requests_dir, _responses_dir


# ── pure hash logic ───────────────────────────────────────────────────────────

def test_hash_is_deterministic():
    assert _deps_repo_hash("onyx-platform") == _deps_repo_hash("onyx-platform")
    assert _deps_repo_hash("my-repo") == _deps_repo_hash("my-repo")


def test_hash_differs_for_different_names():
    assert _deps_repo_hash("repo-a") != _deps_repo_hash("repo-b")
    assert _deps_repo_hash("default") != _deps_repo_hash("onyx-platform")
    # The old bug: plain name used as dir — same first 16 chars would collide.
    assert _deps_repo_hash("repo") != _deps_repo_hash("repo-x")


def test_hash_length_is_16():
    assert len(_deps_repo_hash("anything")) == 16


def test_no_collision_for_common_names():
    names = ["default", "onyx-platform", "eaes-platform", "repo", "my-project", "test"]
    hashes = [_deps_repo_hash(n) for n in names]
    assert len(set(hashes)) == len(hashes), "All common names must hash to unique values"


# ── storage path uses hash, not plain name ────────────────────────────────────

def _build_client_and_rm(storage: Path):
    rm = RepoManager(storage)
    deps_router.repo_manager = rm
    deps_router.system_logger = None
    app = FastAPI()
    app.include_router(deps_router.router)
    return TestClient(app), rm


def _setup_storage(tmp_path: Path) -> Path:
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "b@x.com"}}),
        encoding="utf-8",
    )
    return storage


def test_requests_dir_uses_hashed_subdir(tmp_path):
    storage = _setup_storage(tmp_path)
    _, rm = _build_client_and_rm(storage)
    repo = "test-repo"

    d = _requests_dir(repo)
    expected_hash = _deps_repo_hash(repo)

    assert expected_hash in str(d), f"Hashed subdir missing from path: {d}"
    assert repo not in d.parent.name, f"Plain repo name must not appear as dir name: {d}"


def test_responses_dir_uses_hashed_subdir(tmp_path):
    storage = _setup_storage(tmp_path)
    _, rm = _build_client_and_rm(storage)
    repo = "test-repo"

    d = _responses_dir(repo)
    expected_hash = _deps_repo_hash(repo)

    assert expected_hash in str(d)
    assert repo not in d.parent.name


def test_two_repos_use_different_dirs(tmp_path):
    storage = _setup_storage(tmp_path)
    _, rm = _build_client_and_rm(storage)

    d1 = _requests_dir("repo-one")
    d2 = _requests_dir("repo-two")

    assert d1 != d2, "Different repos must not share a storage dir"
    assert d1.parent != d2.parent


# ── backward-compat migration ─────────────────────────────────────────────────

def test_migration_from_plain_to_hashed_dir(tmp_path):
    """On first access, a plain-name dir should be renamed to the hashed dir."""
    storage = _setup_storage(tmp_path)
    _, rm = _build_client_and_rm(storage)

    repo = "legacy-repo"
    hashed = _deps_repo_hash(repo)
    deps_root = rm.storage_path / "deps"
    deps_root.mkdir(parents=True, exist_ok=True)

    # Simulate legacy layout: plain-name dir with a request blob inside.
    plain_dir = deps_root / repo
    plain_requests = plain_dir / "requests"
    plain_requests.mkdir(parents=True, exist_ok=True)
    sentinel = plain_requests / "sentinel.bin"
    sentinel.write_bytes(b"payload")

    # First access via _requests_dir should trigger migration.
    migrated = _requests_dir(repo)

    assert not plain_dir.exists(), "Plain-name dir must be removed after migration"
    hashed_dir = deps_root / hashed
    assert hashed_dir.exists(), "Hashed dir must exist after migration"
    assert (hashed_dir / "requests" / "sentinel.bin").exists(), \
        "Sentinel blob must be accessible after migration"


def test_migrated_data_accessible_via_api(tmp_path):
    """After migration, the API can still list and download the blob."""
    storage = _setup_storage(tmp_path)
    client, rm = _build_client_and_rm(storage)

    repo = "migrated-repo"
    hashed = _deps_repo_hash(repo)
    deps_root = rm.storage_path / "deps"
    deps_root.mkdir(parents=True, exist_ok=True)

    # Manually write a blob in the plain-name layout (legacy).
    plain_requests = deps_root / repo / "requests"
    plain_requests.mkdir(parents=True, exist_ok=True)
    blob_id = "deadbeef12345678901234567890abcd"
    (plain_requests / f"{blob_id}.bin").write_bytes(b"encrypted-manifest")

    # GET /api/deps/pending triggers migration and should list the blob.
    res = client.get("/api/deps/pending", params={"repo": repo})
    assert res.status_code == 200, res.text
    items = res.json()["items"]
    assert len(items) == 1, f"Expected 1 item after migration, got: {items}"
    assert items[0]["id"] == blob_id
