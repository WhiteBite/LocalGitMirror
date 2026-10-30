"""Snapshot-bound MR review protocol: canonical JSON, digests, strict models.

Normative spec: docs/MR_REVIEW_PROTOCOL.md. Kotlin counterpart:
idea-plugin/src/main/kotlin/localgitmirror/idea/gitlab/MrReviewProtocol.kt.
Both consume tests/fixtures/mr_review_protocol/ and must produce identical
canonical bytes and SHA-256 digests. Stdlib only; validation is strict at the
boundary and never defaults identity, refs or side fields.
"""
from __future__ import annotations

import hashlib
import json
import re
from typing import Any

SCHEMA_VERSION = 1
SUPPORTED_SCHEMA_VERSIONS = frozenset({1})

BUDGET_BYTES_MIN = 4096
BUDGET_BYTES_MAX = 48000
CODE_ARTIFACT_MAX_BYTES = 128 * 1024 * 1024

ERROR_CODES = frozenset({
    "snapshot-raced",
    "diff-not-ready",
    "review-object-missing",
    "review-package-too-large",
    "stale-snapshot",
    "possible-duplicate",
    "incomplete-checkout",
    "invalid-schema",
    "unknown-schema-version",
    "invalid-side",
    "invalid-line",
    "hash-mismatch",
    "invalid-marker",
    "invalid-budget",
    "revision-conflict",
    "store-locked",
})

COMPARISON_KINDS = frozenset({"gitlab-diff"})
MR_STATES = frozenset({"opened", "closed", "locked", "merged"})
SIDES = frozenset({"old", "new"})
VERIFICATION_STATES = frozenset({"hypothesis", "confirmed", "refuted"})
APPLICABILITY_STATES = frozenset({"current", "needs-recheck", "withdrawn", "fixed"})
DELIVERY_STATES = frozenset({"local", "queued", "received", "posted", "failed", "blocked"})
BATCH_ITEM_KINDS = frozenset({"anchored", "thread", "general"})
RECEIPT_OUTCOMES = frozenset({"posted", "failed", "blocked", "recovered"})

SNAPSHOT_FIELDS = frozenset({
    "schema_version", "snapshot_id", "code_id", "version_fingerprint",
    "repo", "gitlab_url", "source_project_id", "target_project_id", "iid",
    "mr_url", "title", "description", "state", "draft",
    "source_branch", "target_branch", "source_sha", "target_tip_sha",
    "source_fetch_ref", "gitlab_diff_refs", "computed_merge_bases",
    "comparison_base_sha", "comparison_kind",
    "observed_started_at", "observed_finished_at", "mr_updated_at",
    "discussions_fetched_at", "discussions_complete", "limitations",
    "discussions",
})
DIFF_REFS_FIELDS = frozenset({"base_sha", "start_sha", "head_sha"})
DISCUSSION_FIELDS = frozenset({"discussion_id", "notes"})
NOTE_FIELDS = frozenset({
    "note_id", "body", "author", "created_at",
    "system", "resolvable", "resolved", "position",
})
POSITION_FIELDS = frozenset({
    "old_path", "new_path", "old_line", "new_line", "side",
    "base_sha", "start_sha", "head_sha",
})
ANCHOR_FIELDS = frozenset({"old_path", "new_path", "side", "line"})
FINDING_FIELDS = frozenset({
    "schema_version", "finding_id", "repo", "gitlab_url",
    "target_project_id", "iid", "revisions",
})
REVISION_FIELDS = frozenset({
    "revision", "created_at", "snapshot_id", "code_id", "version_fingerprint",
    "anchor", "thread_id", "comment", "trigger", "evidence", "limitations",
    "verification", "applicability", "delivery",
})
BATCH_FIELDS = frozenset({
    "schema_version", "batch_id", "repo", "gitlab_url",
    "target_project_id", "iid", "created_at", "items",
})
BATCH_ITEM_FIELDS = frozenset({
    "finding_id", "revision", "kind", "thread_id", "resolve", "body", "anchor",
    "snapshot_id", "code_id", "version_fingerprint",
    "source_sha", "target_tip_sha", "diff_refs",
})
RECEIPT_FIELDS = frozenset({
    "schema_version", "batch_id", "finding_id", "revision", "outcome", "kind",
    "snapshot_id", "code_id", "version_fingerprint",
    "discussion_id", "note_id", "url", "mr_url", "submitted_refs",
    "fallback", "attempted_at", "finished_at", "error",
})
SUBMITTED_REFS_FIELDS = frozenset({
    "source_sha", "target_tip_sha", "base_sha", "start_sha", "head_sha",
})
ENVELOPE_FIELDS = frozenset({
    "schema_version", "snapshot_id", "code_id", "code", "snapshot",
    "prepared_at", "limitations",
})
ENVELOPE_CODE_FIELDS = frozenset({"bundle_sha256", "bundle_size"})

