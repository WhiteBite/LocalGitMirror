from __future__ import annotations

import re
import subprocess
from pathlib import Path

from .client import MirrorClient, LgmError
from .op_models import Ctx, _client, _repo_arg
from .ops_git import send_branches


_MRN_TITLE = re.compile(r"^# MR !(\d+) — (.*)$")
_MRN_BRANCH = re.compile(r"^- \*\*Ветка:\*\* `([^`]+)`")
_NOTES_THREAD_ID = re.compile(r"^\*\*ID треда:\*\* `([^`]+)`")
_NOTES_ANCHOR = re.compile(r"^\*\*Место:\*\* `([^`]+):(\d+)`")
_REPLY_THREAD = re.compile(r"^##\s+thread\s+(\S+)\s*$", re.MULTILINE)
_REPLY_ANCHORED = re.compile(r"^##\s+new\s+(\S+?):(\d+)\s*$", re.MULTILINE)
ANCHOR_WINDOW = 3


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


def _fetch_newest_notes(c: MirrorClient, repo: str, iid: int) -> str | None:
    """Newest mr-notes/mr-!iid.md for the repo, or None (missing/unreachable)."""
    try:
        lst = c.file_sync_list(repo)
    except LgmError:
        return None
    best = None
    for i in (lst.get("items") or []):
        eff = i.get("path") or ""
        if not eff.startswith("mr-notes/"):
            continue
        m = re.search(r"mr-!(\d+)\.md$", eff)
        if not m or int(m.group(1)) != iid:
            continue
        if best is None or (i.get("mtime") or 0) > (best.get("mtime") or 0):
            best = i
    if best is None:
        return None
    try:
        return c.file_sync_fetch(repo, best["id"]).decode("utf-8")
    except Exception:
        return None


def _notes_precheck(c: MirrorClient, repo: str, iid: int, content: str, force: bool) -> dict:
    """Reject replies that duplicate the live review: a '## new' anchor colliding
    with an unresolved thread (±ANCHOR_WINDOW lines, same file) or a '## thread'
    id absent from the notes. Fail-open when the notes are unavailable."""
    thread_refs = _REPLY_THREAD.findall(content)
    anchors = [(m.group(1), int(m.group(2))) for m in _REPLY_ANCHORED.finditer(content)]
    if not thread_refs and not anchors:
        return {"status": "not-needed"}
    notes = _fetch_newest_notes(c, repo, iid)
    if notes is None:
        return {"status": "notes-unavailable"}
    threads = _parse_notes_threads(notes)
    collisions = _anchor_collisions(anchors, threads["unresolved"])
    unknown = [t for t in thread_refs if t not in threads["ids"]]
    result = {"status": "ok", "collisions": collisions, "unknown_threads": unknown}
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
    unresolved = 0
    for line in markdown.splitlines():
        m = _MRN_TITLE.match(line)
        if m:
            iid, title = int(m.group(1)), m.group(2).strip()
            continue
        b = _MRN_BRANCH.match(line)
        if b:
            branch = b.group(1)
        if line.startswith("## ⚠"):
            unresolved += 1
    return {"iid": iid, "title": title, "source_branch": branch, "unresolved": unresolved}


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
        newest: dict[int, dict] = {}
        for i in (lst.get("items") or []):
            eff = i.get("path") or ""
            if not eff.startswith("mr-notes/"):
                continue
            m = re.search(r"mr-!(\d+)\.md$", eff)
            if not m:
                continue
            iid = int(m.group(1))
            if iid not in newest or (i.get("mtime") or 0) > (newest[iid].get("mtime") or 0):
                newest[iid] = i
        for iid, item in sorted(newest.items()):
            try:
                plain = c.file_sync_fetch(name, item["id"]).decode("utf-8")
            except Exception:
                continue
            head = _parse_mr_notes_head(plain)
            if not head["iid"]:
                continue
            out.append({
                "iid": head["iid"],
                "title": head["title"],
                "source_branch": head["source_branch"],
                "updated_at": "",
                "unresolved": head["unresolved"],
                "source": "cache",
                "repo": name,
            })
    return {"items": out, "errors": errors}


