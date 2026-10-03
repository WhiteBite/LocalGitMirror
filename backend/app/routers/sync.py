"""Sync / Documents API Router."""

import base64
import hashlib
import re
import tempfile
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import List, Optional

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool

from app.core import hybrid_crypto
from app.core.bundle_crypto import encrypt_bundle_to_dump
from app.core.git_bundle import (
    BranchNotFoundError,
    _apply_dump_to_repo_and_sync_bare,
    _attach_workspace,
    _attached_branch,
    _batch_check_commits,
    _build_export_bundle,
    _cleanup_lgm_refs,
    _ensure_clean_workspace,
    _export_cache_dir,
    _export_cache_key,
    _export_cache_lookup,
    _export_cache_prune,
    _export_cache_store,
    _git,
    _infer_repo_from_dump_filename,
    _is_junk_branch_name,
    _post_apply_maintenance,
    _redact_git_text,
)
from app.core.repo_lock import repo_lock
from app.core.sync_envelope import (
    _decrypt_params,
    _hybrid_bundle_ctx,
    _hybrid_ctx,
    _sync_password,
    encrypt_envelope,
    get_server_private_key,
)
from app.routers.state import state

router = APIRouter(prefix="/api", tags=["sync"])

# Injected from main.py
repo_manager = None
shared_manager = None
system_logger = None
config = {}

# ============ PYDANTIC MODELS ============


class SyncHasCommitsRequest(BaseModel):
    repo: str
    commits: List[str]


class SyncApplyKnownRequest(BaseModel):
    repo: str
    commit: str
    branches: Optional[dict] = None  # {"branch_name": "commit_hash", ...}


class PreviewPullRequest(BaseModel):
    repo: str
    since: Optional[str] = None


class PreviewPullDetailsRequest(BaseModel):
    repo: str
    since: Optional[str] = None
    branch: Optional[str] = None


class DeleteRefRequest(BaseModel):
    repo: str
    branch: str


class EnvelopeRequest(BaseModel):
    """Opaque encrypted request — all metadata hidden from DLP / TLS inspection.

    `epk` (optional) carries the client's ephemeral X25519 public key (base64).
    When present, the request is decrypted with protocol v3 (hybrid ECIES)
    instead of the shared password. Legacy clients omit it.
    """
    e: str
    epk: Optional[str] = None


class NegotiateRequest(BaseModel):
    """Sealed one-shot negotiation; `k` is the optional v3 ephemeral public key (base64)."""
    e: str
    k: Optional[str] = None


# Refname-safe: no spaces, no control chars, not "." or "..", no ".."
_SAFE_BRANCH = re.compile(r"^[^\x00-\x1f\x7f ~^:?*\[\\]+$")


def _collect_sync_refs(workspace: Path, bare: Path):
    """Merged branch tips for a repo: workspace + bare for-each-ref (bare wins),
    HEAD sha (workspace first, bare fallback), HEAD branch (bare first),
    is_head per ref. Returns (refs, head, head_branch)."""
    def _collect_refs(path: Path) -> dict:
        out = {}
        if not path.exists():
            return out
        proc = _git(
            path, "for-each-ref",
            "--format=%(refname:short) %(objectname) %(committerdate:iso-strict)",
            "refs/heads/"
        )
        if proc.returncode == 0:
            for line in (proc.stdout or "").strip().split("\n"):
                if not line:
                    continue
                parts = line.split(" ", 2)
                if len(parts) >= 2:
                    name = parts[0]
                    sha  = parts[1]
                    updated = parts[2].strip() if len(parts) > 2 else ""
                    out[name] = {"sha": sha, "updated": updated}
        return out

    # Workspace first, then bare overrides
    refs: dict = {}
    refs.update(_collect_refs(workspace))
    refs.update(_collect_refs(bare))

    # HEAD sha: prefer workspace HEAD, fall back to bare
    head = ""
    for path in (workspace, bare):
        if path.exists():
            head_proc = _git(path, "rev-parse", "HEAD")
            if head_proc.returncode == 0 and head_proc.stdout.strip():
                head = head_proc.stdout.strip()
                break

    head_branch = ""
    for path in (bare, workspace):
        if path.exists():
            sym = _git(path, "symbolic-ref", "--short", "HEAD")
            if sym.returncode == 0 and sym.stdout.strip():
                head_branch = sym.stdout.strip()
                break

    for name, info in refs.items():
        info["is_head"] = (name == head_branch)

    return refs, head, head_branch