_SHA_RE = re.compile(r"[0-9a-f]{40}")
_HEX64_RE = re.compile(r"[0-9a-f]{64}")
_UUID_RE = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
_ISO_RE = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})")
_KEY_RE = re.compile(r"[A-Za-z0-9_]+")
_MARKER_RE = re.compile(
    r"lgm-review:(" + _UUID_RE.pattern + r"):(" + _UUID_RE.pattern + r"):([0-9]+)")

_ESCAPES = {
    '"': '\\"', "\\": "\\\\", "\b": "\\b", "\f": "\\f",
    "\n": "\\n", "\r": "\\r", "\t": "\\t",
}


class MrReviewError(Exception):
    """Typed protocol failure; ``code`` is always a member of ERROR_CODES."""

    def __init__(self, code: str, message: str):
        if code not in ERROR_CODES:
            raise ValueError(f"error code outside the registry: {code}")
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


# ── Canonical JSON ───────────────────────────────────────────────────────────

def canonical_json(value: Any) -> str:
    """Canonical encoding: UTF-8, sorted ASCII object keys, compact separators,
    no floats, non-ASCII literal (never \\uXXXX), RFC 8259 minimal escaping."""
    out: list[str] = []
    _write_canonical(value, out)
    return "".join(out)


def _write_canonical(value: Any, out: list[str]) -> None:
    if value is None:
        out.append("null")
    elif value is True:
        out.append("true")
    elif value is False:
        out.append("false")
    elif isinstance(value, int):
        out.append(str(value))
    elif isinstance(value, float):
        raise MrReviewError(
            "invalid-schema", "floating-point numbers are forbidden in canonical payloads")
    elif isinstance(value, str):
        out.append(_escape_string(value))
    elif isinstance(value, (list, tuple)):
        out.append("[")
        first = True
        for item in value:
            if not first:
                out.append(",")
            first = False
            _write_canonical(item, out)
        out.append("]")
    elif isinstance(value, dict):
        out.append("{")
        first = True
        for key in sorted(value):
            if not isinstance(key, str) or not _KEY_RE.fullmatch(key):
                raise MrReviewError(
                    "invalid-schema", f"object keys must be ASCII [A-Za-z0-9_]: {key!r}")
            if not first:
                out.append(",")
            first = False
            out.append(_escape_string(key))
            out.append(":")
            _write_canonical(value[key], out)
        out.append("}")
    else:
        raise MrReviewError(
            "invalid-schema", f"type {type(value).__name__} is not JSON-encodable")


def _escape_string(text: str) -> str:
    out = ['"']
    for ch in text:
        esc = _ESCAPES.get(ch)
        if esc is not None:
            out.append(esc)
        elif ord(ch) < 0x20:
            out.append(f"\\u{ord(ch):04x}")
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


# ── Digests ──────────────────────────────────────────────────────────────────

def compute_snapshot_id(snapshot: dict) -> str:
    """SHA-256 over the canonical payload with the snapshot_id key removed."""
    payload = {k: v for k, v in snapshot.items() if k != "snapshot_id"}
    return sha256_hex(canonical_json(payload))


def compute_code_id(snapshot: dict) -> str:
    """SHA-256 over the sorted unique labeled tips; never dates or discussions."""
    refs = snapshot["gitlab_diff_refs"]
    tips = sorted({
        ("diff_base", refs["base_sha"]),
        ("diff_head", refs["head_sha"]),
        ("diff_start", refs["start_sha"]),
        ("source", snapshot["source_sha"]),
        ("target_tip", snapshot["target_tip_sha"]),
    })
    return sha256_hex(canonical_json([list(t) for t in tips]))


def compute_version_fingerprint(snapshot: dict) -> str:
    """SHA-256 over GitLab identity, source SHA, target tip/branch/project and
    all diff refs."""
    refs = snapshot["gitlab_diff_refs"]
    payload = {
        "gitlab_url": snapshot["gitlab_url"],
        "iid": snapshot["iid"],
        "source_project_id": snapshot["source_project_id"],
        "target_project_id": snapshot["target_project_id"],
        "source_sha": snapshot["source_sha"],
        "target_tip_sha": snapshot["target_tip_sha"],
        "target_branch": snapshot["target_branch"],
        "diff_refs": {
            "base_sha": refs["base_sha"],
            "start_sha": refs["start_sha"],
            "head_sha": refs["head_sha"],
        },
    }
    return sha256_hex(canonical_json(payload))


