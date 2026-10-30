"""Literal search and bounded textual context over MR review snapshots.

mr_review_search scans snapshot Git objects (never the worktree) for a
case-sensitive literal substring and returns paginated hits with capped
excerpts, heuristic test/config candidates and totals. mr_review_context
returns surrounding decoded source lines for one path. Both reuse the
reader's cursor machinery and mcp_result_bytes budget pattern.
"""
from __future__ import annotations

import base64
from pathlib import Path
from typing import Any
from urllib.parse import unquote_to_bytes

from .mr_review_models import MrReviewError, mcp_result_bytes, validate_budget
from .mr_review_reader import (
    DEFAULT_BUDGET_BYTES,
    _RESERVED_BYTES,
    _encode_path,
    decode_cursor,
    encode_cursor,
    open_reader,
)

_STREAM_BLOCK = 1 << 16
_BINARY_PROBE = 8000
_EXCERPT_BYTES = 256
_CANDIDATE_CAP = 20
_SIDES = ("old", "new", "target")
_SCOPES = ("tree", "added", "removed", "changed")
_SIDE_ORDER = {"old": 0, "new": 1, "target": 2}


def _side_sha(snap: dict, side: str) -> str:
    if side == "old":
        return snap["comparison_base_sha"]
    if side == "new":
        return snap["gitlab_diff_refs"]["head_sha"]
    return snap["target_tip_sha"]


def _diff_span(snap: dict) -> tuple[str, str]:
    refs = snap["gitlab_diff_refs"]
    return refs["base_sha"], refs["head_sha"]


def _resolve_sides(scope: str, side: str | None) -> list[str]:
    if side is not None and (not isinstance(side, str) or side not in _SIDES):
        raise MrReviewError("invalid-side", f"side must be one of {_SIDES}, got {side!r}")
    if scope == "tree":
        return [side or "new"]
    if scope == "added":
        eff = side or "new"
        if eff != "new":
            raise MrReviewError("invalid-side", "scope=added searches the new side only")
        return ["new"]
    if scope == "removed":
        eff = side or "old"
        if eff != "old":
            raise MrReviewError("invalid-side", "scope=removed searches the old side only")
        return ["old"]
    if side in (None, ""):
        return ["new", "old"]
    return [side]


def _fits(page: dict, budget: int) -> bool:
    return mcp_result_bytes(page) <= budget - _RESERVED_BYTES


def _excerpt(line: str, start: int) -> tuple[str, bool]:
    raw = line[start:].encode("utf-8")
    if len(raw) <= _EXCERPT_BYTES:
        return line[start:], False
    return raw[:_EXCERPT_BYTES].decode("utf-8", "ignore"), True


def _kind_of(display: str) -> str | None:
    low = display.lower()
    base = low.rsplit("/", 1)[-1]
    if (base.startswith(("test_", "test-"))
            or base.endswith(("_test.py", "_test.go", "test.kt", "test.java"))
            or ".spec." in base
            or low.startswith(("tests/", "test/")) or "/tests/" in low or "/test/" in low):
        return "test"
    if (base.endswith((".yml", ".yaml", ".toml", ".ini", ".cfg", ".gradle", ".kts", ".json"))
            or base == "dockerfile" or ".github/workflows/" in low):
        return "config"
    return None


def _stems(display: str) -> set[str]:
    base = display.rsplit("/", 1)[-1]
    stem = base.rsplit(".", 1)[0] if "." in base else base
    return {t for t in stem.replace("-", "_").split("_") if len(t) >= 3}


def _candidates(git, snap: dict, focus: set[str] | None = None) -> list[dict]:
    base, head = _diff_span(snap)
    entries, _limited = git.diff_tree_entries(base, head, renames=True)
    diff_displays: set[str] = set()
    diff_stems: set[str] = set()
    for entry in entries:
        for raw in (entry.old_path, entry.new_path):
            if raw is None:
                continue
            display = _encode_path(raw)[0]
            diff_displays.add(display)
            diff_stems |= _stems(display)
    tree = git.ls_tree_long(_side_sha(snap, "new"))
    out: list[dict] = []
    for raw in sorted(tree):
        display = _encode_path(raw)[0]
        kind = _kind_of(display)
        if kind is None:
            continue
        stems = _stems(display)
        if focus is not None and not (stems & focus):
            continue
        if display in diff_displays:
            reason = "changed in this MR and matches test/config name patterns"
        elif stems & diff_stems:
            reason = "name tokens overlap paths changed in this MR"
        else:
            continue
        out.append({"path": display, "kind": kind, "method": "heuristic", "reason": reason})
        if len(out) >= _CANDIDATE_CAP:
            break
    return out