# ============ ROUTES ============


@router.post("/documents/check")
def sync_has_commits(request: EnvelopeRequest):
    # NOTE: sync def + threadpool. Old version was async + 1 subprocess per
    # hash (~80 hashes -> ~20s of forking). Now it's a single
    # `git cat-file --batch-check` over stdin (one subprocess regardless of
    # input size).
    password = _sync_password()
    params = _decrypt_params(request.e, password, request.epk)

    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo = (params.get("repo") or "").strip()
    commits_raw = params.get("commits") or []
    commits = [c.strip() for c in commits_raw if c and c.strip()]

    if not repo:
        raise HTTPException(400, "Repository name is required")

    if repo not in repo_manager.get_repos():
        return {"e": encrypt_envelope({"success": True, "repo": repo, "known": []}, password)}

    workspace = repo_manager._get_workspace_path(repo)
    bare = repo_manager._get_bare_path(repo)

    # Pick the source that actually has the most refs
    sources = [p for p in (bare, workspace) if p.exists()]
    if not sources:
        return {"e": encrypt_envelope({"success": True, "repo": repo, "known": []}, password)}

    known = _batch_check_commits(sources, commits)

    head = None
    for src in (workspace, bare):
        if not src.exists():
            continue
        head_proc = _git(src, "rev-parse", "HEAD")
        if head_proc.returncode == 0 and head_proc.stdout.strip():
            head = head_proc.stdout.strip()
            break

    return {"e": encrypt_envelope({"success": True, "repo": repo, "known": known, "head": head}, password)}


@router.post("/documents/link")
def sync_apply_known(request: EnvelopeRequest):
    password = _sync_password()
    params = _decrypt_params(request.e, password, request.epk)

    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo = (params.get("repo") or "").strip()
    commit = (params.get("commit") or "").strip()
    if not repo or not commit:
        raise HTTPException(400, "Repository and commit are required")

    branches_raw = params.get("branches") or {}

    if repo not in repo_manager.get_repos():
        return {"e": encrypt_envelope({"success": False, "message": f"Repository '{repo}' not found", "repo": repo}, password)}

    workspace = repo_manager._get_workspace_path(repo)
    bare = repo_manager._get_bare_path(repo)
    if not workspace.exists():
        return {"e": encrypt_envelope({"success": False, "message": f"Workspace for '{repo}' not found", "repo": repo}, password)}

    with repo_lock(repo):
        _ensure_clean_workspace(workspace)

        # ── Collect all branch→hash mappings ──────────────────────
        branch_refs = {}  # branch_name -> commit_hash
        if branches_raw:
            branch_refs.update(branches_raw)
        branch_refs = {k: v for k, v in branch_refs.items() if not _is_junk_branch_name(k)}

        current_branch = _attached_branch(workspace)
        if _is_junk_branch_name(current_branch):
            current_branch = None
        if current_branch and current_branch not in branch_refs:
            branch_refs[current_branch] = commit

        # Objects may live only in bare (e.g. pushed there via git-http). Pull
        # them into the workspace under a neutral namespace — HEAD and local
        # branches are never touched by this fetch.
        needed = [commit, *branch_refs.values()]
        present = set(_batch_check_commits([workspace], needed))
        if len(present) < len(set(needed)) and bare.exists():
            _git(workspace, "fetch", str(bare), "+refs/heads/*:refs/lgm/bare/*")
            present = set(_batch_check_commits([workspace], needed))

        if commit not in present:
            _cleanup_lgm_refs(workspace)
            return {"e": encrypt_envelope({"success": False, "message": f"Commit not found locally: {commit}", "repo": repo}, password)}

        # Where HEAD should end up: stay on the current branch when attached,
        # otherwise the sender's primary branch. An already-detached workspace
        # heals back onto `preferred` below.
        preferred = current_branch or (next(iter(branch_refs)) if branch_refs else None)
        if not preferred:
            head_proc = _git(bare, "symbolic-ref", "--short", "HEAD") if bare.exists() else None
            preferred = ((head_proc.stdout or "").strip() if head_proc and head_proc.returncode == 0 else "") or "master"

        pushed_branches = []
        pushable = {b: h for b, h in branch_refs.items() if h in present}
        missing_branches = [b for b in branch_refs if b not in pushable]
        if missing_branches and system_logger:
            system_logger.warning(
                "apply-known: commit not found for branch in workspace or bare",
                {"repo": repo, "branches": missing_branches}
            )

        if bare.exists() and pushable:
            for branch_name, branch_hash in pushable.items():
                _git(workspace, "update-ref", f"refs/lgm/ak/{branch_name}", branch_hash)
            refspecs = [f"refs/lgm/ak/{b}:refs/heads/{b}" for b in pushable]
            push = _git(workspace, "push", "--force", str(bare), *refspecs)
            failed_branches = []
            if push.returncode == 0:
                pushed_branches = list(pushable)
            else:
                for branch_name in pushable:
                    marker = f"refs/lgm/ak/{branch_name} ->"
                    status = ""
                    for line in (push.stderr or "").splitlines():
                        if marker in line:
                            status = line.split(marker, 1)[0].strip()
                            break
                    if status[:1] in ("*", "+") or ".." in status:
                        pushed_branches.append(branch_name)
                    else:
                        failed_branches.append(branch_name)
            if failed_branches and system_logger:
                system_logger.warning(
                    "apply-known: failed to push branch",
                    {"repo": repo, "branches": failed_branches,
                     "error": _redact_git_text(push.stderr)}
                )

        for branch_name, branch_hash in pushable.items():
            if branch_name == preferred and current_branch == preferred:
                continue  # moved together with the working tree by reset below
            _git(workspace, "update-ref", f"refs/heads/{branch_name}", branch_hash)

        _attach_workspace(workspace, preferred, branch_refs.get(preferred))
        if _attached_branch(workspace) is None:
            # Last-resort heal: pin HEAD to the sender's commit under `preferred`.
            _git(workspace, "checkout", "-f", "-B", preferred, commit)
        _cleanup_lgm_refs(workspace)
        if _attached_branch(workspace) is None and system_logger:
            system_logger.warning("apply-known: workspace HEAD still detached after attach attempt", {"repo": repo, "preferred": preferred})

        if system_logger:
            system_logger.info("apply-known result", {"repo": repo, "branches_count": len(pushed_branches)})

        result = {
            "success": True,
            "repo": repo,
            "commit": commit,
            "branches": pushed_branches,
            "message": f"Applied known commit ({len(pushed_branches)} branch(es): {', '.join(pushed_branches)})",
        }

        try:
            threading.Thread(
                target=_post_apply_maintenance,
                args=(repo, workspace, bare),
                daemon=True,
            ).start()
        except Exception:
            pass

        return {"e": encrypt_envelope(result, password)}