def snapshot_digests(snapshot: dict) -> dict:
    return {
        "snapshot_id": compute_snapshot_id(snapshot),
        "code_id": compute_code_id(snapshot),
        "version_fingerprint": compute_version_fingerprint(snapshot),
    }


# ── Strict field primitives ──────────────────────────────────────────────────

def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _obj(value: Any, field: str) -> dict:
    if not isinstance(value, dict):
        raise MrReviewError("invalid-schema", f"{field} must be a JSON object")
    return value


def _exact_keys(obj: dict, allowed: frozenset[str], field: str) -> None:
    missing = allowed - obj.keys()
    unknown = set(obj.keys()) - allowed
    if missing:
        raise MrReviewError("invalid-schema", f"{field}: missing keys {sorted(missing)}")
    if unknown:
        raise MrReviewError("invalid-schema", f"{field}: unknown keys {sorted(unknown)}")


def _check_schema_version(obj: dict, field: str) -> int:
    version = obj["schema_version"]
    if not _is_int(version):
        raise MrReviewError("invalid-schema", f"{field}.schema_version must be an integer")
    if version not in SUPPORTED_SCHEMA_VERSIONS:
        raise MrReviewError(
            "unknown-schema-version",
            f"{field}.schema_version {version} is not in {sorted(SUPPORTED_SCHEMA_VERSIONS)}")
    return version


def _str(obj: dict, key: str, field: str) -> str:
    value = obj[key]
    if not isinstance(value, str) or not value:
        raise MrReviewError("invalid-schema", f"{field}.{key} must be a non-empty string")
    return value


def _str_or_none(obj: dict, key: str, field: str, allow_empty: bool = False) -> str | None:
    value = obj[key]
    if value is None:
        return None
    if not isinstance(value, str) or (not allow_empty and not value):
        raise MrReviewError("invalid-schema", f"{field}.{key} must be null or a non-empty string")
    return value


def _int(obj: dict, key: str, field: str, minimum: int | None = None) -> int:
    value = obj[key]
    if not _is_int(value):
        raise MrReviewError("invalid-schema", f"{field}.{key} must be an integer")
    if minimum is not None and value < minimum:
        raise MrReviewError("invalid-schema", f"{field}.{key} must be >= {minimum}")
    return value


def _int_or_none(obj: dict, key: str, field: str, minimum: int) -> int | None:
    value = obj[key]
    if value is None:
        return None
    if not _is_int(value):
        raise MrReviewError("invalid-schema", f"{field}.{key} must be null or an integer")
    if value < minimum:
        raise MrReviewError(
            "invalid-line", f"{field}.{key} must be null or >= {minimum}, got {value}")
    return value


def _bool(obj: dict, key: str, field: str) -> bool:
    value = obj[key]
    if not isinstance(value, bool):
        raise MrReviewError("invalid-schema", f"{field}.{key} must be a boolean")
    return value


def _pattern(obj: dict, key: str, field: str, regex: re.Pattern[str], what: str) -> str:
    value = _str(obj, key, field)
    if not regex.fullmatch(value):
        raise MrReviewError("invalid-schema", f"{field}.{key} must be {what}, got {value!r}")
    return value


def _sha(obj: dict, key: str, field: str) -> str:
    return _pattern(obj, key, field, _SHA_RE, "a 40-char lowercase hex git SHA")


def _hex64(obj: dict, key: str, field: str) -> str:
    return _pattern(obj, key, field, _HEX64_RE, "a 64-char lowercase hex SHA-256")


def _uuid(obj: dict, key: str, field: str) -> str:
    return _pattern(obj, key, field, _UUID_RE, "a lowercase canonical UUID")


def _iso(obj: dict, key: str, field: str) -> str:
    return _pattern(obj, key, field, _ISO_RE, "an ISO-8601 timestamp")


def _enum(obj: dict, key: str, field: str, allowed: frozenset[str],
          code: str = "invalid-schema") -> str:
    value = obj[key]
    if not isinstance(value, str) or value not in allowed:
        raise MrReviewError(code, f"{field}.{key} must be one of {sorted(allowed)}, got {value!r}")
    return value


def _url(obj: dict, key: str, field: str) -> str:
    value = _str(obj, key, field)
    if not value.startswith(("http://", "https://")) or value.endswith("/") \
            or any(ch.isspace() for ch in value):
        raise MrReviewError(
            "invalid-schema",
            f"{field}.{key} must be a normalized http(s) URL without trailing slash, got {value!r}")
    return value


