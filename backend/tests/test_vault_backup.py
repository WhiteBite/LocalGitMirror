"""
Tests for vault backup and restore.

Verifies:
- Backup creates expected files in the backup directory
- Incremental backup skips unchanged CAS files
- Restore recreates vault structure with all artifacts
- Verify detects corrupted/missing files
- Backup/restore round-trip preserves all data
"""

import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.artifact_store import ArtifactStore, MavenCoord
from app.core.vault_backup import (
    _CAS_DIR,
    _CATALOG_DB,
    create_backup,
    restore_from_backup,
    verify_backup,
)
from app.routers import mirror as mirror_mod

POM = MavenCoord("ru.kryptonite.build", "kryptonite-gradle-plugin", "2.0.3", "", "pom")
JAR = MavenCoord("ru.kryptonite.build", "kryptonite-gradle-plugin", "2.0.3", "", "jar")
MARKER = MavenCoord(
    "ru.kryptonite.code-quality",
    "ru.kryptonite.code-quality.gradle.plugin",
    "2.0.3",
    "",
    "pom",
)


# ─────────────────────────────────────────────────────────────────────────────
# Fixtures
# ─────────────────────────────────────────────────────────────────────────────


@pytest.fixture()
def vault(tmp_path, monkeypatch):
    root = tmp_path / "vault"
    monkeypatch.setenv("LGM_VAULT_PATH", str(root))
    monkeypatch.setenv("SYNC_PASSWORD", "test")
    mirror_mod.repo_manager = None
    mirror_mod.system_logger = None
    return root


@pytest.fixture()
def store(vault):
    return ArtifactStore(vault)


@pytest.fixture()
def populated_vault(store):
    store.put(b"pom-content", POM)
    store.put(b"jar-content", JAR)
    store.put(b"<project/>", MARKER)
    store.rebuild_projection()
    return store.root


@pytest.fixture()
def backup_dir(tmp_path):
    return tmp_path / "backup"


@pytest.fixture()
def client(vault):
    app = FastAPI()
    app.include_router(mirror_mod.router)
    return TestClient(app)


# ─────────────────────────────────────────────────────────────────────────────
# Basic backup
# ─────────────────────────────────────────────────────────────────────────────


def test_backup_creates_expected_files(populated_vault, backup_dir):
    report = create_backup(populated_vault, backup_dir)

    assert report.files_copied > 0
    assert report.bytes_copied > 0
    assert report.duration_ms >= 0
    assert report.errors == []

    # CAS files should exist
    assert (backup_dir / _CAS_DIR).is_dir()
    cas_files = list((backup_dir / _CAS_DIR).rglob("*"))
    assert len([f for f in cas_files if f.is_file()]) >= 3

    # Catalog files: index.json always exists; conflicts.json and wanted.json
    # only exist when the store has entries (they are created lazily).
    assert (backup_dir / "index.json").is_file(), "index.json missing from backup"

    # Backup state written
    state = backup_dir / "backup-state" / "last-backup.json"
    assert state.is_file()


def test_backup_includes_all_index_entries(populated_vault, backup_dir):
    create_backup(populated_vault, backup_dir)

    index = json.loads((backup_dir / "index.json").read_text(encoding="utf-8"))
    assert POM.maven_path in index["entries"]
    assert JAR.maven_path in index["entries"]
    assert MARKER.maven_path in index["entries"]


def test_backup_copies_wanted_and_conflicts(populated_vault, backup_dir):
    store = ArtifactStore(populated_vault)
    store.add_wanted(POM.maven_path)
    store.put(b"different", POM)  # creates conflict

    create_backup(populated_vault, backup_dir)

    wanted = json.loads((backup_dir / "wanted.json").read_text(encoding="utf-8"))
    assert len(wanted["entries"]) == 1

    conflicts = json.loads((backup_dir / "conflicts.json").read_text(encoding="utf-8"))
    assert len(conflicts["entries"]) == 1


# ─────────────────────────────────────────────────────────────────────────────
# Incremental backup
# ─────────────────────────────────────────────────────────────────────────────


def test_incremental_backup_skips_unchanged_cas(populated_vault, backup_dir):
    create_backup(populated_vault, backup_dir)

    second = create_backup(populated_vault, backup_dir)
    # No changes — should copy nothing new
    assert second.files_copied == 0


def test_incremental_backup_copies_new_cas(populated_vault, backup_dir):
    create_backup(populated_vault, backup_dir)

    # Add a new artifact
    store = ArtifactStore(populated_vault)
    new_coord = MavenCoord("ru.kryptonite.build", "new-artifact", "1.0", "", "jar")
    store.put(b"new-content", new_coord)

    second = create_backup(populated_vault, backup_dir)
    assert second.files_copied >= 1, "new CAS file should be copied"


