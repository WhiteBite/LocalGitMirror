from __future__ import annotations

import json
import os
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path

from .client import LgmError
from .mr_review_findings import DELIVERY_TRANSITIONS, mr_review_findings
from .mr_review_models import (
    CODE_ARTIFACT_MAX_BYTES,
    SCHEMA_VERSION,
    MrReviewError,
    canonical_json,
    validate_receipt,
)
from .mr_review_store import MrReviewStore, normalize_gitlab_url, reviews_home

JOURNAL_SCHEMA_VERSION = 1
RECEIPT_ENVELOPE_KIND = "review-receipts"
RECEIPT_DIR = "mr-review/receipts/"
TERMINAL_RECEIPT_OUTCOMES = frozenset({"posted", "recovered"})

_RECEIPT_FILE_RE = re.compile(
    r"mr-!(\d+)-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
    r"\.attempt-(\d+)\.receipt\.json$")
_UUID_RE = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
_ISO_RE = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})")
_SAFE_REPO_RE = re.compile(r"[^A-Za-z0-9._-]")
_ENVELOPE_FIELDS = frozenset({
    "schema_version", "kind", "repo", "gitlab_url", "target_project_id",
    "iid", "batch_id", "attempt", "created_at", "receipts",
})
_DELIVERY_TARGET = {"posted": "posted", "recovered": "posted",
                    "failed": "failed", "blocked": "blocked"}


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _safe_repo_dir(repo: str) -> str:
    if not isinstance(repo, str) or not repo:
        raise MrReviewError("invalid-schema", "repo must be a non-empty string")
    name = _SAFE_REPO_RE.sub("_", repo)
    if name in (".", ".."):
        raise MrReviewError("invalid-schema", f"repo is not usable as a directory: {repo!r}")
    return name


def journal_dir(repo: str, home: Path | str | None = None) -> Path:
    return reviews_home(home) / "journal" / _safe_repo_dir(repo)


def journal_path(repo: str, iid: int, batch_id: str,
                 home: Path | str | None = None) -> Path:
    if not _UUID_RE.fullmatch(batch_id or ""):
        raise MrReviewError("invalid-schema", f"batch_id must be a canonical UUID, got {batch_id!r}")
    return journal_dir(repo, home) / f"mr-!{iid}-{batch_id}.journal.json"


def receipt_artifact_match(path: str):
    if not isinstance(path, str) or not path.startswith(RECEIPT_DIR):
        return None
    return _RECEIPT_FILE_RE.search(path)


def receipt_artifact_path(iid: int, batch_id: str, attempt: int) -> str:
    if not _UUID_RE.fullmatch(batch_id or ""):
        raise MrReviewError("invalid-schema", f"batch_id must be a canonical UUID, got {batch_id!r}")
    if not _is_int(attempt) or attempt < 1:
        raise MrReviewError("invalid-schema", f"attempt must be an integer >= 1, got {attempt!r}")
    return f"{RECEIPT_DIR}mr-!{iid}-{batch_id}.attempt-{attempt}.receipt.json"


def _atomic_write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(f".{path.name}.{os.getpid()}.{uuid.uuid4().hex}.tmp")
    try:
        with open(temp, "wb") as handle:
            handle.write(canonical_json(payload).encode("utf-8"))
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp, path)
    finally:
        if temp.exists():
            try:
                temp.unlink()
            except OSError:
                pass