def _str_list(obj: dict, key: str, field: str) -> list[str]:
    value = obj[key]
    if not isinstance(value, list) or any(not isinstance(v, str) for v in value):
        raise MrReviewError("invalid-schema", f"{field}.{key} must be an array of strings")
    return value


def _sha_list(obj: dict, key: str, field: str) -> list[str]:
    value = obj[key]
    if not isinstance(value, list) \
            or any(not isinstance(v, str) or not _SHA_RE.fullmatch(v) for v in value):
        raise MrReviewError(
            "invalid-schema", f"{field}.{key} must be an array of 40-char hex git SHAs")
    return value


# ── Snapshot ─────────────────────────────────────────────────────────────────

def validate_diff_refs(value: Any, field: str) -> dict:
    """Empty/async GitLab diff refs are diff-not-ready, not a schema error."""
    if value is None:
        raise MrReviewError("diff-not-ready", f"{field} is null: GitLab diff refs are not computed yet")
    refs = _obj(value, field)
    _exact_keys(refs, DIFF_REFS_FIELDS, field)
    for key in sorted(DIFF_REFS_FIELDS):
        if not isinstance(refs[key], str):
            raise MrReviewError("invalid-schema", f"{field}.{key} must be a string")
        if not refs[key]:
            raise MrReviewError("diff-not-ready", f"{field}.{key} is empty: GitLab diff refs are not computed yet")
    for key in sorted(DIFF_REFS_FIELDS):
        if not _SHA_RE.fullmatch(refs[key]):
            raise MrReviewError(
                "invalid-schema", f"{field}.{key} must be a 40-char lowercase hex git SHA")
    return refs


def validate_position(value: Any, field: str) -> dict | None:
    if value is None:
        return None
    pos = _obj(value, field)
    _exact_keys(pos, POSITION_FIELDS, field)
    side = _enum(pos, "side", field, SIDES, code="invalid-side")
    old_path = _str_or_none(pos, "old_path", field)
    new_path = _str_or_none(pos, "new_path", field)
    old_line = _int_or_none(pos, "old_line", field, 1)
    new_line = _int_or_none(pos, "new_line", field, 1)
    if old_path is None and new_path is None:
        raise MrReviewError("invalid-schema", f"{field}: old_path and new_path cannot both be null")
    if side == "old" and (old_path is None or old_line is None):
        raise MrReviewError(
            "invalid-line" if old_path is not None else "invalid-schema",
            f"{field}: side=old requires old_path and old_line")
    if side == "new" and (new_path is None or new_line is None):
        raise MrReviewError(
            "invalid-line" if new_path is not None else "invalid-schema",
            f"{field}: side=new requires new_path and new_line")
    for key in sorted(DIFF_REFS_FIELDS):
        _sha(pos, key, field)
    return pos


def validate_note(value: Any, field: str) -> dict:
    note = _obj(value, field)
    _exact_keys(note, NOTE_FIELDS, field)
    _int(note, "note_id", field, minimum=1)
    if not isinstance(note["body"], str):
        raise MrReviewError("invalid-schema", f"{field}.body must be a string")
    _str(note, "author", field)
    _iso(note, "created_at", field)
    for key in ("system", "resolvable", "resolved"):
        _bool(note, key, field)
    validate_position(note["position"], f"{field}.position")
    return note


def validate_discussion(value: Any, field: str) -> dict:
    disc = _obj(value, field)
    _exact_keys(disc, DISCUSSION_FIELDS, field)
    _str(disc, "discussion_id", field)
    notes = disc["notes"]
    if not isinstance(notes, list):
        raise MrReviewError("invalid-schema", f"{field}.notes must be an array")
    for i, note in enumerate(notes):
        validate_note(note, f"{field}.notes[{i}]")
    return disc


