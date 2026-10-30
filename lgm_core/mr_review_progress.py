"""Persisted MR review progress and snapshot refresh without false carryover.

The unit of progress is one inventory path of a snapshot (the reader's display
path: new_path when present, else old_path). Unit states are delivered
(content handed to the reviewer), checked (reviewed; requires snapshot-bound
evidence handles), deferred and not-applicable (both require a reason); a
path without a record is unchecked. Reads never mark: list and every reader
page leave the progress table and the current pointer untouched. Every state
write goes through the store progress table with per-row revision CAS
(expected_revision; a stale write fails with revision-conflict, never a
silent overwrite).

Evidence handles accepted for checked marks: cursor:<reader token>
(digest-verified, bound to the same snapshot), blob:<40-hex> (must exist in
objects.git), snapshot:<64-hex> (must be installed), probe:<environment_id>:
<probe_id> (stdout log must exist under probes/). Free-text summaries are
refused: no agent summary substitutes for actual evidence handles.

mr_review_refresh(repo, iid, previous_snapshot_id=..., home=...) compares the
previous installed snapshot with the current one and migrates progress:

- code_id unchanged (discussion-only or metadata-only change): every progress
  row carries to the new snapshot with its state intact plus carryover
  provenance; findings are not invalidated.
- code_id changed: a row keeps its state only when the unit's (old-side,
  new-side) blob OID pair is identical in both snapshots; every other
  recorded unit falls back to delivered with an explicit reason; units absent
  from the new inventory keep only their old-snapshot rows. Every finding of
  the MR whose latest revision is applicability=current and is bound to
  another snapshot triple gets one appended needs-recheck revision through
  the findings save service (the previous snapshot triple is kept because no
  revalidation happened; the trigger is the mechanical refresh fact; old and
  new snapshot ids are the evidence). Stored revisions are never mutated.
"""
from __future__ import annotations

import json
import re
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from .mr_review_findings import LIST_PAGE_DEFAULT, LIST_PAGE_MAX, mr_review_findings
from .mr_review_models import SCHEMA_VERSION, MrReviewError, canonical_json
from .mr_review_reader import MrReviewReader, peek_cursor_snapshot
from .mr_review_store import MrReviewStore, reviews_home

PROGRESS_STATES = frozenset({"delivered", "checked", "deferred", "not-applicable"})

_ACTIONS = frozenset({"list", "mark"})
_MARK_KEYS = frozenset({"snapshot_id", "path", "state", "reason", "evidence"})
_LIST_KEYS = frozenset({"snapshot_id", "cursor", "limit"})

_SHA_RE = re.compile(r"[0-9a-f]{40}")
_HEX64_RE = re.compile(r"[0-9a-f]{64}")
_ENV_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]*")
_PROBE_ID_RE = re.compile(r"[0-9a-f]{32}")


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _check_repo_iid(repo: str, iid: int) -> None:
    if not isinstance(repo, str) or not repo:
        raise MrReviewError("invalid-schema", "repo must be a non-empty string")
    if not _is_int(iid) or iid < 1:
        raise MrReviewError("invalid-schema", f"iid must be an integer >= 1, got {iid!r}")


def _input_json(file: str, text: str) -> dict:
    if bool(file) == bool(text):
        raise MrReviewError(
            "invalid-schema", "exactly one of file or text must carry the JSON input")
    raw = text
    if file:
        try:
            raw = Path(file).read_text(encoding="utf-8")
        except OSError:
            raise MrReviewError(
                "invalid-schema", f"input file is unreadable: {file}") from None
    try:
        value = json.loads(raw)
    except ValueError:
        raise MrReviewError("invalid-schema", "input is not valid JSON") from None
    if not isinstance(value, dict):
        raise MrReviewError("invalid-schema", "input must be a JSON object")
    return value


def _input_json_or_none(file: str, text: str) -> dict | None:
    if not file and not text:
        return None
    return _input_json(file, text)


