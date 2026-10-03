from __future__ import annotations

import ssl
import subprocess
import tempfile
import urllib.request
import zipfile
import io
from pathlib import Path

from .client import LgmError
from .crypto import decrypt_bundle
from .op_models import Ctx, _client, _role_guess


# ── New ops ──────────────────────────────────────────────────────────────────

def op_status(ctx: Ctx, args: dict) -> dict:
    """Server capabilities + repos + role guess."""
    c = _client(ctx)
    caps = {}
    repos_r = {}
    try:
        caps = c.capabilities()
    except LgmError as e:
        caps = {"error": e.message, "code": e.code}
    try:
        repos_r = c.repos()
    except LgmError as e:
        repos_r = {"error": e.message, "code": e.code}
    role = _role_guess(caps, repos_r) if "error" not in caps else "unknown"
    return {"capabilities": caps, "repos": repos_r, "role_guess": role,
            "base_url": ctx.config.base_url}


def op_repos(ctx: Ctx, args: dict) -> dict:
    """List repos on the mirror."""
    c = _client(ctx)
    return c.repos()


def _branch_divergence(proj: Path, name: str, mirror_sha: str) -> dict:
    if not mirror_sha:
        return {"divergence": "unknown"}
    proc = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"refs/heads/{name}"],
        cwd=str(proj), capture_output=True, text=True, timeout=60,
    )
    if proc.returncode != 0:
        return {"divergence": "local_only"}
    local_sha = proc.stdout.strip()
    if local_sha == mirror_sha:
        return {"divergence": "synced", "ahead": 0, "behind": 0}
    proc = subprocess.run(
        ["git", "rev-list", "--left-right", "--count",
         f"{local_sha}...{mirror_sha}"],
        cwd=str(proj), capture_output=True, text=True, timeout=60,
    )
    if proc.returncode != 0:
        return {"divergence": "unknown"}
    ahead, behind = (int(n) for n in proc.stdout.split())
    if ahead and behind:
        div = "diverged"
    elif ahead:
        div = "ahead"
    else:
        div = "behind"
    return {"divergence": div, "ahead": ahead, "behind": behind}


def op_branches(ctx: Ctx, args: dict) -> dict:
    """List all branch tips on the mirror for a repo."""
    c = _client(ctx)
    repo = args.get("repo", "")
    if not repo:
        raise LgmError("config", "--repo is required")
    project = args.get("project", "")
    proj = None
    if project:
        proj = Path(project).resolve()
        if not proj.is_dir():
            raise LgmError("config", f"project not found: {proj}")
    result = c.sync_refs(repo)
    refs = result.get("refs", {})
    if proj is not None and isinstance(refs, dict):
        for name, info in refs.items():
            if isinstance(info, dict):
                info.update(_branch_divergence(proj, name, info.get("sha", "")))
    return result


_NEGOTIATE_CAP = 300


def _negotiate_excludes(c, repo: str, proj: Path,
                        branches: list[str],
                        probe: bool = False) -> list[str]:
    """Local commits the mirror reports as known, for ``^sha`` exclusions.

    Candidates: branch tips + recent history (``--all`` when no branch).
    Returns ``[]`` on any negotiation failure so the caller sends a full
    bundle; every returned sha exists locally by construction.
    ``probe=True`` skips repo creation on the mirror (dry-run safe).
    """
    candidates: list[str] = []
    for target in (branches or ["--all"]):
        if target != "--all":
            proc = subprocess.run(
                ["git", "rev-parse", f"refs/heads/{target}"],
                cwd=str(proj), capture_output=True, text=True, timeout=60,
            )
            if proc.returncode == 0:
                candidates.append(proc.stdout.strip())
        rev = target if target == "--all" else f"refs/heads/{target}"
        proc = subprocess.run(
            ["git", "rev-list", "--max-count=100", rev],
            cwd=str(proj), capture_output=True, text=True, timeout=60,
        )
        if proc.returncode == 0:
            candidates.extend(proc.stdout.split())
    candidates = [s for s in dict.fromkeys(candidates)
                  if len(s) == 40][:_NEGOTIATE_CAP]
    if not candidates:
        return []
    try:
        resp = c.sync_negotiate(repo, candidates, probe=probe)
    except (LgmError, ValueError):
        return []
    known = set(resp.get("known") or [])
    return [s for s in candidates if s in known]