def validate_snapshot(value: Any) -> dict:
    """Structural validation only; digests are checked by verify_snapshot."""
    snap = _obj(value, "snapshot")
    _exact_keys(snap, SNAPSHOT_FIELDS, "snapshot")
    _check_schema_version(snap, "snapshot")
    _hex64(snap, "snapshot_id", "snapshot")
    _hex64(snap, "code_id", "snapshot")
    _hex64(snap, "version_fingerprint", "snapshot")
    _str(snap, "repo", "snapshot")
    _url(snap, "gitlab_url", "snapshot")
    _int(snap, "source_project_id", "snapshot", minimum=1)
    _int(snap, "target_project_id", "snapshot", minimum=1)
    iid = _int(snap, "iid", "snapshot", minimum=1)
    _url(snap, "mr_url", "snapshot")
    _str(snap, "title", "snapshot")
    _str_or_none(snap, "description", "snapshot", allow_empty=True)
    _enum(snap, "state", "snapshot", MR_STATES)
    _bool(snap, "draft", "snapshot")
    _str(snap, "source_branch", "snapshot")
    _str(snap, "target_branch", "snapshot")
    _sha(snap, "source_sha", "snapshot")
    _sha(snap, "target_tip_sha", "snapshot")

    fetch_ref = _str_or_none(snap, "source_fetch_ref", "snapshot")
    expected_ref = f"refs/merge-requests/{iid}/head"
    if snap["source_project_id"] != snap["target_project_id"]:
        if fetch_ref != expected_ref:
            raise MrReviewError(
                "invalid-schema",
                f"snapshot.source_fetch_ref must be {expected_ref!r} for a fork MR, got {fetch_ref!r}")
    elif fetch_ref is not None:
        raise MrReviewError(
            "invalid-schema",
            "snapshot.source_fetch_ref must be null when source and target projects match")

    refs = validate_diff_refs(snap["gitlab_diff_refs"], "snapshot.gitlab_diff_refs")
    _sha_list(snap, "computed_merge_bases", "snapshot")
    comparison_base = _sha(snap, "comparison_base_sha", "snapshot")
    _enum(snap, "comparison_kind", "snapshot", COMPARISON_KINDS)
    if comparison_base != refs["base_sha"]:
        raise MrReviewError(
            "invalid-schema",
            "snapshot.comparison_base_sha must equal gitlab_diff_refs.base_sha for comparison_kind=gitlab-diff")
    for key in ("observed_started_at", "observed_finished_at",
                "mr_updated_at", "discussions_fetched_at"):
        _iso(snap, key, "snapshot")
    _bool(snap, "discussions_complete", "snapshot")
    _str_list(snap, "limitations", "snapshot")
    discussions = snap["discussions"]
    if not isinstance(discussions, list):
        raise MrReviewError("invalid-schema", "snapshot.discussions must be an array")
    for i, disc in enumerate(discussions):
        validate_discussion(disc, f"snapshot.discussions[{i}]")
    return snap


def verify_snapshot(value: Any) -> dict:
    """Validate structure, then reject hash-mismatch before any persistence."""
    snap = validate_snapshot(value)
    for key, digest in snapshot_digests(snap).items():
        if snap[key] != digest:
            raise MrReviewError(
                "hash-mismatch", f"snapshot.{key} does not match the recomputed digest")
    return snap


# ── Transfer envelope ─────────────────────────────────────────────────────────

def build_transfer_envelope(snapshot: dict, bundle_sha256: str, bundle_size: int,
                            prepared_at: str,
                            limitations: list[str] | tuple[str, ...] = ()) -> dict:
    """Envelope stored at mr-review/snapshots/<snapshot_id>.json: the validated
    snapshot plus the code bundle digest/size, so HOME can verify the bundle
    before import (the snapshot schema itself is closed and carries no bundle
    digest field)."""
    snap = verify_snapshot(snapshot)
    if not isinstance(bundle_sha256, str) or not _HEX64_RE.fullmatch(bundle_sha256):
        raise MrReviewError(
            "invalid-schema", "bundle_sha256 must be a 64-char lowercase hex SHA-256")
    if not _is_int(bundle_size) or bundle_size < 1:
        raise MrReviewError(
            "invalid-schema", f"bundle_size must be an integer >= 1, got {bundle_size!r}")
    if not isinstance(prepared_at, str) or not _ISO_RE.fullmatch(prepared_at):
        raise MrReviewError(
            "invalid-schema", f"prepared_at must be an ISO-8601 timestamp, got {prepared_at!r}")
    if not isinstance(limitations, (list, tuple)) \
            or any(not isinstance(item, str) for item in limitations):
        raise MrReviewError("invalid-schema", "limitations must be an array of strings")
    return {
        "schema_version": SCHEMA_VERSION,
        "snapshot_id": snap["snapshot_id"],
        "code_id": snap["code_id"],
        "code": {"bundle_sha256": bundle_sha256, "bundle_size": bundle_size},
        "snapshot": snap,
        "prepared_at": prepared_at,
        "limitations": list(limitations),
    }


