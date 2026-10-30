"""Bounded reader over imported immutable MR review snapshots.

Every page is measured with mr_review_models.mcp_result_bytes (the fully
serialized MCP tools/call result, content wrapper and re-escaping included)
and never exceeds budget_bytes. Cursors are opaque, versioned and
digest-protected, bound to (snapshot, query, order, offset). All content
comes from the store's objects.git via MrReviewGit: a worktree is never
touched and missing objects never fall back to disk.
"""
from __future__ import annotations

import base64
import codecs
import json
import re
import subprocess
from pathlib import Path
from typing import Any, Iterator

from .mr_review_git import MrReviewGit, RawDiffEntry, resolve_contained
from .mr_review_models import (
    MrReviewError,
    canonical_json,
    mcp_result_bytes,
    sha256_hex,
    validate_budget,
    verify_snapshot,
)
from .mr_review_store import reviews_home

CURSOR_VERSION = 1
DEFAULT_BUDGET_BYTES = 24000
DEFAULT_CONTEXT_LINES = 20
VIEWS = frozenset({"diff", "file", "discussions"})
SIDES = frozenset({"old", "new", "target"})

_CURSOR_SALT = "lgm-mr-review-cursor-v1"
_PROBE_BYTES = 8000
_READ_BLOCK = 1 << 16
_SMALL_BLOB = 1 << 18
_PATH_PART_BYTES = 128
_BODY_PART_BYTES = 2048
_CHUNK_PATH_MAX = 256
_RESERVED_BYTES = 512

_HEX64_RE = re.compile(r"[0-9a-f]{64}")
_HUNK_RE = re.compile(rb"@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@")


def _encode_path(raw: bytes) -> tuple[str, bool]:
    """Literal UTF-8 path when the bytes decode cleanly; otherwise every byte
    percent-encoded (lossless, ASCII-safe, never mojibake)."""
    try:
        return raw.decode("utf-8"), False
    except UnicodeDecodeError:
        return "".join(f"%{b:02X}" for b in raw), True


def _module_of(raw: bytes) -> str:
    if b"/" not in raw:
        return ""
    return _encode_path(raw.split(b"/", 1)[0])[0]


def _cursor_digest(payload: dict) -> str:
    return sha256_hex(canonical_json({"salt": _CURSOR_SALT, **payload}))


def encode_cursor(snapshot_id: str, query: dict, state: dict) -> str:
    payload: dict[str, Any] = {
        "v": CURSOR_VERSION,
        "sid": snapshot_id,
        "q": sha256_hex(canonical_json(query)),
        "s": state,
    }
    payload["d"] = _cursor_digest({k: payload[k] for k in ("v", "sid", "q", "s")})
    return base64.urlsafe_b64encode(canonical_json(payload).encode("utf-8")).decode("ascii")