def send_branch(ctx: Ctx, repo: str, project: str, branch: str,
                dry_run: bool = False) -> dict:
    """Bundle a branch (or --all) from a local git project and upload it.

    Negotiates with the mirror first: its known commits become ``^sha``
    exclusions, so repeat sends carry only new commits.
    """
    branches = [branch] if branch else []
    proj = Path(project).resolve()
    excludes = (_negotiate_excludes(_client(ctx), repo, proj, branches, probe=dry_run)
                if proj.is_dir() else [])
    return send_branches(ctx, repo, project, branches, excludes, dry_run)


def send_branches(ctx: Ctx, repo: str, project: str, branches: list[str],
                  exclude_shas: list[str] | None = None,
                  dry_run: bool = False) -> dict:
    """Bundle one or more branches into a SINGLE bundle and upload it.

    Shared history between the branches is packed once. ``exclude_shas``
    (already-known commits, e.g. mirror tips) become ``^sha`` prerequisites:
    only commits the mirror lacks are packed. The receiving side must already
    hold the excluded commits — always true here because they were reported
    by the mirror itself.
    """
    c = _client(ctx)
    if not ctx.config.sync_password:
        raise LgmError("config", "SYNC_PASSWORD not set")
    proj = Path(project).resolve()
    if not proj.is_dir():
        raise LgmError("config", f"project not found: {proj}")

    excludes = [s for s in dict.fromkeys(exclude_shas or []) if s]
    with tempfile.TemporaryDirectory(prefix="tmp-") as tmp:
        bundle_path = Path(tmp) / "outgoing.bundle"
        cmd = ["git", "bundle", "create", str(bundle_path)]
        if branches:
            cmd += [f"refs/heads/{b}" for b in branches]
        else:
            cmd.append("--all")
        cmd += [f"^{s}" for s in excludes]
        proc = subprocess.run(cmd, cwd=str(proj), capture_output=True, text=True, timeout=300)
        if proc.returncode != 0:
            raise LgmError("git", proc.stderr.strip() or "git bundle create failed")
        bundle_bytes = bundle_path.read_bytes()

    label = ",".join(branches) if branches else "all"
    if dry_run:
        return {"repo": repo, "branch": label,
                "bundle_size": len(bundle_bytes), "excluded_bases": len(excludes),
                "dry_run": True}

    res = c.sync_send(repo, bundle_bytes)
    return {"repo": repo, "branch": label,
            "bundle_size": len(bundle_bytes), "excluded_bases": len(excludes),
            "response": res}


def op_send(ctx: Ctx, args: dict) -> dict:
    """Create a git bundle locally and send it to the mirror."""
    repo = args.get("repo", "")
    branch = args.get("branch", "")
    project = args.get("project", "")
    dry_run = args.get("dry_run", False)
    if not repo:
        raise LgmError("config", "--repo is required")
    if not project:
        raise LgmError("config", "--project is required")
    return send_branch(ctx, repo, project, branch, dry_run)


def _local_haves(proj: Path, branch: str) -> str:
    """Local branch tips + recent history, comma-joined, for export
    negotiation; empty when the dir is not a git repo."""
    haves: list[str] = []
    proc = subprocess.run(
        ["git", "for-each-ref", "--format=%(objectname)", "refs/heads/"],
        cwd=str(proj), capture_output=True, text=True, timeout=60,
    )
    if proc.returncode == 0:
        haves.extend(proc.stdout.split())
    proc = subprocess.run(
        ["git", "rev-list", "--max-count=200", branch or "HEAD"],
        cwd=str(proj), capture_output=True, text=True, timeout=60,
    )
    if proc.returncode == 0:
        haves.extend(proc.stdout.split())
    return ",".join([s for s in dict.fromkeys(haves)
                     if len(s) == 40][:_NEGOTIATE_CAP])


