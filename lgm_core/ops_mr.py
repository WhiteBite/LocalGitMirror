from __future__ import annotations

import hashlib
import re
import subprocess
import time
from datetime import datetime
from pathlib import Path

from .client import MirrorClient, LgmError
from .config import cfg
from .git_remote import gitlab_project_for
from .mr_review_handoff import HandoffRejection, looks_like_json, parse_and_validate
from .mr_review_models import batch_artifact_path, canonical_json
from .op_models import Ctx, _client, _repo_arg
from .ops_git import send_branches


_MRN_TITLE = re.compile(r"^# MR !(\d+) — (.*)$")
_MRN_BRANCH = re.compile(r"^- \*\*Ветка:\*\* `([^`]+)`")
_MRN_TARGET = re.compile(r"^- \*\*(?:Target|Целевая):\*\* `([^`]+)`")
_MRN_UPDATED = re.compile(r"^- \*\*Обновлено:\*\* (\S+)")
_MRN_DETECTED = re.compile(r"^- \*\*Обнаружено:\*\* (\S+)")
_MR_NOTES_FILE = re.compile(r"mr-!(\d+)\.md$")
_MR_CLOSED_FILE = re.compile(r"mr-!(\d+)\.closed\.md$")
_NOTES_THREAD_ID = re.compile(r"^\*\*ID треда:\*\* `([^`]+)`")
_NOTE_LGM_MARKER = re.compile(r"<!-- lgm:([0-9a-f]+) -->")
_NOTES_ANCHOR = re.compile(r"^\*\*Место:\*\* `([^`]+):(\d+)`")
_REPLY_THREAD = re.compile(r"^##\s+thread\s+(\S+)\s*$", re.MULTILINE)
_REPLY_ANCHORED = re.compile(r"^##\s+new\s+(\S+?):(\d+)\s*$", re.MULTILINE)
ANCHOR_WINDOW = 3

_REPLIES_STATUS_MARKER = "<!-- lgm-replies-status v1 -->"
_REPLIES_STATUS_FIELD = re.compile(r"^- ([a-z ]+):\s*(.*)$")


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


def _note_and_marker_items(c: MirrorClient, repo: str, iid: int) -> tuple | None:
    """(newest note item, newest closed-marker item) for the iid; None when unreachable."""
    try:
        lst = c.file_sync_list(repo)
    except LgmError:
        return None
    items = lst.get("items") or []
    note = None
    for i in items:
        eff = i.get("path") or ""
        if not eff.startswith("mr-notes/"):
            continue
        m = _MR_NOTES_FILE.search(eff)
        if not m or int(m.group(1)) != iid:
            continue
        if note is None or (i.get("mtime") or 0) > (note.get("mtime") or 0):
            note = i
    return note, _closed_marker_iids(items).get(iid)


def _anchor_diff_warnings(proj: Path | None, iid: int, source: str, target: str,
                          anchors: list) -> list[str]:
    """Non-blocking file-level check: GitLab anchors '## new' threads only to
    lines inside the MR diff, so a path outside merge-base(target)..source
    posts as a general note. Line-level checks belong to the plugin."""
    if proj is None or not anchors or not source or not target:
        return []
    src = _local_tip(proj, source, missing_ok=True)
    tgt = _local_tip(proj, target, missing_ok=True)
    if not src or not tgt:
        return []
    mb = subprocess.run(
        ["git", "-C", str(proj), "merge-base", tgt, src],
        capture_output=True, text=True, timeout=30,
    )
    base = mb.stdout.strip()
    if mb.returncode != 0 or not base:
        return []
    diff = subprocess.run(
        ["git", "-C", str(proj), "diff", "--name-only", f"{base}..{src}"],
        capture_output=True, text=True, timeout=30,
    )
    if diff.returncode != 0:
        return []
    changed = set(diff.stdout.splitlines())
    return [
        f"{file}:{line} not in MR !{iid} diff ({source} vs {target}) - "
        f"will post as a general note or belongs in an issue"
        for file, line in anchors if file not in changed
    ]


def _notes_precheck(c: MirrorClient, repo: str, iid: int, content: str, force: bool,
                    project: Path | None = None) -> dict:
    """Reject replies for a closed MR or ones duplicating the live review: a '## new'
    anchor colliding with an unresolved thread (±ANCHOR_WINDOW lines, same file) or a
    '## thread' id absent from the notes. Fail-closed when the notes are unavailable:
    thread ids cannot be verified, so the reply is refused unless force.
    ``anchor_warnings`` (non-blocking) list '## new' anchors whose file is outside
    the MR diff; computed only when both branches resolve in ``project``."""
    thread_refs = _REPLY_THREAD.findall(content)
    anchors = [(m.group(1), int(m.group(2))) for m in _REPLY_ANCHORED.finditer(content)]
    found = _note_and_marker_items(c, repo, iid)
    if found is not None and _closed_by_mtime(found[0], found[1]):
        if force:
            return {"status": "forced"}
        state, evidence = _state_with_evidence(found[0], found[1])
        return {"status": "rejected", "code": "closed-mr",
                "state": state, "state_evidence": evidence,
                "error": f"MR !{iid} is closed/merged (evidence: {evidence}) — "
                         f"replies are not sent; the review is over"}
    if not thread_refs and not anchors:
        return {"status": "not-needed"}
    if found is None or found[0] is None:
        if force:
            return {"status": "forced"}
        return {"status": "rejected", "code": "notes-unavailable",
                "error": f"mr-notes for MR !{iid} are unavailable (expired or not "
                         f"transferred) — cannot verify thread ids; "
                         f"force=true to override"}
    try:
        notes = c.file_sync_fetch(repo, found[0]["id"]).decode("utf-8")
    except Exception:
        if force:
            return {"status": "forced"}
        return {"status": "rejected", "code": "notes-unavailable",
                "error": f"mr-notes for MR !{iid} could not be fetched — cannot "
                         f"verify thread ids; force=true to override"}
    threads = _parse_notes_threads(notes)
    collisions = _anchor_collisions(anchors, threads["unresolved"])
    unknown = [t for t in thread_refs if t not in threads["ids"]]
    head = _parse_mr_notes_head(notes)
    anchor_warnings = _anchor_diff_warnings(
        project, iid, head["source_branch"], head["target_branch"], anchors)
    result = {"status": "ok", "collisions": collisions, "unknown_threads": unknown,
              "anchor_warnings": anchor_warnings}
    if not collisions and not unknown:
        return result
    result["status"] = "forced" if force else "rejected"
    if force:
        return result
    parts = []
    for col in collisions:
        parts.append(
            f"'## new {col['new']}' collides with unresolved thread {col['thread']} "
            f"at {col['thread_anchor']} — answer it with '## thread {col['thread']}' "
            f"instead of opening a duplicate (force=true to override)")
    for t in unknown:
        known = ", ".join(sorted(threads["ids"])) or "none"
        parts.append(f"'## thread {t}' not found in mr-notes for MR !{iid} "
                     f"(known: {known}); copy the id verbatim from mr_notes")
    result["error"] = "; ".join(parts)
    return result


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