@router.post("/documents/upload")
async def sync_upload_and_apply(
    e: str = Form(...),
    attachment: UploadFile = File(...),
    k: Optional[str] = Form(None),
    kb: Optional[str] = Form(None),
):
    password = _sync_password()
    params = _decrypt_params(e, password, k)

    # Bind the attachment's (separate) ephemeral key, if the client sealed the
    # bundle with v3. Falls back to the legacy password dump when absent.
    server_private_key = get_server_private_key()
    if kb and server_private_key is not None:
        try:
            _hybrid_bundle_ctx.set(
                hybrid_crypto.HybridServerContext(server_private_key, hybrid_crypto.decode_epk(kb))
            )
        except Exception:
            raise HTTPException(400, "Invalid bundle key")
    else:
        _hybrid_bundle_ctx.set(None)

    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo_name = (params.get("repo") or "").strip()
    if not repo_name:
        raise HTTPException(400, "Repository name is required")

    if repo_name not in repo_manager.get_repos():
        return {"e": encrypt_envelope(
            {"success": False, "message": f"Repository '{repo_name}' not found", "repo": repo_name},
            password,
        )}

    filename = attachment.filename or ""
    inferred = _infer_repo_from_dump_filename(filename)
    repo_hash = hashlib.sha256(repo_name.encode("utf-8")).hexdigest()[:8]
    if inferred and inferred != repo_name and inferred != repo_hash:
        return {"e": encrypt_envelope(
            {"success": False, "repo": repo_name,
             "message": f"Uploaded filename indicates repo '{inferred}' but request repo is '{repo_name}'"},
            password,
        )}

    if system_logger:
        system_logger.info("upload-and-apply requested", {"repo": repo_name, "filename": filename})

    with tempfile.TemporaryDirectory(prefix="idea-sync-") as tmp:
        tmp_dir = Path(tmp)
        ts = datetime.now().strftime("%Y%m%d_%H%M")
        safe_name = filename if (filename.endswith(".dmp") or filename.endswith(".bin")) else f"cache_{repo_hash}_{ts}.bin"
        dump_path = tmp_dir / Path(safe_name).name

        payload = await attachment.read()

        def _apply() -> dict:
            dump_path.write_bytes(payload)
            return _apply_dump_to_repo_and_sync_bare(
                dump_path=dump_path, repo_name=repo_name, dump_filename=dump_path.name
            )

        result = await run_in_threadpool(_apply)

        return {"e": encrypt_envelope(result, password)}