def decode_cursor(token: str, snapshot_id: str, query: dict) -> dict:
    if not isinstance(token, str) or not token:
        raise MrReviewError("invalid-schema", "cursor must be a non-empty string")
    try:
        payload = json.loads(base64.urlsafe_b64decode(token.encode("ascii")).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        raise MrReviewError("invalid-schema", "cursor is not a valid versioned token") from None
    if not isinstance(payload, dict) or set(payload) != {"v", "sid", "q", "s", "d"}:
        raise MrReviewError("invalid-schema", "cursor payload has an unexpected shape")
    if payload["v"] != CURSOR_VERSION:
        raise MrReviewError("invalid-schema", f"cursor version {payload['v']!r} is not supported")
    if payload["d"] != _cursor_digest({k: payload[k] for k in ("v", "sid", "q", "s")}):
        raise MrReviewError("hash-mismatch", "cursor integrity check failed (tampered cursor)")
    if payload["sid"] != snapshot_id:
        raise MrReviewError("invalid-schema", "cursor belongs to a different snapshot")
    if payload["q"] != sha256_hex(canonical_json(query)):
        raise MrReviewError("invalid-schema", "cursor belongs to a different query")
    state = payload["s"]
    if not isinstance(state, dict):
        raise MrReviewError("invalid-schema", "cursor state has an unexpected shape")
    return state


def peek_cursor_snapshot(token: str) -> str:
    """Digest-validated cursor -> its snapshot_id, before the snapshot is
    located; the full query binding is checked by the reader afterwards."""
    if not isinstance(token, str) or not token:
        raise MrReviewError("invalid-schema", "cursor must be a non-empty string")
    try:
        payload = json.loads(base64.urlsafe_b64decode(token.encode("ascii")).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        raise MrReviewError("invalid-schema", "cursor is not a valid versioned token") from None
    if not isinstance(payload, dict) or set(payload) != {"v", "sid", "q", "s", "d"}:
        raise MrReviewError("invalid-schema", "cursor payload has an unexpected shape")
    if payload["v"] != CURSOR_VERSION:
        raise MrReviewError("invalid-schema", f"cursor version {payload['v']!r} is not supported")
    if payload["d"] != _cursor_digest({k: payload[k] for k in ("v", "sid", "q", "s")}):
        raise MrReviewError("hash-mismatch", "cursor integrity check failed (tampered cursor)")
    sid = payload["sid"]
    if not isinstance(sid, str) or not _HEX64_RE.fullmatch(sid):
        raise MrReviewError("invalid-schema", "cursor snapshot_id is malformed")
    return sid


def _encode_chunk_bytes(data: bytes) -> tuple[str, str, bytes]:
    """(content, encoding, pending_tail): text when the bytes are valid UTF-8,
    base64 otherwise. A trailing truncated multi-byte sequence is returned as
    the pending tail so text chunks never split a character."""
    try:
        return data.decode("utf-8"), "utf-8", b""
    except UnicodeDecodeError as exc:
        if exc.reason == "unexpected end of data" and 1 <= len(data) - exc.start <= 3:
            prefix = data[:exc.start]
            try:
                return prefix.decode("utf-8"), "utf-8", data[exc.start:]
            except UnicodeDecodeError:
                pass
        return base64.b64encode(data).decode("ascii"), "base64", b""


def _iter_stream_units(stream: Any) -> Iterator[tuple[bytes, bool]]:
    """Yields (data, is_complete_line): complete lines end with b"\\n"; a line
    without a newline past one block yields partial blocks so RAM stays
    bounded even for multi-megabyte single lines."""
    buf = b""
    while True:
        block = stream.read(_READ_BLOCK)
        if not block:
            break
        buf += block
        while True:
            nl = buf.find(b"\n")
            if nl >= 0:
                yield buf[:nl + 1], True
                buf = buf[nl + 1:]
                continue
            if len(buf) >= _READ_BLOCK:
                yield buf[:_READ_BLOCK], False
                buf = buf[_READ_BLOCK:]
            break
    if buf:
        yield buf, True


class _BlobUnits:
    """Line/block units over a prefix buffer plus an optional streamed tail,
    resuming at byte offset ``start``; RAM stays bounded by the block size."""

    def __init__(self, stream: Any, prefix: bytes, start: int) -> None:
        self._stream = stream
        self._prefix = prefix
        self._start = start

    def __iter__(self) -> Iterator[tuple[bytes, bool]]:
        if self._start < len(self._prefix):
            buf = self._prefix[self._start:]
        else:
            buf = b""
            skip = self._start - len(self._prefix)
            while skip > 0 and self._stream is not None:
                chunk = self._stream.read(min(skip, _READ_BLOCK))
                if not chunk:
                    break
                skip -= len(chunk)
        while self._stream is not None:
            block = self._stream.read(_READ_BLOCK)
            if not block:
                break
            buf += block
            while True:
                nl = buf.find(b"\n")
                if nl >= 0:
                    yield buf[:nl + 1], True
                    buf = buf[nl + 1:]
                    continue
                if len(buf) >= _READ_BLOCK:
                    yield buf[:_READ_BLOCK], False
                    buf = buf[_READ_BLOCK:]
                break
        if buf:
            yield buf, True


def _line_advance(line_head: bytes, ol: int | None,
                  nl: int | None) -> tuple[int | None, int | None]:
    """Coordinates of the line following the line whose head bytes are
    ``line_head``; section resets are handled by the caller."""
    if line_head.startswith(b"@@"):
        match = _HUNK_RE.match(line_head)
        if match:
            return int(match.group(1)), int(match.group(2))
        return ol, nl
    if ol is None or nl is None:
        return None, None
    if line_head[:1] == b" ":
        return ol + 1, nl + 1
    if line_head[:1] == b"-":
        return ol + 1, nl
    if line_head[:1] == b"+":
        return ol, nl + 1
    return ol, nl


_TEST_DIRS = frozenset({"test", "tests", "__tests__", "spec", "specs"})
_GEN_DIRS = frozenset({"build", "dist", "out", "target", "node_modules",
                       "generated", "gen", ".gen"})
_DATA_EXTS = frozenset({"json", "csv", "dat", "xml", "yaml", "yml", "sql",
                        "bin", "parquet", "xlsx", "feather"})


def _path_tags(display: str) -> list[dict]:
    parts = display.split("/")
    base = parts[-1].lower()
    stem = base.rsplit(".", 1)[0] if "." in base else base
    dirs = {p.lower() for p in parts[:-1]}
    tags: list[dict] = []
    if dirs & _TEST_DIRS or stem.startswith(("test_", "conftest")) \
            or stem.endswith(("_test", ".test", ".spec")) \
            or ".test." in base or ".spec." in base:
        tags.append({"tag": "test", "reason": "path matches the test-file heuristic"})
    if dirs & _GEN_DIRS or base.endswith((".min.js", ".min.css", ".pb.go", ".map")) \
            or ".generated." in base:
        tags.append({"tag": "generated", "reason": "path matches the generated-file heuristic"})
    ext = base.rsplit(".", 1)[-1] if "." in base else ""
    if ext in _DATA_EXTS:
        tags.append({"tag": "data", "reason": "extension matches the data-file heuristic"})
    return tags


def _split_oversized(value: str, part_bytes: int) -> tuple[str, list[str]]:
    """(part0, parts1plus) for a string whose UTF-8 form exceeds part_bytes;
    short strings return (value, []). Parts are cut on character boundaries so
    multi-byte content never splits a character, and boundaries are
    budget-independent so cursors stay valid across pages with different
    budgets."""
    if len(value.encode("utf-8")) <= part_bytes:
        return value, []
    parts: list[str] = []
    current: list[str] = []
    current_bytes = 0
    for ch in value:
        code = ord(ch)
        ch_len = 1 if code < 0x80 else 2 if code < 0x800 \
            else 3 if code < 0x10000 else 4
        if current and current_bytes + ch_len > part_bytes:
            parts.append("".join(current))
            current = []
            current_bytes = 0
        current.append(ch)
        current_bytes += ch_len
    if current:
        parts.append("".join(current))
    return parts[0], parts[1:]


class _ChunkPacker:
    """Fits stream units into one open chunk at a time, measuring the whole
    page with mcp_result_bytes; an oversized unit splits with byte
    continuation instead of blowing the page or looping forever."""

    def __init__(self, page: dict, budget: int) -> None:
        self._page = page
        self._budget = budget - _RESERVED_BYTES
        self._chunk: dict | None = None
        self._container: list | None = None
        self._buf = b""
        self._start = 0
        self.cut_pos: int | None = None
        self.last_discarded = False

    @property
    def open(self) -> bool:
        return self._chunk is not None

    def begin(self, container: list, template: dict, start: int) -> None:
        self._container = container
        self._chunk = dict(template)
        self._chunk["content"] = ""
        self._chunk["encoding"] = "utf-8"
        self._chunk["continued"] = True
        container.append(self._chunk)
        self._buf = b""
        self._start = start

    def add(self, unit: bytes) -> bool:
        chunk = self._chunk
        if chunk is None:
            raise MrReviewError("invalid-schema", "chunk packer has no open chunk")
        self.last_discarded = False
        candidate = self._buf + unit
        content, encoding, _tail = _encode_chunk_bytes(candidate)
        chunk["content"] = content
        chunk["encoding"] = encoding
        if mcp_result_bytes(self._page) <= self._budget:
            self._buf = candidate
            return True
        if self._buf:
            self.close(continued=True)
            return False
        chunk["content"] = ""
        chunk["encoding"] = "utf-8"
        if mcp_result_bytes(self._page) > self._budget:
            self._container.pop()
            self._chunk = None
            self._container = None
            self._buf = b""
            self.cut_pos = self._start
            self.last_discarded = True
            return False
        lo, hi = 0, len(unit)
        while lo < hi:
            mid = (lo + hi + 1) // 2
            content, encoding, _tail = _encode_chunk_bytes(unit[:mid])
            chunk["content"] = content
            chunk["encoding"] = encoding
            if mcp_result_bytes(self._page) <= self._budget:
                lo = mid
            else:
                hi = mid - 1
        if lo == 0:
            raise MrReviewError(
                "invalid-budget", "page cannot make progress within budget_bytes")
        content, encoding, _tail = _encode_chunk_bytes(unit[:lo])
        chunk["content"] = content
        chunk["encoding"] = encoding
        self._buf = unit[:lo]
        self.close(continued=True)
        return False

    def close(self, continued: bool) -> int:
        """Finalize the open chunk; returns the stream position after the
        emitted bytes (the resume point)."""
        chunk = self._chunk
        if chunk is None:
            raise MrReviewError("invalid-schema", "chunk packer has no open chunk")
        content, encoding, tail = _encode_chunk_bytes(self._buf)
        chunk["content"] = content
        chunk["encoding"] = encoding
        chunk["continued"] = continued
        self.cut_pos = self._start + len(self._buf) - len(tail)
        self._chunk = None
        self._buf = b""
        return self.cut_pos


def _side_info(tree: dict, path: bytes | None, mode: str, sha: str) -> dict | None:
    if path is None or mode == "000000":
        return None
    info = tree.get(path)
    if info is None:
        raise MrReviewError(
            "review-object-missing", f"path {path!r} is not present in the diff tree")
    return {"oid": sha, "mode": mode, "size": info[3]}


def _lines_for(old_path: bytes | None, new_path: bytes | None, exact: bool,
               numstat_path: dict, numstat_ren: dict) -> tuple[int | None, int | None]:
    if exact:
        return 0, 0
    if old_path is not None and new_path is not None and old_path != new_path:
        rec = numstat_ren.get(new_path)
        if rec is not None and rec[2] == old_path:
            return rec[0], rec[1]
    else:
        rec = numstat_path.get(new_path if new_path is not None else old_path)
        if rec is not None:
            return rec[0], rec[1]
    return None, None


_STATUS_MAP = {"A": "added", "D": "deleted", "M": "modified", "T": "modified"}


class MrReviewReader:
    """Snapshot-bound bounded reader: inventory, diff, file and discussions
    views, all paginated with the shared cursor/budget machinery."""

    def __init__(self, git: MrReviewGit, snapshot: dict) -> None:
        self._git = git
        self._snap = snapshot

    @property
    def snapshot_id(self) -> str:
        return self._snap["snapshot_id"]

    def _query(self, view: str, **extra: Any) -> dict:
        query = {"repo": self._snap["repo"], "snapshot_id": self._snap["snapshot_id"],
                 "view": view}
        query.update(extra)
        return query

    # ── Inventory ───────────────────────────────────────────────────────────

    def inventory_page(self, *, cursor: str = "",
                       budget_bytes: int = DEFAULT_BUDGET_BYTES,
                       enclosing: dict | None = None) -> dict:
        budget = validate_budget(budget_bytes)
        query = {"repo": self._snap["repo"], "iid": self._snap["iid"],
                 "view": "inventory"}
        if cursor:
            state = decode_cursor(cursor, self._snap["snapshot_id"], query)
            if set(state) != {"e", "g"}:
                raise MrReviewError("invalid-schema", "inventory cursor state is malformed")
        else:
            state = {"e": 0, "g": 0}
        entries, totals, limited = self._inventory_entries()
        prepared: list[tuple[dict, list[dict]]] = []
        for entry in entries:
            fragments: list[dict] = []
            for field in ("path", "old_path", "new_path"):
                value = entry.get(field)
                if value is None:
                    continue
                part0, rest = _split_oversized(value, _PATH_PART_BYTES)
                entry[field] = part0
                for k, text in enumerate(rest, start=1):
                    fragments.append({"field": field, "part": k, "text": text})
            prepared.append((entry, fragments))

        def payload(inv: dict) -> dict:
            if enclosing is None:
                return inv
            merged = dict(enclosing)
            merged["inventory"] = inv
            return merged

        page: dict[str, Any] = {
            "entries": [],
            "path_fragments": [],
            "rename_detection_limited": limited,
            "complete": False,
            "next_cursor": None,
            "totals": totals,
            "range": {"from_entry": state["e"], "to_entry": state["e"]},
        }
        e, g = state["e"], state["g"]
        while e < len(prepared):
            entry, fragments = prepared[e]
            if g == 0:
                self._probe_tags(entry)
                page["entries"].append(entry)
                if mcp_result_bytes(payload(page)) <= budget - _RESERVED_BYTES:
                    g = 1 if fragments else 0
                    if not fragments:
                        e += 1
                    continue
                page["entries"].pop()
                if page["entries"] or page["path_fragments"]:
                    break
                raise MrReviewError(
                    "invalid-budget", "inventory entry does not fit budget_bytes")
            frag = dict(fragments[g - 1])
            frag["entry_index"] = e
            page["path_fragments"].append(frag)
            if mcp_result_bytes(payload(page)) <= budget - _RESERVED_BYTES:
                g += 1
                if g > len(fragments):
                    e += 1
                    g = 0
                continue
            page["path_fragments"].pop()
            if page["entries"] or page["path_fragments"]:
                break
            raise MrReviewError(
                "invalid-budget", "inventory path fragment does not fit budget_bytes")
        complete = e >= len(prepared)
        page["complete"] = complete
        page["range"]["to_entry"] = e
        if not complete:
            page["next_cursor"] = encode_cursor(
                self._snap["snapshot_id"], query, {"e": e, "g": g})
        return page

    def _inventory_entries(self) -> tuple[list[dict], dict, bool]:
        base = self._snap["comparison_base_sha"]
        head = self._snap["gitlab_diff_refs"]["head_sha"]
        raw_plain, _ = self._git.diff_tree_entries(base, head, renames=False)
        raw_ren, limited = self._git.diff_tree_entries(base, head, renames=True)
        numstat_path, numstat_ren = self._git.diff_numstat(base, head)
        tree_base = self._git.ls_tree_long(base)
        tree_head = self._git.ls_tree_long(head)

        paired_old: set[bytes] = set()
        paired_new: set[bytes] = set()
        pairs: list[tuple[RawDiffEntry, str, int]] = []
        for entry in raw_ren:
            if not entry.status.startswith("R") or entry.old_path is None \
                    or entry.new_path is None:
                continue
            if entry.old_path in paired_old or entry.new_path in paired_new:
                continue
            exact = entry.src_sha == entry.dst_sha
            kind = "exact" if exact else "changed"
            similarity = 100 if exact else _similarity(entry.status)
            pairs.append((entry, kind, similarity))
            paired_old.add(entry.old_path)
            paired_new.add(entry.new_path)

        deleted_by_oid: dict[str, list[RawDiffEntry]] = {}
        added_by_oid: dict[str, list[RawDiffEntry]] = {}
        for entry in raw_plain:
            if entry.status == "D" and entry.old_path is not None \
                    and entry.old_path not in paired_old:
                deleted_by_oid.setdefault(entry.src_sha, []).append(entry)
            elif entry.status == "A" and entry.new_path is not None \
                    and entry.new_path not in paired_new:
                added_by_oid.setdefault(entry.dst_sha, []).append(entry)
        for oid in sorted(set(deleted_by_oid) & set(added_by_oid)):
            dels = sorted(deleted_by_oid[oid], key=lambda x: x.old_path)
            adds = sorted(added_by_oid[oid], key=lambda x: x.new_path)
            for de, ae in zip(dels, adds):
                merged = RawDiffEntry(
                    status="R100", src_mode=de.src_mode, dst_mode=ae.dst_mode,
                    src_sha=de.src_sha, dst_sha=ae.dst_sha,
                    old_path=de.old_path, new_path=ae.new_path)
                pairs.append((merged, "exact", 100))
                paired_old.add(de.old_path)
                paired_new.add(ae.new_path)

        entries: list[dict] = []
        for entry, kind, similarity in pairs:
            entries.append(self._entry_from(
                entry, tree_base, tree_head, numstat_path, numstat_ren,
                "renamed", {"kind": kind, "similarity": similarity}))
        for entry in raw_plain:
            path = entry.new_path if entry.new_path is not None else entry.old_path
            if path is None or path in paired_old or path in paired_new:
                continue
            status = _STATUS_MAP.get(entry.status)
            if status is None:
                raise MrReviewError(
                    "invalid-schema", f"unknown diff status {entry.status!r}")
            entries.append(self._entry_from(
                entry, tree_base, tree_head, numstat_path, numstat_ren, status, None))
        entries.sort(key=lambda item: item["path"])
        totals = {"files": len(entries), "added": 0, "modified": 0, "deleted": 0,
                  "renamed": 0}
        for entry in entries:
            totals[entry["status"]] += 1
        return entries, totals, limited

    def _entry_from(self, entry: RawDiffEntry, tree_base: dict, tree_head: dict,
                    numstat_path: dict, numstat_ren: dict, status: str,
                    rename: dict | None) -> dict:
        old_path = entry.old_path
        new_path = entry.new_path
        old_display, old_pct = _encode_path(old_path) if old_path is not None else (None, False)
        new_display, new_pct = _encode_path(new_path) if new_path is not None else (None, False)
        display = new_display if new_display is not None else old_display
        exact = rename is not None and rename["kind"] == "exact"
        added, deleted = _lines_for(old_path, new_path, exact, numstat_path, numstat_ren)
        tags = _path_tags(display)
        modes = {entry.src_mode, entry.dst_mode} - {"000000"}
        if "160000" in modes:
            tags.append({"tag": "gitlink",
                         "reason": "mode 160000 (submodule commit reference)"})
        if "120000" in modes:
            tags.append({"tag": "symlink", "reason": "mode 120000 (symbolic link)"})
        if old_pct or new_pct:
            tags.append({"tag": "non-utf8-path",
                         "reason": "path bytes are not valid UTF-8; shown percent-encoded"})
        return {
            "path": display,
            "old_path": old_display,
            "new_path": new_display,
            "status": status,
            "old": _side_info(tree_base, old_path, entry.src_mode, entry.src_sha),
            "new": _side_info(tree_head, new_path, entry.dst_mode, entry.dst_sha),
            "lines_added": added,
            "lines_deleted": deleted,
            "module": _module_of(new_path if new_path is not None else old_path),
            "rename": rename,
            "tags": tags,
        }

    def _probe_tags(self, entry: dict) -> None:
        side = entry["new"] if entry["new"] is not None else entry["old"]
        if side is None or side["mode"] == "160000":
            return
        _info, data = self._git.read_blob(side["oid"], 0, _PROBE_BYTES)
        if b"\x00" in data:
            entry["tags"].append(
                {"tag": "binary", "reason": "NUL byte within the first 8000 bytes"})
            return
        decoder = codecs.getincrementaldecoder("utf-8")()
        try:
            decoder.decode(data)
        except UnicodeDecodeError:
            entry["tags"].append(
                {"tag": "non-utf8-content", "reason": "content is not valid UTF-8"})

    # ── Read views ──────────────────────────────────────────────────────────

    def read_page(self, *, view: str, paths: Any = (), group: str = "",
                  side: str = "new", cursor: str = "",
                  budget_bytes: int = DEFAULT_BUDGET_BYTES,
                  context_lines: int = DEFAULT_CONTEXT_LINES) -> dict:
        budget = validate_budget(budget_bytes)
        if view not in VIEWS:
            raise MrReviewError(
                "invalid-schema", f"view must be one of {sorted(VIEWS)}, got {view!r}")
        if side not in SIDES:
            raise MrReviewError(
                "invalid-side", f"side must be one of {sorted(SIDES)}, got {side!r}")
        if not isinstance(paths, (list, tuple)) \
                or any(not isinstance(p, str) or not p for p in paths):
            raise MrReviewError("invalid-schema", "paths must be an array of non-empty strings")
        if not isinstance(group, str):
            raise MrReviewError("invalid-schema", f"group must be a string, got {group!r}")
        if paths and group:
            raise MrReviewError("invalid-schema", "paths and group are mutually exclusive")
        if not isinstance(context_lines, int) or isinstance(context_lines, bool) \
                or context_lines < 0:
            raise MrReviewError(
                "invalid-schema",
                f"context_lines must be a non-negative integer, got {context_lines!r}")
        sel = sorted(set(paths))
        if view == "diff":
            return self._diff_page(sel, group, cursor, budget, context_lines)
        if view == "file":
            return self._file_page(sel, group, side, cursor, budget)
        return self._discussions_page(cursor, budget)

    def _diff_page(self, sel: list[str], group: str, cursor: str, budget: int,
                   context_lines: int) -> dict:
        base = self._snap["comparison_base_sha"]
        head = self._snap["gitlab_diff_refs"]["head_sha"]
        raw_ren, _ = self._git.diff_tree_entries(base, head, renames=True)
        displays: list[str] = []
        for entry in raw_ren:
            raw = entry.new_path if entry.new_path is not None else entry.old_path
            displays.append(_encode_path(raw)[0])
        if sel:
            selected = [i for i, d in enumerate(displays) if d in set(sel)]
            missing = sorted(set(sel) - set(displays))
        elif group:
            selected = [i for i, entry in enumerate(raw_ren)
                        if _module_of(entry.new_path or entry.old_path) == group]
            missing = []
        else:
            selected = list(range(len(raw_ren)))
            missing = []
        page: dict[str, Any] = {
            "view": "diff",
            "snapshot_id": self._snap["snapshot_id"],
            "repo": self._snap["repo"],
            "base_sha": base,
            "head_sha": head,
            "context_lines": context_lines,
            "paths": sel,
            "group": group,
            "chunks": [],
            "missing_paths": missing,
            "limitations": [],
            "complete": False,
            "next_cursor": None,
            "totals": {"files": len(selected), "bytes": 0},
            "range": {"from_file": 0, "to_file": 0, "from_byte": 0, "to_byte": 0},
        }
        if not selected:
            page["complete"] = True
            return page
        query = self._query("diff", paths=sel, group=group, context_lines=context_lines)
        if cursor:
            state = decode_cursor(cursor, self._snap["snapshot_id"], query)
            if set(state) != {"f", "b", "ol", "nl", "ls"}:
                raise MrReviewError("invalid-schema", "diff cursor state is malformed")
        else:
            state = {"f": selected[0], "b": 0, "ol": None, "nl": None, "ls": 0}
        target_f, target_b = state["f"], state["b"]
        resume_ls, resume_ol, resume_nl = state["ls"], state["ol"], state["nl"]
        prefix_needed = target_b > resume_ls
        line_ls, line_ol, line_nl = 0, None, None
        sel_idx = set(selected)
        packer = _ChunkPacker(page, budget)
        proc = self._git.open_diff_patch(base, head, context_lines)
        section = -1
        pos = 0
        in_selected = False
        line_head = b""
        skipping = True
        page_cut = False
        cursor_state: dict | None = None
        total_bytes = 0
        range_started = False
        try:
            for unit, is_line in _iter_stream_units(proc.stdout):
                if is_line and unit.startswith(b"diff --git "):
                    if packer.open:
                        end = packer.close(continued=False)
                        page["range"]["to_file"] = section
                        page["range"]["to_byte"] = end
                    section += 1
                    if section >= len(raw_ren):
                        raise MrReviewError(
                            "review-object-missing",
                            "git diff sections do not align with the raw diff entries")
                    pos = 0
                    line_ls, line_ol, line_nl = 0, None, None
                    line_head = b""
                    in_selected = section in sel_idx
                if in_selected:
                    total_bytes += len(unit)
                if skipping:
                    end = pos + len(unit)
                    if section < target_f or (section == target_f and end <= target_b):
                        if section == target_f and prefix_needed \
                                and pos <= resume_ls < end:
                            line_head = unit[resume_ls - pos:resume_ls - pos + 32]
                            prefix_needed = False
                        pos = end
                        continue
                    if section == target_f and pos <= target_b <= end:
                        if prefix_needed and pos <= resume_ls <= target_b:
                            offset = resume_ls - pos
                            line_head = unit[offset:offset + 32]
                            prefix_needed = False
                        unit = unit[target_b - pos:]
                        pos = target_b
                        skipping = False
                        line_ls, line_ol, line_nl = resume_ls, resume_ol, resume_nl
                    else:
                        skipping = False
                if pos == line_ls and not line_head:
                    line_head = unit[:32]
                if in_selected and not page_cut:
                    entry = raw_ren[section]
                    if not packer.open:
                        path_cap, path_rest = _split_oversized(
                            displays[section], _CHUNK_PATH_MAX)
                        old_cap, old_rest = _split_oversized(
                            _encode_path(entry.old_path)[0], _CHUNK_PATH_MAX) \
                            if entry.old_path else (None, [])
                        new_cap, new_rest = _split_oversized(
                            _encode_path(entry.new_path)[0], _CHUNK_PATH_MAX) \
                            if entry.new_path else (None, [])
                        template = {
                            "path": path_cap,
                            "old_path": old_cap,
                            "new_path": new_cap,
                            "old_blob": entry.src_sha if entry.src_mode != "000000" else None,
                            "new_blob": entry.dst_sha if entry.dst_mode != "000000" else None,
                            "old_line": line_ol,
                            "new_line": line_nl,
                            "line_byte_offset": pos - line_ls,
                            "byte_offset": pos,
                        }
                        if path_rest or old_rest or new_rest:
                            template["path_truncated"] = True
                        packer.begin(page["chunks"], template, pos)
                        if not range_started:
                            page["range"]["from_file"] = section
                            page["range"]["from_byte"] = pos
                            range_started = True
                    if not packer.add(unit):
                        page_cut = True
                        cursor_state = {"f": section, "b": packer.cut_pos,
                                        "ol": line_ol, "nl": line_nl, "ls": line_ls}
                        if not packer.last_discarded:
                            page["range"]["to_file"] = section
                            page["range"]["to_byte"] = packer.cut_pos
                pos += len(unit)
                if is_line:
                    line_ol, line_nl = _line_advance(line_head, line_ol, line_nl)
                    line_ls = pos
                    line_head = b""
            if packer.open:
                end = packer.close(continued=False)
                page["range"]["to_file"] = section
                page["range"]["to_byte"] = end
            stderr = proc.stderr.read() if proc.stderr is not None else b""
            returncode = proc.wait(timeout=30)
            if returncode != 0:
                raise MrReviewError(
                    "review-object-missing",
                    f"git diff failed: {stderr.decode('utf-8', 'replace').strip()}")
        finally:
            if proc.poll() is None:
                proc.kill()
            for stream in (proc.stdout, proc.stderr):
                if stream is not None:
                    stream.close()
            proc.wait()
        page["totals"]["bytes"] = total_bytes
        page["complete"] = not page_cut
        if page_cut and cursor_state is not None:
            page["next_cursor"] = encode_cursor(
                self._snap["snapshot_id"], query, cursor_state)
        if any(chunk["encoding"] == "base64" for chunk in page["chunks"]):
            page["limitations"].append(
                "diff content contains non-UTF-8 bytes; base64 chunks carry the exact bytes")
        return page

    def _file_page(self, sel: list[str], group: str, side: str, cursor: str,
                   budget: int) -> dict:
        base = self._snap["comparison_base_sha"]
        head = self._snap["gitlab_diff_refs"]["head_sha"]
        side_commit = {"old": base, "new": head,
                       "target": self._snap["target_tip_sha"]}[side]
        tree = self._git.ls_tree_long(side_commit)
        raw_plain, _ = self._git.diff_tree_entries(base, head, renames=False)
        inv: dict[str, tuple[bytes | None, bytes | None]] = {}
        for entry in raw_plain:
            raw = entry.new_path if entry.new_path is not None else entry.old_path
            inv[_encode_path(raw)[0]] = (entry.old_path, entry.new_path)
        if sel:
            selection = sel
        elif group:
            selection = [d for d in sorted(inv) if _module_of(d.encode("utf-8")) == group]
        else:
            selection = sorted(inv)
        page: dict[str, Any] = {
            "view": "file",
            "snapshot_id": self._snap["snapshot_id"],
            "repo": self._snap["repo"],
            "side": side,
            "paths": sel,
            "group": group,
            "files": [],
            "limitations": [],
            "complete": False,
            "next_cursor": None,
            "totals": {"files": len(selection), "bytes": 0},
            "range": {"from_path": 0, "to_path": 0},
        }
        total_bytes = 0
        for d in selection:
            raw = _resolve_side_path(d, *inv.get(d, (None, None)), side)
            info = tree.get(raw) if raw is not None else None
            if info is not None and info[1] != "commit":
                total_bytes += info[3]
        page["totals"]["bytes"] = total_bytes
        if not selection:
            page["complete"] = True
            return page
        query = self._query("file", paths=sel, group=group, side=side)
        if cursor:
            state = decode_cursor(cursor, self._snap["snapshot_id"], query)
            if set(state) != {"p", "b", "ln", "ls"}:
                raise MrReviewError("invalid-schema", "file cursor state is malformed")
        else:
            state = {"p": 0, "b": 0, "ln": 1, "ls": 0}
        p, b, ln, ls = state["p"], state["b"], state["ln"], state["ls"]
        packer = _ChunkPacker(page, budget)
        page_cut = False
        cursor_state: dict | None = None
        page["range"]["from_path"] = p
        while p < len(selection) and not page_cut:
            d = selection[p]
            path_cap, path_rest = _split_oversized(d, _CHUNK_PATH_MAX)
            raw = _resolve_side_path(d, *inv.get(d, (None, None)), side)
            info = tree.get(raw) if raw is not None else None
            if info is None:
                item = {"path": path_cap, "kind": "file", "oid": None,
                        "size": None, "mode": None, "missing": True,
                        "limitation": f"path is not present in the {side} tree",
                        "chunks": []}
                if path_rest:
                    item["path_truncated"] = True
                if not self._append_bounded(page, item, budget):
                    break
                p += 1
                continue
            mode, typ, oid, size = info
            if typ == "commit":
                item = {"path": path_cap, "kind": "gitlink", "oid": oid,
                        "size": None, "mode": mode, "missing": False,
                        "limitation": "gitlink references a submodule commit; "
                                      "no content is traversed",
                        "chunks": []}
                if path_rest:
                    item["path_truncated"] = True
                if not self._append_bounded(page, item, budget):
                    break
                p += 1
                continue
            kind = "symlink" if mode == "120000" else "file"
            item = {"path": path_cap, "kind": kind, "oid": oid, "size": size,
                    "mode": mode, "missing": False, "limitation": None,
                    "chunks": []}
            if path_rest:
                item["path_truncated"] = True
            proc: subprocess.Popen | None = None
            try:
                if size <= _SMALL_BLOB:
                    _info, data = self._git.read_blob(oid, 0, size)
                    probe, stream, prefix = data[:_PROBE_BYTES], None, data
                else:
                    proc = self._git.open_blob_stream(oid)
                    probe = proc.stdout.read(_PROBE_BYTES) \
                        if proc.stdout is not None else b""
                    stream, prefix = proc.stdout, probe
                limitation = _probe_limitation(probe)
                if limitation is not None:
                    item["limitation"] = limitation
                page["files"].append(item)
                if mcp_result_bytes(page) > budget - _RESERVED_BYTES:
                    page["files"].pop()
                    if page["files"]:
                        break
                    raise MrReviewError(
                        "invalid-budget", "file entry does not fit budget_bytes")
                if limitation is not None:
                    p += 1
                    continue
                pos = b
                for unit, is_line in _BlobUnits(stream, prefix, b):
                    if not packer.open:
                        packer.begin(item["chunks"], {
                            "path": path_cap, "line": ln,
                            "line_byte_offset": pos - ls,
                            "byte_offset": pos}, pos)
                    if not packer.add(unit):
                        page_cut = True
                        cursor_state = {"p": p, "b": packer.cut_pos,
                                        "ln": ln, "ls": ls}
                        if not item["chunks"]:
                            page["files"].pop()
                        break
                    pos += len(unit)
                    if is_line:
                        ln += 1
                        ls = pos
                if page_cut:
                    break
                if proc is not None:
                    returncode = proc.wait(timeout=30)
                    if returncode != 0:
                        raise MrReviewError(
                            "review-object-missing",
                            f"git cat-file failed for blob {oid}")
                if packer.open:
                    packer.close(continued=False)
                p += 1
                b, ln, ls = 0, 1, 0
            finally:
                if proc is not None:
                    if proc.poll() is None:
                        proc.kill()
                    for stream_ in (proc.stdout, proc.stderr):
                        if stream_ is not None:
                            stream_.close()
                    proc.wait()
        if cursor_state is None and p < len(selection):
            cursor_state = {"p": p, "b": b, "ln": ln, "ls": ls}
        page["range"]["to_path"] = p
        complete = p >= len(selection)
        page["complete"] = complete
        if not complete and cursor_state is not None:
            page["next_cursor"] = encode_cursor(
                self._snap["snapshot_id"], query, cursor_state)
        if any(chunk["encoding"] == "base64"
               for item in page["files"] for chunk in item["chunks"]):
            page["limitations"].append(
                "file content contains non-UTF-8 bytes; base64 chunks carry the exact bytes")
        return page

    @staticmethod
    def _append_bounded(page: dict, item: dict, budget: int) -> bool:
        page["files"].append(item)
        if mcp_result_bytes(page) <= budget - _RESERVED_BYTES:
            return True
        page["files"].pop()
        if page["files"]:
            return False
        raise MrReviewError("invalid-budget", "file entry does not fit budget_bytes")

    def _discussions_page(self, cursor: str, budget: int) -> dict:
        discs = sorted(self._snap["discussions"],
                       key=lambda d: d["discussion_id"])
        flat: list[tuple[int, int, dict]] = []
        for di, disc in enumerate(discs):
            for ni, note in enumerate(sorted(disc["notes"],
                                              key=lambda n: n["note_id"])):
                flat.append((di, ni, note))
        page: dict[str, Any] = {
            "view": "discussions",
            "snapshot_id": self._snap["snapshot_id"],
            "repo": self._snap["repo"],
            "notes": [],
            "note_fragments": [],
            "complete": False,
            "next_cursor": None,
            "totals": {"discussions": len(discs), "notes": len(flat)},
            "range": {"from_note": 0, "to_note": 0},
        }
        if not flat:
            page["complete"] = True
            return page
        query = self._query("discussions")
        if cursor:
            state = decode_cursor(cursor, self._snap["snapshot_id"], query)
            if set(state) != {"i", "g"}:
                raise MrReviewError(
                    "invalid-schema", "discussions cursor state is malformed")
        else:
            state = {"i": 0, "g": 0}
        page["range"]["from_note"] = state["i"]
        i, g = state["i"], state["g"]
        while i < len(flat):
            di, ni, note = flat[i]
            part0, rest = _split_oversized(note["body"], _BODY_PART_BYTES)
            if g == 0:
                item = {key: note[key] for key in (
                    "note_id", "body", "author", "created_at",
                    "system", "resolvable", "resolved", "position")}
                item["discussion_id"] = discs[di]["discussion_id"]
                item["body"] = part0
                page["notes"].append(item)
                if mcp_result_bytes(page) <= budget - _RESERVED_BYTES:
                    g = 1 if rest else 0
                    if not rest:
                        i += 1
                    continue
                page["notes"].pop()
                if page["notes"] or page["note_fragments"]:
                    break
                raise MrReviewError(
                    "invalid-budget", "discussion note does not fit budget_bytes")
            frag = {"discussion_index": di, "note_index": ni, "part": g,
                    "text": rest[g - 1]}
            page["note_fragments"].append(frag)
            if mcp_result_bytes(page) <= budget - _RESERVED_BYTES:
                g += 1
                if g > len(rest):
                    i += 1
                    g = 0
                continue
            page["note_fragments"].pop()
            if page["notes"] or page["note_fragments"]:
                break
            raise MrReviewError(
                "invalid-budget", "note fragment does not fit budget_bytes")
        page["range"]["to_note"] = i
        complete = i >= len(flat)
        page["complete"] = complete
        if not complete:
            page["next_cursor"] = encode_cursor(
                self._snap["snapshot_id"], query, {"i": i, "g": g})
        return page


def _similarity(status: str) -> int:
    digits = status[1:]
    if digits.isdigit():
        return int(digits)
    return 100


def _resolve_side_path(display: str, old_b: bytes | None, new_b: bytes | None,
                       side: str) -> bytes | None:
    if old_b is None and new_b is None:
        return display.encode("utf-8")
    if side == "old":
        return old_b
    if side == "new":
        return new_b
    return new_b if new_b is not None else old_b


def _probe_limitation(probe: bytes) -> str | None:
    if b"\x00" in probe:
        return "binary content is not delivered as text"
    decoder = codecs.getincrementaldecoder("utf-8")()
    try:
        decoder.decode(probe)
    except UnicodeDecodeError:
        return "content is not valid UTF-8; not delivered as text"
    return None


def _load_manifest_file(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise MrReviewError(
            "hash-mismatch", f"installed snapshot file {path} is unreadable") from None
    return verify_snapshot(data)


def open_reader(home: Path | str | None, repo: str, snapshot_id: str) -> MrReviewReader:
    """Locate an imported snapshot by scanning the review stores under
    ``home``; the snapshot manifest is re-verified on load and its repo must
    match. No store construction, no import lock: reads never block imports."""
    if not isinstance(repo, str) or not repo:
        raise MrReviewError("invalid-schema", "repo must be a non-empty string")
    if not isinstance(snapshot_id, str) or not _HEX64_RE.fullmatch(snapshot_id):
        raise MrReviewError(
            "invalid-schema", f"snapshot_id must be 64-char lowercase hex, got {snapshot_id!r}")
    root_home = reviews_home(home)
    if not root_home.is_dir():
        raise MrReviewError(
            "review-object-missing", f"no review store found under {root_home}")
    for child in sorted(root_home.iterdir()):
        if not child.is_dir():
            continue
        path = resolve_contained(child / "snapshots", f"{snapshot_id}.json")
        if path.is_file():
            snap = _load_manifest_file(path)
            if snap["repo"] != repo:
                raise MrReviewError(
                    "invalid-schema",
                    f"snapshot {snapshot_id} belongs to repo {snap['repo']!r}, "
                    f"requested {repo!r}")
            return MrReviewReader(MrReviewGit(child / "objects.git"), snap)
    raise MrReviewError(
        "review-object-missing",
        f"snapshot {snapshot_id} is not imported under {root_home}")