def _mr_list_from_postbox(c: MirrorClient, ctx: Ctx, args: dict) -> dict:
    """Home without GitLab: MR inventory from transferred mr-notes blobs."""
    repo = (args.get("repo") or "").strip()
    repos = [repo] if repo else [
        r.get("name") if isinstance(r, dict) else r
        for r in (c.repos().get("repos") or [])
    ]
    out: list[dict] = []
    errors: list[dict] = []
    for name in repos:
        if not name:
            continue
        try:
            lst = c.file_sync_list(name)
        except LgmError as e:
            errors.append({"repo": name, "error": e.message})
            continue
        items = lst.get("items") or []
        newest: dict[int, dict] = {}
        for i in items:
            eff = i.get("path") or ""
            if not eff.startswith("mr-notes/"):
                continue
            m = _MR_NOTES_FILE.search(eff)
            if not m:
                continue
            iid = int(m.group(1))
            if iid not in newest or (i.get("mtime") or 0) > (newest[iid].get("mtime") or 0):
                newest[iid] = i
        markers = _closed_marker_iids(items)
        for iid in sorted(set(newest) | set(markers)):
            marker = markers.get(iid)
            state, evidence = _state_with_evidence(newest.get(iid), marker)
            closed = state == "closed"
            src = marker if closed else newest.get(iid)
            if src is None:
                continue
            try:
                plain = c.file_sync_fetch(name, src["id"]).decode("utf-8")
            except Exception:
                continue
            head = _parse_mr_notes_head(plain)
            if not head["iid"]:
                continue
            out.append({
                "iid": head["iid"],
                "title": head["title"],
                "source_branch": head["source_branch"],
                "target_branch": head["target_branch"],
                "updated_at": head["updated"],
                "unresolved": 0 if closed else head["unresolved"],
                "source": "cache",
                "state": state,
                "state_evidence": evidence,
                "repo": name,
            })
    return {"items": out, "errors": errors}


def op_mr_list(ctx: Ctx, args: dict) -> dict:
    """List open GitLab merge requests; without GitLab config fall back to transferred notes."""
    c = _client(ctx)
    items: list[dict] = []
    errors: list[dict] = []
    gitlab_down = False
    try:
        items = [
            {
                "iid": m.get("iid"),
                "title": m.get("title", ""),
                "source_branch": m.get("source_branch", ""),
                "target_branch": m.get("target_branch", ""),
                "updated_at": m.get("updated_at", ""),
                "source": "gitlab",
                "state": "open",
            }
            for m in c.gitlab_list_mrs()
        ]
    except LgmError as e:
        if e.code != "config":
            raise
        gitlab_down = True
    if gitlab_down:
        fallback = _mr_list_from_postbox(c, ctx, args)
        items = fallback["items"]
        errors = fallback["errors"]
    return {"count": len(items), "items": items, "errors": errors}


def _resolve_mr_targets(c: MirrorClient, args: dict,
                        project: str = "") -> list[dict]:
    """Resolve requested MRs/branches to [{iid, branch}], deduped by branch.

    ``project`` is the GitLab project path derived from the --project
    checkout's origin remote; empty falls back to GITLAB_PROJECT.
    """
    try:
        iid_single = int(args.get("iid", 0) or 0)
    except (TypeError, ValueError):
        raise LgmError("config", "--iid must be an integer") from None
    iids: list[int] = [iid_single] if iid_single else []
    iids_raw = (args.get("iids") or "").strip()
    if iids_raw:
        for tok in iids_raw.split(","):
            tok = tok.strip().lstrip("!").strip()
            if not tok:
                continue
            try:
                iids.append(int(tok))
            except ValueError:
                raise LgmError("config", f"--iids: '{tok}' is not an integer") from None
    iids = list(dict.fromkeys(iids))

    targets: list[dict] = []
    seen: set[str] = set()

    def _add(iid, branch: str) -> None:
        branch = branch.strip()
        if branch and branch not in seen:
            seen.add(branch)
            targets.append({"iid": iid, "branch": branch})

    if args.get("all_open", False):
        for m in c.gitlab_list_mrs(project=project):
            _add(m.get("iid"), m.get("source_branch") or "")
    for i in iids:
        mr = c.gitlab_get_mr(i, project=project)
        branch = (mr.get("source_branch") or "").strip()
        if not branch:
            raise LgmError("gitlab", f"MR !{i}: empty source branch")
        _add(i, branch)
    for tok in (args.get("branch") or "").split(","):
        _add(None, tok)
    return targets


def _diverged_warning(branch: str) -> str:
    return (f"local branch '{branch}' diverged from origin — not sent; "
            f"fix with 'git pull origin {branch}' or rebase onto 'origin/{branch}'")


