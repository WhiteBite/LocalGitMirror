"""HOME persistent MR review store.

Layout (plan section A, normative): ``~/.lgm/reviews/<repo-key>/`` with
``snapshots/<snapshot_id>.json`` (immutable after atomic install), one shared
bare ``objects.git`` holding every snapshot's objects, ``review.sqlite3``
for progress/findings/batches/receipts state and ``environments/`` (lifecycle
is task 9). The store is plaintext local, like a checkout. Ordinary mirror
checkouts are never read or written: all Git access goes through
mr_review_git.MrReviewGit on objects.git only.
"""
from __future__ import annotations

import json
import os
import re
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Self

from .config import cfg
from .mr_review_git import MrReviewGit, init_bare, resolve_contained
from .mr_review_models import MrReviewError, canonical_json, sha256_hex, verify_snapshot

_HEX64_RE = re.compile(r"[0-9a-f]{64}")
_ENV_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]*")
_COL_RE = re.compile(r"[A-Za-z0-9_]+")
_GITLAB_URL_RE = re.compile(r"(?i)(https?)://([^/]+)(/.*)?")

SCHEMA_VERSION = 1

_CAS_TABLES = frozenset({"progress", "findings"})

_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS snapshots (
    snapshot_id TEXT PRIMARY KEY,
    code_id TEXT NOT NULL,
    version_fingerprint TEXT NOT NULL,
    repo TEXT NOT NULL,
    gitlab_url TEXT NOT NULL,
    target_project_id INTEGER NOT NULL,
    iid INTEGER NOT NULL,
    imported_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS current_snapshot (
    repo TEXT NOT NULL,
    gitlab_url TEXT NOT NULL,
    target_project_id INTEGER NOT NULL,
    iid INTEGER NOT NULL,
    snapshot_id TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (repo, gitlab_url, target_project_id, iid)
);
CREATE TABLE IF NOT EXISTS progress (
    snapshot_id TEXT NOT NULL,
    path TEXT NOT NULL,
    revision INTEGER NOT NULL,
    payload TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (snapshot_id, path)
);
CREATE TABLE IF NOT EXISTS findings (
    finding_id TEXT PRIMARY KEY,
    repo TEXT NOT NULL,
    gitlab_url TEXT NOT NULL,
    target_project_id INTEGER NOT NULL,
    iid INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    payload TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS finding_revisions (
    finding_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    payload TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (finding_id, revision)
);
CREATE TABLE IF NOT EXISTS batches (
    batch_id TEXT PRIMARY KEY,
    payload TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS receipts (
    batch_id TEXT NOT NULL,
    finding_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    attempt INTEGER NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (batch_id, finding_id, revision, attempt)
);
CREATE TABLE IF NOT EXISTS environments (
    environment_id TEXT PRIMARY KEY,
    snapshot_id TEXT NOT NULL,
    source_sha TEXT NOT NULL,
    purpose TEXT NOT NULL,
    mode TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
"""

_PROCESS_LOCKS: dict[str, threading.RLock] = {}
_PROCESS_LOCKS_GUARD = threading.Lock()
_FILE_LOCK_OWNER: dict[str, tuple[int, int]] = {}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def normalize_gitlab_url(url: str) -> str:
    if not isinstance(url, str) or not url:
        raise MrReviewError("invalid-schema", "gitlab_url must be a non-empty string")
    stripped = url.strip().rstrip("/")
    match = _GITLAB_URL_RE.fullmatch(stripped)
    if not match:
        raise MrReviewError("invalid-schema", f"gitlab_url must be http(s), got {url!r}")
    scheme = match.group(1).lower()
    host = match.group(2).lower()
    path = match.group(3) or ""
    return f"{scheme}://{host}{path}"


def repo_key(gitlab_url: str, target_project_id: int, repo: str) -> str:
    """SHA-256 over the canonical composition of the normalized GitLab URL,
    the numeric target project ID and the mirror repo name."""
    if not isinstance(target_project_id, int) or isinstance(target_project_id, bool) \
            or target_project_id < 1:
        raise MrReviewError(
            "invalid-schema", f"target_project_id must be an integer >= 1, got {target_project_id!r}")
    if not isinstance(repo, str) or not repo:
        raise MrReviewError("invalid-schema", f"repo must be a non-empty string, got {repo!r}")
    payload = {
        "gitlab_url": normalize_gitlab_url(gitlab_url),
        "repo": repo,
        "target_project_id": target_project_id,
    }
    return sha256_hex(canonical_json(payload))


def reviews_home(override: Path | str | None = None) -> Path:
    if override is not None:
        return Path(override)
    configured = cfg("LGM_REVIEWS_HOME")
    if configured:
        return Path(configured)
    return Path.home() / ".lgm" / "reviews"


@dataclass(frozen=True)
class ImportResult:
    snapshot_id: str
    status: str
    repo_key: str
    store_root: Path


class _ImportLock:
    """Per-store import lock: a threading lock serializes threads inside this
    process, a lock file serializes separate processes. The file part is
    reentrant for the owning thread, so store construction under a held
    import lock cannot self-deadlock. A lock file older than ``stale_after``
    seconds (crashed holder) is broken; waiting past ``timeout`` raises
    store-locked instead of blocking forever."""

    def __init__(self, root: Path, timeout: float, stale_after: float) -> None:
        self._root = root
        self._timeout = timeout
        self._stale_after = stale_after
        self._lock_path = root / "import.lock"
        self._thread_lock: threading.RLock | None = None
        self._depth = 0

    def __enter__(self) -> Self:
        with _PROCESS_LOCKS_GUARD:
            lock = _PROCESS_LOCKS.get(str(self._root))
            if lock is None:
                lock = threading.RLock()
                _PROCESS_LOCKS[str(self._root)] = lock
            self._thread_lock = lock
        if not self._thread_lock.acquire(timeout=max(self._timeout, 0.001)):
            raise MrReviewError(
                "store-locked", f"import lock for {self._root} stayed busy for {self._timeout}s")
        try:
            owner = _FILE_LOCK_OWNER.get(str(self._root))
            if owner is not None and owner[0] == threading.get_ident():
                _FILE_LOCK_OWNER[str(self._root)] = (owner[0], owner[1] + 1)
                self._depth = owner[1] + 1
                return self
            deadline = time.monotonic() + self._timeout
            while True:
                try:
                    fd = os.open(str(self._lock_path),
                                 os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                    os.close(fd)
                    _FILE_LOCK_OWNER[str(self._root)] = (threading.get_ident(), 1)
                    self._depth = 1
                    return self
                except FileExistsError:
                    self._break_stale()
                    if time.monotonic() >= deadline:
                        raise MrReviewError(
                            "store-locked",
                            f"import lock {self._lock_path} stayed busy for {self._timeout}s")
                    time.sleep(0.02)
        except BaseException:
            self._thread_lock.release()
            self._thread_lock = None
            raise

    def _break_stale(self) -> None:
        try:
            age = time.time() - self._lock_path.stat().st_mtime
        except OSError:
            return
        if age > self._stale_after:
            try:
                self._lock_path.unlink()
            except OSError:
                pass

    def __exit__(self, *exc: object) -> bool:
        if self._depth > 0:
            self._depth -= 1
            if self._depth == 0:
                _FILE_LOCK_OWNER.pop(str(self._root), None)
                try:
                    self._lock_path.unlink()
                except OSError:
                    pass
        if self._thread_lock is not None:
            self._thread_lock.release()
            self._thread_lock = None
        return False


class MrReviewStore:
    """Persistent review store for one (GitLab URL, target project, repo).

    Construction is idempotent: it creates the directory layout, the bare
    objects.git and the SQLite schema when missing. ``import_snapshot``
    installs a verified snapshot atomically; a half-installed snapshot never
    becomes current.
    """

    def __init__(self, gitlab_url: str, target_project_id: int, repo: str,
                 *, home: Path | str | None = None) -> None:
        self._gitlab_url = normalize_gitlab_url(gitlab_url)
        self._target_project_id = target_project_id
        self._repo = repo
        self._key = repo_key(gitlab_url, target_project_id, repo)
        self.root = (reviews_home(home) / self._key).resolve()
        self._snapshots_dir = self.root / "snapshots"
        self._environments_dir = self.root / "environments"
        self.db_path = self.root / "review.sqlite3"
        self.root.mkdir(parents=True, exist_ok=True)
        with _ImportLock(self.root, timeout=60.0, stale_after=600.0):
            self._snapshots_dir.mkdir(exist_ok=True)
            self._environments_dir.mkdir(exist_ok=True)
            init_bare(self.root / "objects.git")
            self._init_db()
        self.git = MrReviewGit(self.root / "objects.git")


    def _init_db(self) -> None:
        conn = self._connect()
        try:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(_SCHEMA_SQL)
            conn.execute(
                "INSERT OR IGNORE INTO schema_migrations (version, applied_at) VALUES (?, ?)",
                (SCHEMA_VERSION, _now()))
            conn.commit()
        finally:
            conn.close()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path, timeout=30)
        conn.row_factory = sqlite3.Row
        return conn

    @contextmanager
    def transaction(self):
        conn = self._connect()
        try:
            with conn:
                yield conn
        finally:
            conn.close()

    def cas_write(self, table: str, key: dict, expected_revision: int,
                  assignments: dict) -> int:
        """Insert or update one row with compare-and-swap on ``revision``.

        ``expected_revision=0`` inserts a fresh row at revision 1 and fails
        with revision-conflict when the row already exists; any other value
        must equal the stored revision exactly. Returns the new revision;
        conflicting writes never overwrite silently.
        """
        if table not in _CAS_TABLES:
            raise MrReviewError("invalid-schema", f"unknown CAS table: {table!r}")
        if not isinstance(expected_revision, int) or isinstance(expected_revision, bool) \
                or expected_revision < 0:
            raise MrReviewError(
                "invalid-schema", f"expected_revision must be an integer >= 0, got {expected_revision!r}")
        if not key or not assignments:
            raise MrReviewError("invalid-schema", "cas_write needs key and assignment columns")
        for name in (*key, *assignments):
            if not isinstance(name, str) or not _COL_RE.fullmatch(name):
                raise MrReviewError("invalid-schema", f"invalid column name: {name!r}")
        if set(key) & set(assignments):
            raise MrReviewError("invalid-schema", "key columns cannot be assigned")
        now = _now()
        with self.transaction() as conn:
            if expected_revision == 0:
                columns = [*key, *assignments, "revision", "updated_at"]
                placeholders = ",".join("?" for _ in columns)
                values = [*key.values(), *assignments.values(), 1, now]
                try:
                    conn.execute(
                        f"INSERT INTO {table} ({','.join(columns)}) VALUES ({placeholders})",
                        values)
                except sqlite3.IntegrityError:
                    raise MrReviewError(
                        "revision-conflict", f"{table} row {key!r} already exists") from None
                return 1
            set_clause = ",".join(f"{col} = ?" for col in assignments)
            where_clause = " AND ".join(f"{col} = ?" for col in key)
            params = [*assignments.values(), now, *key.values(), expected_revision]
            cursor = conn.execute(
                f"UPDATE {table} SET {set_clause}, revision = revision + 1, updated_at = ? "
                f"WHERE {where_clause} AND revision = ?",
                params)
            if cursor.rowcount != 1:
                raise MrReviewError(
                    "revision-conflict",
                    f"{table} row {key!r} is not at revision {expected_revision}")
            return expected_revision + 1


    def import_snapshot(self, manifest: dict, bundle_path: Path,
                        *, lock_timeout: float = 60.0,
                        stale_after: float = 600.0) -> ImportResult:
        snap = verify_snapshot(manifest)
        self._check_identity(snap)
        with _ImportLock(self.root, timeout=lock_timeout, stale_after=stale_after):
            return self._import_locked(snap, Path(bundle_path))

    def _import_locked(self, snap: dict, bundle_path: Path) -> ImportResult:
        snapshot_id = snap["snapshot_id"]
        if self._snapshot_row(snapshot_id) is not None \
                and self._snapshot_file(snapshot_id) is not None:
            return ImportResult(snapshot_id, "already-imported", self._key, self.root)
        if not bundle_path.is_file():
            raise MrReviewError(
                "review-object-missing", f"code bundle not found: {bundle_path}")
        self.git.verify_bundle(bundle_path)
        heads = self.git.bundle_heads(bundle_path)
        namespace = f"refs/mr-review/{snapshot_id}"
        self.git.fetch_bundle(bundle_path, namespace, [ref for _, ref in heads])
        refs = snap["gitlab_diff_refs"]
        labeled_tips = {
            "source": snap["source_sha"],
            "target_tip": snap["target_tip_sha"],
            "diff_base": refs["base_sha"],
            "diff_start": refs["start_sha"],
            "diff_head": refs["head_sha"],
        }
        for label, sha in sorted(labeled_tips.items()):
            info = self.git.object_info(sha)
            if info.type != "commit":
                raise MrReviewError(
                    "review-object-missing",
                    f"{label} object {sha} is {info.type}, not a commit")
        for sha in snap["computed_merge_bases"]:
            info = self.git.object_info(sha)
            if info.type != "commit":
                raise MrReviewError(
                    "review-object-missing",
                    f"merge base {sha} is {info.type}, not a commit")
        for label, sha in sorted(labeled_tips.items()):
            self.git.update_ref(f"{namespace}/{label}", sha)
        self._install_snapshot_file(snap)
        with self.transaction() as conn:
            conn.execute(
                "INSERT OR IGNORE INTO snapshots (snapshot_id, code_id, version_fingerprint, "
                "repo, gitlab_url, target_project_id, iid, imported_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (snapshot_id, snap["code_id"], snap["version_fingerprint"],
                 snap["repo"], self._gitlab_url, self._target_project_id,
                 snap["iid"], _now()))
            conn.execute(
                "INSERT OR REPLACE INTO current_snapshot "
                "(repo, gitlab_url, target_project_id, iid, snapshot_id, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (self._repo, self._gitlab_url, self._target_project_id,
                 snap["iid"], snapshot_id, _now()))
        return ImportResult(snapshot_id, "imported", self._key, self.root)

    def _check_identity(self, snap: dict) -> None:
        if normalize_gitlab_url(snap["gitlab_url"]) != self._gitlab_url \
                or snap["target_project_id"] != self._target_project_id \
                or snap["repo"] != self._repo:
            raise MrReviewError(
                "invalid-schema",
                "snapshot identity (gitlab_url, target_project_id, repo) does not match this store")

    def _install_snapshot_file(self, snap: dict) -> None:
        snapshot_id = snap["snapshot_id"]
        data = canonical_json(snap).encode("utf-8")
        path = self._snapshots_dir / f"{snapshot_id}.json"
        if path.is_symlink():
            raise MrReviewError(
                "hash-mismatch", f"installed snapshot {snapshot_id} is a symlink")
        if path.exists():
            if path.read_bytes() == data:
                return
            raise MrReviewError(
                "hash-mismatch",
                f"installed snapshot {snapshot_id} differs from the manifest")
        temp = self._snapshots_dir / f".{snapshot_id}.{os.getpid()}.{uuid.uuid4().hex}.tmp"
        try:
            with open(temp, "wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp, path)
        finally:
            if temp.exists():
                try:
                    temp.unlink()
                except OSError:
                    pass

    def _snapshot_row(self, snapshot_id: str) -> sqlite3.Row | None:
        conn = self._connect()
        try:
            return conn.execute(
                "SELECT * FROM snapshots WHERE snapshot_id = ?", (snapshot_id,)).fetchone()
        finally:
            conn.close()

    def _snapshot_file(self, snapshot_id: str) -> bytes | None:
        path = resolve_contained(self._snapshots_dir, f"{snapshot_id}.json")
        if not path.is_file():
            return None
        return path.read_bytes()


    def snapshot_path(self, snapshot_id: str) -> Path:
        if not isinstance(snapshot_id, str) or not _HEX64_RE.fullmatch(snapshot_id):
            raise MrReviewError(
                "invalid-schema", f"snapshot_id must be 64-char lowercase hex, got {snapshot_id!r}")
        return resolve_contained(self._snapshots_dir, f"{snapshot_id}.json")

    def load_snapshot(self, snapshot_id: str) -> dict:
        path = self.snapshot_path(snapshot_id)
        if not path.is_file():
            raise MrReviewError(
                "review-object-missing", f"snapshot {snapshot_id} is not installed")
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            raise MrReviewError(
                "hash-mismatch", f"installed snapshot {snapshot_id} is unreadable") from None
        return verify_snapshot(data)

    def current_snapshot_id(self, iid: int) -> str | None:
        if not isinstance(iid, int) or isinstance(iid, bool) or iid < 1:
            raise MrReviewError("invalid-schema", f"iid must be an integer >= 1, got {iid!r}")
        conn = self._connect()
        try:
            row = conn.execute(
                "SELECT snapshot_id FROM current_snapshot "
                "WHERE repo = ? AND gitlab_url = ? AND target_project_id = ? AND iid = ?",
                (self._repo, self._gitlab_url, self._target_project_id, iid)).fetchone()
        finally:
            conn.close()
        return row["snapshot_id"] if row is not None else None

    def environment_path(self, environment_id: str) -> Path:
        """Reserved environments/<id>/ layout; creation is task 9. Rejects
        traversal/option-like ids and links that escape or dangle."""
        if not isinstance(environment_id, str) or not _ENV_ID_RE.fullmatch(environment_id):
            raise MrReviewError(
                "invalid-schema",
                f"environment_id must match [A-Za-z0-9][A-Za-z0-9_-]*, got {environment_id!r}")
        link = self._environments_dir / environment_id
        resolved = resolve_contained(self._environments_dir, environment_id)
        if os.path.lexists(link) and not os.path.exists(link):
            raise MrReviewError(
                "review-object-missing", f"environment {environment_id} is a dangling link")
        return resolved


def import_snapshot(manifest: dict, bundle_path: Path, *,
                     home: Path | str | None = None,
                     lock_timeout: float = 60.0,
                     stale_after: float = 600.0) -> ImportResult:
    """Import one validated snapshot manifest plus its code bundle into the
    store derived from the manifest identity. Returns the typed result
    (snapshot_id, status); nothing is installed on any validation failure."""
    snap = verify_snapshot(manifest)
    store = MrReviewStore(snap["gitlab_url"], snap["target_project_id"], snap["repo"],
                          home=home)
    return store.import_snapshot(manifest, bundle_path,
                                 lock_timeout=lock_timeout, stale_after=stale_after)
