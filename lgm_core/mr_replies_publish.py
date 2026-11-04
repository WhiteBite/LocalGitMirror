"""v2 review-batch publication: freshness gate, marker scan, journalled posts."""
from __future__ import annotations

import re

from .client import LgmError, MirrorClient
from .mr_review_handoff import HandoffRejection, parse_and_validate
from .mr_review_models import (
    compute_version_fingerprint,
    parse_review_markers,
    receipt_note_url,
)
from .mr_review_receipts import open_journal

_MR_REVIEW_BATCH_FILE = re.compile(r"mr-!(\d+)-([^/]+)\.review\.json$")
_V2_GATE_FIELDS = ("state", "source_sha", "target_tip_sha", "target_project_id",
                   "gitlab_url", "diff_refs", "version_fingerprint")


def _review_gate(c: MirrorClient, doc: dict,
                 project: str) -> tuple[dict, dict, list, str]:
    """One fresh-fingerprint read of the live MR against a v2 batch's refs.

    Returns ``(expected, fresh, drift, web_url)``; ``drift`` lists every field
    whose live GitLab value differs from the batch document. The recomputed
    ``version_fingerprint`` additionally covers ``target_branch``, which the
    batch itself does not carry. Raises LgmError when GitLab cannot be read.
    """
    batch, refs = doc["batch"], doc["refs"]
    detail = c.gitlab_get_mr(doc["iid"], project=project)
    live_refs = detail.get("diff_refs") or {}
    target_branch = (detail.get("target_branch") or "").strip()
    tip = c.gitlab_branch_tip(target_branch, project=project) if target_branch else ""
    live_diff = {k: (live_refs.get(k) or "") for k in ("base_sha", "start_sha", "head_sha")}
    base_url = c.gitlab_base_url(project)
    fresh = {
        "state": detail.get("state") or "",
        "source_sha": detail.get("sha") or "",
        "target_tip_sha": tip,
        "target_branch": target_branch,
        "target_project_id": detail.get("target_project_id") or 0,
        "gitlab_url": base_url,
        "diff_refs": live_diff,
        "version_fingerprint": compute_version_fingerprint({
            "gitlab_url": base_url,
            "iid": doc["iid"],
            "source_project_id": detail.get("source_project_id") or 0,
            "target_project_id": detail.get("target_project_id") or 0,
            "source_sha": detail.get("sha") or "",
            "target_tip_sha": tip,
            "target_branch": target_branch,
            "gitlab_diff_refs": live_diff,
        }),
    }
    expected = {
        "state": "opened",
        "source_sha": refs["source_sha"],
        "target_tip_sha": refs["target_tip_sha"],
        "target_project_id": batch["target_project_id"],
        "gitlab_url": batch["gitlab_url"],
        "diff_refs": dict(refs["diff_refs"]),
        "version_fingerprint": doc["identity"]["version_fingerprint"],
    }
    drift = [f for f in _V2_GATE_FIELDS if fresh[f] != expected[f]]
    return expected, fresh, drift, detail.get("web_url") or ""


def _v2_marker_scan(raw_discussions: list) -> tuple[dict, set]:
    """(finding_id, revision) -> recovery info of already-posted v2 markers
    (matched independently of batch_id), plus every live thread id."""
    posted: dict[tuple[str, int], dict] = {}
    thread_ids: set[str] = set()
    for d in raw_discussions:
        did = d.get("id") or ""
        thread_ids.add(did)
        for n in d.get("notes") or []:
            for batch_id, finding_id, revision in parse_review_markers(n.get("body") or ""):
                posted.setdefault((finding_id, revision),
                                  {"discussion_id": did, "note_id": n.get("id"),
                                   "batch_id": batch_id})
    return posted, thread_ids


def _v2_target(entry: dict) -> str:
    if entry["kind"] == "anchored":
        a = entry["anchor"]
        return f"{a['new_path'] or a['old_path']}:{a['line']} ({a['side']} side)"
    if entry["kind"] == "thread":
        return entry["thread_id"]
    return "general"


def _v2_position(refs: dict, anchor: dict) -> dict:
    """Full side-aware GitLab position from the BATCH diff refs (never live);
    exactly one of old_line/new_line, chosen by the anchor side."""
    position = {
        "base_sha": refs["diff_refs"]["base_sha"],
        "start_sha": refs["diff_refs"]["start_sha"],
        "head_sha": refs["diff_refs"]["head_sha"],
        "position_type": "text",
        "old_path": anchor["old_path"] or anchor["new_path"],
        "new_path": anchor["new_path"] or anchor["old_path"],
    }
    position["new_line" if anchor["side"] == "new" else "old_line"] = anchor["line"]
    return position