def _fetch_mr_branches(proj: Path, branches: list[str]) -> list[str]:
    """Fetch all branches from origin in one call, then align each local ref
    to the origin tip: create it when missing, fast-forward when the local tip
    is an ancestor. Local refs with own commits (ahead or diverged) are left
    untouched and returned, so callers never ship a non-origin tip."""
    refspecs = [f"+refs/heads/{b}:refs/remotes/origin/{b}" for b in branches]
    fetch = subprocess.run(
        ["git", "-C", str(proj), "fetch", "origin", *refspecs],
        capture_output=True, text=True, timeout=300,
    )
    if fetch.returncode != 0:
        raise LgmError("git", fetch.stderr.strip() or "git fetch origin failed")
    diverged: list[str] = []
    for b in branches:
        org = subprocess.run(
            ["git", "-C", str(proj), "rev-parse", "--verify",
             f"refs/remotes/origin/{b}"],
            capture_output=True, text=True, timeout=30,
        )
        if org.returncode != 0:
            raise LgmError("git", f"branch '{b}' not found on origin after fetch")
        origin_sha = org.stdout.strip()
        chk = subprocess.run(
            ["git", "-C", str(proj), "rev-parse", "--verify", f"refs/heads/{b}"],
            capture_output=True, text=True, timeout=30,
        )
        if chk.returncode != 0:
            current = subprocess.run(
                ["git", "-C", str(proj), "symbolic-ref", "--short", "-q", "HEAD"],
                capture_output=True, text=True, timeout=30,
            ).stdout.strip()
            create = (["merge", "--ff-only", "--no-edit", origin_sha] if current == b
                      else ["branch", b, origin_sha])
            mk = subprocess.run(
                ["git", "-C", str(proj), *create],
                capture_output=True, text=True, timeout=30,
            )
            if mk.returncode != 0:
                raise LgmError(
                    "git",
                    mk.stderr.strip() or f"branch '{b}' not found after fetch",
                )
            continue
        local_sha = chk.stdout.strip()
        if local_sha == origin_sha:
            continue
        anc = subprocess.run(
            ["git", "-C", str(proj), "merge-base", "--is-ancestor",
             local_sha, origin_sha],
            capture_output=True, text=True, timeout=30,
        )
        if anc.returncode != 0:
            diverged.append(b)
            continue
        current = subprocess.run(
            ["git", "-C", str(proj), "symbolic-ref", "--short", "-q", "HEAD"],
            capture_output=True, text=True, timeout=30,
        ).stdout.strip()
        update = (["merge", "--ff-only", "--no-edit", origin_sha] if current == b
                  else ["fetch", ".", f"refs/remotes/origin/{b}:refs/heads/{b}"])
        upd = subprocess.run(
            ["git", "-C", str(proj), *update],
            capture_output=True, text=True, timeout=30,
        )
        if upd.returncode != 0:
            raise LgmError(
                "git", upd.stderr.strip() or f"cannot fast-forward branch '{b}'")
    return diverged


def _local_tip(proj: Path, branch: str, missing_ok: bool = False) -> str:
    proc = subprocess.run(
        ["git", "-C", str(proj), "rev-parse", f"refs/heads/{branch}"],
        capture_output=True, text=True, timeout=30,
    )
    if proc.returncode != 0:
        if missing_ok:
            return ""
        raise LgmError("git", f"cannot resolve refs/heads/{branch}")
    return proc.stdout.strip()


def _mirror_refs_safe(c: MirrorClient, repo: str) -> dict:
    """Mirror branch tips; {} when the repo is not on the mirror yet."""
    try:
        return c.sync_refs(repo).get("refs") or {}
    except LgmError as e:
        if e.code != 404:
            raise
        return {}


def _existing_shas(proj: Path, shas: list[str]) -> list[str]:
    """Keep only SHAs present locally (a ^sha exclusion of an unknown commit
    would make git bundle fail)."""
    deduped = [s for s in dict.fromkeys(shas) if s]
    if not deduped:
        return []
    proc = subprocess.run(
        ["git", "-C", str(proj), "cat-file", "--batch-check"],
        input="\n".join(deduped) + "\n",
        capture_output=True, text=True, timeout=30,
    )
    lines = (proc.stdout or "").splitlines()
    out = []
    for i, sha in enumerate(deduped):
        if i >= len(lines):
            break
        parts = lines[i].split(" ", 2)
        if len(parts) >= 2 and parts[1] == "commit":
            out.append(sha)
    return out


def send_exclusions(mirror_tips: list[str], sent_tips: list[str],
                    existing_locally: set[str]) -> list[str]:
    """^exclusions for a send: mirror tips present locally, minus the sent tips
    (excluding a sent tip makes git bundle refuse an empty pack)."""
    sent = set(sent_tips)
    return [s for s in dict.fromkeys(mirror_tips)
            if s and s in existing_locally and s not in sent]


def _dirty_tracked_paths(proj: Path) -> list[str]:
    """Tracked files with uncommitted changes; untracked files are user data
    and never block a send."""
    proc = subprocess.run(
        ["git", "-C", str(proj), "status", "--porcelain", "-uno"],
        capture_output=True, text=True, timeout=60,
    )
    if proc.returncode != 0:
        return []
    paths: list[str] = []
    for line in proc.stdout.splitlines():
        entry = line[3:].strip() if len(line) > 3 else ""
        if " -> " in entry:
            entry = entry.split(" -> ", 1)[1]
        if entry.startswith('"') and entry.endswith('"'):
            entry = entry[1:-1]
        if entry:
            paths.append(entry)
    return paths


def _negotiate_mr_bases(c: MirrorClient, proj: Path, repo: str,
                        branches: list[str]) -> tuple[list[str], bool]:
    history = subprocess.run(
        ["git", "-C", str(proj), "rev-list", "--max-count=300", "--date-order", *branches, "--"],
        capture_output=True, text=True, timeout=60,
    )
    if history.returncode:
        raise LgmError("git", history.stderr.strip())
    candidates = history.stdout.splitlines()
    result = c.sync_negotiate(repo, candidates)
    offered = set(candidates)
    return ([sha for sha in result.get("known", []) if sha in offered],
            bool(result.get("created")))