def verify_transfer_envelope(value: Any) -> dict:
    """Validate the envelope and its full digest chain (snapshot digests plus
    the identity binding between envelope and snapshot) before any use."""
    env = _obj(value, "envelope")
    _exact_keys(env, ENVELOPE_FIELDS, "envelope")
    _check_schema_version(env, "envelope")
    _hex64(env, "snapshot_id", "envelope")
    _hex64(env, "code_id", "envelope")
    code = _obj(env["code"], "envelope.code")
    _exact_keys(code, ENVELOPE_CODE_FIELDS, "envelope.code")
    _hex64(code, "bundle_sha256", "envelope.code")
    _int(code, "bundle_size", "envelope.code", minimum=1)
    snap = verify_snapshot(env["snapshot"])
    if env["snapshot_id"] != snap["snapshot_id"] or env["code_id"] != snap["code_id"]:
        raise MrReviewError(
            "invalid-schema", "envelope identity does not match the snapshot")
    _iso(env, "prepared_at", "envelope")
    _str_list(env, "limitations", "envelope")
    return env


# ── Findings ─────────────────────────────────────────────────────────────────

def validate_anchor(value: Any, field: str) -> dict | None:
    if value is None:
        return None
    anchor = _obj(value, field)
    _exact_keys(anchor, ANCHOR_FIELDS, field)
    side = _enum(anchor, "side", field, SIDES, code="invalid-side")
    _int_or_none(anchor, "line", field, 1)
    old_path = _str_or_none(anchor, "old_path", field)
    new_path = _str_or_none(anchor, "new_path", field)
    if old_path is None and new_path is None:
        raise MrReviewError("invalid-schema", f"{field}: old_path and new_path cannot both be null")
    if side == "old" and old_path is None:
        raise MrReviewError("invalid-schema", f"{field}: side=old requires old_path")
    if side == "new" and new_path is None:
        raise MrReviewError("invalid-schema", f"{field}: side=new requires new_path")
    return anchor


def validate_finding_revision(value: Any, field: str, expected_revision: int) -> dict:
    rev = _obj(value, field)
    _exact_keys(rev, REVISION_FIELDS, field)
    revision = _int(rev, "revision", field, minimum=1)
    if revision != expected_revision:
        raise MrReviewError(
            "invalid-schema",
            f"{field}.revision must be {expected_revision} (revisions are contiguous from 1)")
    _iso(rev, "created_at", field)
    _hex64(rev, "snapshot_id", field)
    _hex64(rev, "code_id", field)
    _hex64(rev, "version_fingerprint", field)
    validate_anchor(rev["anchor"], f"{field}.anchor")
    thread_id = _str_or_none(rev, "thread_id", field)
    if rev["anchor"] is not None and thread_id is not None:
        raise MrReviewError(
            "invalid-schema", f"{field}: anchor and thread_id are mutually exclusive")
    _str(rev, "comment", field)
    if not isinstance(rev["trigger"], str):
        raise MrReviewError("invalid-schema", f"{field}.trigger must be a string")
    _str_list(rev, "evidence", field)
    _str_list(rev, "limitations", field)
    _enum(rev, "verification", field, VERIFICATION_STATES)
    applicability = _enum(rev, "applicability", field, APPLICABILITY_STATES)
    _enum(rev, "delivery", field, DELIVERY_STATES)
    if applicability == "fixed" and not rev["evidence"]:
        raise MrReviewError("invalid-schema", f"{field}: applicability=fixed requires evidence")
    return rev


def validate_finding(value: Any) -> dict:
    finding = _obj(value, "finding")
    _exact_keys(finding, FINDING_FIELDS, "finding")
    _check_schema_version(finding, "finding")
    _uuid(finding, "finding_id", "finding")
    _str(finding, "repo", "finding")
    _url(finding, "gitlab_url", "finding")
    _int(finding, "target_project_id", "finding", minimum=1)
    _int(finding, "iid", "finding", minimum=1)
    revisions = finding["revisions"]
    if not isinstance(revisions, list) or not revisions:
        raise MrReviewError("invalid-schema", "finding.revisions must be a non-empty array")
    for i, rev in enumerate(revisions):
        validate_finding_revision(rev, f"finding.revisions[{i}]", i + 1)
    return finding


def finding_latest_revision(finding: dict) -> dict:
    return finding["revisions"][-1]


def finding_stale_against(finding: dict, snapshot: dict) -> bool:
    """True when the latest checked revision no longer matches the snapshot's
    serialized identity (snapshot_id, code_id or version_fingerprint)."""
    rev = finding_latest_revision(finding)
    return any(rev[key] != snapshot[key]
               for key in ("snapshot_id", "code_id", "version_fingerprint"))


# ── V2 publication batch ─────────────────────────────────────────────────────