class ReviewJournal:
    """WORK-side per-batch publication journal.

    Every mutation rewrites the whole file atomically (temp + fsync +
    os.replace), so a crash at any point leaves either the previous or the
    new valid document. An attempt is only eligible for receipt handoff once
    ``completed_at`` is set; entry outcomes with discussion/note ids and the
    note permalink are recorded before any receipt or ack step. An unreadable
    journal file is replaced by a fresh one: protocol marker scans remain the
    backstop against duplicate posts.
    """

    def __init__(self, path: Path, data: dict | None = None) -> None:
        self.path = path
        self.data = self._load() if data is None else data

    def _load(self) -> dict | None:
        if not self.path.is_file():
            return None
        try:
            value = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        if not isinstance(value, dict) or value.get("schema_version") != JOURNAL_SCHEMA_VERSION \
                or not isinstance(value.get("attempts"), list):
            return None
        return value

    def _persist(self) -> None:
        _atomic_write_json(self.path, self.data)

    @property
    def readable(self) -> bool:
        return self.data is not None

    def begin_attempt(self, postbox_item_id: str, postbox_path: str) -> dict:
        attempts = self.data["attempts"]
        attempt = {
            "attempt": max((a.get("attempt") or 0) for a in attempts) + 1 if attempts else 1,
            "started_at": _now_iso(),
            "completed_at": None,
            "web_url": "",
            "postbox_item_id": postbox_item_id,
            "postbox_path": postbox_path,
            "receipt_uploaded_at": None,
            "receipt_path": None,
            "acked_at": None,
            "entries": [],
        }
        attempts.append(attempt)
        self._persist()
        return attempt

    def set_web_url(self, attempt: dict, web_url: str) -> None:
        if web_url and attempt.get("web_url") != web_url:
            attempt["web_url"] = web_url
            self._persist()

    def _entry(self, attempt: dict, finding_id: str, revision: int) -> dict | None:
        return next((e for e in attempt["entries"]
                     if e.get("finding_id") == finding_id and e.get("revision") == revision),
                    None)

    def record_post_start(self, attempt: dict, entry: dict) -> None:
        record = self._entry(attempt, entry["finding_id"], entry["revision"])
        if record is None:
            record = {"finding_id": entry["finding_id"], "revision": entry["revision"],
                      "kind": entry["kind"], "marker": entry["marker"]}
            attempt["entries"].append(record)
        record.update({"state": "post_start", "post_attempted": True,
                       "started_at": _now_iso(), "finished_at": None, "outcome": None,
                       "code": "", "error": None, "discussion_id": None,
                       "note_id": None, "url": None, "recovered_from": None})
        self._persist()

    def record_result(self, attempt: dict, result: dict, *,
                      post_attempted: bool, recovered_from: str | None = None) -> None:
        record = self._entry(attempt, result["finding_id"], result["revision"])
        if record is None:
            record = {"finding_id": result["finding_id"], "revision": result["revision"],
                      "kind": result["kind"], "marker": result.get("marker"),
                      "started_at": attempt["started_at"]}
            attempt["entries"].append(record)
        record.update({
            "state": "done",
            "post_attempted": post_attempted or record.get("post_attempted", False),
            "outcome": result["outcome"],
            "code": result.get("code") or "",
            "error": result.get("error"),
            "discussion_id": result.get("discussion_id"),
            "note_id": result.get("note_id"),
            "url": result.get("url"),
            "finished_at": _now_iso(),
            "recovered_from": recovered_from,
        })
        self._persist()

    def update_entry_ids(self, attempt: dict, finding_id: str, revision: int,
                         discussion_id: str | None, url: str | None) -> None:
        record = self._entry(attempt, finding_id, revision)
        if record is None:
            return
        record["discussion_id"] = discussion_id
        if url:
            record["url"] = url
        self._persist()

    def complete_attempt(self, attempt: dict) -> None:
        attempt["completed_at"] = _now_iso()
        self._persist()

    def mark_receipt_uploaded(self, attempt: dict, path: str) -> None:
        attempt["receipt_uploaded_at"] = _now_iso()
        attempt["receipt_path"] = path
        self._persist()

    def mark_acked(self, attempt: dict) -> None:
        attempt["acked_at"] = _now_iso()
        self._persist()

    def posted_entries(self) -> dict[tuple[str, int], dict]:
        """(finding_id, revision) -> ids of entries a previous attempt really
        POSTed; these must never be POSTed again even when the live marker
        scan no longer sees them."""
        posted: dict[tuple[str, int], dict] = {}
        for attempt in self.data["attempts"]:
            for ent in attempt.get("entries") or []:
                if ent.get("state") == "done" and ent.get("outcome") == "posted":
                    posted[(ent["finding_id"], ent["revision"])] = {
                        "discussion_id": ent.get("discussion_id"),
                        "note_id": ent.get("note_id"),
                        "batch_id": self.data["batch_id"],
                        "url": ent.get("url"),
                    }
        return posted

    def uncertain_entries(self) -> dict[tuple[str, int], int]:
        """(finding_id, revision) -> attempt number whose latest record is a
        post_start without a recorded response: the POST outcome is unknown
        and a second POST is forbidden."""
        latest: dict[tuple[str, int], tuple[int, str]] = {}
        for attempt in self.data["attempts"]:
            number = attempt.get("attempt") or 0
            for ent in attempt.get("entries") or []:
                key = (ent["finding_id"], ent["revision"])
                state = ent.get("state") or ""
                if state == "done" or key not in latest or latest[key][0] <= number:
                    latest[key] = (number, state)
        return {key: number for key, (number, state) in latest.items()
                if state == "post_start"}


