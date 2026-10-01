from __future__ import annotations

import base64
import os
import re
import subprocess
from pathlib import Path

from .client import MirrorClient, LgmError
from .crypto import encrypt_bundle, decrypt_bundle
from .op_models import Ctx, _client, _repo_arg
from .ops_git import send_branches


_MRN_TITLE = re.compile(r"^# MR !(\d+) — (.*)$")
_MRN_BRANCH = re.compile(r"^- \*\*Ветка:\*\* `([^`]+)`")


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
    pwd = ctx.config.sync_password
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
            eff = _postbox_display_path(i, pwd)
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
                plain = decrypt_bundle(c.file_sync_fetch(name, item["id"]), pwd).decode("utf-8")
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


def _postbox_display_path(item: dict, password: str) -> str:
    """Effective postbox path: decrypt path_enc when present, else plaintext."""
    enc = item.get("path_enc") or ""
    if enc and password:
        try:
            blob = base64.b64decode(enc)
            return decrypt_bundle(blob, password).decode("utf-8")
        except Exception:
            pass
    return item.get("path") or ""


def op_mr_notes(ctx: Ctx, args: dict) -> dict:
    """Decrypt MR discussion notes (mr-notes/mr-!N.md) from the file postbox."""
    c = _client(ctx)
    repo = _repo_arg(args)
    lst = c.file_sync_list(repo)
    pwd = ctx.config.sync_password
    items = []
    for i in (lst.get("items") or []):
        eff = _postbox_display_path(i, pwd)
        if eff.startswith("mr-notes/"):
            items.append({**i, "path": eff})
    iid = int(args.get("iid") or 0)
    if iid:
        items = [i for i in items if str(i.get("path", "")).endswith(f"mr-!{iid}.md")]
    notes = []
    for i in items:
        blob = c.file_sync_fetch(repo, i["id"])
        try:
            plain = decrypt_bundle(blob, ctx.config.sync_password)
            notes.append({"path": i["path"], "markdown": plain.decode("utf-8")})
        except Exception:
            notes.append({"path": i["path"], "error": "decrypt failed: sync password mismatch?"})
    return {"success": True, "repo": repo, "notes": notes}


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

    pwd = ctx.config.sync_password
    encrypted = encrypt_bundle(content.encode("utf-8"), pwd)
    display = f"mr-replies/mr-!{iid}.md"
    path_enc = base64.b64encode(encrypt_bundle(display.encode("utf-8"), pwd)).decode("ascii")
    res = c.file_sync_send(repo, f"x/{os.urandom(4).hex()}", len(content), encrypted, path_enc=path_enc)
    return {"success": True, "repo": repo, "path": display, "size": len(content), "response": res}


def op_mr_replies_status(ctx: Ctx, args: dict) -> dict:
    """Read the work PC's publish reports (mr-replies-status/mr-!N.md) from the postbox."""
    c = _client(ctx)
    repo = _repo_arg(args)
    lst = c.file_sync_list(repo)
    pwd = ctx.config.sync_password
    iid = int(args.get("iid") or 0)
    statuses = []
    for i in (lst.get("items") or []):
        eff = _postbox_display_path(i, pwd)
        if not eff.startswith("mr-replies-status/"):
            continue
        if iid and not eff.endswith(f"mr-!{iid}.md"):
            continue
        blob = c.file_sync_fetch(repo, i["id"])
        try:
            statuses.append({"path": eff, "markdown": decrypt_bundle(blob, pwd).decode("utf-8")})
        except Exception:
            statuses.append({"path": eff, "error": "decrypt failed: sync password mismatch?"})
    return {"success": True, "repo": repo, "statuses": statuses}


def op_mr_notes_request(ctx: Ctx, args: dict) -> dict:
    """Ask the work PC to transfer GitLab threads for an MR into the postbox."""
    c = _client(ctx)
    repo = _repo_arg(args)
    iid = int(args.get("iid") or 0)
    if not iid:
        return {"success": False, "error": "iid is required"}
    pwd = ctx.config.sync_password
    display = f"mr-notes-request/mr-!{iid}.md"
    body = f"request mr-notes for !{iid}\n"
    encrypted = encrypt_bundle(body.encode("utf-8"), pwd)
    path_enc = base64.b64encode(encrypt_bundle(display.encode("utf-8"), pwd)).decode("ascii")
    res = c.file_sync_send(repo, f"x/{os.urandom(4).hex()}", len(body), encrypted, path_enc=path_enc)
    return {"success": True, "repo": repo, "path": display, "response": res}