def _exact_keys(obj: dict, allowed: frozenset[str], required: frozenset[str],
                field: str) -> None:
    unknown = set(obj) - allowed
    missing = required - set(obj)
    if unknown:
        raise MrReviewError("invalid-schema", f"{field}: unknown keys {sorted(unknown)}")
    if missing:
        raise MrReviewError("invalid-schema", f"{field}: missing keys {sorted(missing)}")


def _connect(db_path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, timeout=30)
    conn.row_factory = sqlite3.Row
    return conn


def _store_dirs(home: Path | str | None) -> list[Path]:
    root = reviews_home(home)
    if not root.is_dir():
        raise MrReviewError(
            "review-object-missing", f"no review store found under {root}")
    return sorted(child for child in root.iterdir() if child.is_dir())


def _scan_current(home: Path | str | None, repo: str, iid: int) -> MrReviewStore:
    matches: list[sqlite3.Row] = []
    for child in _store_dirs(home):
        db = child / "review.sqlite3"
        if not db.is_file():
            continue
        conn = _connect(db)
        try:
            matches.extend(conn.execute(
                "SELECT repo, gitlab_url, target_project_id, snapshot_id "
                "FROM current_snapshot WHERE repo = ? AND iid = ?",
                (repo, iid)).fetchall())
        finally:
            conn.close()
    if not matches:
        raise MrReviewError(
            "review-object-missing",
            f"no imported snapshot for MR !{iid} of repo {repo!r} under {reviews_home(home)}")
    if len({row["snapshot_id"] for row in matches}) > 1:
        raise MrReviewError(
            "invalid-schema",
            f"several stores hold a current snapshot for MR !{iid}; "
            "pass an explicit snapshot_id")
    row = matches[0]
    return MrReviewStore(row["gitlab_url"], row["target_project_id"], repo, home=home)


def _scan_snapshot(home: Path | str | None, repo: str,
                   snapshot_id: str) -> MrReviewStore:
    if not isinstance(snapshot_id, str) or not _HEX64_RE.fullmatch(snapshot_id):
        raise MrReviewError(
            "invalid-schema", f"snapshot_id must be 64-char lowercase hex, got {snapshot_id!r}")
    for child in _store_dirs(home):
        db = child / "review.sqlite3"
        if not db.is_file():
            continue
        conn = _connect(db)
        try:
            row = conn.execute(
                "SELECT repo, gitlab_url, target_project_id FROM snapshots "
                "WHERE snapshot_id = ?", (snapshot_id,)).fetchone()
        finally:
            conn.close()
        if row is None:
            continue
        if row["repo"] != repo:
            raise MrReviewError(
                "invalid-schema",
                f"snapshot {snapshot_id} belongs to repo {row['repo']!r}, requested {repo!r}")
        return MrReviewStore(row["gitlab_url"], row["target_project_id"], repo, home=home)
    raise MrReviewError(
        "review-object-missing",
        f"snapshot {snapshot_id} is not imported under {reviews_home(home)}")


def _resolve_snapshot(store: MrReviewStore, iid: int, snapshot_id: str) -> dict:
    if snapshot_id:
        snap = store.load_snapshot(snapshot_id)
        if snap["iid"] != iid:
            raise MrReviewError(
                "invalid-schema",
                f"snapshot {snapshot_id} belongs to MR !{snap['iid']}, requested !{iid}")
        return snap
    current = store.current_snapshot_id(iid)
    if current is None:
        raise MrReviewError(
            "review-object-missing", f"no current snapshot for MR !{iid} in this store")
    return store.load_snapshot(current)


def _snapshot_units(store: MrReviewStore,
                    snap: dict) -> dict[str, tuple[str | None, str | None]]:
    entries, _totals, _limited = MrReviewReader(store.git, snap)._inventory_entries()
    units: dict[str, tuple[str | None, str | None]] = {}
    for entry in entries:
        old = entry["old"]
        new = entry["new"]
        units[entry["path"]] = (
            old["oid"] if old is not None else None,
            new["oid"] if new is not None else None)
    return units