@router.post("/documents/list")
def sync_refs(request: EnvelopeRequest):
    """
    Get all branch tips visible to the server.

    Branches can live in two places:
      - the bare repo (<repo>.git): where `git push` lands, AND where
        upload-and-apply pushes branches. This is the source of truth.
      - the workspace (<repo>): a single checked-out tree (web UI editing).

    We merge both, with the bare repo taking precedence.
    """
    password = _sync_password()
    params = _decrypt_params(request.e, password, request.epk)

    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo_name = (params.get("repo") or "").strip()
    if not repo_name:
        raise HTTPException(400, "Repository name is required")
    if repo_name not in repo_manager.get_repos():
        raise HTTPException(404, "Repository not found")

    workspace = repo_manager._get_workspace_path(repo_name)
    bare = repo_manager._get_bare_path(repo_name)

    if not workspace.exists() and not bare.exists():
        raise HTTPException(404, "Repository data not found")

    refs, head, _head_branch = _collect_sync_refs(workspace, bare)

    return {"e": encrypt_envelope(
        {"success": True, "repo": repo_name, "head": head, "refs": refs},
        password,
    )}


@router.post("/documents/negotiate")
def sync_negotiate(request: NegotiateRequest):
    """One-shot negotiation for the work PC: create the repo if missing, then
    return refs, HEAD and which of the client's commits the mirror already
    has — everything sealed in one response envelope."""
    password = _sync_password()
    params = _decrypt_params(request.e, password, request.k)

    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo = (params.get("repo") or "").strip()
    if not repo:
        raise HTTPException(400, "Repository name is required")

    created = False
    if repo not in repo_manager.get_repos():
        result = repo_manager.create_repo(repo)
        if not result["success"]:
            raise HTTPException(400, result["message"])
        created = True

    workspace = repo_manager._get_workspace_path(repo)
    bare = repo_manager._get_bare_path(repo)

    refs, head, _head_branch = _collect_sync_refs(workspace, bare)

    commits_raw = params.get("commits") or []
    commits = [c.strip() for c in commits_raw if c and c.strip()]
    known = _batch_check_commits([p for p in (workspace, bare) if p and p.exists()], commits)

    return {"e": encrypt_envelope(
        {
            "success": True,
            "repo": repo,
            "created": created,
            "refs": refs,
            "head": head,
            "known": known,
        },
        password,
    )}


@router.post("/documents/delete-ref")
def delete_ref(request: EnvelopeRequest):
    """
    Delete a branch from the Mirror bare repo (and workspace if present).

    Guards:
      - Cannot delete the last remaining branch.
      - Cannot delete the branch that is currently HEAD in the bare repo
        (would orphan it).
      - Branch name must be a valid git refname.
    """
    password = _sync_password()
    params = _decrypt_params(request.e, password, request.epk)

    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo_name = (params.get("repo") or "").strip()
    branch = (params.get("branch") or "").strip()

    if not repo_name or repo_name not in repo_manager.get_repos():
        raise HTTPException(404, "Repository not found")

    if not branch or ".." in branch or not _SAFE_BRANCH.match(branch):
        raise HTTPException(400, "Invalid branch name")

    bare = repo_manager._get_bare_path(repo_name)
    workspace = repo_manager._get_workspace_path(repo_name)

    # Verify branch exists in bare
    check = _git(bare, "rev-parse", "--verify", f"refs/heads/{branch}")
    if check.returncode != 0:
        raise HTTPException(404, f"Branch '{branch}' not found in bare repo")

    # Guard: not the last branch
    all_branches_proc = _git(bare, "for-each-ref", "--format=%(refname:short)", "refs/heads/")
    all_branches = [
        l.strip() for l in (all_branches_proc.stdout or "").strip().splitlines() if l.strip()
    ]
    if len(all_branches) <= 1:
        raise HTTPException(409, "Cannot delete the last remaining branch")

    # Guard: not the current HEAD of bare
    bare_head_proc = _git(bare, "symbolic-ref", "--short", "HEAD")
    if bare_head_proc.returncode == 0:
        bare_head = (bare_head_proc.stdout or "").strip()
        if bare_head == branch:
            raise HTTPException(
                409,
                f"Branch '{branch}' is the current HEAD of the bare repo. "
                "Switch HEAD to another branch first."
            )

    # Delete from bare repo
    del_bare = _git(bare, "update-ref", "-d", f"refs/heads/{branch}")
    if del_bare.returncode != 0:
        raise HTTPException(500, f"Failed to delete branch from bare repo: {del_bare.stderr}")

    # Best-effort: delete from workspace too (may not have it)
    if workspace.exists():
        ws_check = _git(workspace, "rev-parse", "--verify", f"refs/heads/{branch}")
        if ws_check.returncode == 0:
            _git(workspace, "branch", "-D", branch)

    if system_logger:
        system_logger.info("branch deleted", {"repo": repo_name})

    return {"e": encrypt_envelope({"success": True, "repo": repo_name, "branch": branch}, password)}