def validate_batch_item(value: Any, field: str) -> dict:
    item = _obj(value, field)
    _exact_keys(item, BATCH_ITEM_FIELDS, field)
    _uuid(item, "finding_id", field)
    _int(item, "revision", field, minimum=1)
    kind = _enum(item, "kind", field, BATCH_ITEM_KINDS)
    thread_id = _str_or_none(item, "thread_id", field)
    resolve = _bool(item, "resolve", field)
    anchor = validate_anchor(item["anchor"], f"{field}.anchor")
    if kind == "anchored" and (anchor is None or thread_id is not None):
        raise MrReviewError("invalid-schema", f"{field}: kind=anchored requires anchor and null thread_id")
    if kind == "thread" and (anchor is not None or thread_id is None):
        raise MrReviewError("invalid-schema", f"{field}: kind=thread requires thread_id and null anchor")
    if kind == "general" and (anchor is not None or thread_id is not None):
        raise MrReviewError("invalid-schema", f"{field}: kind=general requires null anchor and thread_id")
    if resolve and kind != "thread":
        raise MrReviewError("invalid-schema", f"{field}: resolve=true is only valid for kind=thread")
    _str(item, "body", field)
    _hex64(item, "snapshot_id", field)
    _hex64(item, "code_id", field)
    _hex64(item, "version_fingerprint", field)
    _sha(item, "source_sha", field)
    _sha(item, "target_tip_sha", field)
    validate_diff_refs(item["diff_refs"], f"{field}.diff_refs")
    return item


def validate_batch(value: Any) -> dict:
    batch = _obj(value, "batch")
    _exact_keys(batch, BATCH_FIELDS, "batch")
    _check_schema_version(batch, "batch")
    _uuid(batch, "batch_id", "batch")
    _str(batch, "repo", "batch")
    _url(batch, "gitlab_url", "batch")
    _int(batch, "target_project_id", "batch", minimum=1)
    _int(batch, "iid", "batch", minimum=1)
    _iso(batch, "created_at", "batch")
    items = batch["items"]
    if not isinstance(items, list) or not items:
        raise MrReviewError("invalid-schema", "batch.items must be a non-empty array")
    seen: set[tuple[str, int]] = set()
    identity: tuple[str, str, str] | None = None
    for i, item in enumerate(items):
        validated = validate_batch_item(item, f"batch.items[{i}]")
        key = (validated["finding_id"], validated["revision"])
        if key in seen:
            raise MrReviewError(
                "invalid-schema", f"batch.items[{i}]: duplicate finding revision {key}")
        seen.add(key)
        triple = (validated["snapshot_id"], validated["code_id"], validated["version_fingerprint"])
        if identity is None:
            identity = triple
        elif triple != identity:
            raise MrReviewError(
                "invalid-schema",
                f"batch.items[{i}]: all items must reference one snapshot identity")
    return batch


def batch_identity(batch: dict) -> dict:
    item = batch["items"][0]
    return {
        "snapshot_id": item["snapshot_id"],
        "code_id": item["code_id"],
        "version_fingerprint": item["version_fingerprint"],
    }


# ── Receipts ─────────────────────────────────────────────────────────────────

def receipt_note_url(mr_url: str, note_id: int) -> str:
    return f"{mr_url}#note_{note_id}"


def validate_receipt(value: Any) -> dict:
    receipt = _obj(value, "receipt")
    _exact_keys(receipt, RECEIPT_FIELDS, "receipt")
    _check_schema_version(receipt, "receipt")
    _uuid(receipt, "batch_id", "receipt")
    _uuid(receipt, "finding_id", "receipt")
    _int(receipt, "revision", "receipt", minimum=1)
    outcome = _enum(receipt, "outcome", "receipt", RECEIPT_OUTCOMES)
    _enum(receipt, "kind", "receipt", BATCH_ITEM_KINDS)
    _hex64(receipt, "snapshot_id", "receipt")
    _hex64(receipt, "code_id", "receipt")
    _hex64(receipt, "version_fingerprint", "receipt")
    discussion_id = _str_or_none(receipt, "discussion_id", "receipt")
    note_id = receipt["note_id"]
    if note_id is not None and (not _is_int(note_id) or note_id < 1):
        raise MrReviewError("invalid-schema", "receipt.note_id must be null or an integer >= 1")
    url = _str_or_none(receipt, "url", "receipt")
    mr_url = _url(receipt, "mr_url", "receipt")
    refs = receipt["submitted_refs"]
    if refs is not None:
        refs_obj = _obj(refs, "receipt.submitted_refs")
        _exact_keys(refs_obj, SUBMITTED_REFS_FIELDS, "receipt.submitted_refs")
        for key in sorted(SUBMITTED_REFS_FIELDS):
            _sha(refs_obj, key, "receipt.submitted_refs")
    _bool(receipt, "fallback", "receipt")
    _iso(receipt, "attempted_at", "receipt")
    finished = receipt["finished_at"]
    if finished is not None and (not isinstance(finished, str) or not _ISO_RE.fullmatch(finished)):
        raise MrReviewError(
            "invalid-schema", "receipt.finished_at must be null or an ISO-8601 timestamp")
    error = _str_or_none(receipt, "error", "receipt")
    if outcome in ("posted", "recovered"):
        if discussion_id is None or note_id is None or url is None:
            raise MrReviewError(
                "invalid-schema",
                f"receipt.outcome={outcome} requires discussion_id, note_id and url")
        if url != receipt_note_url(mr_url, note_id):
            raise MrReviewError(
                "invalid-schema",
                f"receipt.url must be the note permalink {receipt_note_url(mr_url, note_id)!r}")
        if refs is None:
            raise MrReviewError(
                "invalid-schema", f"receipt.outcome={outcome} requires submitted_refs")
        if error is not None:
            raise MrReviewError(
                "invalid-schema", f"receipt.outcome={outcome} requires error=null")
    else:
        if error is None:
            raise MrReviewError(
                "invalid-schema", f"receipt.outcome={outcome} requires a non-null error")
    return receipt