def open_journal(repo: str, iid: int, doc: dict, postbox_item_id: str,
                 postbox_path: str, home: Path | str | None = None) -> ReviewJournal:
    batch = doc["batch"]
    header = {
        "schema_version": JOURNAL_SCHEMA_VERSION,
        "repo": batch["repo"],
        "iid": iid,
        "batch_id": doc["batch_id"],
        "gitlab_url": batch["gitlab_url"],
        "target_project_id": batch["target_project_id"],
        "identity": dict(doc["identity"]),
        "refs": {"source_sha": doc["refs"]["source_sha"],
                 "target_tip_sha": doc["refs"]["target_tip_sha"],
                 "diff_refs": dict(doc["refs"]["diff_refs"])},
        "attempts": [],
    }
    path = journal_path(repo, iid, doc["batch_id"], home)
    journal = ReviewJournal(path)
    journal.replaced_corrupt = path.exists() and journal.data is None
    if journal.data is None:
        journal.data = header
    return journal


def _mapped_outcome(ent: dict) -> tuple[str, str | None]:
    outcome = ent.get("outcome") or "blocked"
    post_attempted = bool(ent.get("post_attempted"))
    error = ent.get("error")
    if outcome == "posted":
        mapped = "posted"
    elif outcome == "skipped-duplicate":
        mapped = "recovered"
    else:
        mapped = "failed" if post_attempted else "blocked"
    if mapped in TERMINAL_RECEIPT_OUTCOMES:
        discussion_id = ent.get("discussion_id") or None
        note_id = ent.get("note_id")
        url = ent.get("url") or None
        if discussion_id is None or note_id is None or url is None:
            mapped = "blocked"
            error = (f"note published but receipt incomplete: discussion_id={discussion_id!r}, "
                     f"note_id={note_id!r}, url={url!r} — ids must be recovered before ack")
        else:
            error = None
    if mapped not in TERMINAL_RECEIPT_OUTCOMES and not error:
        error = f"entry ended {outcome} without a recorded reason"
    return mapped, error


def attempt_receipt_outcomes(header: dict, attempt: dict) -> list[str]:
    return [_mapped_outcome(ent)[0] for ent in attempt.get("entries") or []]


def _receipt_for_entry(header: dict, attempt: dict, ent: dict, mr_url: str) -> dict:
    mapped, error = _mapped_outcome(ent)
    refs = None
    if ent.get("post_attempted") or mapped in TERMINAL_RECEIPT_OUTCOMES:
        refs = {
            "source_sha": header["refs"]["source_sha"],
            "target_tip_sha": header["refs"]["target_tip_sha"],
            "base_sha": header["refs"]["diff_refs"]["base_sha"],
            "start_sha": header["refs"]["diff_refs"]["start_sha"],
            "head_sha": header["refs"]["diff_refs"]["head_sha"],
        }
    receipt = {
        "schema_version": SCHEMA_VERSION,
        "batch_id": header["batch_id"],
        "finding_id": ent["finding_id"],
        "revision": ent["revision"],
        "outcome": mapped,
        "kind": ent.get("kind") or "general",
        "snapshot_id": header["identity"]["snapshot_id"],
        "code_id": header["identity"]["code_id"],
        "version_fingerprint": header["identity"]["version_fingerprint"],
        "discussion_id": ent.get("discussion_id") or None,
        "note_id": ent.get("note_id"),
        "url": ent.get("url") or None,
        "mr_url": mr_url,
        "submitted_refs": refs,
        "fallback": False,
        "attempted_at": ent.get("started_at") or attempt["started_at"],
        "finished_at": ent.get("finished_at"),
        "error": error,
    }
    return validate_receipt(receipt)


