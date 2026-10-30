"""Revisioned review findings with independent state axes and immutable
publication batches (plan section E, protocol sections 4-6).

Service: ``mr_review_findings(action=list|save|create_batch, file=..., text=...,
expected_revision=..., home=...)``. The JSON input arrives in exactly one of
``text`` / ``file``. The store is derived from the finding/query identity
(gitlab_url, target_project_id, repo); ``iid`` is a query filter, not a store
key.

Save contract: the input carries the FULL revision history; exactly one new
revision per save (``len(revisions) == expected_revision + 1``). Stored
revisions are immutable: input revisions 1..M must equal the stored canonical
payloads byte-wise. The new revision is bound to an installed snapshot (iid and
snapshot/code/fingerprint triple must match). State axes are independent and
move only along the transition tables below; a new revision may always start
at delivery=local (fresh unpublished draft), while a posted revision stays
posted forever and can never re-enter a batch.

List contract: bounded pages of at most LIST_PAGE_MAX findings; the cursor is
a plain integer offset into the deterministic finding_id-ordered result.

create_batch contract: input items select explicit stored revisions; only
verification=confirmed AND applicability=current revisions may enter; a
revision with delivery=posted or one already inside ANY stored immutable
batch is refused with possible-duplicate. The batch document is exactly
protocol section 5 shaped, stored immutably in the store batches table and
mirrored as canonical JSON bytes under <store_root>/batches/ for the
mr_replies_send review_file wiring (task 11).
"""
from __future__ import annotations

import json
import os
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path

from .mr_review_models import (
    BATCH_ITEM_KINDS,
    SCHEMA_VERSION,
    MrReviewError,
    batch_artifact_path,
    canonical_json,
    validate_batch,
    validate_finding,
)
from .mr_review_store import MrReviewStore, normalize_gitlab_url

LIST_PAGE_MAX = 50
LIST_PAGE_DEFAULT = 50

VERIFICATION_TRANSITIONS = {
    "hypothesis": frozenset({"hypothesis", "confirmed", "refuted"}),
    "confirmed": frozenset({"confirmed", "refuted"}),
    "refuted": frozenset({"refuted"}),
}
APPLICABILITY_TRANSITIONS = {
    "current": frozenset({"current", "needs-recheck", "withdrawn", "fixed"}),
    "needs-recheck": frozenset({"current", "needs-recheck", "withdrawn", "fixed"}),
    "withdrawn": frozenset({"withdrawn"}),
    "fixed": frozenset({"fixed"}),
}
DELIVERY_TRANSITIONS = {
    "local": frozenset({"local", "queued"}),
    "queued": frozenset({"queued", "received", "posted", "failed", "blocked"}),
    "received": frozenset({"received", "posted", "failed", "blocked"}),
    "failed": frozenset({"failed", "queued"}),
    "blocked": frozenset({"blocked", "queued"}),
    "posted": frozenset({"posted"}),
}

_ACTIONS = frozenset({"list", "save", "create_batch"})
_QUERY_KEYS = frozenset({"repo", "gitlab_url", "target_project_id", "iid"})
_LIST_KEYS = _QUERY_KEYS | frozenset({"cursor", "limit"})
_BATCH_KEYS = _QUERY_KEYS | frozenset({"items"})
_ITEM_KEYS = frozenset({"finding_id", "revision", "kind", "thread_id", "resolve", "body"})


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


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


def _exact_keys(obj: dict, allowed: frozenset[str], required: frozenset[str],
                field: str) -> None:
    unknown = set(obj) - allowed
    missing = required - set(obj)
    if unknown:
        raise MrReviewError("invalid-schema", f"{field}: unknown keys {sorted(unknown)}")
    if missing:
        raise MrReviewError("invalid-schema", f"{field}: missing keys {sorted(missing)}")


