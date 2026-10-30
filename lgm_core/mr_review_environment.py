"""Managed review environments: detached worktrees inside the HOME store.

Plan section D (normative): materialization checks out the exact snapshot
source SHA as a git worktree under environments/<environment_id>/ and never
writes the ordinary mirror checkout. A worktree isolates files only -
processes, network and caches are NOT isolated. Preparation runs no hooks,
no build, no submodule update, no LFS fetch and no smudge/clean filters:
every git subprocess runs with system/global config neutralized
(GIT_CONFIG_NOSYSTEM, HOME/XDG pointed at an empty store-owned directory,
every caller GIT_* env var scrubbed - config overrides as well as
GIT_DIR/GIT_WORK_TREE/GIT_INDEX_FILE redirection,
core.hooksPath/core.fsmonitor/core.autocrlf overridden). Gitlinks and LFS
pointer files are declared as incomplete-checkout limitations, never as a
fully ready environment. Probes execute caller-supplied local argv with the
environment as cwd and leave evidence logs under probes/<environment_id>/.
Release is explicit, clean-only and registered-only; dirty/busy/unknown are
typed refusals, never force/reset/clean.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import threading
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator

from .mr_review_git import resolve_contained
from .mr_review_models import MrReviewError, canonical_json, sha256_hex
from .mr_review_store import MrReviewStore

GIT_TIMEOUT_SECONDS = 300
PROBE_TIMEOUT_SECONDS = 600.0

_ACTIONS = frozenset({"create", "status", "release"})
_LFS_POINTER_HEADER = b"version https://git-lfs.github.com/spec/v1\n"
_LFS_POINTER_MAX_BYTES = 512
_FILES_ONLY_LIMITATION = (
    "worktree isolation is file-level only: processes, network and caches are not isolated")
_SUMMARY_PATH_LIMIT = 50

_SCHEMA_READY: set[str] = set()
_NOTES_LOCK = threading.Lock()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@dataclass(frozen=True)
class _EnvRow:
    environment_id: str
    snapshot_id: str
    source_sha: str
    purpose: str
    mode: str
    status: str


@dataclass(frozen=True)
class _EnvState:
    status: str
    tracked_digest: str
    tracked_count: int
    untracked_paths: list[str]
    ignored_paths: list[str]


def _ensure_schema(store: MrReviewStore) -> None:
    key = str(store.root)
    if key in _SCHEMA_READY:
        return
    with store.transaction() as conn:
        conn.execute(
            "CREATE TABLE IF NOT EXISTS mr_review_checkout_notes ("
            "source_sha TEXT PRIMARY KEY, limitations TEXT NOT NULL)")
        conn.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS mr_review_env_reusable "
            "ON environments (snapshot_id) WHERE mode = 'reusable'")
    _SCHEMA_READY.add(key)


def _isolated_dir(store: MrReviewStore) -> Path:
    path = store.root / "environments" / ".git-isolated"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _git_env(store: MrReviewStore) -> dict[str, str]:
    isolated = _isolated_dir(store)
    env: dict[str, str] = {}
    for key, value in os.environ.items():
        if key.upper().startswith("GIT_"):
            continue
        env[key] = value
    env["GIT_CONFIG_NOSYSTEM"] = "1"
    env["HOME"] = str(isolated)
    env["XDG_CONFIG_HOME"] = str(isolated)
    return env


def _stderr(proc: subprocess.CompletedProcess) -> str:
    return (proc.stderr or b"").decode("utf-8", "replace").strip()


def _run_git(store: MrReviewStore, *args: str, cwd: Path | None = None,
             timeout: int = GIT_TIMEOUT_SECONDS) -> subprocess.CompletedProcess:
    isolated = _isolated_dir(store)
    command = [
        "git",
        "-c", f"core.hooksPath={isolated}".replace("\\", "/"),
        "-c", "core.fsmonitor=false",
        "-c", "core.autocrlf=false",
        "-C", str(cwd if cwd is not None else store.root / "objects.git"),
        *args,
    ]
    return subprocess.run(command, capture_output=True, env=_git_env(store),
                          timeout=timeout, check=False)


def _discard_tree(path: Path) -> None:
    # git marks object/pack files read-only on Windows; plain rmtree fails on them
    def _reset(func, target, _exc):
        os.chmod(target, 0o666)
        func(target)

    shutil.rmtree(path, onexc=_reset)


def _env_row(store: MrReviewStore, environment_id: str) -> _EnvRow | None:
    with store.transaction() as conn:
        row = conn.execute(
            "SELECT environment_id, snapshot_id, source_sha, purpose, mode, status "
            "FROM environments WHERE environment_id = ?", (environment_id,)).fetchone()
    return _EnvRow(**dict(row)) if row is not None else None


def _reusable_row(store: MrReviewStore, snapshot_id: str) -> _EnvRow | None:
    with store.transaction() as conn:
        row = conn.execute(
            "SELECT environment_id, snapshot_id, source_sha, purpose, mode, status "
            "FROM environments WHERE snapshot_id = ? AND mode = 'reusable'",
            (snapshot_id,)).fetchone()
    return _EnvRow(**dict(row)) if row is not None else None


def _insert_row(store: MrReviewStore, row: _EnvRow) -> bool:
    try:
        with store.transaction() as conn:
            conn.execute(
                "INSERT INTO environments (environment_id, snapshot_id, source_sha, purpose, "
                "mode, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (row.environment_id, row.snapshot_id, row.source_sha, row.purpose,
                 row.mode, row.status, _now(), _now()))
    except sqlite3.IntegrityError:
        return False
    return True


def _delete_row(store: MrReviewStore, environment_id: str) -> None:
    with store.transaction() as conn:
        conn.execute("DELETE FROM environments WHERE environment_id = ?", (environment_id,))


def _acquire_lease(store: MrReviewStore, environment_id: str) -> bool:
    with store.transaction() as conn:
        cursor = conn.execute(
            "UPDATE environments SET status = 'leased', updated_at = ? "
            "WHERE environment_id = ? AND status = 'active'",
            (_now(), environment_id))
        acquired = cursor.rowcount == 1
    return acquired


def _release_lease(store: MrReviewStore, environment_id: str) -> None:
    with store.transaction() as conn:
        conn.execute(
            "UPDATE environments SET status = 'active', updated_at = ? "
            "WHERE environment_id = ? AND status = 'leased'",
            (_now(), environment_id))


@contextmanager
def environment_lease(store: MrReviewStore, environment_id: str) -> Iterator[None]:
    """Hold the LGM busy-lease on a registered environment. The lease marks
    the environment busy for LGM operations (probe runs, release); it is not
    a detector of external processes."""
    store.environment_path(environment_id)
    if _env_row(store, environment_id) is None:
        raise MrReviewError(
            "review-object-missing", f"environment {environment_id} is not registered")
    if not _acquire_lease(store, environment_id):
        raise MrReviewError(
            "store-locked", f"environment {environment_id} is busy: an LGM lease is already held")
    try:
        yield
    finally:
        _release_lease(store, environment_id)


def _tree_entries(store: MrReviewStore, source_sha: str) -> list[tuple[str, str, int | None, str]]:
    proc = _run_git(store, "ls-tree", "-r", "-l", "-z", source_sha)
    if proc.returncode != 0:
        raise MrReviewError("review-object-missing", f"git ls-tree failed: {_stderr(proc)}")
    entries: list[tuple[str, str, int | None, str]] = []
    for record in proc.stdout.split(b"\x00"):
        if not record:
            continue
        meta, _, path = record.partition(b"\t")
        fields = meta.split()
        if len(fields) != 4:
            continue
        size: int | None
        size = int(fields[3]) if fields[3].isdigit() else None
        entries.append((fields[0].decode("ascii"), fields[2].decode("ascii"),
                        size, path.decode("utf-8", "replace")))
    return entries


def _gitlink_paths(entries: list[tuple[str, str, int | None, str]]) -> list[str]:
    return [path for mode, _, _, path in entries if mode == "160000"]


def _pointer_count(store: MrReviewStore, source_sha: str,
                   entries: list[tuple[str, str, int | None, str]]) -> int:
    proc = _run_git(store, "grep", "-l", "-z", "-F", "-e",
                    "version https://git-lfs.github.com/spec/v1", source_sha)
    if proc.returncode not in (0, 1):
        raise MrReviewError("review-object-missing", f"git grep failed: {_stderr(proc)}")
    if proc.returncode == 1:
        return 0
    prefix = f"{source_sha}:".encode("ascii")
    by_path = {path: (oid, size) for _, oid, size, path in entries}
    candidates: list[tuple[str, int]] = []
    for record in proc.stdout.split(b"\x00"):
        if not record.startswith(prefix):
            continue
        path = record[len(prefix):].decode("utf-8", "replace")
        entry = by_path.get(path)
        if entry is not None and entry[1] is not None and entry[1] <= _LFS_POINTER_MAX_BYTES:
            candidates.append((entry[0], entry[1]))
    if not candidates:
        return 0
    proc = subprocess.run(
        ["git", "-C", str(store.root / "objects.git"), "cat-file", "--batch"],
        input="".join(f"{oid}\n" for oid, _ in candidates).encode("ascii"),
        capture_output=True, env=_git_env(store),
        timeout=GIT_TIMEOUT_SECONDS, check=False)
    if proc.returncode != 0:
        raise MrReviewError("review-object-missing", f"git cat-file failed: {_stderr(proc)}")
    data = proc.stdout
    position = 0
    pointers = 0
    for oid, size in candidates:
        nl = data.find(b"\n", position)
        if nl < 0:
            raise MrReviewError("review-object-missing", f"cat-file stream ended before {oid}")
        fields = data[position:nl].split()
        position = nl + 1
        if len(fields) != 3 or fields[0].decode("ascii", "replace") != oid \
                or not fields[2].isdigit():
            raise MrReviewError("review-object-missing", f"unexpected cat-file record for {oid}")
        if data[position:position + len(_LFS_POINTER_HEADER)] == _LFS_POINTER_HEADER:
            pointers += 1
        position += size + 1
    return pointers


def _checkout_limitations(store: MrReviewStore, source_sha: str) -> list[str]:
    with _NOTES_LOCK:
        with store.transaction() as conn:
            row = conn.execute(
                "SELECT limitations FROM mr_review_checkout_notes WHERE source_sha = ?",
                (source_sha,)).fetchone()
        if row is not None:
            return json.loads(row["limitations"])
        entries = _tree_entries(store, source_sha)
        gitlinks = _gitlink_paths(entries)
        pointers = _pointer_count(store, source_sha, entries)
        limitations: list[str] = []
        if gitlinks:
            limitations.append(
                f"incomplete-checkout: {len(gitlinks)} gitlink path(s) present; "
                "submodule content is not checked out")
        if pointers:
            limitations.append(
                f"incomplete-checkout: {pointers} LFS pointer file(s) present; "
                "large content is not fetched")
        with store.transaction() as conn:
            conn.execute(
                "INSERT OR REPLACE INTO mr_review_checkout_notes (source_sha, limitations) "
                "VALUES (?, ?)", (source_sha, json.dumps(limitations)))
        return limitations


def _worktree_state(store: MrReviewStore, env_path: Path) -> _EnvState:
    proc = _run_git(store, "status", "--porcelain=v1", "-z", "--ignored", cwd=env_path)
    if proc.returncode != 0:
        raise MrReviewError("review-object-missing", f"git status failed: {_stderr(proc)}")
    tracked: list[bytes] = []
    untracked: list[str] = []
    ignored: list[str] = []
    for record in proc.stdout.split(b"\x00"):
        if not record:
            continue
        if record.startswith(b"?? "):
            untracked.append(record[3:].decode("utf-8", "replace"))
        elif record.startswith(b"!! "):
            ignored.append(record[3:].decode("utf-8", "replace"))
        else:
            tracked.append(record)
    status = "dirty" if tracked or untracked or ignored else "clean"
    return _EnvState(status, hashlib.sha256(b"\x00".join(sorted(tracked))).hexdigest(),
                     len(tracked), untracked, ignored)


def _current_state(store: MrReviewStore, row: _EnvRow,
                   env_path: Path) -> tuple[str, _EnvState | None]:
    if not env_path.exists():
        return "missing", None
    state = _worktree_state(store, env_path)
    if row.status == "leased":
        return "busy", state
    return state.status, state


def _summary(paths: list[str]) -> dict:
    return {"count": len(paths), "paths": paths[:_SUMMARY_PATH_LIMIT]}


def _existing_result(store: MrReviewStore, row: _EnvRow) -> dict:
    env_path = store.environment_path(row.environment_id)
    state_status, state = _current_state(store, row, env_path)
    extra: list[str] = []
    if state_status == "missing":
        extra.append("environment directory is missing: release the stale registration "
                     "explicitly or pass fresh=true")
    elif state_status == "busy":
        extra.append("environment is busy: an LGM lease is held; pass fresh=true or wait "
                     "for the lease to be released")
    elif state is not None and state.status == "dirty":
        extra.append(f"environment is dirty ({state.tracked_count} tracked change(s), "
                     f"{len(state.untracked_paths)} untracked, {len(state.ignored_paths)} ignored): "
                     "pass fresh=true for an independent environment or resolve the existing "
                     "one explicitly")
    return {
        "action": "create",
        "environment_id": row.environment_id,
        "path": str(env_path),
        "sha": row.source_sha,
        "mode": "reused",
        "purpose": row.purpose,
        "status": state_status,
        "limitations": [*_checkout_limitations(store, row.source_sha), *extra,
                        _FILES_ONLY_LIMITATION],
    }


def _cleanup_unissued(store: MrReviewStore, env_path: Path) -> None:
    if env_path.exists():
        _discard_tree(env_path)
    _run_git(store, "worktree", "prune")


def _create(store: MrReviewStore, snapshot_id: str, purpose: str, fresh: bool) -> dict:
    if not snapshot_id:
        raise MrReviewError("invalid-schema", "create requires a snapshot_id")
    snap = store.load_snapshot(snapshot_id)
    source_sha = snap["source_sha"]
    info = store.git.object_info(source_sha)
    if info.type != "commit":
        raise MrReviewError(
            "review-object-missing", f"source {source_sha} is {info.type}, not a commit")
    if not fresh:
        existing = _reusable_row(store, snapshot_id)
        if existing is not None:
            return _existing_result(store, existing)
    env_id = f"env-{uuid.uuid4().hex}"
    env_path = store.environment_path(env_id)
    mode = "fresh" if fresh else "reusable"
    if not _insert_row(store, _EnvRow(env_id, snapshot_id, source_sha, purpose, mode, "active")):
        winner = _reusable_row(store, snapshot_id)
        if winner is None:
            raise MrReviewError(
                "revision-conflict", "environment registration raced; retry the call")
        return _existing_result(store, winner)
    proc = _run_git(store, "worktree", "add", "--detach", "--", str(env_path), source_sha)
    if proc.returncode != 0:
        _delete_row(store, env_id)
        _cleanup_unissued(store, env_path)
        raise MrReviewError(
            "review-object-missing", f"git worktree add failed: {_stderr(proc)}")
    return {
        "action": "create",
        "environment_id": env_id,
        "path": str(env_path),
        "sha": source_sha,
        "mode": "fresh",
        "purpose": purpose,
        "status": "clean",
        "limitations": [*_checkout_limitations(store, source_sha), _FILES_ONLY_LIMITATION],
    }


def _status(store: MrReviewStore, environment_id: str) -> dict:
    env_path = store.environment_path(environment_id)
    row = _env_row(store, environment_id)
    if row is None:
        raise MrReviewError(
            "review-object-missing", f"environment {environment_id} is not registered")
    state_status, state = _current_state(store, row, env_path)
    return {
        "action": "status",
        "environment_id": environment_id,
        "path": str(env_path),
        "sha": row.source_sha,
        "mode": row.mode,
        "purpose": row.purpose,
        "status": state_status,
        "tracked_diff_digest": state.tracked_digest if state is not None else "",
        "untracked": _summary(state.untracked_paths) if state is not None
        else {"count": 0, "paths": []},
        "ignored": _summary(state.ignored_paths) if state is not None
        else {"count": 0, "paths": []},
        "limitations": [*_checkout_limitations(store, row.source_sha), _FILES_ONLY_LIMITATION],
    }


def _release(store: MrReviewStore, environment_id: str) -> dict:
    env_path = store.environment_path(environment_id)
    row = _env_row(store, environment_id)
    if row is None:
        raise MrReviewError(
            "review-object-missing", f"environment {environment_id} is not registered")
    if row.status == "leased":
        raise MrReviewError(
            "store-locked", f"environment {environment_id} is busy: an LGM lease is held")
    if not env_path.exists():
        _run_git(store, "worktree", "prune")
        _delete_row(store, environment_id)
        return {"action": "release", "environment_id": environment_id, "status": "released"}
    state = _worktree_state(store, env_path)
    if state.status != "clean":
        raise MrReviewError(
            "incomplete-checkout",
            f"environment {environment_id} is not clean ({state.tracked_count} tracked "
            f"change(s), {len(state.untracked_paths)} untracked, {len(state.ignored_paths)} "
            "ignored); release requires zero tracked modifications and zero "
            "untracked/ignored outputs")
    for gitlink in _gitlink_paths(_tree_entries(store, row.source_sha)):
        target = env_path / gitlink
        if target.is_dir() and any(target.iterdir()):
            raise MrReviewError(
                "incomplete-checkout",
                f"gitlink directory {gitlink} contains untracked content; "
                "release requires it empty")
    if not _acquire_lease(store, environment_id):
        raise MrReviewError(
            "store-locked", f"environment {environment_id} is busy: an LGM lease is held")
    try:
        proc = _run_git(store, "worktree", "remove", str(env_path))
        if proc.returncode != 0:
            raise MrReviewError(
                "review-object-missing", f"git worktree remove failed: {_stderr(proc)}")
        _delete_row(store, environment_id)
    finally:
        _release_lease(store, environment_id)
    return {"action": "release", "environment_id": environment_id, "status": "released"}


def mr_review_materialize(store: MrReviewStore, action: str, *,
                          environment_id: str = "", purpose: str = "",
                          fresh: bool = False, snapshot_id: str = "") -> dict:
    """Materialization service on a HOME review store.

    action=create: detached worktree at the snapshot's exact source_sha.
    One reusable clean environment per snapshot by default (mode=reused);
    fresh=true creates an independent environment (mode=fresh). A dirty or
    busy reusable environment is never reset: its status is returned with a
    limitation demanding fresh=true or explicit resolution. action=status:
    registered state (clean/dirty/busy/missing), tracked-diff digest and
    untracked/ignored summaries. action=release: explicit removal of a clean
    registered environment; dirty -> incomplete-checkout, busy ->
    store-locked, unknown registration -> review-object-missing.
    """
    if not isinstance(action, str) or action not in _ACTIONS:
        raise MrReviewError(
            "invalid-schema", f"action must be one of {sorted(_ACTIONS)}, got {action!r}")
    if not isinstance(environment_id, str) or not isinstance(purpose, str) \
            or not isinstance(snapshot_id, str):
        raise MrReviewError(
            "invalid-schema", "environment_id, purpose and snapshot_id must be strings")
    if not isinstance(fresh, bool):
        raise MrReviewError("invalid-schema", f"fresh must be a boolean, got {fresh!r}")
    _ensure_schema(store)
    if action == "create":
        if environment_id:
            raise MrReviewError(
                "invalid-schema",
                "create issues its own environment ids; environment_id must be empty")
        return _create(store, snapshot_id, purpose, fresh)
    if not environment_id:
        raise MrReviewError("invalid-schema", f"{action} requires environment_id")
    if fresh or snapshot_id:
        raise MrReviewError(
            "invalid-schema", "fresh and snapshot_id are only valid for action=create")
    if action == "status":
        return _status(store, environment_id)
    return _release(store, environment_id)


def _probes_dir(store: MrReviewStore, environment_id: str) -> Path:
    probes_root = store.root / "probes"
    probes_root.mkdir(exist_ok=True)
    path = resolve_contained(probes_root, environment_id)
    if os.path.lexists(path) and not path.is_dir():
        raise MrReviewError(
            "invalid-schema", f"probes path for {environment_id} is not a directory")
    path.mkdir(parents=True, exist_ok=True)
    return path


def mr_review_probe(store: MrReviewStore, environment_id: str, argv: list[str], *,
                    test_count: int | None = None,
                    timeout: float = PROBE_TIMEOUT_SECONDS) -> dict:
    """Run caller-supplied local argv with the environment as cwd and return
    the evidence record. stdout/stderr land under probes/<environment_id>/;
    the tracked-diff digests before and after the run prove whether the
    probe kept the no-tracked-writes contract. test_count is recorded only
    when the caller knows it: a missing count is never 0 and never success.
    The probe holds the environment lease for its duration."""
    env_path = store.environment_path(environment_id)
    row = _env_row(store, environment_id)
    if row is None:
        raise MrReviewError(
            "review-object-missing", f"environment {environment_id} is not registered")
    if not env_path.exists():
        raise MrReviewError(
            "review-object-missing", f"environment {environment_id} directory is missing")
    if not isinstance(argv, list) or not argv \
            or any(not isinstance(arg, str) or not arg for arg in argv):
        raise MrReviewError("invalid-schema", "argv must be a non-empty list of non-empty strings")
    if test_count is not None and (not isinstance(test_count, int)
                                   or isinstance(test_count, bool) or test_count < 0):
        raise MrReviewError(
            "invalid-schema", f"test_count must be a non-negative integer or null, got {test_count!r}")
    if not isinstance(timeout, (int, float)) or isinstance(timeout, bool) or timeout <= 0:
        raise MrReviewError("invalid-schema", f"timeout must be a positive number, got {timeout!r}")
    with environment_lease(store, environment_id):
        before = _worktree_state(store, env_path).tracked_digest
        probe_id = uuid.uuid4().hex
        probes_dir = _probes_dir(store, environment_id)
        stdout_path = probes_dir / f"{probe_id}.stdout.log"
        stderr_path = probes_dir / f"{probe_id}.stderr.log"
        exit_code: int | None
        try:
            proc = subprocess.run(argv, cwd=str(env_path), capture_output=True,
                                  timeout=timeout, check=False)
            exit_code = proc.returncode
            stdout, stderr = proc.stdout, proc.stderr
        except subprocess.TimeoutExpired as exc:
            exit_code = None
            stdout = exc.stdout if isinstance(exc.stdout, bytes) else b""
            stderr = exc.stderr if isinstance(exc.stderr, bytes) else b""
        except FileNotFoundError as exc:
            raise MrReviewError(
                "invalid-schema", f"probe command not found: {argv[0]!r}") from exc
        stdout_path.write_bytes(stdout)
        stderr_path.write_bytes(stderr)
        after = _worktree_state(store, env_path).tracked_digest
    return {
        "argv": list(argv),
        "cwd": str(env_path),
        "source_sha": row.source_sha,
        "environment_id": environment_id,
        "tracked_diff_digest": after,
        "tracked_diff_digest_before": before,
        "probe_digest": sha256_hex(canonical_json({"argv": list(argv)})),
        "exit_code": exit_code,
        "log_paths": {
            "stdout": f"probes/{environment_id}/{probe_id}.stdout.log",
            "stderr": f"probes/{environment_id}/{probe_id}.stderr.log",
        },
        "test_count": test_count,
    }
