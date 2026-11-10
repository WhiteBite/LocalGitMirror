"""Pure parsing and rendering helpers for mr-notes, mr-replies and status documents."""
from __future__ import annotations

import hashlib
import re
from datetime import datetime

_MRN_TITLE = re.compile(r"^# MR !(\d+) — (.*)$")
_MRN_BRANCH = re.compile(r"^- \*\*Ветка:\*\* `([^`]+)`")
_MRN_TARGET = re.compile(r"^- \*\*(?:Target|Целевая):\*\* `([^`]+)`")
_MRN_UPDATED = re.compile(r"^- \*\*Обновлено:\*\* (\S+)")
_MRN_DETECTED = re.compile(r"^- \*\*Обнаружено:\*\* (\S+)")
_MR_CLOSED_FILE = re.compile(r"mr-!(\d+)\.closed\.md$")
_NOTES_THREAD_ID = re.compile(r"^\*\*ID треда:\*\* `([^`]+)`")
_NOTES_ANCHOR = re.compile(r"^\*\*Место:\*\* `([^`]+):(\d+)`")
ANCHOR_WINDOW = 3
_REPLIES_STATUS_MARKER = "<!-- lgm-replies-status v1 -->"
_REPLIES_STATUS_FIELD = re.compile(r"^- ([a-z ]+):\s*(.*)$")
_HUNK_HEADER = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def _parse_replies_status(markdown: str) -> dict:
    """Numeric fields of a lgm-replies-status v1 document; ``parsed`` is
    False when the v1 marker is absent (foreign or hand-edited file), in
    which case every counter stays None and only ``markdown`` is truth."""
    out = {"posted": None, "duplicates": None, "skipped": None, "failed": None,
           "not_anchored": None, "at": "", "details": [], "parsed": False}
    if _REPLIES_STATUS_MARKER not in markdown:
        return out
    out["parsed"] = True
    for line in markdown.splitlines():
        m = _REPLIES_STATUS_FIELD.match(line)
        if m:
            key, raw = m.group(1), m.group(2).strip()
            if key == "at":
                out["at"] = raw
            elif key == "not anchored":
                out["not_anchored"] = int(raw) if raw.isdigit() else None
            elif key in ("posted", "duplicates", "skipped", "failed"):
                out[key] = int(raw) if raw.isdigit() else None
        elif line.startswith("  - "):
            out["details"].append(line[4:].strip())
    return out


def _parse_notes_threads(markdown: str) -> dict:
    """Unresolved anchors [{id, file, line}] and every thread id rendered in mr-notes."""
    unresolved: list[dict] = []
    ids: set[str] = set()
    section: str | None = None
    tid = ""
    for line in markdown.splitlines():
        if line.startswith("## ⚠"):
            section, tid = "unresolved", ""
        elif line.startswith("## ✓ решено") or line.startswith("## ✓ resolved"):
            section, tid = "resolved", ""
        elif line.startswith("## "):
            section, tid = None, ""
        elif section:
            m = _NOTES_THREAD_ID.match(line)
            if m:
                tid = m.group(1)
                ids.add(tid)
                continue
            a = _NOTES_ANCHOR.match(line)
            if a and tid:
                if section == "unresolved":
                    unresolved.append({"id": tid, "file": a.group(1), "line": int(a.group(2))})
    return {"ids": ids, "unresolved": unresolved}


def _anchor_collisions(anchors: list, unresolved: list) -> list:
    out = []
    for file, line in anchors:
        for t in unresolved:
            if t["file"] == file and abs(t["line"] - line) <= ANCHOR_WINDOW:
                out.append({"new": f"{file}:{line}", "thread": t["id"],
                            "thread_anchor": f"{t['file']}:{t['line']}"})
    return out


def _parse_mr_notes_head(markdown: str) -> dict:
    iid, title, branch = 0, "", ""
    target = ""
    unresolved = 0
    updated = ""
    for line in markdown.splitlines():
        m = _MRN_TITLE.match(line)
        if m:
            iid, title = int(m.group(1)), m.group(2).strip()
            continue
        b = _MRN_BRANCH.match(line)
        if b:
            branch = b.group(1)
        t = _MRN_TARGET.match(line)
        if t:
            target = t.group(1)
        d = _MRN_DETECTED.match(line)
        if d:
            updated = d.group(1)
        u = _MRN_UPDATED.match(line)
        if u:
            updated = u.group(1)
        if line.startswith("## ⚠"):
            unresolved += 1
    return {"iid": iid, "title": title, "source_branch": branch,
            "target_branch": target,
            "unresolved": unresolved, "updated": updated}


def _iso_age_seconds(value: str, now: float) -> int | None:
    """Age of an ISO-8601 stamp in seconds; None when absent or unparseable."""
    if not value:
        return None
    try:
        ts = datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None
    return max(0, int(now - ts))


def _closed_by_mtime(note: dict | None, marker: dict | None) -> bool:
    """Closed iff a marker exists and is at least as new as the note (marker wins ties;
    a marker without a note means the note expired). Parity with the Kotlin reader."""
    if marker is None:
        return False
    if note is None:
        return True
    return (marker.get("mtime") or 0) >= (note.get("mtime") or 0)