def _query_identity(query: dict, allowed: frozenset[str],
                    required: frozenset[str],
                    field: str = "query") -> tuple[str, str, int, int]:
    _exact_keys(query, allowed, required, field)
    repo = query["repo"]
    if not isinstance(repo, str) or not repo:
        raise MrReviewError("invalid-schema", "query.repo must be a non-empty string")
    gitlab_url = normalize_gitlab_url(query["gitlab_url"])
    target_project_id = query["target_project_id"]
    if not _is_int(target_project_id) or target_project_id < 1:
        raise MrReviewError(
            "invalid-schema", f"query.target_project_id must be an integer >= 1, got {target_project_id!r}")
    iid = query["iid"]
    if not _is_int(iid) or iid < 1:
        raise MrReviewError("invalid-schema", f"query.iid must be an integer >= 1, got {iid!r}")
    return repo, gitlab_url, target_project_id, iid


def _finding_row(store: MrReviewStore, finding_id: str) -> sqlite3.Row | None:
    with store.transaction() as conn:
        return conn.execute(
            "SELECT finding_id, repo, gitlab_url, target_project_id, iid, revision, payload "
            "FROM findings WHERE finding_id = ?", (finding_id,)).fetchone()


def _stored_revisions(store: MrReviewStore, finding_id: str) -> list[sqlite3.Row]:
    with store.transaction() as conn:
        return conn.execute(
            "SELECT revision, payload FROM finding_revisions WHERE finding_id = ? "
            "ORDER BY revision", (finding_id,)).fetchall()


def _check_axis(field: str, previous: str, new: str,
                transitions: dict[str, frozenset[str]]) -> None:
    if new not in transitions[previous]:
        raise MrReviewError(
            "invalid-schema",
            f"{field} transition {previous} -> {new} is not allowed "
            f"(allowed: {sorted(transitions[previous])})")


def _check_delivery(previous: str, new: str) -> None:
    allowed = DELIVERY_TRANSITIONS[previous] | {"local"}
    if new not in allowed:
        raise MrReviewError(
            "invalid-schema",
            f"delivery transition {previous} -> {new} is not allowed "
            f"(allowed: {sorted(allowed)}; a new revision may always start at local)")


def _bind_snapshot(store: MrReviewStore, revision: dict, iid: int) -> dict:
    snap = store.load_snapshot(revision["snapshot_id"])
    if snap["iid"] != iid:
        raise MrReviewError(
            "invalid-schema",
            f"snapshot {revision['snapshot_id']} belongs to MR !{snap['iid']}, finding is !{iid}")
    if snap["snapshot_id"] != revision["snapshot_id"] \
            or snap["code_id"] != revision["code_id"] \
            or snap["version_fingerprint"] != revision["version_fingerprint"]:
        raise MrReviewError(
            "invalid-schema",
            "revision snapshot_id/code_id/version_fingerprint do not match the installed snapshot")
    return snap


