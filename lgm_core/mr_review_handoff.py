"""Cross-language parse -> normalize -> validate pipeline for MR review handoffs.

One pipeline feeds every delivery entry point (mr_replies_send check/send and
mr_replies_push), so preview, upload and publication reject identical
malformed documents. Two formats, routed by content, never by caller hope:

- ``legacy`` Markdown replies — the shipped grammar ('# MR !N' header,
  '## thread <id>' / '## new <file>:<line>' / '## new' sections,
  'resolve: yes' first line). Semantics frozen byte-compatible with the
  published plugin parser (MrReplies.kt) and the legacy publisher.
- ``v2`` JSON batches — exactly the documents mr_review_findings create_batch
  produces (docs/MR_REVIEW_PROTOCOL.md section 5), validated through
  mr_review_models. JSON-looking content is NEVER interpreted as legacy
  Markdown; v2 anchored entries carry side-aware anchors and allow no
  general-note fallback (legacy-only behavior).

Kotlin twin: idea-plugin .../gitlab/MrReviewHandoff.kt. Shared fixtures in
tests/fixtures/mr_review_handoff/ pin identical acceptance/rejection outcomes
in both languages. Publication gates against GitLab (fresh fingerprint,
possible-duplicate proximity) are NOT part of this pipeline.
"""
from __future__ import annotations

import json
import re
import sqlite3
from pathlib import Path
from typing import Any

from .mr_review_models import (
    MrReviewError,
    batch_identity,
    canonical_json,
    review_marker,
    validate_batch,
)

LEGACY = "legacy"
V2 = "v2"

_REPLY_HEADER = re.compile(r"^#\s+MR\s+!(\d+)")
_REPLY_THREAD_LINE = re.compile(r"^##\s+thread\s+(\S+)\s*$")
_REPLY_ANCHORED_LINE = re.compile(r"^##\s+new\s+(\S+?):(\d+)\s*$")
_REPLY_GENERAL_LINE = re.compile(r"^##\s+new\s*$")
_REPLY_RESOLVE_LINE = re.compile(r"^resolve:\s*(yes|no)\s*$", re.IGNORECASE)
_REPLY_BRANCH_LINE = "- branch:"


class HandoffRejection(Exception):
    """Document-level refusal: nothing may be uploaded, posted or acked.

    ``code`` is a protocol-registry code (v2/JSON refusals) or ``""`` for
    legacy grammar refusals, which keep their shipped human-readable
    messages. ``errors`` carries the per-section legacy parse errors.
    """

    def __init__(self, message: str, *, code: str = "",
                 errors: list[str] | tuple[str, ...] = ()):
        super().__init__(message)
        self.message = message
        self.code = code
        self.errors = list(errors)


def looks_like_json(content: str) -> bool:
    return content.lstrip().startswith("{")


# ── Legacy Markdown (shipped grammar, frozen) ────────────────────────────────