def _validate_evidence(store: MrReviewStore, snapshot_id: str,
                       evidence: object) -> list[str]:
    if not isinstance(evidence, list):
        raise MrReviewError("invalid-schema", "evidence must be an array of handle strings")
    handles: list[str] = []
    for item in evidence:
        if not isinstance(item, str) or not item:
            raise MrReviewError(
                "invalid-schema", "evidence entries must be non-empty handle strings")
        if item.startswith("cursor:"):
            if peek_cursor_snapshot(item[len("cursor:"):]) != snapshot_id:
                raise MrReviewError(
                    "invalid-schema", f"evidence cursor is not bound to snapshot {snapshot_id}")
        elif item.startswith("blob:"):
            oid = item[len("blob:"):]
            if not _SHA_RE.fullmatch(oid):
                raise MrReviewError(
                    "invalid-schema", f"evidence blob {oid!r} is not a 40-char lowercase hex OID")
            if not store.git.object_exists(oid):
                raise MrReviewError(
                    "review-object-missing", f"evidence blob {oid} is not present in the store")
        elif item.startswith("snapshot:"):
            sid = item[len("snapshot:"):]
            if not _HEX64_RE.fullmatch(sid):
                raise MrReviewError(
                    "invalid-schema", f"evidence snapshot {sid!r} is not a 64-char lowercase hex id")
            if not store.has_snapshot(sid):
                raise MrReviewError(
                    "review-object-missing", f"evidence snapshot {sid} is not installed")
        elif item.startswith("probe:"):
            parts = item[len("probe:"):].split(":")
            if len(parts) != 2 or not _ENV_ID_RE.fullmatch(parts[0]) \
                    or not _PROBE_ID_RE.fullmatch(parts[1]):
                raise MrReviewError(
                    "invalid-schema",
                    f"evidence probe {item!r} is not probe:<environment_id>:<probe_id>")
            if not (store.root / "probes" / parts[0] / f"{parts[1]}.stdout.log").is_file():
                raise MrReviewError(
                    "review-object-missing", f"evidence probe {item} has no captured logs")
        else:
            raise MrReviewError(
                "invalid-schema",
                f"evidence {item!r} is not a snapshot-bound handle "
                "(cursor:/blob:/snapshot:/probe:); free-text summaries are not evidence")
        handles.append(item)
    return handles


def _mark(repo: str, iid: int, payload: dict, expected_revision: int,
          home: Path | str | None) -> dict:
    _exact_keys(payload, _MARK_KEYS, frozenset({"path", "state"}), "mark")
    if not _is_int(expected_revision) or expected_revision < 0:
        raise MrReviewError(
            "invalid-schema",
            f"expected_revision must be an integer >= 0, got {expected_revision!r}")
    path = payload["path"]
    if not isinstance(path, str) or not path:
        raise MrReviewError("invalid-schema", "mark.path must be a non-empty string")
    state = payload["state"]
    if state not in PROGRESS_STATES:
        raise MrReviewError(
            "invalid-schema",
            f"mark.state must be one of {sorted(PROGRESS_STATES)}, got {state!r}")
    reason = payload.get("reason")
    if reason is not None and (not isinstance(reason, str) or not reason):
        raise MrReviewError("invalid-schema", "mark.reason must be null or a non-empty string")
    if state in ("deferred", "not-applicable") and not reason:
        raise MrReviewError(
            "invalid-schema", f"mark.state {state!r} requires a non-empty reason")
    evidence = payload.get("evidence", [])
    if state == "checked" and not evidence:
        raise MrReviewError(
            "invalid-schema",
            "mark.state 'checked' requires non-empty evidence handles; "
            "no agent free-text summary substitutes for actual evidence handles")

    snapshot_id = payload.get("snapshot_id", "")
    if not isinstance(snapshot_id, str):
        raise MrReviewError("invalid-schema", "mark.snapshot_id must be a string")
    store = _scan_snapshot(home, repo, snapshot_id) if snapshot_id \
        else _scan_current(home, repo, iid)
    snap = _resolve_snapshot(store, iid, snapshot_id)
    handles = _validate_evidence(store, snap["snapshot_id"], evidence)
    units = _snapshot_units(store, snap)
    if path not in units:
        raise MrReviewError(
            "invalid-schema",
            f"path {path!r} is not a unit of snapshot {snap['snapshot_id']}")

    record = {"state": state, "reason": reason, "evidence": handles,
              "marked_at": _now_iso()}
    revision = store.cas_write(
        "progress", {"snapshot_id": snap["snapshot_id"], "path": path},
        expected_revision, {"payload": canonical_json(record)})
    return {"status": "marked", "repo": repo, "iid": iid,
            "snapshot_id": snap["snapshot_id"], "path": path, "state": state,
            "revision": revision}