def build_receipt_envelope(data: dict, attempt: dict) -> dict:
    """Deterministic receipt envelope for one completed attempt: rebuilding it
    from the same journal state yields byte-identical canonical JSON, so a
    re-upload after a crash can never fork the immutable (batch, attempt)
    receipt."""
    mr_url = attempt.get("web_url") or data["gitlab_url"]
    receipts = [_receipt_for_entry(data, attempt, ent, mr_url)
                for ent in attempt.get("entries") or []]
    if not receipts:
        raise MrReviewError("invalid-schema",
                            f"attempt {attempt.get('attempt')} of batch {data['batch_id']} has no entries")
    return {
        "schema_version": JOURNAL_SCHEMA_VERSION,
        "kind": RECEIPT_ENVELOPE_KIND,
        "repo": data["repo"],
        "gitlab_url": data["gitlab_url"],
        "target_project_id": data["target_project_id"],
        "iid": data["iid"],
        "batch_id": data["batch_id"],
        "attempt": attempt["attempt"],
        "created_at": attempt["completed_at"],
        "receipts": receipts,
    }


def validate_receipt_envelope(value: object) -> dict:
    if not isinstance(value, dict):
        raise MrReviewError("invalid-schema", "receipt envelope must be a JSON object")
    unknown = set(value) - _ENVELOPE_FIELDS
    missing = _ENVELOPE_FIELDS - set(value)
    if unknown or missing:
        raise MrReviewError(
            "invalid-schema",
            f"receipt envelope keys: unknown {sorted(unknown)}, missing {sorted(missing)}")
    if value["schema_version"] != JOURNAL_SCHEMA_VERSION:
        raise MrReviewError(
            "invalid-schema", f"receipt envelope schema_version must be 1, got {value['schema_version']!r}")
    if value["kind"] != RECEIPT_ENVELOPE_KIND:
        raise MrReviewError("invalid-schema", f"receipt envelope kind must be {RECEIPT_ENVELOPE_KIND!r}")
    if not isinstance(value["repo"], str) or not value["repo"]:
        raise MrReviewError("invalid-schema", "receipt envelope repo must be a non-empty string")
    gitlab_url = normalize_gitlab_url(value["gitlab_url"])
    for field in ("target_project_id", "iid", "attempt"):
        if not _is_int(value[field]) or value[field] < 1:
            raise MrReviewError("invalid-schema", f"receipt envelope {field} must be an integer >= 1")
    batch_id = value["batch_id"]
    if not isinstance(batch_id, str) or not _UUID_RE.fullmatch(batch_id):
        raise MrReviewError("invalid-schema", f"receipt envelope batch_id must be a canonical UUID, got {batch_id!r}")
    created_at = value["created_at"]
    if not isinstance(created_at, str) or not _ISO_RE.fullmatch(created_at):
        raise MrReviewError("invalid-schema", "receipt envelope created_at must be an ISO-8601 timestamp")
    raw_receipts = value["receipts"]
    if not isinstance(raw_receipts, list) or not raw_receipts:
        raise MrReviewError("invalid-schema", "receipt envelope receipts must be a non-empty array")
    seen: set[tuple[str, int]] = set()
    receipts: list[dict] = []
    for i, raw in enumerate(raw_receipts):
        receipt = validate_receipt(raw)
        if receipt["batch_id"] != batch_id:
            raise MrReviewError(
                "invalid-schema", f"receipts[{i}] belongs to batch {receipt['batch_id']}, envelope to {batch_id}")
        key = (receipt["finding_id"], receipt["revision"])
        if key in seen:
            raise MrReviewError("invalid-schema", f"receipts[{i}]: duplicate finding revision {key}")
        seen.add(key)
        receipts.append(receipt)
    return {**value, "gitlab_url": gitlab_url, "receipts": receipts}