def op_mr_send(ctx: Ctx, args: dict) -> dict:
    """Fetch GitLab MR branches into a local project and send them to the mirror.

    Multi-MR: --iid 41 / --iids 41,42,43 / --all-open / --branch a,b combine
    into one deduplicated transfer:
      - one `git fetch` for all branches;
      - branches whose tip the mirror already has are skipped entirely;
      - all remaining branches go into ONE bundle, so their shared history is
        packed once, with mirror tips as ^exclusions (only new commits travel).
    --dry-run skips every git mutation and the upload: the plan (sent/skipped
    per branch, from local tips vs mirror tips) is returned instead.
    A worktree with uncommitted tracked changes is refused (code
    dirty-worktree); untracked files never block. ``created`` in the result
    says whether the negotiate registered the mirror repo.
    """
    c = _client(ctx)
    repo = args.get("repo", "")
    project = args.get("project", "")
    if not repo:
        raise LgmError("config", "--repo is required")
    if not project:
        raise LgmError("config", "--project is required")
    proj = Path(project).resolve()
    if not proj.is_dir():
        raise LgmError("config", f"project not found: {proj}")

    gitlab_project = gitlab_project_for(proj)
    targets = _resolve_mr_targets(c, args, project=gitlab_project)
    if not targets:
        raise LgmError("config", "--iid, --iids, --all-open or --branch is required")

    mirror_refs = _mirror_refs_safe(c, repo)

    if args.get("dry_run", False):
        sent: list[dict] = []
        skipped: list[dict] = []
        for t in targets:
            tip = _local_tip(proj, t["branch"], missing_ok=True)
            mirror_sha = (mirror_refs.get(t["branch"]) or {}).get("sha", "")
            t["tip"] = tip
            if mirror_sha and tip and mirror_sha == tip:
                skipped.append(t)
            else:
                sent.append(t)
        return {"repo": repo, "project": str(proj), "dry_run": True,
                "sent": sent, "skipped": skipped, "warnings": [], "blocked": [],
                "message": (f"dry-run: would send {len(sent)} branch(es), "
                            f"skip {len(skipped)}; no refs moved, nothing "
                            f"uploaded (plan uses local tips, origin not fetched)")}

    dirty = _dirty_tracked_paths(proj)
    if dirty:
        shown = ", ".join(dirty[:10])
        if len(dirty) > 10:
            shown += f" (+{len(dirty) - 10} more)"
        raise LgmError(
            "config",
            f"worktree has uncommitted tracked changes — commit or stash "
            f"before sending MR branches: {shown}",
            machine_code="dirty-worktree")

    diverged = _fetch_mr_branches(proj, [t["branch"] for t in targets])
    warnings = [_diverged_warning(b) for b in diverged]
    blocked = [{"iid": next((t["iid"] for t in targets if t["branch"] == b), None),
                "branch": b, "reason": "diverged from origin"}
               for b in diverged]
    blocked_branches = set(diverged)

    sent: list[dict] = []
    skipped: list[dict] = []
    for t in targets:
        if t["branch"] in blocked_branches:
            continue
        tip = _local_tip(proj, t["branch"])
        mirror_sha = (mirror_refs.get(t["branch"]) or {}).get("sha", "")
        t["tip"] = tip
        if mirror_sha and mirror_sha == tip:
            skipped.append(t)
        else:
            sent.append(t)

    if not sent:
        return {"repo": repo, "project": str(proj), "sent": [], "skipped": skipped,
                "warnings": warnings, "blocked": blocked,
                "message": (f"no branches sent: {len(blocked)} blocked, {len(skipped)} already current"
                            if blocked else "nothing new: mirror already has all requested commits")}

    mirror_shas = [info.get("sha", "") for info in mirror_refs.values()]
    known, created = _negotiate_mr_bases(c, proj, repo, [t["branch"] for t in sent])
    mirror_shas = list(dict.fromkeys([*mirror_shas, *known]))
    exclude_shas = send_exclusions(
        mirror_shas, [t["tip"] for t in sent],
        set(_existing_shas(proj, mirror_shas)))
    send_result = send_branches(ctx, repo, str(proj), [t["branch"] for t in sent],
                                exclude_shas)
    return {"repo": repo, "project": str(proj), "sent": sent, "skipped": skipped,
            "warnings": warnings, "blocked": blocked, "created": created,
            "send": send_result}


def op_mr_notes(ctx: Ctx, args: dict) -> dict:
    """Read MR discussion notes (mr-notes/mr-!N.md) from the v3 file postbox."""
    c = _client(ctx)
    repo = _repo_arg(args)
    lst = c.file_sync_list(repo)
    iid = int(args.get("iid") or 0)
    newest: dict[int, dict] = {}
    for i in (lst.get("items") or []):
        eff = i.get("path") or ""
        if not eff.startswith("mr-notes/"):
            continue
        m = _MR_NOTES_FILE.search(eff)
        if not m:
            continue
        n = int(m.group(1))
        if iid and n != iid:
            continue
        if n not in newest or (i.get("mtime") or 0) > (newest[n].get("mtime") or 0):
            newest[n] = i
    markers = _closed_marker_iids(lst.get("items") or [])
    if iid:
        markers = {k: v for k, v in markers.items() if k == iid}
    notes = []
    for n in sorted(set(newest) | set(markers)):
        src = newest.get(n) or markers[n]
        try:
            plain = c.file_sync_fetch(repo, src["id"])
            entry = {"iid": n, "path": src["path"], "markdown": plain.decode("utf-8")}
        except Exception:
            entry = {"iid": n, "path": src["path"], "error": "fetch/decrypt failed (v3 relay)"}
        if _closed_by_mtime(newest.get(n), markers.get(n)):
            entry["state"] = "closed"
        notes.append(entry)
    return {"success": True, "repo": repo, "count": len(notes), "notes": notes}


def _handoff_refusal(e: HandoffRejection, check: bool) -> dict:
    out = {"success": False, "check_only": check, "error": e.message}
    if e.code:
        out["code"] = e.code
    return out