def _save(finding_input: dict, expected_revision: int,
          home: Path | str | None) -> dict:
    if not _is_int(expected_revision) or expected_revision < 0:
        raise MrReviewError(
            "invalid-schema",
            f"expected_revision must be an integer >= 0, got {expected_revision!r}")
    finding = validate_finding(finding_input)
    revisions = finding["revisions"]
    if len(revisions) != expected_revision + 1:
        raise MrReviewError(
            "invalid-schema",
            f"finding carries {len(revisions)} revisions; expected_revision "
            f"{expected_revision} requires exactly {expected_revision + 1}")
    finding_id = finding["finding_id"]
    gitlab_url = normalize_gitlab_url(finding["gitlab_url"])
    store = MrReviewStore(gitlab_url, finding["target_project_id"],
                          finding["repo"], home=home)

    if expected_revision > 0:
        row = _finding_row(store, finding_id)
        if row is None:
            raise MrReviewError(
                "revision-conflict",
                f"finding {finding_id} is not stored; expected_revision {expected_revision} cannot be met")
        if row["repo"] != finding["repo"] or row["gitlab_url"] != gitlab_url \
                or row["target_project_id"] != finding["target_project_id"] \
                or row["iid"] != finding["iid"]:
            raise MrReviewError(
                "invalid-schema",
                "finding identity (repo, gitlab_url, target_project_id, iid) does not match the stored row")
        stored = _stored_revisions(store, finding_id)
        if len(stored) != expected_revision:
            raise MrReviewError(
                "revision-conflict",
                f"finding {finding_id} has {len(stored)} stored revisions, "
                f"expected_revision is {expected_revision}")
        for i, stored_row in enumerate(stored):
            if stored_row["payload"] != canonical_json(revisions[i]):
                raise MrReviewError(
                    "invalid-schema", f"revision {i + 1} is immutable")
        previous = revisions[expected_revision - 1]
        new = revisions[expected_revision]
        _check_axis("verification", previous["verification"], new["verification"],
                    VERIFICATION_TRANSITIONS)
        _check_axis("applicability", previous["applicability"], new["applicability"],
                    APPLICABILITY_TRANSITIONS)
        _check_delivery(previous["delivery"], new["delivery"])

    latest = revisions[-1]
    _bind_snapshot(store, latest, finding["iid"])
    latest_json = canonical_json(latest)
    store.cas_write("findings", {"finding_id": finding_id}, expected_revision, {
        "repo": finding["repo"],
        "gitlab_url": gitlab_url,
        "target_project_id": finding["target_project_id"],
        "iid": finding["iid"],
        "payload": latest_json,
    })
    try:
        with store.transaction() as conn:
            conn.execute(
                "INSERT INTO finding_revisions (finding_id, revision, payload, created_at) "
                "VALUES (?, ?, ?, ?)",
                (finding_id, expected_revision + 1, latest_json, latest["created_at"]))
    except sqlite3.IntegrityError:
        raise MrReviewError(
            "revision-conflict",
            f"finding {finding_id} revision {expected_revision + 1} already exists") from None
    return {"status": "saved", "finding_id": finding_id,
            "revision": expected_revision + 1}


def _list(query: dict, home: Path | str | None) -> dict:
    repo, gitlab_url, target_project_id, iid = _query_identity(
        query, _LIST_KEYS, _QUERY_KEYS)
    cursor = query.get("cursor", 0)
    if not _is_int(cursor) or cursor < 0:
        raise MrReviewError(
            "invalid-schema", f"cursor must be a non-negative integer offset, got {cursor!r}")
    limit = query.get("limit", LIST_PAGE_DEFAULT)
    if not _is_int(limit) or limit < 1:
        raise MrReviewError(
            "invalid-schema", f"limit must be an integer >= 1, got {limit!r}")
    limit = min(limit, LIST_PAGE_MAX)
    store = MrReviewStore(gitlab_url, target_project_id, repo, home=home)
    where = ("repo = ? AND gitlab_url = ? AND target_project_id = ? AND iid = ?")
    params = (repo, gitlab_url, target_project_id, iid)
    with store.transaction() as conn:
        total = conn.execute(
            f"SELECT COUNT(*) FROM findings WHERE {where}", params).fetchone()[0]
        rows = conn.execute(
            f"SELECT finding_id, repo, gitlab_url, target_project_id, iid FROM findings "
            f"WHERE {where} ORDER BY finding_id LIMIT ? OFFSET ?",
            (*params, limit, cursor)).fetchall()
    findings = []
    for row in rows:
        revisions = [json.loads(stored["payload"])
                     for stored in _stored_revisions(store, row["finding_id"])]
        findings.append({
            "schema_version": SCHEMA_VERSION,
            "finding_id": row["finding_id"],
            "repo": row["repo"],
            "gitlab_url": row["gitlab_url"],
            "target_project_id": row["target_project_id"],
            "iid": row["iid"],
            "revisions": revisions,
        })
    next_cursor = cursor + len(findings) if cursor + len(findings) < total else None
    return {
        "status": "listed",
        "repo": repo, "gitlab_url": gitlab_url,
        "target_project_id": target_project_id, "iid": iid,
        "total": total, "offset": cursor, "limit": limit,
        "findings": findings, "next_cursor": next_cursor,
    }


def _revision_in_any_batch(store: MrReviewStore, finding_id: str,
                           revision: int) -> bool:
    with store.transaction() as conn:
        rows = conn.execute("SELECT payload FROM batches").fetchall()
    for row in rows:
        for item in json.loads(row["payload"])["items"]:
            if item["finding_id"] == finding_id and item["revision"] == revision:
                return True
    return False