@router.post("/documents/prune-branches")
def prune_branches(request: EnvelopeRequest):
    """
    List (dry-run) or delete merged/stale branches from the Mirror bare repo.

    Envelope plaintext params:
      repo       (str)  mirror repo name
      bases      (list) base refs — a branch merged into ANY existing base
                        (git merge-base --is-ancestor branch base) is a
                        candidate. Bases missing from the bare repo are
                        ignored.
      older_days (int)  also treat branches whose committerdate is older than
                        now - older_days as candidates (0 = off).
      keep       (list) branches to never delete. Defaults to
                        ["master", "develop", "plan_fix"] when empty.
      apply      (bool) false = dry-run (report candidates, delete nothing);
                        true  = delete candidates from bare (+ best-effort
                        workspace branch -D).

    Guards (same family as delete-ref):
      - HEAD branch of the bare repo is always protected.
      - Never delete if it would leave < 1 branch in the bare repo.
      - keep/bases entries must be valid git refnames.
    """
    password = _sync_password()
    params = _decrypt_params(request.e, password, request.epk)

    if not repo_manager:
        raise HTTPException(500, "Repo manager not initialized")

    repo_name = (params.get("repo") or "").strip()
    if not repo_name or repo_name not in repo_manager.get_repos():
        raise HTTPException(404, "Repository not found")

    bases_raw = params.get("bases") or []
    bases = [b.strip() for b in bases_raw if isinstance(b, str) and b.strip()]

    try:
        older_days = int(params.get("older_days") or 0)
    except (TypeError, ValueError):
        raise HTTPException(400, "older_days must be an integer")
    if older_days < 0:
        raise HTTPException(400, "older_days must be >= 0")

    keep_raw = params.get("keep") or []
    keep = [k.strip() for k in keep_raw if isinstance(k, str) and k.strip()]
    if not keep:
        keep = ["master", "develop", "plan_fix"]

    apply = bool(params.get("apply"))

    # Branch-name safety for keep/bases entries (same rule as delete-ref).
    for name in bases + keep:
        if ".." in name or not _SAFE_BRANCH.match(name):
            raise HTTPException(400, f"Invalid branch name: {name!r}")

    bare = repo_manager._get_bare_path(repo_name)
    workspace = repo_manager._get_workspace_path(repo_name)
    if not bare.exists():
        raise HTTPException(404, "Repository data not found")

    # All branches in the bare repo (source of truth).
    branches_proc = _git(bare, "for-each-ref", "--format=%(refname:short)", "refs/heads/")
    if branches_proc.returncode != 0:
        raise HTTPException(500, "Failed to list branches in bare repo")
    all_branches = [line.strip() for line in (branches_proc.stdout or "").splitlines() if line.strip()]
    branch_set = set(all_branches)

    # HEAD branch (always protected).
    head_proc = _git(bare, "symbolic-ref", "--short", "HEAD")
    head_branch = (head_proc.stdout or "").strip() if head_proc.returncode == 0 else ""

    # Bases that actually exist in the bare repo — the rest are ignored.
    existing_bases = [b for b in bases if b in branch_set]

    # Committer dates (unix seconds) for the older_days rule.
    branch_dates: dict = {}
    date_proc = _git(bare, "for-each-ref",
                     "--format=%(refname:short) %(committerdate:unix)", "refs/heads/")
    if date_proc.returncode == 0:
        for line in (date_proc.stdout or "").splitlines():
            parts = line.strip().split()
            if len(parts) == 2 and parts[1].isdigit():
                branch_dates[parts[0]] = int(parts[1])
    cutoff = (time.time() - older_days * 86400) if older_days > 0 else None

    candidates = []
    for branch in all_branches:
        if branch == head_branch or branch in keep:
            continue
        merged = any(
            _git(bare, "merge-base", "--is-ancestor", branch, base).returncode == 0
            for base in existing_bases
        )
        old = False
        if cutoff is not None:
            d = branch_dates.get(branch)
            old = d is not None and d < cutoff
        if merged or old:
            candidates.append(branch)

    # Guard: never delete if it would leave < 1 branch in the bare repo.
    guard_fired = False
    if len(all_branches) - len(candidates) < 1:
        candidates = []
        guard_fired = True

    # protected: HEAD + keep branches actually present in the repo.
    seen: set = set()
    protected = [
        b for b in ([head_branch] + keep)
        if b and b in branch_set and not (b in seen or seen.add(b))
    ]

    pruned: list = []
    if apply and candidates:
        for branch in candidates:
            del_result = _git(bare, "update-ref", "-d", f"refs/heads/{branch}")
            if del_result.returncode == 0:
                pruned.append(branch)
                # Best-effort: also remove from workspace.
                if workspace.exists():
                    _git(workspace, "branch", "-D", branch)

    if system_logger:
        system_logger.info("prune-branches", {
            "repo": repo_name,
            "apply": apply,
            "candidates": len(candidates),
            "pruned": len(pruned),
            "protected": len(protected),
        })

    if guard_fired:
        message = "Refusing to prune: it would leave no branches in the bare repo"
    elif apply:
        message = f"Pruned {len(pruned)} branch(es)" if pruned else "Nothing to prune"
    else:
        message = f"{len(candidates)} candidate(s) for pruning (dry-run)"

    return {"e": encrypt_envelope({
        "success": True,
        "repo": repo_name,
        "apply": apply,
        "candidates": candidates,
        "pruned": pruned,
        "protected": protected,
        "message": message,
    }, password)}