def op_mr_replies_send(ctx: Ctx, args: dict) -> dict:
    """Upload the agent's MR review replies to the postbox.

    Legacy Markdown (file/text) lands at mr-replies/mr-!N.md; a v2 review
    batch (review_file — the JSON document mr_review_findings create_batch
    produces) lands at mr-replies/mr-!<iid>-<batch_id>.review.json and never
    supersedes siblings per IID. Both formats run the same parse -> normalize
    -> validate pipeline (mr_review_handoff), so --check, the upload and the
    work-side push reject identical malformed documents. --check runs the full
    parser + validator and writes nothing (the result carries
    check_only: true); force only skips the legacy anchor-collision precheck,
    never validation."""
    c = _client(ctx)
    repo = _repo_arg(args)
    iid = int(args.get("iid") or 0)
    file_path = (args.get("file") or "").strip()
    text = args.get("text") or ""
    review_file = (args.get("review_file") or "").strip()
    check = bool(args.get("check"))
    if not iid:
        return {"success": False, "error": "iid is required"}
    if review_file:
        if file_path or text.strip():
            return {"success": False, "check_only": check,
                    "error": "review_file is exclusive with file/text: a v2 batch "
                             "is never sent together with legacy Markdown"}
        p = Path(review_file)
        if not p.is_file():
            return {"success": False, "error": f"file not found: {review_file}"}
        try:
            doc = parse_and_validate(p.read_text(encoding="utf-8"),
                                     expected_iid=iid, want="v2")
        except HandoffRejection as e:
            return _handoff_refusal(e, check)
        if check:
            return {"success": True, "check_only": True, "repo": repo, "iid": iid,
                    "format": "v2", "batch_id": doc["batch_id"],
                    "sections": len(doc["entries"]), "identity": doc["identity"],
                    "message": "check-only: v2 batch validated, nothing uploaded"}
        payload = canonical_json(doc["batch"]).encode("utf-8")
        display = batch_artifact_path(iid, doc["batch_id"])
        res = c.file_sync_send(repo, display, len(payload), payload)
        return {"success": True, "repo": repo, "path": display,
                "size": len(payload), "format": "v2",
                "batch_id": doc["batch_id"], "identity": doc["identity"],
                "response": res}
    if file_path:
        p = Path(file_path)
        if not p.is_file():
            return {"success": False, "error": f"file not found: {file_path}"}
        content = p.read_text(encoding="utf-8")
    elif text.strip():
        content = text
    else:
        return {"success": False, "error": "provide file (path) or text (markdown body)"}
    if looks_like_json(content):
        return {"success": False, "check_only": check,
                "error": "content is JSON: a v2 review batch must be passed via "
                         "review_file; legacy file/text accepts Markdown replies "
                         "only (unknown JSON is never parsed as legacy Markdown)"}
    if "## thread" not in content and "## new" not in content:
        return {"success": False, "error": "no reply sections ('## thread <id>' / '## new') found in content"}
    project = (args.get("project") or "").strip()
    if project:
        proj: Path | None = Path(project).resolve()
    else:
        storage = getattr(ctx.config, "storage_path", "") or ""
        proj = Path(storage).resolve() / repo if storage else None
    if proj is not None and not proj.is_dir():
        proj = None
    precheck = _notes_precheck(c, repo, iid, content, force=bool(args.get("force")),
                               project=proj)
    if precheck.get("error"):
        return {"success": False, "error": precheck["error"],
                "code": precheck.get("code"), "precheck": precheck,
                "check_only": check}
    # The work-side parser keys off the "# MR !N" header; inject it so the file is never skipped.
    if not re.search(r"^#\s+MR\s+!(\d+)", content, re.MULTILINE):
        content = f"# MR !{iid}\n\n{content}"
    try:
        doc = parse_and_validate(content, expected_iid=iid)
    except HandoffRejection as e:
        return _handoff_refusal(e, check)

    if check:
        return {"success": True, "check_only": True, "repo": repo, "iid": iid,
                "format": doc["format"], "sections": len(doc["entries"]),
                "precheck": precheck,
                "anchor_warnings": precheck.get("anchor_warnings", []),
                "message": "check-only: grammar and precheck passed, nothing uploaded"}

    data = content.encode("utf-8")
    display = f"mr-replies/mr-!{iid}.md"
    res = c.file_sync_send(repo, display, len(data), data)
    return {"success": True, "repo": repo, "path": display, "size": len(data),
            "format": doc["format"],
            "precheck": precheck,
            "anchor_warnings": precheck.get("anchor_warnings", []),
            "response": res}


def op_mr_replies_status(ctx: Ctx, args: dict) -> dict:
    """Read the work PC's publish reports (mr-replies-status/mr-!N.md) from
    the postbox; each entry carries the parsed v1 numeric fields plus the
    raw ``markdown`` for humans."""
    c = _client(ctx)
    repo = _repo_arg(args)
    lst = c.file_sync_list(repo)
    iid = int(args.get("iid") or 0)
    newest: dict[int, dict] = {}
    for i in (lst.get("items") or []):
        eff = i.get("path") or ""
        if not eff.startswith("mr-replies-status/"):
            continue
        m = re.search(r"mr-!(\d+)\.md$", eff)
        if not m:
            continue
        n = int(m.group(1))
        if iid and n != iid:
            continue
        if n not in newest or (i.get("mtime") or 0) > (newest[n].get("mtime") or 0):
            newest[n] = i
    statuses = []
    for n, i in sorted(newest.items()):
        try:
            plain = c.file_sync_fetch(repo, i["id"])
            entry = {"iid": n, "path": i["path"],
                     "markdown": plain.decode("utf-8")}
            entry.update(_parse_replies_status(entry["markdown"]))
            statuses.append(entry)
        except Exception:
            statuses.append({"iid": n, "path": i["path"], "error": "fetch/decrypt failed (v3 relay)"})
    return {"success": True, "repo": repo, "count": len(statuses), "statuses": statuses}


_HUNK_HEADER = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
_MARKER_RE = re.compile(r"<!-- lgm:[0-9a-f]{8} -->")
_MR_REVIEW_BATCH_FILE = re.compile(r"mr-!(\d+)-([^/]+)\.review\.json$")


def _reply_marker(iid: int, reply: dict) -> str:
    key = ("t:" + reply["thread_id"] if reply["kind"] == "thread"
           else f"a:{reply['file']}:{reply['line']}" if reply["kind"] == "new_anchored"
           else "g")
    digest = hashlib.sha1(f"{iid}|{key}|{reply['body']}".encode("utf-8")).digest()
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


def _repo_gitlab_project(repo: str) -> str:
    """Registry mapping repo -> GitLab project path; empty means the global
    GITLAB_PROJECT stays in charge (single-project setups)."""
    try:
        # lazy import: ops_projects imports this module at top level
        from .ops_projects import _load_registry
        return next((str(e.get("gitlab", "") or "")
                     for e in _load_registry().get("projects", [])
                     if e.get("repo") == repo), "")
    except Exception:
        return ""