def parse_legacy(markdown: str) -> dict:
    """Parse the mr-replies v1 format (same grammar as the plugin's MrReplies):
    '## thread <id>' / '## new <file>:<line>' / '## new' sections, an optional
    'resolve: yes' first line per thread section, a '# MR !N' header and a
    '- branch:' line. Malformed sections land in errors and are never sent."""
    iid, branch = 0, ""
    replies: list[dict] = []
    errors: list[str] = []
    kind: str | None = None
    thread_id, file = "", ""
    line_no = 0
    resolve = False
    resolve_seen = False
    body: list[str] = []
    section_no = 0

    def flush() -> None:
        nonlocal kind, thread_id, file, line_no, resolve, resolve_seen, body, section_no
        if kind is None:
            return
        section_no += 1
        text = "\n".join(body).strip()
        if not text:
            errors.append(f"section {section_no}: empty body, skipped")
        elif kind == "thread" and not thread_id:
            errors.append(f"section {section_no}: blank thread id, skipped")
        elif kind == "new_anchored" and (not file or line_no <= 0):
            errors.append(f"section {section_no}: bad file:line, skipped")
        else:
            replies.append({"kind": kind, "thread_id": thread_id, "file": file,
                            "line": line_no, "resolve": resolve and kind == "thread",
                            "body": text})
        kind = None
        thread_id = ""
        file = ""
        line_no = 0
        resolve = False
        resolve_seen = False
        body = []

    for raw in markdown.splitlines():
        text = raw.rstrip()
        m = _REPLY_HEADER.match(text)
        if m:
            iid = int(m.group(1))
        if text.startswith(_REPLY_BRANCH_LINE):
            branch = text.split(_REPLY_BRANCH_LINE, 1)[1].strip()
            continue
        tm = _REPLY_THREAD_LINE.match(text)
        am = _REPLY_ANCHORED_LINE.match(text)
        gm = _REPLY_GENERAL_LINE.match(text)
        if tm or am or gm:
            flush()
            if tm:
                kind, thread_id = "thread", tm.group(1)
            elif am:
                kind, file = "new_anchored", am.group(1)
                line_no = int(am.group(2))
            else:
                kind = "new_general"
            continue
        if kind is None:
            continue
        rm = _REPLY_RESOLVE_LINE.match(text)
        if rm and not resolve_seen and not "".join(body).strip():
            resolve = rm.group(1).lower() == "yes"
            resolve_seen = True
            continue
        body.append(text)
    flush()
    return {"iid": iid, "branch": branch, "replies": replies, "errors": errors}


# ── V2 JSON batches ──────────────────────────────────────────────────────────

def _posted_revisions(batch: dict, home: Path | str | None) -> set[tuple[str, int]]:
    """(finding_id, revision) pairs the available HOME state records as posted.

    Best-effort and strictly read-only: a missing/unreadable store means the
    state is simply unavailable and nothing is refused because of it.
    """
    from .mr_review_store import repo_key, reviews_home
    db = (reviews_home(home)
          / repo_key(batch["gitlab_url"], batch["target_project_id"], batch["repo"])
          / "review.sqlite3")
    if not db.is_file():
        return set()
    try:
        conn = sqlite3.connect(f"file:{db.as_posix()}?mode=ro", uri=True, timeout=5)
    except sqlite3.Error:
        return set()
    try:
        rows = conn.execute(
            "SELECT finding_id, revision, payload FROM finding_revisions").fetchall()
    except sqlite3.Error:
        return set()
    finally:
        conn.close()
    posted: set[tuple[str, int]] = set()
    for finding_id, revision, payload in rows:
        try:
            delivery = json.loads(payload).get("delivery")
        except (ValueError, AttributeError):
            continue
        if delivery == "posted":
            posted.add((finding_id, int(revision)))
    return posted


def _check_v2_conflicts(batch: dict, home: Path | str | None) -> None:
    seen: dict[str, int] = {}
    for item in batch["items"]:
        finding_id = item["finding_id"]
        if finding_id in seen:
            raise MrReviewError(
                "possible-duplicate",
                f"finding {finding_id} appears twice in one submission "
                f"(revisions {seen[finding_id]} and {item['revision']})")
        seen[finding_id] = item["revision"]
    first = batch["items"][0]
    refs = (first["source_sha"], first["target_tip_sha"],
            canonical_json(first["diff_refs"]))
    for i, item in enumerate(batch["items"][1:], start=1):
        item_refs = (item["source_sha"], item["target_tip_sha"],
                     canonical_json(item["diff_refs"]))
        if item_refs != refs:
            raise MrReviewError(
                "invalid-schema",
                f"batch.items[{i}]: all items must carry one snapshot's refs")
    posted = _posted_revisions(batch, home)
    for item in batch["items"]:
        if (item["finding_id"], item["revision"]) in posted:
            raise MrReviewError(
                "possible-duplicate",
                f"finding {item['finding_id']} revision {item['revision']} "
                f"is already posted")