def _v2_result(label: str, entry: dict, outcome: str, *, code: str = "",
               error: str | None = None, note: str = "",
               discussion_id=None, note_id=None, url: str | None = None,
               response: dict | None = None) -> dict:
    out = {"label": label, "kind": entry["kind"], "target": _v2_target(entry),
           "finding_id": entry["finding_id"], "revision": entry["revision"],
           "marker": entry["marker"], "outcome": outcome, "code": code,
           "error": error, "note": note,
           "discussion_id": discussion_id, "note_id": note_id, "url": url}
    if response is not None:
        out["response"] = response
    return out


def _v2_dup_result(label: str, entry: dict, dup: dict, web_url: str) -> dict:
    note_id = dup["note_id"]
    url = dup.get("url") or (
        receipt_note_url(web_url, note_id) if web_url and note_id is not None else None)
    return _v2_result(
        label, entry, "skipped-duplicate", code="possible-duplicate",
        note=(f"finding revision already posted (discussion {dup['discussion_id']}, "
              f"note {note_id}, batch {dup['batch_id']}) — ids recovered, no new POST"),
        discussion_id=dup["discussion_id"], note_id=note_id, url=url)


def _v2_blocked(label: str, entry: dict, code: str, error: str) -> dict:
    return _v2_result(label, entry, "blocked", code=code, error=error, note=error)


def _v2_finish(report: dict, results: list, details: list) -> dict:
    report["posted"] = sum(1 for r in results if r["outcome"] == "posted")
    report["duplicates"] = sum(1 for r in results if r["outcome"] == "skipped-duplicate")
    report["failed"] = sum(1 for r in results if r["outcome"] == "blocked")
    report["skipped"] = 0
    report["replies"] = results
    report["details"] = details
    return report


def _v2_halt(report: dict, entries: list, posted_map: dict, web_url: str,
             error: str, code: str = "") -> dict:
    """Whole-batch stop before any write: duplicates keep their recovered ids,
    every other entry ends blocked with the same typed reason."""
    results = []
    for idx, entry in enumerate(entries):
        label = f"entry {idx + 1}"
        dup = posted_map.get((entry["finding_id"], entry["revision"]))
        results.append(_v2_dup_result(label, entry, dup, web_url) if dup is not None
                       else _v2_blocked(label, entry, code, error))
    return _v2_finish(report, results, [error])


def _v2_post_entry(c: MirrorClient, iid: int, refs: dict, entry: dict, label: str,
                   thread_ids: set, web_url: str, dry_run: bool, project: str,
                   details: list, journal=None,
                   attempt: dict | None = None) -> tuple[dict, bool]:
    """Post one non-duplicate entry; returns (result, write_attempted)."""
    kind = entry["kind"]
    target = _v2_target(entry)
    if dry_run:
        plan = ("would post anchored discussion" if kind == "anchored"
                else "would reply into thread" if kind == "thread"
                else "would post general note")
        suffix = f" at {target}" if kind != "general" else ""
        return _v2_result(label, entry, "posted", note=f"{plan}{suffix}"), False
    if kind == "thread" and entry["thread_id"] not in thread_ids:
        return _v2_blocked(label, entry, "",
                           f"thread {entry['thread_id']} no longer exists"), False
    if kind == "anchored" and entry["anchor"]["line"] is None:
        return _v2_blocked(
            label, entry, "invalid-line",
            "anchor has no side line; GitLab diff notes require one and v2 "
            "anchored entries never fall back to a general note"), False
    body = f"<!-- {entry['marker']} -->\n\n{entry['body']}"
    if journal is not None:
        journal.record_post_start(attempt, entry)
    try:
        if kind == "anchored":
            resp = c.gitlab_post_new_discussion_positioned(
                iid, body, _v2_position(refs, entry["anchor"]), project=project)
            discussion_id = resp.get("id")
            notes = resp.get("notes") or []
            note_id = notes[0].get("id") if notes else None
        elif kind == "thread":
            resp = c.gitlab_post_thread_note(iid, entry["thread_id"], body,
                                             project=project)
            discussion_id, note_id = entry["thread_id"], resp.get("id")
        else:
            resp = c.gitlab_post_mr_note(iid, body, project=project)
            discussion_id, note_id = None, resp.get("id")
    except LgmError as e:
        if kind == "anchored" and e.code in (400, 422):
            return _v2_blocked(
                label, entry, "",
                f"GitLab rejected the anchor: HTTP {e.code} {e.message} — "
                f"no general fallback for v2"), True
        return _v2_blocked(label, entry, "", f"HTTP {e.code} {e.message}"), True
    url = receipt_note_url(web_url, note_id) if web_url and note_id is not None else None
    result = _v2_result(label, entry, "posted",
                        note=f"posted {kind} at {target}",
                        discussion_id=discussion_id, note_id=note_id, url=url,
                        response=resp)
    if kind == "thread" and entry["resolve"]:
        try:
            c.gitlab_resolve_discussion(iid, entry["thread_id"], project=project)
            result["note"] += " (resolved)"
        except LgmError as e:
            result["note"] += f" (resolve failed HTTP {e.code})"
            details.append(f"{label}: posted, resolve failed HTTP {e.code}")
    return result, True