def _push_replies_file(c: MirrorClient, repo: str, group: list, parsed: dict,
                       dry_run: bool, project: str = "") -> dict:
    newest = max(group, key=lambda i: (i.get("mtime") or 0))
    path = newest.get("path") or ""
    pm = _MR_NOTES_FILE.search(path)
    iid = parsed["iid"] or (int(pm.group(1)) if pm else 0)
    try:
        raw_discussions = c.gitlab_list_discussions(iid, project=project)
    except LgmError as e:
        return {"iid": iid, "path": path, "posted": 0, "duplicates": 0, "skipped": 0,
                "failed": 1, "parse_errors": len(parsed["errors"]), "not_anchored": [],
                "details": [f"list discussions: {e.message}"], "replies": [],
                "acked": False, "ack_superseded": []}
    discussions = [_discussion_view(d) for d in raw_discussions]
    diff_refs: dict | None = None
    changed_ranges: dict | None = None
    try:
        detail = c.gitlab_get_mr(iid, project=project)
        diff_refs = detail.get("diff_refs") or None
        target = (detail.get("target_branch") or "").strip()
        source = (detail.get("source_branch") or "").strip()
        if target and source:
            changed_ranges = _parse_compare_ranges(
                c.gitlab_compare(target, source, project=project))
    except LgmError:
        pass  # anchoring data is optional; unanchorable remarks downgrade to notes

    seen_markers: set[str] = set()
    for d in discussions:
        for n in d["notes"]:
            m = _MARKER_RE.search(n["body"] or "")
            if m:
                seen_markers.add(m.group(0))

    posted = duplicates = skipped = failed = 0
    outcomes: dict[int, str] = {}
    details = [f"parse: {e}" for e in parsed["errors"]]
    not_anchored: list[str] = []
    per_reply: list[dict] = []

    for idx, reply in enumerate(parsed["replies"]):
        label = f"reply {idx + 1}"
        marker = _reply_marker(iid, reply)
        thread = next((d for d in discussions
                       if reply["kind"] == "thread" and d["id"] == reply["thread_id"]), None)
        target = (reply["thread_id"] if reply["kind"] == "thread"
                  else f"{reply['file']}:{reply['line']}" if reply["kind"] == "new_anchored"
                  else "")
        entry = {"label": label, "kind": reply["kind"], "target": target}
        wants_resolve = reply["resolve"] and thread is not None
        thread_resolved = thread["resolved"] if thread else True
        action = _resolve_decision(marker in seen_markers, wants_resolve, thread_resolved)

        def record(outcome: str, note: str, in_status: bool = True) -> None:
            nonlocal posted, duplicates, skipped, failed
            if outcome == "posted":
                posted += 1
            elif outcome == "dup":
                duplicates += 1
            elif outcome == "dead":
                skipped += 1
            else:
                failed += 1
            outcomes[idx] = outcome
            if in_status:
                details.append(f"{label}: {note}")
            per_reply.append({**entry, "outcome": outcome, "note": note})

        if action == "skip":
            record("dup", "duplicate, already in GitLab")
            continue
        if action == "resolve_only":
            if dry_run:
                record("dup", f"would resolve duplicate thread {reply['thread_id']}")
                continue
            try:
                c.gitlab_resolve_discussion(iid, reply["thread_id"], project=project)
                record("dup", f"duplicate, thread {reply['thread_id']} resolved")
            except LgmError as e:
                record("failed",
                       f"duplicate, resolve {reply['thread_id']} failed HTTP {e.code}")
            continue

        body = f"{marker}\n\n{reply['body']}"
        if reply["kind"] == "thread":
            if thread is None:
                record("dead", f"thread {reply['thread_id']} no longer exists")
                continue
            if dry_run:
                record("posted", f"would post into thread {reply['thread_id']}")
                if reply["resolve"]:
                    details.append(f"{label}: would resolve thread {reply['thread_id']} after posting")
                continue
            try:
                c.gitlab_post_thread_note(iid, reply["thread_id"], body, project=project)
                seen_markers.add(marker)
                note = f"posted into thread {reply['thread_id']}"
                if reply["resolve"]:
                    try:
                        c.gitlab_resolve_discussion(iid, reply["thread_id"], project=project)
                        note += " (resolved)"
                    except LgmError as e:
                        failed += 1
                        details.append(f"{label}: posted, resolve failed HTTP {e.code}")
                        note += f" (resolve failed HTTP {e.code})"
                record("posted", note, in_status=False)
            except LgmError as e:
                if e.code == 404:
                    record("dead", f"thread {reply['thread_id']} no longer exists")
                else:
                    record("failed", f"HTTP {e.code} {e.message}")
            continue

        if reply["kind"] == "new_anchored":
            clash = _anchor_clash(reply, discussions)
            if clash is not None:
                record("dead", f"unresolved thread {clash['id']} already at "
                               f"{reply['file']}:{clash['anchor_line']} — "
                               f"reply into it, don't duplicate")
                continue
            pre_reason = _anchorability_reason(reply, changed_ranges)
            if dry_run:
                if pre_reason:
                    not_anchored.append(f"{label}: {reply['file']}:{reply['line']} — {pre_reason}")
                    record("posted", f"would post as general note ({pre_reason})")
                else:
                    record("posted",
                           f"would post anchored discussion at {reply['file']}:{reply['line']}")
                continue
            fallback = False
            try:
                c.gitlab_post_new_discussion(iid, body, diff_refs=diff_refs,
                                             position=(reply["file"], reply["line"]),
                                             project=project)
            except LgmError as e:
                if e.code not in (400, 422):
                    record("failed", f"HTTP {e.code} {e.message}")
                    continue
                fallback = True
                not_anchored.append(f"{label}: {reply['file']}:{reply['line']} — "
                                    f"{pre_reason or 'line not in MR diff'}")
                try:
                    c.gitlab_post_mr_note(iid, f"`{reply['file']}:{reply['line']}`:\n\n{body}",
                                          project=project)
                except LgmError as e2:
                    record("failed", f"HTTP {e2.code} {e2.message}")
                    continue
            seen_markers.add(marker)
            if fallback:
                details.append(f"{label}: line not in MR diff, posted as general note")
                record("posted", "posted as general note (line not in MR diff)",
                       in_status=False)
            else:
                record("posted", f"posted anchored discussion at {reply['file']}:{reply['line']}",
                       in_status=False)
            continue

        if dry_run:
            record("posted", "would post general note")
            continue
        try:
            c.gitlab_post_mr_note(iid, body, project=project)
        except LgmError as e:
            record("failed", f"HTTP {e.code} {e.message}")
            continue
        seen_markers.add(marker)
        record("posted", "posted general note", in_status=False)

    rep = {"iid": iid, "path": path, "posted": posted, "duplicates": duplicates,
           "skipped": skipped, "failed": failed, "parse_errors": len(parsed["errors"]),
           "not_anchored": not_anchored, "details": details, "replies": per_reply,
           "acked": False, "ack_superseded": []}
    if not dry_run and _should_ack(parsed["replies"], outcomes, seen_markers, iid,
                                    rep["parse_errors"], failed):
        rep["acked"] = True
        for s in group:
            try:
                c.file_sync_ack(repo, s["id"])
            except LgmError as e:
                rep["acked"] = False
                rep["details"].append(f"ack {s.get('path')}: {e.message}")
                continue
            if s["id"] != newest["id"]:
                rep["ack_superseded"].append(s["id"])
    if not dry_run and (posted > 0 or failed > 0):
        data = _render_replies_status(iid, rep).encode("utf-8")
        status_path = f"mr-replies-status/mr-!{iid}.md"
        try:
            c.file_sync_send(repo, status_path, len(data), data)
            rep["status_path"] = status_path
        except LgmError as e:
            rep["details"].append(f"status report upload failed: {e.message}")
    return rep