def run_receipt_handoff(c, repo: str, *, home: Path | str | None = None,
                        iid: int = 0, dry_run: bool = False) -> dict:
    """WORK: persist journal -> upload detailed receipt -> ack batch handoff.

    Processes every completed journal attempt of the repo that has no receipt
    in the postbox yet; a receipt upload failure never acks. The batch item is
    acked only when the receipt upload succeeded and every entry of the attempt
    reached a terminal outcome (posted/recovered); blocked or failed entries
    keep the batch pending for a protected retry.
    """
    out: dict = {"uploaded": [], "acked": [], "failed": [], "pending": []}
    jdir = journal_dir(repo, home)
    if not jdir.is_dir():
        return out
    for jpath in sorted(jdir.glob("mr-!*.journal.json")):
        journal = ReviewJournal(jpath)
        if not journal.readable:
            out["failed"].append({"journal": jpath.name, "error": "journal is unreadable"})
            continue
        data = journal.data
        if iid and data.get("iid") != iid:
            continue
        for attempt in data.get("attempts") or []:
            where = {"batch_id": data.get("batch_id"), "attempt": attempt.get("attempt")}
            if not attempt.get("completed_at"):
                continue
            if not attempt.get("receipt_uploaded_at"):
                try:
                    envelope = build_receipt_envelope(data, attempt)
                except MrReviewError as e:
                    out["failed"].append({**where, "error": f"receipt: {e.message}"})
                    continue
                path = receipt_artifact_path(data["iid"], data["batch_id"], attempt["attempt"])
                if dry_run:
                    out["pending"].append({**where, "path": path, "would": "upload"})
                    continue
                payload = canonical_json(envelope).encode("utf-8")
                try:
                    c.file_sync_send(repo, path, len(payload), payload)
                except LgmError as e:
                    out["failed"].append({**where, "path": path, "error": f"receipt upload: {e.message}"})
                    continue
                journal.mark_receipt_uploaded(attempt, path)
                out["uploaded"].append(path)
            outcomes = attempt_receipt_outcomes(data, attempt)
            if not outcomes or any(o not in TERMINAL_RECEIPT_OUTCOMES for o in outcomes):
                continue
            if attempt.get("acked_at"):
                continue
            item_id = attempt.get("postbox_item_id") or ""
            if not item_id:
                out["failed"].append({**where, "error": "attempt carries no postbox item id"})
                continue
            if dry_run:
                out["pending"].append({**where, "item_id": item_id, "would": "ack"})
                continue
            try:
                c.file_sync_ack(repo, item_id)
            except LgmError as e:
                out["failed"].append({**where, "item_id": item_id, "error": f"ack: {e.message}"})
                continue
            journal.mark_acked(attempt)
            out["acked"].append(item_id)
    return out


def _import_receipt_row(store: MrReviewStore, receipt: dict, attempt: int) -> bool:
    payload = canonical_json(receipt)
    with store.transaction() as conn:
        row = conn.execute(
            "SELECT payload FROM receipts WHERE batch_id = ? AND finding_id = ? "
            "AND revision = ? AND attempt = ?",
            (receipt["batch_id"], receipt["finding_id"], receipt["revision"], attempt)).fetchone()
        if row is not None:
            if row["payload"] != payload:
                raise MrReviewError(
                    "hash-mismatch",
                    f"receipt {receipt['finding_id']} revision {receipt['revision']} "
                    f"attempt {attempt} differs from the stored immutable row")
            return False
        conn.execute(
            "INSERT INTO receipts (batch_id, finding_id, revision, attempt, payload) "
            "VALUES (?, ?, ?, ?, ?)",
            (receipt["batch_id"], receipt["finding_id"], receipt["revision"], attempt, payload))
        return True


