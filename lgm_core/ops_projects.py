"""Multi-project MR registry and batch sync (work-side).

mr_projects_scan / mr_projects_list / mr_sync_all: keep a registry of local
GitLab checkouts, then ship every open MR's branch and discussion notes to
the mirror in one run — only what changed. Branches dedupe against mirror
tips; notes dedupe by content hash per (repo, iid) and re-upload once the
last upload is older than six days (the postbox TTL is seven).
"""
from __future__ import annotations

import hashlib
import json
import time
from pathlib import Path

from .client import LgmError
from .config import cfg
from .git_remote import gitlab_project_for
from .mr_notes_render import parse_discussions, render_markdown
from .op_models import Ctx, _client
from .ops_git import send_branches
from .ops_mr import (
    _fetch_mr_branches, _local_tip, _mirror_refs_safe, _existing_shas,
    _new_commit_count,
)

_LGM_DIR = Path.home() / ".lgm"
_REGISTRY_PATH = _LGM_DIR / "mr-projects.json"
_STATE_PATH = _LGM_DIR / "mr-notes-state.json"
_RESYNC_SECONDS = 6 * 86400


def _load_registry() -> dict:
    try:
        data = json.loads(_REGISTRY_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"projects": []}
    if not isinstance(data, dict) or not isinstance(data.get("projects"), list):
        return {"projects": []}
    return data


def _save_registry(reg: dict) -> None:
    _LGM_DIR.mkdir(parents=True, exist_ok=True)
    _REGISTRY_PATH.write_text(
        json.dumps(reg, ensure_ascii=False, indent=2), encoding="utf-8")