def _batch_item(store: MrReviewStore, selection: dict, iid: int) -> tuple[dict, tuple[str, str, str]]:
    _exact_keys(selection, _ITEM_KEYS,
                frozenset({"finding_id", "revision", "kind"}), "batch item")
    finding_id = selection["finding_id"]
    if not isinstance(finding_id, str):
        raise MrReviewError("invalid-schema", "batch item.finding_id must be a string")
    revision = selection["revision"]
    if not _is_int(revision) or revision < 1:
        raise MrReviewError(
            "invalid-schema", f"batch item.revision must be an integer >= 1, got {revision!r}")
    kind = selection["kind"]
    if kind not in BATCH_ITEM_KINDS:
        raise MrReviewError(
            "invalid-schema", f"batch item.kind must be one of {sorted(BATCH_ITEM_KINDS)}, got {kind!r}")

    row = _finding_row(store, finding_id)
    if row is None:
        raise MrReviewError(
            "invalid-schema", f"finding {finding_id} is not stored in this review store")
    if row["iid"] != iid:
        raise MrReviewError(
            "invalid-schema",
            f"finding {finding_id} belongs to MR !{row['iid']}, batch is for !{iid}")
    stored = {stored["revision"]: stored["payload"]
              for stored in _stored_revisions(store, finding_id)}
    if revision not in stored:
        raise MrReviewError(
            "invalid-schema", f"finding {finding_id} has no stored revision {revision}")
    rev = json.loads(stored[revision])

    if rev["verification"] != "confirmed":
        raise MrReviewError(
            "invalid-schema",
            f"finding {finding_id} revision {revision} is verification={rev['verification']}; "
            "only confirmed revisions may enter a batch")
    if rev["applicability"] != "current":
        raise MrReviewError(
            "invalid-schema",
            f"finding {finding_id} revision {revision} is applicability={rev['applicability']}; "
            "only current revisions may enter a batch")
    if rev["delivery"] == "posted":
        raise MrReviewError(
            "possible-duplicate",
            f"finding {finding_id} revision {revision} is already posted and can never re-enter a batch")
    if _revision_in_any_batch(store, finding_id, revision):
        raise MrReviewError(
            "possible-duplicate",
            f"finding {finding_id} revision {revision} is already inside a stored batch")

    thread_id = selection.get("thread_id")
    if kind == "thread":
        if thread_id is None:
            thread_id = rev["thread_id"]
        if not isinstance(thread_id, str) or not thread_id:
            raise MrReviewError(
                "invalid-schema",
                f"batch item for finding {finding_id}: kind=thread requires a non-empty thread_id")
    elif thread_id is not None:
        raise MrReviewError(
            "invalid-schema",
            f"batch item for finding {finding_id}: thread_id is only valid for kind=thread")
    anchor = rev["anchor"] if kind == "anchored" else None
    if kind == "anchored" and anchor is None:
        raise MrReviewError(
            "invalid-schema",
            f"finding {finding_id} revision {revision} has no anchor; kind=anchored is impossible")
    body = selection.get("body")
    if body is None:
        body = rev["comment"]
    elif not isinstance(body, str) or not body:
        raise MrReviewError(
            "invalid-schema", "batch item.body must be a non-empty string")
    resolve = selection.get("resolve", False)
    if not isinstance(resolve, bool):
        raise MrReviewError("invalid-schema", "batch item.resolve must be a boolean")

    triple = (rev["snapshot_id"], rev["code_id"], rev["version_fingerprint"])
    item = {
        "finding_id": finding_id,
        "revision": revision,
        "kind": kind,
        "thread_id": thread_id,
        "resolve": resolve,
        "body": body,
        "anchor": anchor,
        "snapshot_id": triple[0],
        "code_id": triple[1],
        "version_fingerprint": triple[2],
    }
    return item, triple