def _delivery_path(current: str, target: str) -> list[str]:
    if target in DELIVERY_TRANSITIONS[current]:
        return [target]
    if "queued" in DELIVERY_TRANSITIONS[current] \
            and target in DELIVERY_TRANSITIONS["queued"]:
        return ["queued", target]
    raise MrReviewError(
        "invalid-schema", f"no legal delivery path from {current} to {target}")


def _evidence_ref(envelope: dict, receipt: dict) -> str:
    parts = [f"receipt: batch {receipt['batch_id']} attempt {envelope['attempt']} "
             f"outcome {receipt['outcome']}"]
    if receipt.get("discussion_id"):
        parts.append(f"discussion {receipt['discussion_id']}")
    if receipt.get("note_id") is not None:
        parts.append(f"note {receipt['note_id']}")
    if receipt.get("url"):
        parts.append(receipt["url"])
    return ", ".join(parts)


def _apply_receipt_delivery(store: MrReviewStore, envelope: dict, receipt: dict,
                            home: Path | str | None) -> int:
    """Append finding revisions along legal delivery transitions until the
    receipt's outcome state is reached; stored revisions are never mutated.
    Idempotent: a finding already at (or terminal past) the target state is
    left alone, so a re-import after a crash between the receipt row insert
    and this step converges instead of double-appending."""
    target = _DELIVERY_TARGET[receipt["outcome"]]
    finding_id = receipt["finding_id"]
    with store.transaction() as conn:
        row = conn.execute(
            "SELECT repo, gitlab_url, target_project_id, iid FROM findings "
            "WHERE finding_id = ?", (finding_id,)).fetchone()
        if row is None:
            raise MrReviewError(
                "invalid-schema", f"finding {finding_id} is not stored in this review store")
        if row["iid"] != envelope["iid"]:
            raise MrReviewError(
                "invalid-schema",
                f"finding {finding_id} belongs to MR !{row['iid']}, receipt is for !{envelope['iid']}")
        rev_rows = conn.execute(
            "SELECT payload FROM finding_revisions WHERE finding_id = ? ORDER BY revision",
            (finding_id,)).fetchall()
    revisions = [json.loads(r["payload"]) for r in rev_rows]
    current = revisions[-1]["delivery"]
    if current == target or current == "posted":
        return 0
    steps = _delivery_path(current, target)
    appended = 0
    for i, step in enumerate(steps):
        latest = revisions[-1]
        evidence = list(latest["evidence"])
        if i == len(steps) - 1:
            evidence.append(_evidence_ref(envelope, receipt))
        else:
            evidence.append(f"delivery {step}: receipt batch {envelope['batch_id']} "
                            f"attempt {envelope['attempt']} en route to {target}")
        new_revision = {**latest, "revision": len(revisions) + 1,
                        "created_at": _now_iso(), "delivery": step, "evidence": evidence}
        doc = {"schema_version": SCHEMA_VERSION, "finding_id": finding_id,
               "repo": row["repo"], "gitlab_url": row["gitlab_url"],
               "target_project_id": row["target_project_id"], "iid": row["iid"],
               "revisions": [*revisions, new_revision]}
        mr_review_findings("save", text=canonical_json(doc),
                           expected_revision=len(revisions), home=home)
        revisions.append(new_revision)
        appended += 1
    return appended