def op_pull(ctx: Ctx, args: dict) -> dict:
    """Pull an encrypted git bundle from the mirror and fetch it locally.

    Without ``--haves`` the local repo is negotiated automatically so the
    mirror bundles only what this machine lacks.
    """
    c = _client(ctx)
    repo = args.get("repo", "")
    branch = args.get("branch", "")
    since = args.get("since", "")
    haves = args.get("haves", "")
    project = args.get("project", "")
    dry_run = args.get("dry_run", False)
    if not repo:
        raise LgmError("config", "--repo is required")
    if not ctx.config.sync_password:
        raise LgmError("config", "SYNC_PASSWORD not set")

    if not haves and project:
        proj = Path(project).resolve()
        if proj.is_dir():
            haves = _local_haves(proj, branch)

    result = c.sync_pull(repo, branch=branch, since=since, haves=haves)
    status = result.get("status", "")
    head = result.get("head", "")
    dump = result.get("dump", b"")

    if not dump:
        return {"repo": repo, "branch": branch, "status": status,
                "head": head, "message": "No content to pull."}

    if dry_run:
        return {"repo": repo, "branch": branch, "status": status,
                "head": head, "dump_size": len(dump), "dry_run": True}

    bundle_bytes = decrypt_bundle(dump, ctx.config.sync_password)

    if not project:
        return {"repo": repo, "branch": branch, "status": status,
                "head": head, "bundle_size": len(bundle_bytes),
                "message": "Bundle decrypted. Pass --project to fetch into a repo."}

    proj = Path(project).resolve()
    if not proj.is_dir():
        raise LgmError("config", f"project not found: {proj}")
    with tempfile.TemporaryDirectory(prefix="tmp-") as tmp:
        bundle_path = Path(tmp) / "incoming.bundle"
        bundle_path.write_bytes(bundle_bytes)
        fetch_proc = subprocess.run(
            ["git", "fetch", str(bundle_path), "+refs/heads/*:refs/heads/*"],
            cwd=str(proj), capture_output=True, text=True, timeout=300,
        )
        if fetch_proc.returncode != 0:
            raise LgmError("git", fetch_proc.stderr.strip() or "git fetch failed")
        return {"repo": repo, "branch": branch, "status": status,
                "head": head, "bundle_size": len(bundle_bytes),
                "fetch_exit": fetch_proc.returncode,
                "fetch_stderr": fetch_proc.stderr.strip()[:500] if fetch_proc.stderr else ""}


def op_deps_request(ctx: Ctx, args: dict) -> dict:
    """Post a pre-built encrypted manifest file to /api/documents/submit."""
    c = _client(ctx)
    repo = args.get("repo") or "onyx-platform"
    manifest_path = args.get("manifest", "")
    if not manifest_path:
        raise LgmError("config", "--manifest is required (path to encrypted manifest)")
    p = Path(manifest_path)
    if not p.is_file():
        raise LgmError("config", f"manifest file not found: {p}")
    data = p.read_bytes()
    return c.deps_request(repo, data)


def op_vault_status(ctx: Ctx, args: dict) -> dict:
    """Vault diagnostics: inventory, conflicts, wanted."""
    c = _client(ctx)
    return c.vault_status()


# ── Branch management / GitLab MR ops ────────────────────────────────────────

def op_branch_delete(ctx: Ctx, args: dict) -> dict:
    """Delete one or more branches on the mirror (delete-ref per branch)."""
    c = _client(ctx)
    repo = args.get("repo", "")
    branches_raw = args.get("branches", "")
    if not repo:
        raise LgmError("config", "--repo is required")
    branch_list = [b.strip() for b in branches_raw.split(",") if b.strip()]
    if not branch_list:
        raise LgmError("config", "--branches is required (comma-separated branch names)")

    deleted: list[str] = []
    failed: list[dict] = []
    for br in branch_list:
        try:
            res = c.delete_ref(repo, br)
            if res.get("success"):
                deleted.append(br)
            else:
                failed.append({"branch": br, "error": res.get("message", "unknown error")})
        except LgmError as e:
            failed.append({"branch": br, "error": e.message})
    return {
        "repo": repo,
        "deleted": deleted,
        "failed": failed,
        "message": f"deleted {len(deleted)}/{len(branch_list)} branch(es)",
    }


def op_prune(ctx: Ctx, args: dict) -> dict:
    """List (dry-run) or delete merged/stale branches on the mirror.

    Dry-run by default: without --apply the server only reports candidates.
    """
    c = _client(ctx)
    repo = args.get("repo", "")
    if not repo:
        raise LgmError("config", "--repo is required")
    bases = [b.strip() for b in (args.get("bases") or "").split(",") if b.strip()]
    keep = [k.strip() for k in (args.get("keep") or "").split(",") if k.strip()]
    try:
        older_days = int(args.get("older_days", 0) or 0)
    except (TypeError, ValueError):
        raise LgmError("config", "--older-days must be an integer") from None
    apply = bool(args.get("apply", False))
    return c.prune_branches(repo, bases=bases, older_days=older_days,
                            keep=keep, apply=apply)