def _files_for(git, snap: dict, scope: str, sides: list[str],
               wanted: set[str] | None) -> tuple[list[dict], bool]:
    limited = False
    if scope == "tree":
        picked: list[tuple[bytes, str]] = []
        for side in sides:
            for raw in git.ls_tree_long(_side_sha(snap, side)):
                picked.append((raw, side))
    else:
        base, head = _diff_span(snap)
        entries, limited = git.diff_tree_entries(base, head, renames=True)
        picked = []
        for entry in entries:
            status = entry.status[0]
            if status == "A":
                pairs = [("new", entry.new_path)]
            elif status == "D":
                pairs = [("old", entry.old_path)]
            else:
                pairs = [("old", entry.old_path), ("new", entry.new_path)]
            for side, raw in pairs:
                if side in sides and raw is not None:
                    picked.append((raw, side))
    picked.sort(key=lambda p: (p[0], _SIDE_ORDER[p[1]]))
    trees = {side: git.ls_tree_long(_side_sha(snap, side)) for side in sides}
    files: list[dict] = []
    for raw, side in picked:
        display, _encoded = _encode_path(raw)
        if wanted is not None and display not in wanted:
            continue
        info = trees[side].get(raw)
        if info is None:
            continue
        mode, typ, oid, _size = info
        files.append({"raw": raw, "display": display, "side": side,
                      "mode": mode, "type": typ, "oid": oid})
    return files, limited


def _emit_line(page: dict, budget: int, item: dict, line_no: int, line_b: bytes,
               seen: int, skip: int, line_start: int):
    try:
        line_s = line_b.decode("utf-8")
    except UnicodeDecodeError:
        return "binary", seen
    query = page["_query"]
    pos = line_s.find(query)
    index = seen
    while pos != -1:
        if index >= skip:
            text, truncated = _excerpt(line_s, pos)
            page["hits"].append({
                "path": item["display"], "side": item["side"], "line": line_no,
                "column": pos + 1, "blob": item["oid"], "excerpt": text,
                "truncated": truncated, "match_length": len(query)})
            if not _fits(page, budget):
                page["hits"].pop()
                return "pause", index
        index += 1
        pos = line_s.find(query, pos + len(query))
    return "ok", index


def _scan_file(git, page: dict, budget: int, item: dict, state: dict,
               resume: bool) -> tuple[str, dict | None]:
    """Stream one blob appending hits to ``page``; returns ('done', None),
    ('pause', resume_state) or ('binary', None) so the caller can roll back
    hits of a file that turned out binary mid-stream."""
    file_index = page["_fi"]
    proc = git.open_blob_stream(item["oid"])
    seen = 0
    try:
        buf = b""
        consumed = 0
        line_no = 0
        skip = state["hs"] if resume else 0
        if resume and state["bo"] > 0:
            left = state["bo"]
            while left > 0:
                block = proc.stdout.read(min(_STREAM_BLOCK, left))
                if not block:
                    break
                left -= len(block)
            consumed = state["bo"]
            line_no = state["ln"]
        while True:
            block = proc.stdout.read(_STREAM_BLOCK)
            if not block:
                break
            buf += block
            while b"\n" in buf:
                line_b, buf = buf.split(b"\n", 1)
                line_no += 1
                line_start = consumed
                consumed += len(line_b) + 1
                status, seen = _emit_line(page, budget, item, line_no, line_b,
                                          seen, skip, line_start)
                if status == "binary":
                    return "binary", None
                if status == "pause":
                    return "pause", {"fi": file_index, "bo": line_start,
                                     "ln": line_no - 1, "hs": seen}
        if buf:
            line_no += 1
            line_start = consumed
            status, seen = _emit_line(page, budget, item, line_no, buf,
                                      seen, skip, line_start)
            if status == "binary":
                return "binary", None
            if status == "pause":
                return "pause", {"fi": file_index, "bo": line_start,
                                 "ln": line_no - 1, "hs": seen}
        return "done", None
    finally:
        proc.stdout.close()
        proc.wait(timeout=120)