def _v2_entry(batch_id: str, item: dict) -> dict:
    return {
        "kind": item["kind"],
        "finding_id": item["finding_id"],
        "revision": item["revision"],
        "thread_id": item["thread_id"],
        "resolve": item["resolve"],
        "body": item["body"],
        "anchor": item["anchor"],
        "marker": review_marker(batch_id, item["finding_id"], item["revision"]),
    }


def parse_v2(text: str, *, expected_iid: int = 0, expected_batch_id: str = "",
             home: Path | str | None = None) -> dict:
    """Validate a v2 batch document and normalize it; raises MrReviewError.

    ``force``-style flags never reach this function: a v2 document always
    validates its full schema, marker grammar and fingerprint fields.
    """
    try:
        value: Any = json.loads(text)
    except ValueError as e:
        raise MrReviewError(
            "invalid-schema", f"v2 handoff is not valid JSON: {e}") from None
    batch = validate_batch(value)
    _check_v2_conflicts(batch, home)
    if expected_iid and batch["iid"] != expected_iid:
        raise MrReviewError(
            "invalid-schema",
            f"batch document says MR !{batch['iid']} but iid={expected_iid}")
    if expected_batch_id and batch["batch_id"] != expected_batch_id:
        raise MrReviewError(
            "invalid-schema",
            f"batch document id {batch['batch_id']} does not match the "
            f"artifact path id {expected_batch_id}")
    first = batch["items"][0]
    return {
        "format": V2,
        "iid": batch["iid"],
        "branch": "",
        "batch_id": batch["batch_id"],
        "entries": [_v2_entry(batch["batch_id"], item) for item in batch["items"]],
        "batch": batch,
        "identity": batch_identity(batch),
        "refs": {"source_sha": first["source_sha"],
                 "target_tip_sha": first["target_tip_sha"],
                 "diff_refs": dict(first["diff_refs"])},
        "general_fallback": False,
    }


# ── Unified entry point ──────────────────────────────────────────────────────

def parse_and_validate(content: str, *, expected_iid: int = 0,
                       expected_batch_id: str = "", want: str = "",
                       home: Path | str | None = None) -> dict:
    """Route by content, parse, normalize and validate one handoff document.

    Returns the normalized document; every refusal raises HandoffRejection so
    check, send and push cannot diverge. ``want`` restricts the format
    ("" accepts either); JSON-looking content is always v2, never legacy.
    """
    if not isinstance(content, str):
        raise HandoffRejection("handoff content must be a string",
                               code="invalid-schema")
    if looks_like_json(content):
        if want == LEGACY:
            raise HandoffRejection(
                "JSON document where legacy Markdown replies are required; "
                "unknown JSON is never parsed as legacy Markdown",
                code="invalid-schema")
        try:
            return parse_v2(content, expected_iid=expected_iid,
                            expected_batch_id=expected_batch_id, home=home)
        except MrReviewError as e:
            raise HandoffRejection(e.message, code=e.code) from None
    if want == V2:
        raise HandoffRejection(
            "a v2 review batch must be a JSON document (mr_review_findings "
            "create_batch output); got legacy Markdown",
            code="invalid-schema")
    parsed = parse_legacy(content)
    if expected_iid and parsed["iid"] and parsed["iid"] != expected_iid:
        raise HandoffRejection(
            f"content header says MR !{parsed['iid']} but iid={expected_iid}")
    if parsed["errors"]:
        raise HandoffRejection(
            "malformed reply sections: " + "; ".join(parsed["errors"]),
            errors=parsed["errors"])
    return {
        "format": LEGACY,
        "iid": parsed["iid"] or expected_iid,
        "branch": parsed["branch"],
        "batch_id": None,
        "entries": parsed["replies"],
        "batch": None,
        "identity": None,
        "refs": None,
        "general_fallback": True,
    }