def _push_review_batch(c: MirrorClient, repo: str, item: dict) -> dict:
    """Document-level handling of one v2 batch artifact (mr-!<iid>-<batch_id>
    .review.json) in the postbox: full pipeline validation, per-batch report,
    no sibling supersede and no ack. Batches are immutable and coexist per
    IID; the snapshot-bound GitLab publication gate validates and posts them
    separately, so a malformed batch here is refused with zero side effects."""
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
    report["details"] = [(f"v2 batch {doc['batch_id']} validated; "
                          f"snapshot-bound publication not wired yet")]
    return report


def op_mr_replies_push(ctx: Ctx, args: dict) -> dict:
    """Publish pending mr-replies files from the postbox to GitLab (work side).

    Mirrors the plugin's MrReplyPushService: every posted note carries an
    invisible ``<!-- lgm:<hash> -->`` marker so retries never duplicate; a
    '## new' remark whose line is outside the MR diff (compare ranges) posts
    as a general note with the reason recorded; the postbox item is acked
    only when every parsed reply reached a terminal outcome — superseded
    sibling files for the same MR are acked with it and listed in
    ``ack_superseded``; a status report lands in mr-replies-status/mr-!N.md.
    Legacy files run the shared handoff pipeline before anything is posted:
    a document with malformed sections is rejected whole (zero GitLab calls,
    zero acks). V2 batch artifacts (mr-!<iid>-<batch_id>.review.json) are
    validated per batch, never superseded and never acked here.
    --dry-run plans without touching GitLab, the postbox or the status files.

    The GitLab project comes from the registry entry for ``repo`` so one work
    machine can publish to several projects; ``GITLAB_PROJECT`` is the
    fallback when the repo is not registered.
    """
    c = _client(ctx)
    repo = _repo_arg(args)
    project = _repo_gitlab_project(repo)
    missing = [k for k in ("GITLAB_URL", "GITLAB_TOKEN") if not cfg(k)]
    if not project and not cfg("GITLAB_PROJECT"):
        missing.append("GITLAB_PROJECT")
    if missing:
        raise LgmError("config",
                       "GitLab URL/project/token not configured — set " + ", ".join(missing))
    role = cfg("LGM_MACHINE_ROLE").strip().lower()
    if role == "home":
        raise LgmError("config",
                       "mr_replies_push publishes to GitLab from the work machine; "
                       "this machine is home (LGM_MACHINE_ROLE=home)",
                       machine_code="wrong-machine")
    if role != "work":
        raise LgmError("config",
                       "LGM_MACHINE_ROLE is neither home nor work — publishing "
                       "needs to know this is the work machine; set "
                       "LGM_MACHINE_ROLE=work",
                       machine_code="role-unset")
    dry_run = bool(args.get("dry_run"))
    iid_filter = int(args.get("iid") or 0)
    items = (c.file_sync_list(repo).get("items") or [])
    groups: dict[int, list] = {}
    v2_items: list[dict] = []
    stray: list[dict] = []
    for i in items:
        eff = i.get("path") or ""
        if not eff.startswith("mr-replies/"):
            continue
        m = _MR_NOTES_FILE.search(eff)
        if m:
            n = int(m.group(1))
            if not iid_filter or n == iid_filter:
                groups.setdefault(n, []).append(i)
            continue
        v2 = _MR_REVIEW_BATCH_FILE.search(eff)
        if v2:
            n = int(v2.group(1))
            if not iid_filter or n == iid_filter:
                v2_items.append(i)
            continue
        if not iid_filter:
            stray.append(i)
    reports = [{"iid": 0, "path": i.get("path"), "posted": 0, "duplicates": 0,
                "skipped": 1, "failed": 0, "parse_errors": 0, "not_anchored": [],
                "details": ["not an mr-!N.md reply file"], "replies": [],
                "acked": False, "ack_superseded": []} for i in stray]
    for path_iid in sorted(groups):
        group = groups[path_iid]
        newest = max(group, key=lambda i: (i.get("mtime") or 0))
        try:
            content = c.file_sync_fetch(repo, newest["id"]).decode("utf-8")
        except Exception as e:
            reports.append({"iid": path_iid, "path": newest.get("path"), "posted": 0,
                            "duplicates": 0, "skipped": 1, "failed": 0, "parse_errors": 0,
                            "not_anchored": [], "details": [f"download/decrypt: {e}"],
                            "replies": [], "acked": False, "ack_superseded": []})
            continue
        try:
            doc = parse_and_validate(content, want="legacy")
        except HandoffRejection as e:
            reports.append({"iid": path_iid, "path": newest.get("path"), "posted": 0,
                            "duplicates": 0, "skipped": 1, "failed": 0,
                            "parse_errors": len(e.errors) or 1, "not_anchored": [],
                            "details": [f"rejected: {msg}"
                                        for msg in (e.errors or [e.message])],
                            "replies": [], "acked": False, "ack_superseded": []})
            continue
        parsed = {"iid": doc["iid"], "branch": doc["branch"],
                  "replies": doc["entries"], "errors": []}
        reports.append(_push_replies_file(c, repo, group, parsed, dry_run, project))
    for i in sorted(v2_items, key=lambda x: x.get("path") or ""):
        reports.append(_push_review_batch(c, repo, i))
    totals = {k: sum(r.get(k, 0) for r in reports)
              for k in ("posted", "duplicates", "skipped", "failed")}
    totals["not_anchored"] = sum(len(r.get("not_anchored") or []) for r in reports)
    result = {"success": True, "repo": repo, "dry_run": dry_run,
              "count": len(reports), "reports": reports, "totals": totals}
    if dry_run:
        result["message"] = "dry-run: nothing posted, no acks, no status upload"
    elif not reports:
        result["message"] = "no pending mr-replies files in the postbox"
    return result