def test_incremental_notices_index_changes(populated_vault, backup_dir):
    create_backup(populated_vault, backup_dir)

    # Add a new artifact — changes index.json but not existing CAS files
    store = ArtifactStore(populated_vault)
    new_coord = MavenCoord("ru.kryptonite.build", "another", "2.0", "", "jar")
    store.put(b"another-content", new_coord)

    second = create_backup(populated_vault, backup_dir)
    # index.json updated + new CAS file
    assert second.files_copied >= 2


# ─────────────────────────────────────────────────────────────────────────────
# Restore
# ─────────────────────────────────────────────────────────────────────────────


def test_restore_recreates_vault_structure(populated_vault, backup_dir, tmp_path):
    create_backup(populated_vault, backup_dir)
    target = tmp_path / "restored"

    report = restore_from_backup(backup_dir, target)
    assert report.files_restored > 0
    assert report.errors == []

    # Verify CAS files are present
    assert (target / _CAS_DIR).is_dir()
    assert (target / "index.json").is_file()

    # Load index and verify each entry's CAS file
    index = json.loads((target / "index.json").read_text(encoding="utf-8"))
    for maven_path, entry in index["entries"].items():
        digest = entry["sha256"]
        cas_path = target / _CAS_DIR / digest[:2] / digest
        assert cas_path.is_file(), f"CAS file missing for {maven_path}: {digest}"


def test_restore_preserves_data(populated_vault, backup_dir, tmp_path):
    create_backup(populated_vault, backup_dir)
    target = tmp_path / "restored"
    restore_from_backup(backup_dir, target)

    # Verify ArtifactStore on restored vault works
    restored = ArtifactStore(target)
    result = restored.resolve(POM.maven_path)
    assert result is not None
    assert result[0].read_bytes() == b"pom-content"


def test_restore_rebuilds_projection(populated_vault, backup_dir, tmp_path):
    create_backup(populated_vault, backup_dir)
    target = tmp_path / "restored"

    report = restore_from_backup(backup_dir, target)
    assert report.projection_rebuilt

    proj = target / "projections" / "maven2" / POM.maven_path
    assert proj.is_file()
    assert proj.read_bytes() == b"pom-content"


def test_restore_from_empty_vault_is_safe(populated_vault, backup_dir, tmp_path):
    """Restoring to a non-existent directory should work (creates it)."""
    create_backup(populated_vault, backup_dir)
    target = tmp_path / "nonexistent"

    report = restore_from_backup(backup_dir, target)
    assert report.files_restored > 0
    assert (target / "index.json").is_file()


# ─────────────────────────────────────────────────────────────────────────────
# Verify
# ─────────────────────────────────────────────────────────────────────────────


def test_verify_passes_on_clean_backup(populated_vault, backup_dir):
    create_backup(populated_vault, backup_dir)
    report = verify_backup(populated_vault, backup_dir)

    assert report.files_checked > 0
    assert report.mismatches == []
    assert report.missing == []


def test_verify_detects_missing_cas_file(populated_vault, backup_dir):
    create_backup(populated_vault, backup_dir)

    # Delete a CAS file from the backup
    import hashlib
    digest = hashlib.sha256(b"pom-content").hexdigest()
    cas_path = backup_dir / _CAS_DIR / digest[:2] / digest
    assert cas_path.is_file()
    cas_path.unlink()

    report = verify_backup(populated_vault, backup_dir)
    assert len(report.missing) > 0


def test_verify_detects_corrupted_cas_file(populated_vault, backup_dir):
    create_backup(populated_vault, backup_dir)

    # Corrupt a CAS file in the backup
    import hashlib
    digest = hashlib.sha256(b"pom-content").hexdigest()
    cas_path = backup_dir / _CAS_DIR / digest[:2] / digest
    cas_path.write_bytes(b"corrupted")

    report = verify_backup(populated_vault, backup_dir)
    assert len(report.mismatches) > 0


def test_verify_detects_index_referenced_missing_file(populated_vault, backup_dir, tmp_path):
    """When catalog refers to a CAS digest not in backup, verify reports it."""
    create_backup(populated_vault, backup_dir)

    # Remove a CAS file and the corresponding index entry won't match
    # Delete the jar CAS (which is referenced by index)
    import hashlib
    digest = hashlib.sha256(b"jar-content").hexdigest()
    cas_path = backup_dir / _CAS_DIR / digest[:2] / digest
    cas_path.unlink()

    report = verify_backup(populated_vault, backup_dir)
    # Either missing (file not found) or index-referenced
    assert len(report.missing) >= 1 or len(report.mismatches) >= 1


# ─────────────────────────────────────────────────────────────────────────────
# Round-trip
# ─────────────────────────────────────────────────────────────────────────────