@router.post("/documents/export")
def sync_export_dump(e: str = Form(...), k: Optional[str] = Form(None)):
    # NOTE: intentionally a sync `def` (not async). The body does heavy blocking
    # work (git bundle, encryption, base64 of a potentially large repo). As a
    # sync handler Starlette runs it in a threadpool, keeping the event loop free.
    password = _sync_password()
    params = _decrypt_params(e, password, k)

    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo_name = (params.get("repo") or "").strip()
    if not repo_name:
        raise HTTPException(400, "Repository name is required")
    if repo_name not in repo_manager.get_repos():
        raise HTTPException(404, "Repository not found")

    since = params.get("since") or None
    branch = params.get("branch") or None
    haves = params.get("haves") or None

    workspace = repo_manager._get_workspace_path(repo_name)
    bare = repo_manager._get_bare_path(repo_name)
    if not workspace.exists() and not bare.exists():
        raise HTTPException(404, "Repository data not found")

    branch_name = (branch or "").strip() or None

    # Choose the source repo that actually contains the requested branch.
    def _has_branch(path: Path, br: str) -> bool:
        return path.exists() and _git(path, "rev-parse", "--verify", f"refs/heads/{br}").returncode == 0

    if branch_name and _has_branch(bare, branch_name) and not _has_branch(workspace, branch_name):
        source = bare
    elif workspace.exists():
        source = workspace
    else:
        source = bare

    head_proc = _git(source, "rev-parse", "HEAD")
    if head_proc.returncode != 0:
        # workspace HEAD may be unborn; try bare
        head_proc = _git(bare, "rev-parse", "HEAD") if bare.exists() else head_proc
    if head_proc.returncode != 0:
        raise HTTPException(400, "Repository has no commits")
    head = (head_proc.stdout or "").strip()

    with tempfile.TemporaryDirectory(prefix="idea-sync-") as tmp:
        tmp_dir = Path(tmp)
        bundle_path = tmp_dir / "export.bundle"

        # D3: content-addressed cache for the expensive `git bundle create`
        # history walk. Key includes `head`, so a moved branch tip misses.
        cache_dir = _export_cache_dir()
        cache_key = None
        served_from_cache = False
        if cache_dir is not None:
            cache_key = _export_cache_key(repo_name, head, branch_name, since, haves)
            if _export_cache_lookup(cache_dir, cache_key, bundle_path):
                # Validate the cached bytes are a real git bundle before trusting
                # them. A corrupt/garbage cache file is treated as a miss so we
                # gracefully fall back to rebuilding (cache is never a
                # correctness dependency).
                if _git(source, "bundle", "list-heads", str(bundle_path)).returncode == 0:
                    served_from_cache = True
                else:
                    # Drop the poisoned entry and rebuild below.
                    try:
                        (cache_dir / cache_key).unlink()
                    except Exception:
                        pass
                    try:
                        bundle_path.unlink()
                    except Exception:
                        pass

        if not served_from_cache:
            try:
                bundle_proc = _build_export_bundle(source, bundle_path, since, branch_name, haves)
            except BranchNotFoundError as exc:
                raise HTTPException(404, str(exc))

            if bundle_proc.returncode != 0:
                if "Refusing to create empty bundle" in bundle_proc.stderr:
                    return {"e": encrypt_envelope(
                        {"status": "no_content", "head": head, "repo": repo_name},
                        password,
                    )}
                raise HTTPException(500, bundle_proc.stderr.strip() or "Failed to create bundle")

            # Store the freshly built bundle for subsequent identical requests.
            if cache_dir is not None and cache_key is not None:
                _export_cache_store(cache_dir, cache_key, bundle_path)
                _export_cache_prune(cache_dir)

        # password is already obtained at the top of the function from _sync_password()
        hybrid_ctx = _hybrid_ctx.get()
        if hybrid_ctx is not None:
            # v3: seal the bundle to this request's ephemeral. No password, no
            # on-disk encrypted artifact — the sealed bytes go straight into "d".
            try:
                sealed = hybrid_ctx.seal_bundle(bundle_path.read_bytes())
            except Exception as exc:
                raise HTTPException(500, f"Failed to create sync package: {exc}")
            return {
                "e": encrypt_envelope({"status": "ok", "head": head, "repo": repo_name}, password),
                "d": base64.b64encode(sealed).decode("ascii"),
            }

        ts = datetime.now().strftime("%Y%m%d_%H%M")
        dump_path = tmp_dir / f".tmp_{uuid.uuid4().hex[:8]}"
        try:
            encrypt_bundle_to_dump(bundle_path, dump_path, password)
        except Exception as exc:
            raise HTTPException(500, f"Failed to create sync package: {exc}")

        return {
            "e": encrypt_envelope({"status": "ok", "head": head, "repo": repo_name}, password),
            "d": base64.b64encode(dump_path.read_bytes()).decode("ascii"),
        }