def op_mr_list(ctx: Ctx, args: dict) -> dict:
    """List open GitLab merge requests; without GitLab config fall back to transferred notes."""
    c = _client(ctx)
    items: list[dict] = []
    errors: list[dict] = []
    try:
        items = [
            {
                "iid": m.get("iid"),
                "title": m.get("title", ""),
                "source_branch": m.get("source_branch", ""),
                "updated_at": m.get("updated_at", ""),
                "source": "gitlab",
            }
            for m in c.gitlab_list_mrs()
        ]
    except LgmError as e:
        if e.code != "config":
            raise
    if not items:
        fallback = _mr_list_from_postbox(c, ctx, args)
        items = fallback["items"]
        errors = fallback["errors"]
    return {"count": len(items), "items": items, "errors": errors}


def _resolve_mr_targets(c: MirrorClient, args: dict) -> list[dict]:
    """Resolve requested MRs/branches to [{iid, branch}], deduped by branch."""
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
        for m in c.gitlab_list_mrs():
            _add(m.get("iid"), m.get("source_branch") or "")
    for i in iids:
        mr = c.gitlab_get_mr(i)
        branch = (mr.get("source_branch") or "").strip()
        if not branch:
            raise LgmError("gitlab", f"MR !{i}: empty source branch")
        _add(i, branch)
    for tok in (args.get("branch") or "").split(","):
        _add(None, tok)
    return targets


def _fetch_mr_branches(proj: Path, branches: list[str]) -> None:
    """Fetch all branches from origin in one call; ensure local refs exist."""
    refspecs = [f"+refs/heads/{b}:refs/remotes/origin/{b}" for b in branches]
    fetch = subprocess.run(
        ["git", "-C", str(proj), "fetch", "origin", *refspecs],
        capture_output=True, text=True, timeout=300,
    )
    if fetch.returncode != 0:
        raise LgmError("git", fetch.stderr.strip() or "git fetch origin failed")
    for b in branches:
        # git fetch writes only remote-tracking refs; create the local ref for the bundle.
        chk = subprocess.run(
            ["git", "-C", str(proj), "rev-parse", "--verify", f"refs/heads/{b}"],
            capture_output=True, text=True, timeout=30,
        )
        if chk.returncode != 0:
            mk = subprocess.run(
                ["git", "-C", str(proj), "update-ref",
                 f"refs/heads/{b}", f"refs/remotes/origin/{b}"],
                capture_output=True, text=True, timeout=30,
            )
            if mk.returncode != 0:
                raise LgmError(
                    "git",
                    mk.stderr.strip() or f"branch '{b}' not found after fetch",
                )


def _local_tip(proj: Path, branch: str) -> str:
    proc = subprocess.run(
        ["git", "-C", str(proj), "rev-parse", f"refs/heads/{branch}"],
        capture_output=True, text=True, timeout=30,
    )
    if proc.returncode != 0:
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
    out = []
    for sha in dict.fromkeys(shas):
        if not sha:
            continue
        chk = subprocess.run(
            ["git", "-C", str(proj), "cat-file", "-e", f"{sha}^{{commit}}"],
            capture_output=True, text=True, timeout=30,
        )
        if chk.returncode == 0:
            out.append(sha)
    return out


def _new_commit_count(proj: Path, branches: list[str], exclude_shas: list[str]) -> int:
    """Commits in the sent branches that the exclusions do not cover."""
    cmd = ["git", "-C", str(proj), "rev-list", "--count",
           *(f"refs/heads/{b}" for b in branches)]
    cmd += [f"^{s}" for s in exclude_shas]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    if proc.returncode != 0:
        raise LgmError("git", proc.stderr.strip() or "git rev-list failed")
    return int(proc.stdout.strip() or 0)