def run_receipt_import(c, repo: str, *, home: Path | str | None = None,
                       iid: int = 0, dry_run: bool = False) -> dict:
    """HOME: import receipt artifacts into the review store BEFORE acking them.

    Per artifact, ascending by (batch, attempt): every receipt row goes into
    the store receipts table (immutable — a differing payload for the same key
    is hash-mismatch), then finding delivery states are appended through the
    findings save service; the postbox artifact is acked only when all of its
    receipts imported without error.
    """
    items = c.file_sync_list(repo).get("items") or []
    artifacts: list[tuple[str, int, int, dict]] = []
    for i in items:
        path = i.get("path") or ""
        if not path.startswith(RECEIPT_DIR):
            continue
        m = _RECEIPT_FILE_RE.search(path)
        if not m:
            continue
        if iid and int(m.group(1)) != iid:
            continue
        artifacts.append((m.group(2), int(m.group(3)), int(m.group(1)), i))
    artifacts.sort(key=lambda t: (t[0], t[1]))
    results: list[dict] = []
    for batch_id, attempt_no, art_iid, item in artifacts:
        entry: dict = {"path": item["path"], "iid": art_iid, "batch_id": batch_id,
                       "attempt": attempt_no, "imported": 0, "skipped": 0,
                       "delivery_updates": 0, "acked": False, "errors": []}
        results.append(entry)
        try:
            data = c.file_sync_fetch(repo, item["id"])
            if len(data) > CODE_ARTIFACT_MAX_BYTES:
                raise LgmError(
                    "server",
                    f"receipt envelope is {len(data)} bytes, above the "
                    f"{CODE_ARTIFACT_MAX_BYTES} byte cap")
            raw = data.decode("utf-8")
            envelope = validate_receipt_envelope(json.loads(raw))
        except (LgmError, ValueError, UnicodeDecodeError, MrReviewError) as e:
            entry["errors"].append(f"envelope: {getattr(e, 'message', None) or e}")
            continue
        if envelope["batch_id"] != batch_id or envelope["attempt"] != attempt_no \
                or envelope["iid"] != art_iid:
            entry["errors"].append("envelope identity does not match the artifact path")
            continue
        if dry_run:
            entry["imported"] = len(envelope["receipts"])
            continue
        try:
            store = MrReviewStore(envelope["gitlab_url"], envelope["target_project_id"],
                                  envelope["repo"], home=home)
        except MrReviewError as e:
            entry["errors"].append(f"store: {e.code}: {e.message}")
            continue
        for receipt in envelope["receipts"]:
            label = f"{receipt['finding_id']} r{receipt['revision']}"
            try:
                if _import_receipt_row(store, receipt, attempt_no):
                    entry["imported"] += 1
                else:
                    entry["skipped"] += 1
                entry["delivery_updates"] += _apply_receipt_delivery(store, envelope, receipt, home)
            except MrReviewError as e:
                entry["errors"].append(f"{label}: {e.code}: {e.message}")
        if entry["errors"]:
            continue
        try:
            c.file_sync_ack(repo, item["id"])
            entry["acked"] = True
        except LgmError as e:
            entry["errors"].append(f"ack: {e.message}")
    return {"success": True, "repo": repo, "dry_run": dry_run,
            "count": len(results), "artifacts": results}


def summarize_receipt_artifacts(c, repo: str, items: list) -> list[dict]:
    """Latest attempt per (iid, batch) of the receipt artifacts in the postbox,
    with per-finding states; best effort — an unreadable artifact keeps its
    path and an error instead of failing the whole view."""
    newest: dict[tuple[int, str], tuple[int, dict]] = {}
    for i in items:
        path = i.get("path") or ""
        if not path.startswith(RECEIPT_DIR):
            continue
        m = _RECEIPT_FILE_RE.search(path)
        if not m:
            continue
        key = (int(m.group(1)), m.group(2))
        attempt = int(m.group(3))
        current = newest.get(key)
        if current is None or attempt > current[0]:
            newest[key] = (attempt, i)
    out: list[dict] = []
    for (art_iid, batch_id), (attempt, item) in sorted(newest.items()):
        summary: dict = {"iid": art_iid, "batch_id": batch_id, "attempt": attempt,
                         "path": item["path"], "outcomes": {}, "findings": []}
        try:
            envelope = validate_receipt_envelope(
                json.loads(c.file_sync_fetch(repo, item["id"]).decode("utf-8")))
        except Exception:
            summary["error"] = "fetch/decrypt failed (v3 relay)"
            out.append(summary)
            continue
        for r in envelope["receipts"]:
            summary["outcomes"][r["outcome"]] = summary["outcomes"].get(r["outcome"], 0) + 1
            summary["findings"].append({
                "finding_id": r["finding_id"], "revision": r["revision"],
                "outcome": r["outcome"], "note_id": r["note_id"], "url": r["url"],
            })
        out.append(summary)
    return out
