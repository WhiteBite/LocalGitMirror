"""
Backup and restore for the CAS vault.

The vault is the single source of truth for corporate artifacts. Losing it
means losing the entire mirror. This module provides:

* ``create_backup`` — incremental copy of CAS + catalog files
* ``verify_backup`` — check SHA-256 of backed-up CAS files
* ``restore_from_backup`` — reconstruct vault from backup

Backup directory layout::

    <backup_dir>/
      cas/sha256/<ab>/<digest>     — copy of CAS
      *.json                        — index.json, conflicts.json, wanted.json
      backup-state/
        last-backup.json            — {timestamp, files_copied, bytes_copied}
        verify.json                 — last verify result
"""

from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import List

# ─────────────────────────────────────────────────────────────────────────────
# Reports
# ─────────────────────────────────────────────────────────────────────────────


@dataclass
class BackupReport:
    files_copied: int = 0
    bytes_copied: int = 0
    duration_ms: int = 0
    errors: List[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "files_copied": self.files_copied,
            "bytes_copied": self.bytes_copied,
            "duration_ms": self.duration_ms,
            "errors": self.errors,
        }


@dataclass
class VerifyReport:
    files_checked: int = 0
    mismatches: List[str] = field(default_factory=list)
    missing: List[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "files_checked": self.files_checked,
            "mismatches": len(self.mismatches),
            "mismatch_details": self.mismatches[:50],
            "missing": self.missing,
            "ok": len(self.mismatches) == 0 and len(self.missing) == 0,
        }


@dataclass
class RestoreReport:
    files_restored: int = 0
    projection_rebuilt: bool = False
    errors: List[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "files_restored": self.files_restored,
            "projection_rebuilt": self.projection_rebuilt,
            "errors": self.errors,
        }


# ─────────────────────────────────────────────────────────────────────────────
# Internal helpers
# ─────────────────────────────────────────────────────────────────────────────

_BACKUP_STATE_DIR = "backup-state"
_LAST_BACKUP = "last-backup.json"
_VERIFY_STATE = "verify.json"
_CAS_DIR = "cas/sha256"
_VAULT_JSON_FILES = ("index.json", "conflicts.json", "wanted.json")
_SKIP_DIRS = {"staging", "projections", "quarantine", _BACKUP_STATE_DIR}
_CATALOG_DB = "catalog/catalog.sqlite"


def _state_dir(backup_dir: Path) -> Path:
    return backup_dir / _BACKUP_STATE_DIR


def _load_last_backup(backup_dir: Path) -> dict:
    path = _state_dir(backup_dir) / _LAST_BACKUP
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {"timestamp": 0, "files_copied": 0, "bytes_copied": 0}


def _save_last_backup(backup_dir: Path, report: BackupReport) -> None:
    state = _state_dir(backup_dir)
    state.mkdir(parents=True, exist_ok=True)
    payload = {
        "timestamp": int(time.time()),
        "files_copied": report.files_copied,
        "bytes_copied": report.bytes_copied,
        "duration_ms": report.duration_ms,
        "errors": report.errors if report.errors else [],
    }
    tmp = state / f"{_LAST_BACKUP}.tmp{os.getpid()}"
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, state / _LAST_BACKUP)


def _save_verify_state(backup_dir: Path, report: VerifyReport) -> None:
    state = _state_dir(backup_dir)
    state.mkdir(parents=True, exist_ok=True)
    payload = {
        "timestamp": int(time.time()),
        "files_checked": report.files_checked,
        "mismatches": report.mismatches,
        "missing": report.missing,
        "ok": report.as_dict()["ok"],
    }
    tmp = state / f"{_VERIFY_STATE}.tmp{os.getpid()}"
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, state / _VERIFY_STATE)


def _copy_file(src: Path, dst: Path) -> None:
    """Copy a single file, creating parent directories as needed."""
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.parent / f"{dst.name}.tmp{os.getpid()}"
    tmp.write_bytes(src.read_bytes())
    os.replace(tmp, dst)


def _iter_cas_files(vault_root: Path):
    """Yield (vault_root, cas_dir, sha256) tuples for every file in CAS."""
    cas_root = vault_root / _CAS_DIR
    if not cas_root.is_dir():
        return
    for prefix_dir in sorted(cas_root.iterdir()):
        if not prefix_dir.is_dir() or len(prefix_dir.name) != 2:
            continue
        for cas_file in sorted(prefix_dir.iterdir()):
            if cas_file.is_file():
                yield cas_file