def _list(repo: str, iid: int, query: dict | None,
          home: Path | str | None) -> dict:
    query = query or {}
    _exact_keys(query, _LIST_KEYS, frozenset(), "list query")
    snapshot_id = query.get("snapshot_id", "")
    if not isinstance(snapshot_id, str):
        raise MrReviewError("invalid-schema", "snapshot_id must be a string")
    cursor = query.get("cursor", 0)
    if not _is_int(cursor) or cursor < 0:
        raise MrReviewError(
            "invalid-schema", f"cursor must be a non-negative integer offset, got {cursor!r}")
    limit = query.get("limit", LIST_PAGE_DEFAULT)
    if not _is_int(limit) or limit < 1:
        raise MrReviewError("invalid-schema", f"limit must be an integer >= 1, got {limit!r}")
    limit = min(limit, LIST_PAGE_MAX)

    store = _scan_snapshot(home, repo, snapshot_id) if snapshot_id \
        else _scan_current(home, repo, iid)
    snap = _resolve_snapshot(store, iid, snapshot_id)
    current_id = store.current_snapshot_id(iid)
    units = _snapshot_units(store, snap)
    with store.transaction() as conn:
        rows = conn.execute(
            "SELECT path, revision, payload FROM progress WHERE snapshot_id = ? ORDER BY path",
            (snap["snapshot_id"],)).fetchall()
    records = {row["path"]: (row["revision"], json.loads(row["payload"]))
               for row in rows}
    merged = sorted(set(units) | set(records))
    page = merged[cursor:cursor + limit]
    entries = []
    totals = {"units": len(merged), "delivered": 0, "checked": 0, "deferred": 0,
              "not-applicable": 0, "unchecked": 0, "not_in_inventory": 0}
    for unit_path in page:
        record = records.get(unit_path)
        if record is None:
            entry = {"path": unit_path, "state": "unchecked", "revision": 0,
                     "reason": None, "evidence": [], "marked_at": None,
                     "carryover": None, "in_inventory": unit_path in units}
            totals["unchecked"] += 1
        else:
            revision, payload = record
            entry = {
                "path": unit_path,
                "state": payload["state"],
                "revision": revision,
                "reason": payload.get("reason"),
                "evidence": payload.get("evidence", []),
                "marked_at": payload.get("marked_at"),
                "carryover": payload.get("carryover"),
                "in_inventory": unit_path in units,
            }
            totals[payload["state"]] += 1
        if unit_path not in units:
            totals["not_in_inventory"] += 1
        entries.append(entry)
    next_cursor = cursor + len(entries) if cursor + len(entries) < len(merged) else None
    return {
        "status": "listed",
        "repo": repo, "iid": iid,
        "snapshot_id": snap["snapshot_id"],
        "snapshot": {"code_id": snap["code_id"],
                     "version_fingerprint": snap["version_fingerprint"],
                     "is_current": current_id == snap["snapshot_id"]},
        "total": len(merged), "offset": cursor, "limit": limit,
        "units": entries, "next_cursor": next_cursor,
        "totals": totals,
    }


def _fingerprint_values(snap: dict) -> dict[str, object]:
    refs = snap["gitlab_diff_refs"]
    return {
        "gitlab_url": snap["gitlab_url"],
        "iid": snap["iid"],
        "source_project_id": snap["source_project_id"],
        "target_project_id": snap["target_project_id"],
        "source_sha": snap["source_sha"],
        "target_tip_sha": snap["target_tip_sha"],
        "target_branch": snap["target_branch"],
        "diff_refs.base_sha": refs["base_sha"],
        "diff_refs.start_sha": refs["start_sha"],
        "diff_refs.head_sha": refs["head_sha"],
    }