def _state_with_evidence(note: dict | None, marker: dict | None) -> tuple[str, str]:
    """(state, state_evidence) with the mr_pending semantics: the closed marker
    wins ties, a newer note means open, neither means unknown/expired."""
    if marker is not None and _closed_by_mtime(note, marker):
        return "closed", "marker"
    if note is not None:
        return "open", "note-mtime"
    return "unknown", "expired"


def _closed_marker_iids(items: list) -> dict:
    """Newest mr-notes/mr-!N.closed.md postbox item per iid."""
    markers: dict[int, dict] = {}
    for i in items:
        eff = i.get("path") or ""
        if not eff.startswith("mr-notes/"):
            continue
        m = _MR_CLOSED_FILE.search(eff)
        if not m:
            continue
        iid = int(m.group(1))
        if iid not in markers or (i.get("mtime") or 0) > (markers[iid].get("mtime") or 0):
            markers[iid] = i
    return markers


def send_exclusions(mirror_tips: list[str], sent_tips: list[str],
                    existing_locally: set[str]) -> list[str]:
    """^exclusions for a send: mirror tips present locally, minus the sent tips
    (excluding a sent tip makes git bundle refuse an empty pack)."""
    sent = set(sent_tips)
    return [s for s in dict.fromkeys(mirror_tips)
            if s and s in existing_locally and s not in sent]


def _reply_marker(iid: int, reply: dict) -> str:
    key = ("t:" + reply["thread_id"] if reply["kind"] == "thread"
           else f"a:{reply['file']}:{reply['line']}" if reply["kind"] == "new_anchored"
           else "g")
    digest = hashlib.sha1(f"{iid}|{key}|{reply['body']}".encode()).digest()
    return f"<!-- lgm:{digest[:4].hex()} -->"


def _discussion_view(d: dict) -> dict:
    notes: list[dict] = []
    any_resolvable = False
    all_resolved = True
    for n in d.get("notes") or []:
        resolvable = bool(n.get("resolvable"))
        is_resolved = bool(n.get("resolved"))
        if resolvable:
            any_resolvable = True
            if not is_resolved:
                all_resolved = False
        pos = n.get("position") or {}
        new_line = pos.get("new_line")
        old_line = pos.get("old_line")
        notes.append({
            "system": bool(n.get("system")),
            "body": n.get("body") or "",
            "file": pos.get("new_path") or pos.get("old_path"),
            "line": new_line if new_line is not None else old_line,
        })
    return {
        "id": d.get("id") or "",
        "resolved": any_resolvable and all_resolved,
        "notes": notes,
        "anchor_file": next((n["file"] for n in notes if n["file"] is not None), None),
        "anchor_line": next((n["line"] for n in notes if n["line"] is not None), None),
    }


def _anchor_clash(reply: dict, discussions: list) -> dict | None:
    for d in discussions:
        if d["resolved"] or d["anchor_file"] != reply["file"] or d["anchor_line"] is None:
            continue
        if any(not n["system"] for n in d["notes"]) \
                and abs(d["anchor_line"] - reply["line"]) <= ANCHOR_WINDOW:
            return d
    return None


def _anchorability_reason(reply: dict, ranges: dict | None) -> str | None:
    if ranges is None:
        return None
    file_ranges = ranges.get(reply["file"])
    if file_ranges is None:
        return "file not in MR diff"
    if any(lo <= reply["line"] <= hi for lo, hi in file_ranges):
        return None
    return "line outside diff hunks"


def _parse_hunk_new_ranges(diff_text: str) -> list:
    out = []
    for line in diff_text.splitlines():
        m = _HUNK_HEADER.match(line)
        if not m:
            continue
        start = int(m.group(1))
        count = int(m.group(2)) if m.group(2) else 1
        out.append((start, start + count - 1))
    return out


def _parse_compare_ranges(compare: dict) -> dict:
    ranges: dict[str, list] = {}
    for d in compare.get("diffs") or []:
        new_path = d.get("new_path")
        diff_text = d.get("diff")
        if not new_path or not diff_text:
            continue
        ranges[new_path] = _parse_hunk_new_ranges(diff_text)
    return ranges


def _resolve_decision(marker_seen: bool, wants_resolve: bool, thread_resolved: bool) -> str:
    if not marker_seen:
        return "post"
    if thread_resolved:
        return "skip"
    if wants_resolve:
        return "resolve_only"
    return "skip"


def _should_ack(replies: list, outcomes: dict, seen_markers: set, iid: int,
                parse_errors: int, failed: int) -> bool:
    if parse_errors > 0:
        return False
    terminal = {i for i, r in enumerate(replies)
                if outcomes.get(i) in ("posted", "dup", "dead")
                or _reply_marker(iid, r) in seen_markers}
    if len(terminal) == len(replies):
        return True
    return failed == 0


def _render_replies_status(iid: int, rep: dict) -> str:
    at = datetime.now().strftime("%Y-%m-%d %H:%M")
    lines = [
        "<!-- lgm-replies-status v1 -->",
        f"# MR !{iid} — status",
        f"- posted: {rep['posted']}",
        f"- duplicates: {rep['duplicates']}",
        f"- skipped: {rep['skipped']}",
        f"- failed: {rep['failed']}",
        f"- not anchored: {len(rep['not_anchored'])}",
        f"- at: {at}",
    ]
    lines += [f"  - {d}" for d in rep["details"]]
    lines += [f"  - {n}" for n in rep["not_anchored"]]
    return "\n".join(lines) + "\n"