def op_mr_send(ctx: Ctx, args: dict) -> dict:
    """Fetch GitLab MR branches into a local project and send them to the mirror.

    Multi-MR: --iid 41 / --iids 41,42,43 / --all-open / --branch a,b combine
    into one deduplicated transfer:
      - one `git fetch` for all branches;
      - branches whose tip the mirror already has are skipped entirely;
      - all remaining branches go into ONE bundle, so their shared history is
        packed once, with mirror tips as ^exclusions (only new commits travel).
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

    targets = _resolve_mr_targets(c, args)
    if not targets:
        raise LgmError("config", "--iid, --iids, --all-open or --branch is required")

    _fetch_mr_branches(proj, [t["branch"] for t in targets])

    mirror_refs = _mirror_refs_safe(c, repo)
    sent: list[dict] = []
    skipped: list[dict] = []
    for t in targets:
        tip = _local_tip(proj, t["branch"])
        mirror_sha = (mirror_refs.get(t["branch"]) or {}).get("sha", "")
        t["tip"] = tip
        if mirror_sha and mirror_sha == tip:
            skipped.append(t)
        else:
            sent.append(t)

    exclude_shas = _existing_shas(
        proj, [info.get("sha", "") for info in mirror_refs.values()]) if sent else []
    if sent and exclude_shas and \
            _new_commit_count(proj, [t["branch"] for t in sent], exclude_shas) == 0:
        skipped.extend(sent)
        sent = []
    if not sent:
        return {"repo": repo, "project": str(proj), "sent": [], "skipped": skipped,
                "message": "nothing new: mirror already has all requested commits"}

    send_result = send_branches(ctx, repo, str(proj), [t["branch"] for t in sent],
                                exclude_shas)
    return {"repo": repo, "project": str(proj), "sent": sent, "skipped": skipped,
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
        m = re.search(r"mr-!(\d+)\.md$", eff)
        if not m:
            continue
        n = int(m.group(1))
        if iid and n != iid:
            continue
        if n not in newest or (i.get("mtime") or 0) > (newest[n].get("mtime") or 0):
            newest[n] = i
    notes = []
    for n, i in sorted(newest.items()):
        try:
            plain = c.file_sync_fetch(repo, i["id"])
            notes.append({"iid": n, "path": i["path"], "markdown": plain.decode("utf-8")})
        except Exception:
            notes.append({"iid": n, "path": i["path"], "error": "fetch/decrypt failed (v3 relay)"})
    return {"success": True, "repo": repo, "count": len(notes), "notes": notes}


def op_mr_replies_send(ctx: Ctx, args: dict) -> dict:
    """Upload the agent's MR review replies (mr-replies/mr-!N.md) to the postbox."""
    c = _client(ctx)
    repo = _repo_arg(args)
    iid = int(args.get("iid") or 0)
    file_path = (args.get("file") or "").strip()
    text = args.get("text") or ""
    if not iid:
        return {"success": False, "error": "iid is required"}
    if file_path:
        p = Path(file_path)
        if not p.is_file():
            return {"success": False, "error": f"file not found: {file_path}"}
        content = p.read_text(encoding="utf-8")
    elif text.strip():
        content = text
    else:
        return {"success": False, "error": "provide file (path) or text (markdown body)"}
    if "## thread" not in content and "## new" not in content:
        return {"success": False, "error": "no reply sections ('## thread <id>' / '## new') found in content"}
    precheck = _notes_precheck(c, repo, iid, content, force=bool(args.get("force")))
    if precheck.get("error"):
        return {"success": False, "error": precheck["error"], "precheck": precheck}
    # The work-side parser keys off the "# MR !N" header; inject it so the file is never skipped.
    m = re.search(r"^#\s+MR\s+!(\d+)", content, re.MULTILINE)
    if m:
        if int(m.group(1)) != iid:
            return {"success": False,
                    "error": f"content header says MR !{m.group(1)} but iid={iid}"}
    else:
        content = f"# MR !{iid}\n\n{content}"

    data = content.encode("utf-8")
    display = f"mr-replies/mr-!{iid}.md"
    res = c.file_sync_send(repo, display, len(data), data)
    return {"success": True, "repo": repo, "path": display, "size": len(data),
            "precheck": precheck, "response": res}


def op_mr_replies_status(ctx: Ctx, args: dict) -> dict:
    """Read the work PC's publish reports (mr-replies-status/mr-!N.md) from the postbox."""
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
            statuses.append({"iid": n, "path": i["path"], "markdown": plain.decode("utf-8")})
        except Exception:
            statuses.append({"iid": n, "path": i["path"], "error": "fetch/decrypt failed (v3 relay)"})
    return {"success": True, "repo": repo, "count": len(statuses), "statuses": statuses}