def _previous_snapshot(store: MrReviewStore, iid: int, current_id: str,
                       explicit: str) -> dict | None:
    if explicit:
        if explicit == current_id:
            raise MrReviewError(
                "invalid-schema", "previous_snapshot_id equals the current snapshot")
        snap = store.load_snapshot(explicit)
        if snap["iid"] != iid:
            raise MrReviewError(
                "invalid-schema",
                f"snapshot {explicit} belongs to MR !{snap['iid']}, requested !{iid}")
        return snap
    with store.transaction() as conn:
        row = conn.execute(
            "SELECT snapshot_id FROM snapshots WHERE iid = ? AND snapshot_id != ? "
            "ORDER BY imported_at DESC, snapshot_id LIMIT 1",
            (iid, current_id)).fetchone()
    if row is None:
        return None
    return store.load_snapshot(row["snapshot_id"])


def _invalidate_stale_findings(store: MrReviewStore, iid: int, old_snap: dict,
                               new_snap: dict, changed_fields: list[str],
                               home: Path | str | None) -> int:
    new_triple = (new_snap["snapshot_id"], new_snap["code_id"],
                  new_snap["version_fingerprint"])
    fields = ", ".join(changed_fields) if changed_fields else "code tips"
    trigger = f"snapshot refresh: code_id changed ({fields})"
    evidence = [f"snapshot:{old_snap['snapshot_id']}",
                f"snapshot:{new_snap['snapshot_id']}"]
    now = _now_iso()
    with store.transaction() as conn:
        rows = conn.execute(
            "SELECT finding_id, repo, gitlab_url, target_project_id, iid, payload "
            "FROM findings WHERE iid = ? ORDER BY finding_id", (iid,)).fetchall()
    invalidated = 0
    for row in rows:
        latest = json.loads(row["payload"])
        if latest["applicability"] != "current":
            continue
        if (latest["snapshot_id"], latest["code_id"],
                latest["version_fingerprint"]) == new_triple:
            continue
        with store.transaction() as conn:
            stored = conn.execute(
                "SELECT payload FROM finding_revisions WHERE finding_id = ? "
                "ORDER BY revision", (row["finding_id"],)).fetchall()
        revisions = [json.loads(item["payload"]) for item in stored]
        new_rev = dict(latest)
        new_rev["revision"] = len(revisions) + 1
        new_rev["created_at"] = now
        new_rev["applicability"] = "needs-recheck"
        new_rev["trigger"] = trigger
        new_rev["evidence"] = evidence
        revisions.append(new_rev)
        document = {
            "schema_version": SCHEMA_VERSION,
            "finding_id": row["finding_id"],
            "repo": row["repo"],
            "gitlab_url": row["gitlab_url"],
            "target_project_id": row["target_project_id"],
            "iid": row["iid"],
            "revisions": revisions,
        }
        mr_review_findings("save", text=json.dumps(document),
                           expected_revision=len(revisions) - 1, home=home)
        invalidated += 1
    return invalidated


