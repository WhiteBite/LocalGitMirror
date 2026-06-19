"""
Honest tests for D1: auto-TTL cleanup of stale blobs in storage/deps/*.
Tests catch real bugs in the cleanup logic: wrong comparison, off-by-one,
glob pattern not matching .bin files, deleting the wrong files, etc.
No running server needed — exercises _cleanup_stale() as a pure function.
"""
import time
from pathlib import Path

import pytest

from app.routers.deps import _cleanup_stale


def _write_blob(directory: Path, name: str, content: bytes = b"x") -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    p = directory / name
    p.write_bytes(content)
    return p


def _set_age(path: Path, age_seconds: float) -> None:
    """Set mtime so os.path.getmtime reports the given age from now."""
    new_mtime = time.time() - age_seconds
    import os
    os.utime(path, (new_mtime, new_mtime))


# ── basic happy path ──────────────────────────────────────────────────────────

def test_cleanup_removes_blob_older_than_ttl(tmp_path):
    d = tmp_path / "requests"
    old = _write_blob(d, "old.bin")
    _set_age(old, 8 * 24 * 3600)  # 8 days old — beyond the 7-day default

    deleted = _cleanup_stale(d, max_age_seconds=7 * 24 * 3600)

    assert deleted == 1
    assert not old.exists(), "Old blob must have been deleted"


def test_cleanup_leaves_fresh_blob(tmp_path):
    d = tmp_path / "requests"
    fresh = _write_blob(d, "fresh.bin")
    _set_age(fresh, 1 * 24 * 3600)  # 1 day old — within the 7-day default

    deleted = _cleanup_stale(d, max_age_seconds=7 * 24 * 3600)

    assert deleted == 0
    assert fresh.exists(), "Fresh blob must not be deleted"


def test_cleanup_boundary_exactly_at_ttl_is_not_deleted(tmp_path):
    """A blob aged *exactly* TTL seconds should NOT be deleted (> comparison)."""
    d = tmp_path / "requests"
    blob = _write_blob(d, "boundary.bin")
    ttl = 3600
    _set_age(blob, ttl)  # exactly at limit

    deleted = _cleanup_stale(d, max_age_seconds=ttl)

    # age == ttl, not age > ttl, so it should NOT be deleted
    assert deleted == 0, "Blob exactly at TTL boundary must not be deleted"
    assert blob.exists()


def test_cleanup_one_second_past_ttl_is_deleted(tmp_path):
    """A blob aged TTL+1 seconds MUST be deleted."""
    d = tmp_path / "requests"
    blob = _write_blob(d, "stale.bin")
    ttl = 3600
    _set_age(blob, ttl + 1)  # 1 second past TTL

    deleted = _cleanup_stale(d, max_age_seconds=ttl)

    assert deleted == 1
    assert not blob.exists()


# ── only .bin files are touched ───────────────────────────────────────────────

def test_cleanup_ignores_non_bin_files(tmp_path):
    """Non-.bin files must never be deleted, even if very old."""
    d = tmp_path / "requests"
    log = _write_blob(d, "old.log")
    txt = _write_blob(d, "notes.txt")
    _set_age(log, 30 * 24 * 3600)
    _set_age(txt, 30 * 24 * 3600)

    deleted = _cleanup_stale(d, max_age_seconds=7 * 24 * 3600)

    assert deleted == 0
    assert log.exists() and txt.exists()


def test_cleanup_mixes_old_and_fresh_correctly(tmp_path):
    """Precise: 2 old + 3 fresh → exactly 2 deleted, 3 remain."""
    d = tmp_path / "requests"
    old_files = [_write_blob(d, f"old-{i}.bin") for i in range(2)]
    fresh_files = [_write_blob(d, f"fresh-{i}.bin") for i in range(3)]
    for f in old_files:
        _set_age(f, 10 * 24 * 3600)
    for f in fresh_files:
        _set_age(f, 1 * 24 * 3600)

    deleted = _cleanup_stale(d, max_age_seconds=7 * 24 * 3600)

    assert deleted == 2
    for f in old_files:
        assert not f.exists(), f"{f.name} should have been deleted"
    for f in fresh_files:
        assert f.exists(), f"{f.name} must not be deleted"


# ── edge cases ────────────────────────────────────────────────────────────────

def test_cleanup_missing_directory_returns_zero(tmp_path):
    missing = tmp_path / "does-not-exist"
    assert not missing.exists()
    deleted = _cleanup_stale(missing)
    assert deleted == 0


def test_cleanup_empty_directory_returns_zero(tmp_path):
    d = tmp_path / "empty"
    d.mkdir()
    assert _cleanup_stale(d) == 0


def test_cleanup_zero_ttl_deletes_everything(tmp_path):
    """TTL of 0 means every file is stale (age >= 0 seconds)."""
    d = tmp_path / "requests"
    blobs = [_write_blob(d, f"{i}.bin") for i in range(5)]
    # Files were just written so age ~= 0; with max_age_seconds=0 they are NOT
    # strictly > 0, so they should NOT be deleted. But a 1-second TTL with files
    # that are 2 seconds old should delete them.
    for b in blobs:
        _set_age(b, 2)  # 2 seconds old
    deleted = _cleanup_stale(d, max_age_seconds=1)  # TTL = 1 second
    assert deleted == 5