def mr_review_search(repo: str, snapshot_id: str, query: str, *,
                     side: str | None = None, scope: str = "tree",
                     paths: list[str] | tuple[str, ...] | None = None,
                     cursor: str = "", budget_bytes: int = DEFAULT_BUDGET_BYTES,
                     home: Path | str | None = None) -> dict:
    if not isinstance(repo, str) or not repo:
        raise MrReviewError("invalid-schema", "repo must be a non-empty string")
    if not isinstance(query, str) or not query:
        raise MrReviewError("invalid-schema", "query must be a non-empty string")
    if not isinstance(scope, str) or scope not in _SCOPES:
        raise MrReviewError("invalid-schema", f"scope must be one of {_SCOPES}")
    sides = _resolve_sides(scope, side)
    budget = validate_budget(budget_bytes)
    wanted = None
    if paths:
        if not isinstance(paths, (list, tuple)) or any(not isinstance(p, str) for p in paths):
            raise MrReviewError("invalid-schema", "paths must be a list of strings")
        wanted = set(paths)
    reader = open_reader(home, repo, snapshot_id)
    snap = reader._snap
    git = reader._git
    bind = {"query": query, "scope": scope, "sides": sides,
            "paths": sorted(wanted) if wanted else [], "budget": budget}
    state = decode_cursor(cursor, snapshot_id, bind) if cursor else \
        {"fi": 0, "bo": 0, "ln": 0, "hs": 0}
    files, limited = _files_for(git, snap, scope, sides, wanted)
    page: dict[str, Any] = {
        "repo": repo, "snapshot_id": snapshot_id, "query": query,
        "side": side if side is not None else (sides[0] if len(sides) == 1 else ""),
        "sides": sides, "scope": scope, "hits": [],
        "totals": {"files_scanned": 0, "hits": 0},
        "complete": False, "next_cursor": None,
        "candidates": [], "limitations": [],
        "_query": query, "_fi": state["fi"],
    }
    if limited:
        page["limitations"].append(
            "rename detection limited: similarity renames past the limit are "
            "reported as add/delete pairs")
    scanned = 0
    pause_state = None
    for idx in range(state["fi"], len(files)):
        item = files[idx]
        page["_fi"] = idx
        if item["type"] == "commit":
            page["limitations"].append(f"gitlink {item['display']}: not searched")
            scanned += 1
            continue
        probe = git.read_blob(item["oid"], 0, _BINARY_PROBE)[1]
        if b"\x00" in probe:
            page["limitations"].append(f"binary {item['display']}: not searched")
            scanned += 1
            continue
        try:
            probe.decode("utf-8")
        except UnicodeDecodeError:
            page["limitations"].append(
                f"non-UTF-8 content {item['display']}: not searched")
            scanned += 1
            continue
        resume = idx == state["fi"] and bool(state["bo"] or state["hs"])
        before = len(page["hits"])
        outcome, payload = _scan_file(git, page, budget, item, state, resume)
        if outcome == "binary":
            del page["hits"][before:]
            page["limitations"].append(
                f"non-UTF-8 content {item['display']}: not searched")
            scanned += 1
            continue
        if outcome == "pause":
            if payload == {k: state[k] for k in payload}:
                raise MrReviewError(
                    "invalid-budget", "page cannot make progress within budget_bytes")
            pause_state = payload
            break
        scanned += 1
    page["totals"] = {"files_scanned": scanned, "hits": len(page["hits"])}
    page["candidates"] = _candidates(git, snap)
    if pause_state is not None:
        page["next_cursor"] = encode_cursor(snapshot_id, bind, pause_state)
    else:
        page["complete"] = True
    page["shown"] = {"from_file": state["fi"],
                     "to_file": page["_fi"] if files else state["fi"]}
    page.pop("_query", None)
    page.pop("_fi", None)
    return page