def _load_state() -> dict:
    try:
        data = json.loads(_STATE_PATH.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _save_state(state: dict) -> None:
    _LGM_DIR.mkdir(parents=True, exist_ok=True)
    _STATE_PATH.write_text(
        json.dumps(state, ensure_ascii=False), encoding="utf-8")


def _should_skip(stored: dict | None, current_hash: str, now: float) -> bool:
    if not isinstance(stored, dict) or stored.get("hash") != current_hash:
        return False
    sent_at = stored.get("sent_at") or 0
    try:
        return float(sent_at) > now - _RESYNC_SECONDS
    except (TypeError, ValueError):
        return False


def op_mr_projects_scan(ctx: Ctx, args: dict) -> dict:
    """Register local GitLab checkouts found under --root into the sync registry."""
    root = (args.get("root") or "").strip()
    if not root:
        raise LgmError("config", "--root is required (directory with project checkouts)")
    root_dir = Path(root).resolve()
    if not root_dir.is_dir():
        raise LgmError("config", f"root not found: {root_dir}")

    gitlab_url = cfg("GITLAB_URL").rstrip("/")
    if not gitlab_url:
        raise LgmError("config", "GITLAB_URL is not set — cannot match project remotes")

    reg = _load_registry()
    entries = [e for e in reg["projects"] if isinstance(e, dict)]
    by_path = {e.get("path", "").lower(): i for i, e in enumerate(entries)}
    added: list[str] = []
    updated: list[str] = []

    for child in sorted(root_dir.iterdir()):
        if not (child / ".git").exists():
            continue
        gitlab_path = gitlab_project_for(child)
        if not gitlab_path:
            continue
        repo = gitlab_path.rsplit("/", 1)[-1].strip()
        if not repo:
            continue
        entry = {"repo": repo, "path": str(child), "gitlab": gitlab_path}
        key = str(child).lower()
        i = by_path.get(key)
        if i is None:
            entries.append(entry)
            by_path[key] = len(entries) - 1
            added.append(repo)
        elif entries[i] != entry:
            entries[i] = entry
            updated.append(repo)

    reg["projects"] = entries
    _save_registry(reg)
    return {"success": True, "root": str(root_dir),
            "added": added, "updated": updated, "total": len(entries),
            "projects": entries}


def op_mr_projects_list(ctx: Ctx, args: dict) -> dict:
    """Show the registered MR-sync projects (repo, path, GitLab project)."""
    reg = _load_registry()
    projects = [
        {"repo": e.get("repo", ""), "path": e.get("path", ""),
         "gitlab": e.get("gitlab", ""), "exists": Path(e.get("path", "")).is_dir()}
        for e in reg["projects"] if isinstance(e, dict)
    ]
    return {"success": True, "count": len(projects), "projects": projects}


def _sync_project(ctx: Ctx, c, entry: dict, state: dict, now: float,
                  dry_run: bool) -> dict:
    repo = entry.get("repo", "")
    gitlab = entry.get("gitlab", "")
    res: dict = {"repo": repo, "gitlab": gitlab, "mrs": [],
                 "branches_sent": [], "branches_skipped": []}
    proj = Path(entry.get("path", ""))
    if not proj.is_dir():
        res["error"] = f"project dir not found: {proj}"
        return res
    try:
        mrs = c.gitlab_list_mrs(project=gitlab)
    except LgmError as e:
        res["error"] = f"gitlab: {e.message}"
        return res
    res["open_mrs"] = len(mrs)

    if dry_run:
        res["mrs"] = [
            {"iid": m.get("iid"), "title": m.get("title", ""),
             "branch": m.get("source_branch", ""), "notes": "dry-run"}
            for m in mrs
        ]
        return res

    branches = [m.get("source_branch") or "" for m in mrs]
    branches = [b for b in branches if b]
    if branches:
        try:
            _fetch_mr_branches(proj, branches)
            mirror_refs = _mirror_refs_safe(c, repo)
            sent: list[str] = []
            skipped: list[str] = []
            for b in branches:
                tip = _local_tip(proj, b)
                mirror_sha = (mirror_refs.get(b) or {}).get("sha", "")
                if mirror_sha and mirror_sha == tip:
                    skipped.append(b)
                else:
                    sent.append(b)
            exclude_shas = _existing_shas(
                proj, [info.get("sha", "") for info in mirror_refs.values()]
            ) if sent else []
            if sent and exclude_shas and \
                    _new_commit_count(proj, sent, exclude_shas) == 0:
                skipped.extend(sent)
                sent = []
            if sent:
                send_branches(ctx, repo, str(proj), sent, exclude_shas)
            res["branches_sent"] = sent
            res["branches_skipped"] = skipped
        except LgmError as e:
            res["branches_error"] = e.message

    for m in mrs:
        iid = m.get("iid")
        item = {"iid": iid, "title": m.get("title", ""),
                "branch": m.get("source_branch", "")}
        res["mrs"].append(item)
        try:
            raw = c.gitlab_list_discussions(iid, project=gitlab)
        except LgmError as e:
            item["notes"] = f"failed: {e.message}"
            continue
        discussions = parse_discussions(raw)
        if not any(any(not n["system"] for n in d["notes"]) for d in discussions):
            item["notes"] = "skipped (no threads)"
            continue
        unresolved = sum(
            1 for d in discussions
            if not d["resolved"] and any(not n["system"] for n in d["notes"]))
        resolved = sum(
            1 for d in discussions
            if d["resolved"] and any(not n["system"] for n in d["notes"]))
        md = render_markdown(
            iid, m.get("title", ""), m.get("source_branch") or "",
            m.get("updated_at", ""), unresolved, unresolved + resolved,
            discussions, project_dir=proj, branch=m.get("source_branch") or "",
        )
        h = hashlib.sha256(md.encode("utf-8")).hexdigest()
        repo_state = state.setdefault(repo, {})
        if _should_skip(repo_state.get(str(iid)), h, now):
            item["notes"] = "unchanged"
            continue
        try:
            data = md.encode("utf-8")
            c.file_sync_send(repo, f"mr-notes/mr-!{iid}.md", len(data), data)
        except LgmError as e:
            item["notes"] = f"failed: {e.message}"
            continue
        repo_state[str(iid)] = {"hash": h, "sent_at": now}
        item["notes"] = "uploaded"
    return res


def op_mr_sync_all(ctx: Ctx, args: dict) -> dict:
    """Sync every open MR of every registered project to the mirror.

    Branches ship as mirror-delta bundles (only commits the mirror lacks);
    notes ship only when their rendered content hash changed (or the last
    upload is older than six days). --dry-run lists the MRs without touching
    anything.
    """
    c = _client(ctx)
    dry_run = bool(args.get("dry_run", False))
    reg = _load_registry()
    if not reg["projects"]:
        raise LgmError(
            "config", "no projects in the registry — run mr_projects_scan --root <dir> first")

    state = _load_state()
    now = time.time()
    results = [_sync_project(ctx, c, e, state, now, dry_run)
               for e in reg["projects"] if isinstance(e, dict)]
    if not dry_run:
        _save_state(state)

    def _count(field: str) -> int:
        return sum(len(r.get(field) or []) for r in results)

    notes_uploaded = sum(
        1 for r in results for m in r.get("mrs", []) if m.get("notes") == "uploaded")
    notes_unchanged = sum(
        1 for r in results for m in r.get("mrs", []) if m.get("notes") == "unchanged")
    errors = [f"{r['repo']}: {r['error']}" for r in results if r.get("error")]
    return {"success": not errors, "dry_run": dry_run, "repos": results,
            "summary": {
                "repos": len(results),
                "mrs": sum(r.get("open_mrs", 0) for r in results),
                "branches_sent": _count("branches_sent"),
                "branches_skipped": _count("branches_skipped"),
                "notes_uploaded": notes_uploaded,
                "notes_unchanged": notes_unchanged,
                "errors": errors,
            }}