def _v2_backfill_ids(c: MirrorClient, iid: int, project: str, results: list,
                     web_url: str, journal, attempt: dict | None) -> None:
    """One read-only re-scan for posted/duplicate entries missing a discussion
    id or permalink (general notes carry only a note id in the POST response);
    fills only what the live marker scan proves, never invents ids."""
    missing = [r for r in results
               if r["outcome"] in ("posted", "skipped-duplicate")
               and r.get("note_id") is not None
               and (not r.get("discussion_id") or not r.get("url"))]
    if not missing:
        return
    try:
        raw = c.gitlab_list_discussions(iid, project=project)
    except LgmError:
        return
    scanned, _ = _v2_marker_scan(raw)
    for r in missing:
        hit = scanned.get((r["finding_id"], r["revision"]))
        if hit is None or hit.get("note_id") != r["note_id"]:
            continue
        if not r.get("discussion_id") and hit.get("discussion_id"):
            r["discussion_id"] = hit["discussion_id"]
        if not r.get("url") and web_url:
            r["url"] = receipt_note_url(web_url, r["note_id"])
        if journal is not None:
            journal.update_entry_ids(attempt, r["finding_id"], r["revision"],
                                     r.get("discussion_id"), r.get("url"))


def _publish_review_batch(c: MirrorClient, doc: dict, report: dict,
                          dry_run: bool, project: str, journal=None,
                          postbox_item_id: str = "", postbox_path: str = "") -> dict:
    """Fresh-fingerprint gate + publication of one validated v2 batch.

    Read-only marker scan first, then the gate; any drift or unreachable API
    halts the whole batch with zero writes. The gate re-runs before every
    entry whose predecessor attempted a write, so a mid-batch head change
    blocks the remainder while posted entries keep their ids. Anchored v2
    entries never downgrade to general notes; the batch is never acked here.
    With a journal, the attempt and every entry outcome (discussion/note ids
    and the note permalink included) are persisted before any receipt step:
    entries a previous attempt posted are never re-POSTed, and an entry whose
    previous POST ended without a recorded response is re-looked-up through
    its marker only — a second POST is refused and the entry stays blocked.
    """
    iid = doc["iid"]
    entries = doc["entries"]
    attempt = None
    if journal is not None:
        attempt = journal.begin_attempt(postbox_item_id, postbox_path)
        report["attempt"] = attempt["attempt"]
    journal_keys: set[tuple[str, int]] = set()

    def finish(rep: dict) -> dict:
        if journal is not None:
            journal.complete_attempt(attempt)
        return rep

    def blocked(label: str, entry: dict, code: str, error: str) -> dict:
        result = _v2_blocked(label, entry, code, error)
        if journal is not None:
            journal.record_result(attempt, result, post_attempted=False)
        return result

    def halt_journalled(posted_map: dict, web_url: str, error: str,
                        code: str = "") -> dict:
        rep = _v2_halt(report, entries, posted_map, web_url, error, code)
        if journal is not None:
            for result in rep["replies"]:
                key = (result["finding_id"], result["revision"])
                recovered_from = None
                if result["outcome"] == "skipped-duplicate":
                    recovered_from = "journal" if key in journal_keys else "scan"
                journal.record_result(attempt, result, post_attempted=False,
                                      recovered_from=recovered_from)
        return finish(rep)

    try:
        raw = c.gitlab_list_discussions(iid, project=project)
    except LgmError as e:
        known = journal.posted_entries() if journal is not None else {}
        journal_keys.update(known)
        return halt_journalled(known, "", f"list discussions: {e.message}")
    posted_map, thread_ids = _v2_marker_scan(raw)
    if journal is not None:
        for key, dup in journal.posted_entries().items():
            if key not in posted_map:
                posted_map[key] = dup
                journal_keys.add(key)
    uncertain = journal.uncertain_entries() if journal is not None else {}
    web_url = ""
    try:
        expected, fresh, drift, web_url = _review_gate(c, doc, project)
        if journal is not None:
            journal.set_web_url(attempt, web_url)
    except LgmError as e:
        return halt_journalled(posted_map, web_url, f"freshness gate: {e.message}")
    if drift:
        report["stale"] = {"expected": expected, "fresh": fresh, "drift": drift}
        return halt_journalled(posted_map, web_url,
                               f"stale-snapshot: {', '.join(drift)} drifted since the "
                               f"snapshot — zero posts/resolves",
                               code="stale-snapshot")
    results: list[dict] = []
    details: list[str] = []
    halt: tuple[str, str] | None = None
    need_recheck = False
    for idx, entry in enumerate(entries):
        label = f"entry {idx + 1}"
        key = (entry["finding_id"], entry["revision"])
        dup = posted_map.get(key)
        if dup is not None:
            result = _v2_dup_result(label, entry, dup, web_url)
            results.append(result)
            if journal is not None:
                journal.record_result(attempt, result, post_attempted=False,
                                      recovered_from="journal" if key in journal_keys
                                      else "scan")
            continue
        if halt is not None:
            results.append(blocked(label, entry, halt[0], halt[1]))
            continue
        if key in uncertain:
            results.append(blocked(
                label, entry, "",
                f"uncertain publication outcome: attempt {uncertain[key]} started a "
                f"POST without a recorded response and the marker re-lookup found "
                f"nothing — refusing a second POST"))
            continue
        if need_recheck:
            try:
                expected, fresh, drift, web_url = _review_gate(c, doc, project)
                if journal is not None:
                    journal.set_web_url(attempt, web_url)
            except LgmError as e:
                halt = ("", f"freshness gate: {e.message}")
                results.append(blocked(label, entry, halt[0], halt[1]))
                continue
            if drift:
                report["stale"] = {"expected": expected, "fresh": fresh,
                                    "drift": drift}
                halt = ("stale-snapshot",
                        f"stale-snapshot: {', '.join(drift)} drifted mid-batch — "
                        f"remaining entries not posted")
                results.append(blocked(label, entry, halt[0], halt[1]))
                continue
            need_recheck = False
        result, attempted = _v2_post_entry(c, iid, doc["refs"], entry, label,
                                           thread_ids, web_url, dry_run, project,
                                           details, journal, attempt)
        results.append(result)
        if journal is not None:
            journal.record_result(attempt, result, post_attempted=attempted)
        need_recheck = attempted
    _v2_backfill_ids(c, iid, project, results, web_url, journal, attempt)
    details.insert(0, f"v2 batch {doc['batch_id']}: "
                      f"{sum(1 for r in results if r['outcome'] == 'posted')} posted, "
                      f"{sum(1 for r in results if r['outcome'] == 'skipped-duplicate')} duplicate, "
                      f"{sum(1 for r in results if r['outcome'] == 'blocked')} blocked")
    return finish(_v2_finish(report, results, details))