def mr_review_refresh(repo: str, iid: int, *, previous_snapshot_id: str = "",
                      home: Path | str | None = None) -> dict:
    """Compare the previous installed snapshot with the current one and
    migrate progress plus finding applicability per the module rules.

    ``previous_snapshot_id`` empty derives the most recently imported other
    snapshot of the MR; an explicit id must be installed and belong to the
    same iid. The current pointer is never moved: the new snapshot was
    imported (and made current) before the refresh runs.
    """
    _check_repo_iid(repo, iid)
    store = _scan_current(home, repo, iid)
    current_id = store.current_snapshot_id(iid)
    new_snap = store.load_snapshot(current_id)
    old_snap = _previous_snapshot(store, iid, current_id, previous_snapshot_id)
    if old_snap is None:
        return {"status": "no-previous", "repo": repo, "iid": iid,
                "current_snapshot_id": current_id,
                "current_code_id": new_snap["code_id"],
                "progress": {"carried": 0, "fallback_delivered": 0,
                             "skipped_existing": 0, "dropped_absent": 0,
                             "total_old_rows": 0},
                "findings": {"invalidated": 0}}
    old_id = old_snap["snapshot_id"]
    old_values = _fingerprint_values(old_snap)
    new_values = _fingerprint_values(new_snap)
    changed_fields = sorted(key for key in old_values
                            if old_values[key] != new_values[key])
    code_changed = old_snap["code_id"] != new_snap["code_id"]

    with store.transaction() as conn:
        old_rows = conn.execute(
            "SELECT path, payload FROM progress WHERE snapshot_id = ? ORDER BY path",
            (old_id,)).fetchall()
    counts = {"carried": 0, "fallback_delivered": 0, "skipped_existing": 0,
              "dropped_absent": 0}
    if code_changed:
        old_units = _snapshot_units(store, old_snap)
        new_units = _snapshot_units(store, new_snap)
    else:
        old_units = new_units = None
    now = _now_iso()
    for row in old_rows:
        record = json.loads(row["payload"])
        path = row["path"]
        if new_units is not None and path not in new_units:
            counts["dropped_absent"] += 1
            continue
        identical = new_units is None or old_units.get(path) == new_units.get(path)
        carryover = {"from_snapshot_id": old_id, "state": record["state"], "at": now}
        if identical:
            new_record = {**record, "carryover": carryover}
        else:
            new_record = {
                "state": "delivered",
                "reason": f"snapshot refresh: blob OID changed; previous state "
                          f"{record['state']!r} not carried over",
                "evidence": [],
                "marked_at": now,
                "carryover": carryover,
            }
        try:
            store.cas_write("progress", {"snapshot_id": current_id, "path": path},
                            0, {"payload": canonical_json(new_record)})
        except MrReviewError as exc:
            if exc.code == "revision-conflict":
                counts["skipped_existing"] += 1
                continue
            raise
        counts["carried" if identical else "fallback_delivered"] += 1

    invalidated = 0
    if code_changed:
        invalidated = _invalidate_stale_findings(
            store, iid, old_snap, new_snap, changed_fields, home)

    return {
        "status": "refreshed",
        "repo": repo, "iid": iid,
        "previous_snapshot_id": old_id, "current_snapshot_id": current_id,
        "previous_code_id": old_snap["code_id"],
        "current_code_id": new_snap["code_id"],
        "code_changed": code_changed,
        "metadata": {
            "version_fingerprint_changed":
                old_snap["version_fingerprint"] != new_snap["version_fingerprint"],
            "changed_fields": changed_fields,
        },
        "progress": {**counts, "total_old_rows": len(old_rows)},
        "findings": {"invalidated": invalidated},
    }


def mr_review_progress(repo: str, iid: int, *, action: str = "list",
                       file: str = "", text: str = "",
                       expected_revision: int = 0,
                       home: Path | str | None = None) -> dict:
    """List or mark per-unit review progress for one MR in the HOME store.

    ``action=list``: optional JSON query ``{snapshot_id?, cursor?, limit?}``;
    empty input lists the current snapshot's first page. The cursor is a
    plain integer offset into the path-sorted unit list, the limit is capped
    at LIST_PAGE_MAX. ``action=mark``: JSON input ``{snapshot_id?, path,
    state, reason?, evidence?}`` in exactly one of ``file``/``text``;
    ``expected_revision`` is the unit row's stored revision (0 inserts a
    fresh row, a stale value fails with revision-conflict).
    """
    _check_repo_iid(repo, iid)
    if action not in _ACTIONS:
        raise MrReviewError(
            "invalid-schema", f"action must be one of {sorted(_ACTIONS)}, got {action!r}")
    if action == "mark":
        return _mark(repo, iid, _input_json(file, text), expected_revision, home)
    return _list(repo, iid, _input_json_or_none(file, text), home)