def _write_batch_file(store: MrReviewStore, iid: int, batch: dict) -> Path:
    batches_dir = store.root / "batches"
    batches_dir.mkdir(exist_ok=True)
    name = f"mr-!{iid}-{batch['batch_id']}.review.json"
    data = canonical_json(batch).encode("utf-8")
    temp = batches_dir / f".{batch['batch_id']}.{os.getpid()}.tmp"
    try:
        with open(temp, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp, batches_dir / name)
    finally:
        if temp.exists():
            try:
                temp.unlink()
            except OSError:
                pass
    return batches_dir / name


def _create_batch(selection: dict, home: Path | str | None) -> dict:
    repo, gitlab_url, target_project_id, iid = _query_identity(
        selection, _BATCH_KEYS, _BATCH_KEYS, "batch selection")
    items_input = selection["items"]
    if not isinstance(items_input, list) or not items_input:
        raise MrReviewError("invalid-schema", "items must be a non-empty array")
    store = MrReviewStore(gitlab_url, target_project_id, repo, home=home)

    items: list[dict] = []
    triple: tuple[str, str, str] | None = None
    for i, item_input in enumerate(items_input):
        if not isinstance(item_input, dict):
            raise MrReviewError("invalid-schema", f"items[{i}] must be a JSON object")
        item, item_triple = _batch_item(store, item_input, iid)
        if triple is None:
            triple = item_triple
        elif triple != item_triple:
            raise MrReviewError(
                "invalid-schema",
                f"items[{i}]: all batch items must reference one snapshot identity")
        items.append(item)

    snap = store.load_snapshot(triple[0])
    if snap["iid"] != iid:
        raise MrReviewError(
            "invalid-schema",
            f"snapshot {triple[0]} belongs to MR !{snap['iid']}, batch is for !{iid}")
    if (snap["snapshot_id"], snap["code_id"], snap["version_fingerprint"]) != triple:
        raise MrReviewError(
            "invalid-schema",
            "batch snapshot identity does not match the installed snapshot")
    for item in items:
        item["source_sha"] = snap["source_sha"]
        item["target_tip_sha"] = snap["target_tip_sha"]
        item["diff_refs"] = dict(snap["gitlab_diff_refs"])

    batch = {
        "schema_version": SCHEMA_VERSION,
        "batch_id": str(uuid.uuid4()),
        "repo": repo,
        "gitlab_url": gitlab_url,
        "target_project_id": target_project_id,
        "iid": iid,
        "created_at": _now_iso(),
        "items": items,
    }
    validate_batch(batch)
    payload = canonical_json(batch)
    try:
        with store.transaction() as conn:
            conn.execute(
                "INSERT INTO batches (batch_id, payload, created_at) VALUES (?, ?, ?)",
                (batch["batch_id"], payload, batch["created_at"]))
    except sqlite3.IntegrityError:
        raise MrReviewError(
            "invalid-schema", f"batch {batch['batch_id']} already exists") from None
    path = _write_batch_file(store, iid, batch)
    return {
        "status": "created",
        "batch": batch,
        "batch_id": batch["batch_id"],
        "artifact_path": batch_artifact_path(iid, batch["batch_id"]),
        "path": str(path),
    }


def mr_review_findings(action: str, *, file: str = "", text: str = "",
                       expected_revision: int = 0,
                       home: Path | str | None = None) -> dict:
    """Save, list or batch-select revisioned review findings in the HOME store.

    ``action=save``: input is a full finding document (protocol section 4);
    ``expected_revision`` is the count of already stored revisions (0 for a
    new finding). ``action=list``: input is an identity query with optional
    integer ``cursor`` offset and ``limit`` (capped at LIST_PAGE_MAX).
    ``action=create_batch``: input is an identity plus explicit
    ``items: [{finding_id, revision, kind, thread_id?, resolve?, body?}]``.
    """
    if action not in _ACTIONS:
        raise MrReviewError(
            "invalid-schema", f"action must be one of {sorted(_ACTIONS)}, got {action!r}")
    payload = _input_json(file, text)
    if action == "save":
        return _save(payload, expected_revision, home)
    if action == "list":
        return _list(payload, home)
    return _create_batch(payload, home)