def _push_review_batch(c: MirrorClient, repo: str, item: dict,
                       dry_run: bool = False, project: str = "") -> dict:
    """Document-level handling of one v2 batch artifact (mr-!<iid>-<batch_id>
    .review.json) in the postbox: full pipeline validation, then the
    snapshot-bound publication gate. Batches are immutable, coexist per IID,
    are never superseded and never acked here (the receipt handoff owns the
    ack); a malformed batch is refused with zero side effects. Unless this is
    a dry run, the publication is journalled per entry so the receipt handoff
    and restart recovery can run without ever re-POSTing."""
    path = item.get("path") or ""
    m = _MR_REVIEW_BATCH_FILE.search(path)
    iid = int(m.group(1))
    report = {"iid": iid, "path": path, "posted": 0, "duplicates": 0,
              "skipped": 1, "failed": 0, "parse_errors": 0, "not_anchored": [],
              "details": [], "replies": [], "acked": False, "ack_superseded": [],
              "format": "v2"}
    try:
        content = c.file_sync_fetch(repo, item["id"]).decode("utf-8")
    except Exception as e:
        report["details"] = [f"download/decrypt: {e}"]
        return report
    try:
        doc = parse_and_validate(content, expected_iid=iid,
                                 expected_batch_id=m.group(2), want="v2")
    except HandoffRejection as e:
        report["parse_errors"] = 1
        refusal = f"{e.code}: {e.message}" if e.code else e.message
        report["details"] = [f"rejected: {refusal}"]
        return report
    report["batch_id"] = doc["batch_id"]
    report["identity"] = doc["identity"]
    item_id = item.get("id") or ""
    journal = None if dry_run else open_journal(repo, iid, doc, item_id, path)
    published = _publish_review_batch(c, doc, report, dry_run, project, journal=journal,
                                      postbox_item_id=item_id, postbox_path=path)
    if journal is not None and journal.replaced_corrupt:
        published.setdefault("details", []).append(
            "journal was corrupt and has been replaced: records of prior attempts "
            "are lost; the live marker scan remains the duplicate backstop")
    return published