def _sha256_file(path: Path) -> str:
    """Compute SHA-256 of a file."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


# ─────────────────────────────────────────────────────────────────────────────
# SQLite online backup
# ─────────────────────────────────────────────────────────────────────────────


def _backup_sqlite(src: Path, dst: Path) -> None:
    """Copy SQLite database using the online backup API (safe for live DBs).

    Falls back to file copy with WAL checkpoint if the backup API is not
    available (Python < 3.11 or platform limitation).
    """
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.parent / f"{dst.name}.tmp{os.getpid()}"

    try:
        src_conn = sqlite3.connect(str(src))
        dst_conn = sqlite3.connect(str(tmp))
        src_conn.backup(dst_conn)
        src_conn.close()
        dst_conn.close()
    except Exception:
        # Fallback: checkpoint WAL, then copy the file
        try:
            conn = sqlite3.connect(str(src))
            conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            conn.close()
        except Exception:
            pass
        _copy_file(src, tmp)

    os.replace(tmp, dst)


# ─────────────────────────────────────────────────────────────────────────────
# Public API
# ─────────────────────────────────────────────────────────────────────────────


def create_backup(vault_root: Path, backup_dir: Path) -> BackupReport:
    """Create incremental backup of the vault.

    Only copies files newer than the last backup timestamp. CAS files are
    immutable, so once they exist in the backup they are never re-copied.

    Returns BackupReport with files_copied, bytes_copied, duration_ms, errors.
    """
    t0 = time.monotonic()
    report = BackupReport()
    last = _load_last_backup(backup_dir)
    last_ts = last.get("timestamp", 0)

    vault_root = Path(vault_root)
    backup_dir = Path(backup_dir)
    backup_dir.mkdir(parents=True, exist_ok=True)

    # ── CAS files ─────────────────────────────────────────────────────────
    for src in _iter_cas_files(vault_root):
        rel = src.relative_to(vault_root)
        dst = backup_dir / rel

        # CAS is immutable — skip if already in backup with same size
        if dst.is_file():
            try:
                if dst.stat().st_size == src.stat().st_size:
                    continue
            except OSError:
                pass

        try:
            _copy_file(src, dst)
            report.files_copied += 1
            report.bytes_copied += src.stat().st_size
        except OSError as e:
            report.errors.append(f"{rel}: {e}")

    # ── JSON catalog files ────────────────────────────────────────────────
    for name in _VAULT_JSON_FILES:
        src = vault_root / name
        if not src.is_file():
            continue
        dst = backup_dir / name
        try:
            src_mtime = src.stat().st_mtime
            if dst.is_file() and dst.stat().st_mtime >= src_mtime:
                continue
            _copy_file(src, dst)
            report.files_copied += 1
            report.bytes_copied += src.stat().st_size
        except OSError as e:
            report.errors.append(f"{name}: {e}")

    # ── SQLite catalog (if exists) ────────────────────────────────────────
    sqlite_path = vault_root / _CATALOG_DB
    if sqlite_path.is_file():
        dst = backup_dir / _CATALOG_DB
        try:
            src_mtime = sqlite_path.stat().st_mtime
            if dst.is_file() and dst.stat().st_mtime >= src_mtime:
                pass
            else:
                _backup_sqlite(sqlite_path, dst)
                report.files_copied += 1
                report.bytes_copied += dst.stat().st_size
        except OSError as e:
            report.errors.append(f"{_CATALOG_DB}: {e}")

    # ── Any other top-level files not covered (config, policy) ───────────
    for entry in sorted(vault_root.iterdir()):
        if entry.is_dir():
            continue
        if entry.name in _VAULT_JSON_FILES:
            continue
        if entry.name == _CATALOG_DB.split("/")[-1]:
            continue
        dst = backup_dir / entry.name
        try:
            src_mtime = entry.stat().st_mtime
            if dst.is_file() and dst.stat().st_mtime >= src_mtime:
                continue
            if entry.stat().st_mtime > last_ts or not dst.is_file():
                _copy_file(entry, dst)
                report.files_copied += 1
                report.bytes_copied += entry.stat().st_size
        except OSError as e:
            report.errors.append(f"{entry.name}: {e}")

    report.duration_ms = int((time.monotonic() - t0) * 1000)
    _save_last_backup(backup_dir, report)
    return report


def verify_backup(vault_root: Path, backup_dir: Path) -> VerifyReport:
    """Verify backup integrity by checking SHA-256 of backed-up CAS files.

    Compares every CAS file in the backup against the vault source. If a
    catalog/index file exists, also verifies that every referenced CAS file
    is present in the backup.

    Returns VerifyReport with files_checked, mismatches, missing.
    """
    vault_root = Path(vault_root)
    backup_dir = Path(backup_dir)
    report = VerifyReport()

    # ── Verify CAS files ──────────────────────────────────────────────────
    for src in _iter_cas_files(vault_root):
        rel = src.relative_to(vault_root)
        dst = backup_dir / rel

        if not dst.is_file():
            report.missing.append(str(rel))
            continue

        src_digest = _sha256_file(src)
        dst_digest = _sha256_file(dst)
        report.files_checked += 1

        if src_digest != dst_digest:
            report.mismatches.append(str(rel))

    # ── Cross-check: catalog entries should all have CAS files in backup ──
    index_path = backup_dir / "index.json"
    if index_path.is_file():
        try:
            index = json.loads(index_path.read_text(encoding="utf-8"))
            for _maven_path, entry in index.get("entries", {}).items():
                digest = entry.get("sha256", "")
                if not digest:
                    continue
                cas_path = backup_dir / _CAS_DIR / digest[:2] / digest
                if not cas_path.is_file():
                    report.missing.append(f"index-referenced: {digest}")
        except (json.JSONDecodeError, OSError):
            pass

    _save_verify_state(backup_dir, report)
    return report


def restore_from_backup(backup_dir: Path, target_vault: Path) -> RestoreReport:
    """Restore vault from backup to a target directory.

    Steps:
    1. Copy CAS files from backup to target
    2. Copy index.json, conflicts.json, wanted.json
    3. Copy SQLite catalog (if present)
    4. Verify restored CAS hashes match catalog entries

    Returns RestoreReport with files_restored, projection_rebuilt, errors.
    """
    backup_dir = Path(backup_dir)
    target_vault = Path(target_vault)
    report = RestoreReport()

    # ── 1. CAS files ─────────────────────────────────────────────────────
    backup_cas = backup_dir / _CAS_DIR
    if backup_cas.is_dir():
        for src in _iter_cas_files(backup_dir):
            rel = src.relative_to(backup_dir)
            dst = target_vault / rel
            try:
                _copy_file(src, dst)
                report.files_restored += 1
            except OSError as e:
                report.errors.append(f"cas: {rel}: {e}")

    # ── 2. JSON catalog files ────────────────────────────────────────────
    for name in _VAULT_JSON_FILES:
        src = backup_dir / name
        if not src.is_file():
            continue
        dst = target_vault / name
        try:
            _copy_file(src, dst)
            report.files_restored += 1
        except OSError as e:
            report.errors.append(f"{name}: {e}")

    # ── 3. SQLite catalog ────────────────────────────────────────────────
    sqlite_path = backup_dir / _CATALOG_DB
    if sqlite_path.is_file():
        dst = target_vault / _CATALOG_DB
        try:
            _copy_file(sqlite_path, dst)
            report.files_restored += 1
        except OSError as e:
            report.errors.append(f"{_CATALOG_DB}: {e}")

    # ── 4. Other top-level files ────────────────────────────────────────
    for entry in sorted(backup_dir.iterdir()):
        if entry.is_dir():
            continue
        if entry.name in _VAULT_JSON_FILES:
            continue
        if entry.name == _CATALOG_DB.split("/")[-1]:
            continue
        dst = target_vault / entry.name
        try:
            _copy_file(entry, dst)
            report.files_restored += 1
        except OSError as e:
            report.errors.append(f"{entry.name}: {e}")

    # ── 5. Verify restored CAS hashes match catalog ──────────────────────
    index_path = target_vault / "index.json"
    if index_path.is_file():
        try:
            index = json.loads(index_path.read_text(encoding="utf-8"))
            for maven_path, entry in index.get("entries", {}).items():
                digest = entry.get("sha256", "")
                if not digest:
                    continue
                cas_path = target_vault / _CAS_DIR / digest[:2] / digest
                if not cas_path.is_file():
                    report.errors.append(f"missing CAS during restore: {digest}")
                    continue
                actual = _sha256_file(cas_path)
                if actual != digest:
                    report.errors.append(
                        f"hash mismatch: {maven_path} expects {digest}, got {actual}"
                    )
        except (json.JSONDecodeError, OSError) as e:
            report.errors.append(f"index verification failed: {e}")

    # ── Rebuild projection if ArtifactStore is available ──────────────────
    # ponytail: only rebuild if the module is available; import deferred
    # to avoid circular dependency at module load time.
    try:
        from app.core.artifact_store import ArtifactStore

        store = ArtifactStore(target_vault)
        store.rebuild_projection()
        report.projection_rebuilt = True
    except Exception as e:
        report.errors.append(f"projection rebuild: {e}")

    return report