def mr_review_context(repo: str, snapshot_id: str, path: str, *,
                      line: int = 0, side: str = "new",
                      budget_bytes: int = DEFAULT_BUDGET_BYTES,
                      context_lines: int = 20,
                      home: Path | str | None = None) -> dict:
    if not isinstance(repo, str) or not repo:
        raise MrReviewError("invalid-schema", "repo must be a non-empty string")
    if not isinstance(path, str) or not path:
        raise MrReviewError("invalid-schema", "path must be a non-empty string")
    if not isinstance(line, int) or isinstance(line, bool) or line < 0:
        raise MrReviewError("invalid-schema", "line must be a non-negative integer")
    if not isinstance(context_lines, int) or isinstance(context_lines, bool) \
            or context_lines < 0:
        raise MrReviewError("invalid-schema",
                            "context_lines must be a non-negative integer")
    if side not in _SIDES:
        raise MrReviewError("invalid-side", f"side must be one of {_SIDES}, got {side!r}")
    budget = validate_budget(budget_bytes)
    reader = open_reader(home, repo, snapshot_id)
    snap = reader._snap
    git = reader._git
    raw = path.encode("utf-8")
    tree = git.ls_tree_long(_side_sha(snap, side))
    if raw not in tree and "%" in path:
        raw = unquote_to_bytes(path)
    if raw not in tree:
        raise MrReviewError(
            "review-object-missing",
            f"path {path!r} is absent from the {side} side of snapshot {snapshot_id}")
    _mode, typ, oid, _size = tree[raw]
    display, _encoded = _encode_path(raw)
    result: dict[str, Any] = {
        "path": display, "side": side, "blob": oid, "lines": [],
        "method": "textual", "semantic_complete": False,
        "candidates": [], "limitations": [], "complete": True,
    }
    if typ == "commit":
        result["limitations"].append(f"gitlink {display}: no file content to show")
        result["candidates"] = _candidates(git, snap, _stems(display))
        return result
    lo = 1 if line == 0 else max(1, line - context_lines)
    hi = None if line == 0 else line + context_lines
    proc = git.open_blob_stream(oid)
    try:
        buf = b""
        line_no = 0
        while True:
            block = proc.stdout.read(_STREAM_BLOCK)
            if not block:
                break
            buf += block
            while b"\n" in buf:
                line_b, buf = buf.split(b"\n", 1)
                line_no += 1
                if not _emit_context_line(result, budget, line_no, line_b, lo, hi):
                    return result
        if buf:
            line_no += 1
            if not _emit_context_line(result, budget, line_no, buf, lo, hi):
                return result
    finally:
        proc.stdout.close()
        proc.wait(timeout=120)
    result["candidates"] = _candidates(git, snap, _stems(display))
    return result


def _emit_context_line(result: dict, budget: int, line_no: int, line_b: bytes,
                       lo: int, hi: int | None) -> bool:
    if line_no < lo or (hi is not None and line_no > hi):
        return True
    try:
        text = line_b.decode("utf-8")
        encoded = line_b
    except UnicodeDecodeError:
        text = base64.b64encode(line_b).decode("ascii")
        encoded = line_b
        result["limitations"].append(
            f"line {line_no}: non-UTF-8 bytes shown as base64")
    offset = 0
    while True:
        piece = encoded[offset:]
        entry: dict[str, Any] = {"line": line_no, "text": piece.decode("utf-8", "ignore")}
        if offset:
            entry["byte_offset"] = offset
        result["lines"].append(entry)
        if _fits(result, budget):
            return True
        result["lines"].pop()
        lo_b, hi_b = 0, len(piece)
        while lo_b < hi_b:
            mid = (lo_b + hi_b + 1) // 2
            trial = {"line": line_no, "text": piece[:mid].decode("utf-8", "ignore"),
                     "byte_offset": offset, "continued": True}
            result["lines"].append(trial)
            ok = _fits(result, budget)
            result["lines"].pop()
            if ok:
                lo_b = mid
            else:
                hi_b = mid - 1
        head = piece[:lo_b].decode("utf-8", "ignore")
        head_len = len(head.encode("utf-8"))
        if head_len == 0:
            result["limitations"].append(
                f"line {line_no}: does not fit budget_bytes")
            result["complete"] = False
            return False
        result["lines"].append({"line": line_no, "text": head,
                                "byte_offset": offset, "continued": True})
        offset += head_len