# ── Markers, artifact paths, budget ──────────────────────────────────────────

def review_marker(batch_id: str, finding_id: str, revision: int) -> str:
    if not isinstance(batch_id, str) or not _UUID_RE.fullmatch(batch_id):
        raise MrReviewError("invalid-marker", f"batch_id must be a canonical UUID, got {batch_id!r}")
    if not isinstance(finding_id, str) or not _UUID_RE.fullmatch(finding_id):
        raise MrReviewError("invalid-marker", f"finding_id must be a canonical UUID, got {finding_id!r}")
    if not _is_int(revision) or revision < 1:
        raise MrReviewError("invalid-marker", f"revision must be an integer >= 1, got {revision!r}")
    return f"lgm-review:{batch_id}:{finding_id}:{revision}"


def parse_review_markers(text: str) -> list[tuple[str, str, int]]:
    if not isinstance(text, str):
        raise MrReviewError("invalid-marker", "text must be a string")
    return [(m.group(1), m.group(2), int(m.group(3))) for m in _MARKER_RE.finditer(text)]


def batch_artifact_path(iid: int, batch_id: str) -> str:
    if not _is_int(iid) or iid < 1:
        raise MrReviewError("invalid-schema", f"iid must be an integer >= 1, got {iid!r}")
    if not isinstance(batch_id, str) or not _UUID_RE.fullmatch(batch_id):
        raise MrReviewError("invalid-schema", f"batch_id must be a canonical UUID, got {batch_id!r}")
    return f"mr-replies/mr-!{iid}-{batch_id}.review.json"


def code_artifact_path(code_id: str) -> str:
    if not isinstance(code_id, str) or not _HEX64_RE.fullmatch(code_id):
        raise MrReviewError("invalid-schema", f"code_id must be a 64-char hex SHA-256, got {code_id!r}")
    return f"mr-review/code/{code_id}.bundle"


def snapshot_artifact_path(snapshot_id: str) -> str:
    if not isinstance(snapshot_id, str) or not _HEX64_RE.fullmatch(snapshot_id):
        raise MrReviewError("invalid-schema", f"snapshot_id must be a 64-char hex SHA-256, got {snapshot_id!r}")
    return f"mr-review/snapshots/{snapshot_id}.json"


def validate_budget(budget_bytes: Any) -> int:
    if not _is_int(budget_bytes):
        raise MrReviewError("invalid-budget", f"budget_bytes must be an integer, got {budget_bytes!r}")
    if not BUDGET_BYTES_MIN <= budget_bytes <= BUDGET_BYTES_MAX:
        raise MrReviewError(
            "invalid-budget",
            f"budget_bytes must be within {BUDGET_BYTES_MIN}..{BUDGET_BYTES_MAX}, got {budget_bytes}")
    return budget_bytes


def mcp_result_bytes(payload: Any, is_error: bool = False) -> int:
    """UTF-8 size of the fully serialized MCP tools/call result: pretty-printed
    payload inside the content wrapper, re-escaping included, outer JSON-RPC
    id/framing excluded. Mirrors lgm_mcp._call_tool/_error_result/_send exactly;
    every bounded surface must reuse this helper."""
    text = json.dumps(payload, ensure_ascii=False, indent=2, default=str)
    content = [{"type": "text", "text": text}]
    result: dict = {"isError": True, "content": content} if is_error else {"content": content}
    return len(json.dumps(result, ensure_ascii=False).encode("utf-8"))