def op_mr_pending(ctx: Ctx, args: dict) -> dict:
    """One pipeline view per MR iid: notes freshness, replies pending in the
    postbox (bool + ids), the last publish outcome (parsed from the newest
    mr-replies-status entry) and the closed state with evidence.

    Freshness carries two independent signals: ``updated_at`` is GitLab's MR
    ``updated_at`` as stamped into the note header (it moves on MR-level events
    and can postdate the newest remark), while ``mtime``/``age_seconds`` say
    when the postbox blob was last written. ``lgm_markers`` counts the
    exactly-once markers of published replies inside that blob; compared with
    ``last_publish.posted`` it shows whether the notes already reflect the push
    or still predate it.

    ``state_evidence``: ``gitlab`` (GitLab answered, authoritative),
    ``marker`` (closed marker at least as new as the note), ``note-mtime``
    (note newer than the marker, or no marker at all), ``expired``
    (neither note nor marker — the postbox TTL ate them).
    """
    c = _client(ctx)
    repo = _repo_arg(args)
    items = (c.file_sync_list(repo).get("items") or [])
    now = time.time()

    notes: dict[int, dict] = {}
    markers: dict[int, dict] = {}
    replies: dict[int, list] = {}
    statuses: dict[int, dict] = {}
    for i in items:
        eff = i.get("path") or ""
        if eff.startswith("mr-notes/"):
            cm = _MR_CLOSED_FILE.search(eff)
            nm = _MR_NOTES_FILE.search(eff)
            if cm:
                iid = int(cm.group(1))
                if iid not in markers or (i.get("mtime") or 0) > (markers[iid].get("mtime") or 0):
                    markers[iid] = i
            elif nm:
                iid = int(nm.group(1))
                if iid not in notes or (i.get("mtime") or 0) > (notes[iid].get("mtime") or 0):
                    notes[iid] = i
        elif eff.startswith("mr-replies/"):
            rm = _MR_NOTES_FILE.search(eff)
            if rm:
                replies.setdefault(int(rm.group(1)), []).append(i)
        elif eff.startswith("mr-replies-status/"):
            sm = _MR_NOTES_FILE.search(eff)
            if sm:
                iid = int(sm.group(1))
                if iid not in statuses or (i.get("mtime") or 0) > (statuses[iid].get("mtime") or 0):
                    statuses[iid] = i

    gitlab_usable = True
    gitlab_project = _repo_gitlab_project(repo)
    out: list[dict] = []
    for iid in sorted(set(notes) | set(markers) | set(replies) | set(statuses)):
        entry: dict = {"iid": iid, "notes": None, "replies_pending": False,
                       "reply_ids": [], "last_publish": None,
                       "state": "unknown", "state_evidence": "expired"}
        note = notes.get(iid)
        if note is not None:
            mtime = int(note.get("mtime") or 0)
            entry["notes"] = {"mtime": mtime,
                              "age_seconds": max(0, int(now - mtime)),
                              "updated_at": None, "updated_age_seconds": None,
                              "lgm_markers": 0}
            try:
                plain = c.file_sync_fetch(repo, note["id"]).decode("utf-8")
                stamp = _parse_mr_notes_head(plain).get("updated") or ""
                entry["notes"]["updated_at"] = stamp or None
                entry["notes"]["updated_age_seconds"] = _iso_age_seconds(stamp, now)
                entry["notes"]["lgm_markers"] = len(
                    set(_NOTE_LGM_MARKER.findall(plain)))
            except Exception:
                entry["notes"]["updated_error"] = "fetch/decrypt failed (v3 relay)"
        rep = replies.get(iid)
        if rep:
            entry["replies_pending"] = True
            entry["reply_ids"] = [r.get("id") for r in rep]
        st = statuses.get(iid)
        if st is not None:
            try:
                plain = c.file_sync_fetch(repo, st["id"]).decode("utf-8")
                entry["last_publish"] = _parse_replies_status(plain)
            except Exception:
                entry["last_publish"] = {"parsed": False,
                                         "error": "fetch/decrypt failed (v3 relay)"}
        if gitlab_usable:
            try:
                state = (c.gitlab_get_mr(iid, project=gitlab_project).get("state") or "").lower()
                if state in ("opened", "closed", "merged", "locked"):
                    entry["state"] = "closed" if state in ("closed", "merged") else "open"
                    entry["state_evidence"] = "gitlab"
            except LgmError as e:
                if e.code == "config":
                    gitlab_usable = False
        if entry["state_evidence"] != "gitlab":
            entry["state"], entry["state_evidence"] = _state_with_evidence(
                note, markers.get(iid))
        out.append(entry)
    return {"success": True, "repo": repo, "count": len(out), "items": out}