@router.post("/documents/preview")
def sync_preview_pull(request: EnvelopeRequest):
    """Lightweight preview: are there incoming commits to pull?"""
    password = _sync_password()
    params = _decrypt_params(request.e, password, request.epk)

    def _respond(result: dict) -> dict:
        return {"e": encrypt_envelope(result, password)}

    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo_name = (params.get("repo") or "").strip()
    if not repo_name:
        raise HTTPException(400, "Repository name is required")

    if repo_name not in repo_manager.get_repos():
        return _respond({"success": True, "repo": repo_name, "remoteHead": None,
                         "hasUpdates": False, "reason": "repo-not-found"})

    workspace = repo_manager._get_workspace_path(repo_name)
    if not workspace.exists():
        return _respond({"success": True, "repo": repo_name, "remoteHead": None,
                         "hasUpdates": False, "reason": "workspace-not-found"})

    head_proc = _git(workspace, "rev-parse", "HEAD")
    if head_proc.returncode != 0:
        return _respond({"success": True, "repo": repo_name, "remoteHead": None,
                         "hasUpdates": False, "reason": "no-commits"})

    head = (head_proc.stdout or "").strip()
    since = (params.get("since") or "").strip()

    if not since:
        return _respond({"success": True, "repo": repo_name, "remoteHead": head,
                         "hasUpdates": True, "reason": "full-sync-needed"})

    if since == head:
        return _respond({"success": True, "repo": repo_name, "remoteHead": head,
                         "hasUpdates": False, "reason": "no-new-commits"})

    check_since = _git(workspace, "cat-file", "-e", f"{since}^{{commit}}")
    if check_since.returncode != 0:
        return _respond({"success": True, "repo": repo_name, "remoteHead": head,
                         "hasUpdates": True, "reason": "since-not-found"})

    return _respond({"success": True, "repo": repo_name, "remoteHead": head,
                     "hasUpdates": True, "reason": "ahead"})