def test_roundtrip_preserves_all_data(populated_vault, backup_dir, tmp_path):
    """Backup + restore = identical vault (same index, same CAS files)."""
    create_backup(populated_vault, backup_dir)
    target = tmp_path / "restored"
    restore_from_backup(backup_dir, target)

    original = ArtifactStore(populated_vault)
    restored = ArtifactStore(target)

    assert original.inventory() == restored.inventory()
    assert original.stats() == restored.stats()


def test_roundtrip_preserves_wanted_queue(populated_vault, backup_dir, tmp_path):
    store = ArtifactStore(populated_vault)
    store.add_wanted("ru/kryptonite/missing/1.0/missing-1.0.jar")
    create_backup(populated_vault, backup_dir)

    target = tmp_path / "restored"
    restore_from_backup(backup_dir, target)

    restored = ArtifactStore(target)
    wanted = restored.list_wanted()
    assert len(wanted) == 1
    assert "missing" in wanted[0]["maven_path"]


def test_roundtrip_preserves_conflicts(populated_vault, backup_dir, tmp_path):
    store = ArtifactStore(populated_vault)
    store.put(b"different-jar", JAR)  # creates conflict
    create_backup(populated_vault, backup_dir)

    target = tmp_path / "restored"
    restore_from_backup(backup_dir, target)

    restored = ArtifactStore(target)
    assert len(restored.list_conflicts()) == 1


# ─────────────────────────────────────────────────────────────────────────────
# Empty vault
# ─────────────────────────────────────────────────────────────────────────────


def test_backup_empty_vault(store, backup_dir):
    """Backing up an empty vault should not crash."""
    # Ensure the vault directory exists but is empty
    assert store.root.is_dir()
    report = create_backup(store.root, backup_dir)
    assert report.files_copied == 0
    assert report.errors == []


def test_restore_empty_backup(backup_dir, tmp_path):
    """Restoring from an empty backup should not crash."""
    backup_dir.mkdir(parents=True, exist_ok=True)
    (backup_dir / "backup-state").mkdir(exist_ok=True)
    target = tmp_path / "restored"
    report = restore_from_backup(backup_dir, target)
    assert report.files_restored == 0


# ─────────────────────────────────────────────────────────────────────────────
# SQLite catalog (if present)
# ─────────────────────────────────────────────────────────────────────────────


def test_backup_handles_sqlite_catalog(populated_vault, backup_dir):
    """If a catalog.sqlite exists, it should be backed up using online API."""
    import sqlite3

    sqlite_path = populated_vault / _CATALOG_DB
    sqlite_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(sqlite_path))
    conn.execute("CREATE TABLE IF NOT EXISTS artifacts (path TEXT, sha256 TEXT)")
    conn.execute("INSERT INTO artifacts VALUES (?, ?)", (POM.maven_path, "abc"))
    conn.commit()
    conn.close()

    create_backup(populated_vault, backup_dir)

    backup_db = backup_dir / _CATALOG_DB
    assert backup_db.is_file()
    conn = sqlite3.connect(str(backup_db))
    rows = list(conn.execute("SELECT * FROM artifacts"))
    conn.close()
    assert len(rows) == 1


# ─────────────────────────────────────────────────────────────────────────────
# Router endpoints
# ─────────────────────────────────────────────────────────────────────────────


def test_backup_endpoint_succeeds(populated_vault, client):
    r = client.post("/api/cache/backup")
    assert r.status_code == 200
    body = r.json()
    assert body["success"]
    assert body["files_copied"] > 0


def test_backup_status_endpoint(populated_vault, client):
    client.post("/api/cache/backup")
    r = client.get("/api/cache/backup/status")
    assert r.status_code == 200
    body = r.json()
    assert body["success"]
    assert body["last_backup"]["files_copied"] > 0


def test_restore_endpoint_rejects_missing_dir(client):
    r = client.post("/api/cache/restore", data={"backup_path": "/nonexistent/path"})
    assert r.status_code == 400


def test_restore_endpoint_succeeds(populated_vault, client, tmp_path):
    client.post("/api/cache/backup")
    backup = populated_vault.parent / "backup"

    r = client.post("/api/cache/restore", data={"backup_path": str(backup)})
    assert r.status_code == 200
    body = r.json()
    assert body["success"]
    assert body["files_restored"] > 0


def test_backup_endpoint_roundtrip(populated_vault, client, tmp_path):
    """Endpoint round-trip: backup via API, restore via API, verify data."""
    # Backup
    r = client.post("/api/cache/backup")
    assert r.status_code == 200

    # Restore to the same vault (it already contains the data)
    backup = populated_vault.parent / "backup"
    r = client.post("/api/cache/restore", data={"backup_path": str(backup)})
    assert r.status_code == 200

    # Verify restored vault
    restored = ArtifactStore(populated_vault)
    assert len(restored.inventory()) == 3