_GUIDE = """\
LocalGitMirror transfer guide (work PC <-> home PC via the stealth mirror).

CODE (branches/commits):
  1. branches repo=<name>          — see what the mirror has (names + SHAs).
  2. send repo=<name> project=<git root> [branch=<b>]   — bundle & upload (work side).
  3. pull repo=<name> [branch=<b>] [project=<git root>] — download & fetch (home side).
  Direct git push/pull between the machines does NOT exist; corporate GitLab is
  unreachable from home. Local git inside a workspace is fine.

CORPORATE DEPS (gradle/npm):
  HOME:  request project=<gradle root>   — build manifest of unresolvable deps, post it.
  WORK:  pending [repo=<name>]           — see incoming requests.
  WORK:  respond [project=<root>]        — ship requested artifacts from local cache/Nexus.
  HOME:  apply [project=<root>]          — unpack response into ~/.gradle / ~/.m2.
  Extras: scan (inspect local gradle cache), fetch-poms (public poms),
          publish / vault_status (corporate artifact vault).

MR REVIEW REPLIES (home agent -> work PC -> GitLab):
  0. mr_list [repo=<name>]                — open MRs; without GitLab config lists MRs from transferred notes.
  1. mr_notes repo=<name> [iid=<N>]       — read reviewer threads transferred from work.
  2. Write answers, then EITHER:
     mr_replies_send repo=<name> iid=<N> file=<path to replies-!N.md>
     mr_replies_send repo=<name> iid=<N> text=<inline markdown>
   Replies format: sections '## thread <id>' / '## new <file>:<line>' / '## new',
   optional 'resolve: yes' as the first line of a section.
   mr_replies_send prechecks the reply against the newest mr-notes: '## new'
   anchors within +-3 lines of an unresolved thread and '## thread' ids missing
   from the notes are rejected as duplicates/hallucinations (force=true skips).
   Comment style: as a human reviewer — issues only (bug, bad call, miss),
   1-3 sentences, long only when a scenario needs it; anchored at the offending
   line, imperative, concrete; no praise, no code restating, no filler openers.
   3. mr_replies_status repo=<name> [iid=<N>] — work PC's publish report
     (posted/failed counts); empty means not published yet.
  The work PC posts approved replies to GitLab; a human may approve them in
  the IDE first, so a pending send is normal — poll mr_replies_status.

DIAGNOSTICS: status, repos, debug.

Rules: never hardcode URLs/keys (config comes from .env next to lgm.py);
check `branches` before `pull`; git bundles are sealed with the sync password,
the file postbox is v3 relay-sealed to the server key.
"""


def op_guide(ctx: Ctx, args: dict) -> dict:
    return {"success": True, "guide": _GUIDE}


def op_update(ctx: Ctx, args: dict) -> dict:
    """Download and install the latest CLI tools from the mirror."""
    c = _client(ctx)
    tools_root = Path(__file__).resolve().parent.parent

    url = c.base_url + "/api/tools/latest"
    req = urllib.request.Request(
        url, headers={
            "Authorization": f"Bearer {c.api_key}",
            "X-Session-ID": c.api_key,
        },
    )
    try:
        with urllib.request.urlopen(
            req, context=ssl._create_unverified_context(), timeout=60,
        ) as r:
            blob = r.read()
            remote_version = r.headers.get("X-LGM-Version", "unknown")
    except Exception as e:
        raise LgmError("network", f"tools download failed: {e}") from None

    zip_bytes = decrypt_bundle(blob, ctx.config.sync_password)

    z = zipfile.ZipFile(io.BytesIO(zip_bytes))
    updated = []
    for entry in z.namelist():
        target = tools_root / entry
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(z.read(entry))
        updated.append(entry)
    z.close()

    return {
        "success": True,
        "version": remote_version,
        "files_updated": len(updated),
        "tools_root": str(tools_root),
        "message": f"Updated {len(updated)} files to version {remote_version}. Restart the CLI to use the new version.",
    }