@router.post("/documents/preview-details")
def sync_preview_pull_details(request: EnvelopeRequest):
    """Get commit list and diffstat for incoming changes.

    Picks the source repo (bare or workspace) that actually has the requested
    branch, since `git push` lands branches in BARE while workspace is just
    one checkout. Without this, preview is empty for any branch the user has
    pushed but never had checked out.
    """
    password = _sync_password()
    params = _decrypt_params(request.e, password, request.epk)

    if not repo_manager:
        raise HTTPException(500, "Repo manager not initialized")

    repo_name = (params.get("repo") or "").strip()
    if repo_name not in repo_manager.get_repos():
        raise HTTPException(404, "Repository not found")

    workspace = repo_manager._get_workspace_path(repo_name)
    bare = repo_manager._get_bare_path(repo_name)
    if not workspace.exists() and not bare.exists():
        raise HTTPException(404, "Repository data not found")

    since = (params.get("since") or "").strip()
    target = (params.get("branch") or "").strip() or "HEAD"
    rev_range = f"{since}..{target}" if since else target

    # Pick the source that actually has the target ref (bare-only branches!)
    def _has_ref(path: Path, ref: str) -> bool:
        if not path.exists():
            return False
        if ref == "HEAD":
            return _git(path, "rev-parse", "--verify", "HEAD").returncode == 0
        return _git(path, "rev-parse", "--verify", ref).returncode == 0

    if _has_ref(bare, target) and not _has_ref(workspace, target):
        source = bare
    elif workspace.exists():
        source = workspace
    else:
        source = bare

    # Get commits
    log_proc = _git(source, "log", "--oneline", "-n", "30", rev_range)
    commits = []
    if log_proc.returncode == 0:
        for line in (log_proc.stdout or "").strip().split("\n"):
            if not line:
                continue
            parts = line.split(" ", 1)
            commits.append({
                "hash": parts[0],
                "message": parts[1] if len(parts) > 1 else ""
            })

    # Get diffstat
    diff_proc = _git(source, "diff", "--stat", rev_range)
    diffstat = diff_proc.stdout or ""

    return {"e": encrypt_envelope(
        {"success": True, "repo": repo_name, "commits": commits, "diffstat": diffstat},
        password,
    )}


@router.post("/documents/process")
def sync_workspace():
    """Sync workspace from bare repo (after push from work)"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    result = repo_manager.sync_workspace()
    if result["success"]:
        state.status = "processing"
        state.last_sync_time = datetime.now()
    return result


@router.get("/session/state")
def get_sync_state():
    """Get sync state for dashboard"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    try:
        # Look for backup folder
        backup_folders = ["work-backups", "backups", "sync"]
        latest_backup = None
        backup_count = 0

        for folder_name in backup_folders:
            folder_path = shared_manager._get_folder_path(folder_name)
            if folder_path.exists():
                # Find latest chunk_*.bin file
                backups = sorted(folder_path.glob("chunk_*.bin"), key=lambda p: p.stat().st_mtime, reverse=True)
                if backups:
                    latest = backups[0]
                    backup_count = len(backups)
                    latest_backup = {
                        "filename": latest.name,
                        "size": latest.stat().st_size,
                        "modified": datetime.fromtimestamp(latest.stat().st_mtime).isoformat(),
                        "folder": folder_name,
                    }
                    break

        if latest_backup:
            # Calculate time ago
            modified_time = datetime.fromisoformat(latest_backup["modified"])
            time_diff = datetime.now() - modified_time

            if time_diff.total_seconds() < 3600:
                time_ago = f"{int(time_diff.total_seconds() / 60)} minutes ago"
            elif time_diff.total_seconds() < 86400:
                time_ago = f"{int(time_diff.total_seconds() / 3600)} hours ago"
            else:
                time_ago = f"{int(time_diff.total_seconds() / 86400)} days ago"

            return {
                "success": True,
                "state": {
                    "status": "active",
                    "lastSync": time_ago,
                    "lastSize": f"{latest_backup['size'] / (1024 * 1024):.1f} MB",
                    "backupCount": backup_count,
                    "latestFile": latest_backup["filename"],
                },
            }
        else:
            return {
                "success": True,
                "state": {"status": "idle", "lastSync": None, "lastSize": None, "backupCount": 0, "latestFile": None},
            }
    except Exception as e:
        raise HTTPException(500, f"Не удалось получить статус синхронизации: {str(e)}")
