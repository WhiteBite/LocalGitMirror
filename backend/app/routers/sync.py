"""Sync / Documents API Router."""

import asyncio
import base64
import hashlib
import os
import random
import re
import shutil
import struct
import subprocess
import tempfile
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import List, Optional

from fastapi import APIRouter, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel

from app.core.bundle_crypto import MAGIC, decrypt_dump_to_bundle, encrypt_bundle_to_dump
from app.core.envelope_crypto import decrypt_envelope, encrypt_envelope
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
    """Opaque encrypted request — all metadata hidden from DLP / TLS inspection."""
    e: str


# Refname-safe: no spaces, no control chars, not "." or "..", no ".."
_SAFE_BRANCH = re.compile(r"^[^\x00-\x1f\x7f ~^:?*\[\\]+$")


# ============ HELPER FUNCTIONS ============


def _git(cwd: Path, *args: str, timeout: int = 600) -> subprocess.CompletedProcess:
    cmd = ["git", *args]
    if system_logger:
        system_logger.info(f"Exec: {' '.join(cmd)} (cwd={cwd.name})")

    try:
        proc = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired:
        if system_logger:
            system_logger.error(f"Git timed out after {timeout}s: {' '.join(cmd)}")
        return subprocess.CompletedProcess(
            cmd, returncode=124, stdout="",
            stderr=f"git command timed out after {timeout}s",
        )

    if system_logger:
        if proc.stderr and proc.stderr.strip():
            system_logger.info(f"Git Stderr: {proc.stderr.strip()}")
        if proc.returncode != 0:
            system_logger.error(f"Git Failed ({proc.returncode}): {proc.stdout.strip()}")

    return proc


def _infer_repo_from_dump_filename(filename: str) -> Optional[str]:
    # Normalize both / and \ separators so Windows paths work on Linux too
    name = filename.replace("\\", "/").rsplit("/", 1)[-1]
    # Support both legacy dump_*.dmp and new cache_*.bin patterns
    m = re.fullmatch(r"(?:dump|cache)_([A-Za-z0-9_-]+)_([0-9]{8})_([0-9]{4})\.(?:dmp|bin)", name)
    if not m:
        return None
    repo = m.group(1)
    # Defensive: prevent path traversal-like tokens even if regex already blocks '/'
    if "/" in repo or "\\" in repo:
        return None
    return repo


def _pick_bundle_ref(workspace_path: Path, bundle_path: Path, preferred_branch: str = "main") -> str:
    proc = _git(workspace_path, "bundle", "list-heads", str(bundle_path))
    if proc.returncode != 0:
        return "HEAD"

    refs = []
    for line in (proc.stdout or "").splitlines():
        parts = line.strip().split()
        if len(parts) >= 2:
            refs.append(parts[1])

    if not refs:
        return "HEAD"

    preferred = [
        f"refs/heads/{preferred_branch}",
        preferred_branch,
        "refs/heads/main",
        "main",
        "refs/heads/master",
        "master",
    ]
    for candidate in preferred:
        if candidate in refs:
            return candidate

    if "HEAD" in refs:
        return "HEAD"
    return refs[0]


# ============ ENVELOPE HELPERS ============


def _sync_password() -> str:
    """Return SYNC_PASSWORD from env. Raises 503 when not configured."""
    pwd = os.getenv("SYNC_PASSWORD", "")
    if not pwd:
        raise HTTPException(503, "Sync password not configured on server")
    return pwd


def _decrypt_params(e: str, password: str) -> dict:
    """Decrypt envelope field. Raises 400 on invalid/tampered data."""
    try:
        return decrypt_envelope(e, password)
    except Exception:
        raise HTTPException(400, "Invalid request envelope")


def _prune_stale_branches(repo_name: str, local_branches: list, password: str) -> list:
    """Remove branches from Mirror that don't exist locally and aren't HEAD.

    Returns list of pruned branch names. Safe: never deletes HEAD or the last branch.
    """
    if not repo_manager or not local_branches:
        return []

    bare = repo_manager._get_bare_path(repo_name)
    if not bare.exists():
        return []

    # Get all branches on Mirror bare repo
    proc = _git(bare, "for-each-ref", "--format=%(refname:short)", "refs/heads/")
    if proc.returncode != 0:
        return []
    mirror_branches = {b.strip() for b in (proc.stdout or "").splitlines() if b.strip()}

    # Get HEAD branch (protected)
    head_proc = _git(bare, "symbolic-ref", "--short", "HEAD")
    head_branch = (head_proc.stdout or "").strip() if head_proc.returncode == 0 else ""

    # Normalize local branches for comparison
    local_set = {b.strip() for b in local_branches if b and b.strip()}

    # Candidates = on Mirror but NOT local and NOT HEAD
    candidates = mirror_branches - local_set - {head_branch}

    # Safety: never delete if it would leave zero branches
    if len(mirror_branches) - len(candidates) < 1:
        return []

    pruned = []
    workspace = repo_manager._get_workspace_path(repo_name)
    for branch in candidates:
        del_result = _git(bare, "update-ref", "-d", f"refs/heads/{branch}")
        if del_result.returncode == 0:
            pruned.append(branch)
            # Best-effort: also remove from workspace
            if workspace.exists():
                _git(workspace, "branch", "-D", branch)

    if pruned and system_logger:
        system_logger.info("auto-pruned stale branches", {"repo": repo_name, "pruned": pruned})

    return pruned


def _ensure_clean_workspace(path: Path) -> Optional[dict]:
    """Check if workspace is clean; if not, try to reset/clean. Never blocks upload."""
    status_proc = _git(path, "status", "--porcelain")
    if status_proc.returncode != 0:
        # Can't even check status — likely broken repo, but don't block upload
        if system_logger:
            system_logger.warning("Cannot inspect workspace status, proceeding anyway", {"path": str(path)})
        return None

    if not (status_proc.stdout or "").strip():
        return None  # Clean

    # Workspace is dirty, try to self-heal
    if system_logger:
        system_logger.warning("Workspace dirty, attempting self-healing reset", {"path": str(path)})

    # Try reset --hard HEAD first; if no commits yet, reset to empty tree
    reset_proc = _git(path, "reset", "--hard", "HEAD")
    if reset_proc.returncode != 0:
        # No commits — remove all tracked/staged files
        _git(path, "rm", "-rf", "--cached", ".")
        _git(path, "checkout", "--", ".")
    _git(path, "clean", "-fd")

    # Even if still dirty — log warning but DON'T block upload
    status_proc = _git(path, "status", "--porcelain")
    if (status_proc.stdout or "").strip():
        if system_logger:
            system_logger.warning("Workspace still dirty after self-healing, proceeding anyway", {"path": str(path)})

    return None  # Never block upload


def _apply_dump_to_repo_and_sync_bare(dump_path: Path, repo_name: str, dump_filename: str) -> dict:
    if not repo_manager:
        return {"success": False, "message": "Repo manager is not initialized"}

    workspace_path = repo_manager._get_workspace_path(repo_name)
    bare_path = repo_manager._get_bare_path(repo_name)

    if not workspace_path.exists():
        return {"success": False, "message": f"Workspace '{repo_name}' is not found"}

    _ensure_clean_workspace(workspace_path)

    password = os.getenv("SYNC_PASSWORD", "")
    if not password:
        return {"success": False, "message": "SYNC_PASSWORD not configured in environment"}

    with tempfile.TemporaryDirectory(prefix="idea-sync-") as tmp:
        tmp_dir = Path(tmp)
        bundle_path = tmp_dir / "incoming.bundle"

        try:
            decrypt_dump_to_bundle(dump_path, bundle_path, password)
        except Exception as e:
            decrypt_msg = str(e).strip() or e.__class__.__name__
            if "Unsupported" in decrypt_msg and ("format" in decrypt_msg.lower() or "dump" in decrypt_msg.lower()):
                base_error = "Failed to decrypt dump: Unsupported dump format (likely stale/legacy work_kit on sender)."
            elif "InvalidTag" in decrypt_msg:
                base_error = (
                    "Failed to decrypt dump: InvalidTag "
                    "(encryption password mismatch between plugin Sync Password and backend SYNC_PASSWORD)"
                )
            else:
                base_error = f"Failed to decrypt dump: {decrypt_msg}"
            return {"success": False, "message": base_error}

        # ── Step 1: List ALL refs in the bundle ─────────────────────────
        list_proc = _git(workspace_path, "bundle", "list-heads", str(bundle_path))
        bundle_refs = {}  # ref_name -> commit_hash
        if list_proc.returncode == 0:
            for line in (list_proc.stdout or "").splitlines():
                parts = line.strip().split()
                if len(parts) >= 2:
                    commit_hash, ref_name = parts[0], parts[1]
                    bundle_refs[ref_name] = commit_hash

        if system_logger:
            system_logger.info("Bundle refs", {"repo": repo_name, "refs": list(bundle_refs.keys())})

        # ── Step 2: Fetch ALL refs from the bundle at once ──────────────
        # Detach HEAD first so fetch can update all branch refs
        _git(workspace_path, "checkout", "--detach")

        # Use refspec to map bundle's refs/heads/* into local refs/heads/*
        fetch_proc = _git(workspace_path, "fetch", str(bundle_path), "+refs/heads/*:refs/heads/*")
        if fetch_proc.returncode != 0:
            fetch_err = (fetch_proc.stderr or "").strip()
            if "prerequisite" in fetch_err.lower():
                if system_logger:
                    system_logger.warning("Bundle has prerequisite commits, trying HEAD fetch", {"repo": repo_name})
                head_fetch = _git(workspace_path, "fetch", str(bundle_path), "HEAD")
                if head_fetch.returncode != 0:
                    return {
                        "success": False,
                        "message": f"Bundle requires prerequisite commits not present on mirror. "
                                   f"Try a full sync (clear .git/.cache/ on sender). Details: {fetch_err}"
                    }
            else:
                fetch_bare = _git(workspace_path, "fetch", str(bundle_path))
                if fetch_bare.returncode != 0:
                    return {"success": False, "message": fetch_err or "Failed to fetch bundle"}

        # ── Step 3: Identify branches to push ──────────────────────────
        branch_names = []
        for ref_name, commit_hash in bundle_refs.items():
            if ref_name.startswith("refs/heads/"):
                branch_name = ref_name[len("refs/heads/"):]
                branch_names.append(branch_name)
            elif ref_name == "HEAD":
                pass  # HEAD is handled via branch refs
            else:
                # Arbitrary ref — also update it
                branch_names.append(ref_name.split("/")[-1] if "/" in ref_name else ref_name)

        if not branch_names:
            # Fallback — no refs/heads/ found, use FETCH_HEAD
            branch_names = ["main"]

        # ── Step 4: Determine preferred branch for workspace checkout ───
        current_branch = None
        branch_proc = _git(workspace_path, "rev-parse", "--abbrev-ref", "HEAD")
        if branch_proc.returncode == 0:
            branch = (branch_proc.stdout or "").strip()
            if branch and branch != "HEAD":
                current_branch = branch

        # Prefer the work computer's current branch (first in bundle), then master/main
        preferred_branch = branch_names[0] if branch_names else (current_branch or "main")

        # ── Step 5: Checkout preferred branch on workspace ──────────────
        checkout = _git(workspace_path, "checkout", "-f", preferred_branch)
        if checkout.returncode != 0:
            preferred_ref = f"refs/heads/{preferred_branch}"
            target_hash = bundle_refs.get(preferred_ref, "FETCH_HEAD")
            _git(workspace_path, "checkout", "-B", preferred_branch, target_hash)

        # ── Step 6: Push ALL branches to bare repo ──────────────────────
        push_errors = []
        pushed_branches = []
        if bare_path.exists():
            for branch_name in branch_names:
                push_proc = _git(
                    workspace_path, "push", "--force",
                    str(bare_path),
                    f"refs/heads/{branch_name}:refs/heads/{branch_name}"
                )
                if push_proc.returncode == 0:
                    pushed_branches.append(branch_name)
                else:
                    err = (push_proc.stderr or "").strip()
                    push_errors.append(f"{branch_name}: {err}")
                    if system_logger:
                        system_logger.warning(f"Failed to push branch {branch_name}", {"repo": repo_name, "error": err})

        if not pushed_branches and push_errors:
            return {"success": False, "message": f"Failed to push any branch to bare repo: {'; '.join(push_errors)}"}

        log_proc = _git(workspace_path, "log", "-1", "--oneline")
        commit = (log_proc.stdout or "").strip() if log_proc.returncode == 0 else ""

        if system_logger:
            system_logger.info(
                "upload-and-apply result",
                {
                    "repo": repo_name, "success": True,
                    "attachment": dump_filename, "commit": commit,
                    "branches_pushed": pushed_branches,
                    "branches_failed": [e.split(":")[0] for e in push_errors],
                },
            )

        return {
            "success": True,
            "repo": repo_name,
            "attachment": dump_filename,
            "commit": commit,
            "message": f"Sync applied successfully ({len(pushed_branches)} branch(es): {', '.join(pushed_branches)})",
            "branches": pushed_branches,
        }


def _batch_check_commits(sources, commits):
    """
    Verify which commit hashes are present in any of the provided git dirs.
    Uses a single `git cat-file --batch-check` per source over stdin instead
    of fork-per-hash, which is ~50x faster on a list of ~80 hashes.
    """
    if not commits:
        return []
    # Deduplicate while preserving caller's order so the response order is stable.
    seen = set()
    deduped = []
    for c in commits:
        if c not in seen:
            seen.add(c)
            deduped.append(c)

    stdin_payload = "\n".join(deduped) + "\n"
    known_full = set()  # full SHAs reported as present
    for src in sources:
        if not deduped:
            break
        try:
            proc = subprocess.run(
                ["git", "cat-file", "--batch-check"],
                cwd=str(src),
                input=stdin_payload,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=30,
            )
        except subprocess.TimeoutExpired:
            continue
        for line in (proc.stdout or "").splitlines():
            parts = line.split(" ", 2)
            # Format: "<sha> <type> <size>" for known, "<input> missing" for not.
            if len(parts) >= 2 and parts[1] != "missing":
                known_full.add(parts[0].lower())

    def _is_known(h: str) -> bool:
        h_lc = h.lower()
        if h_lc in known_full:
            return True
        # Short-hash input: any full SHA starting with it counts as known
        if len(h_lc) < 40:
            return any(full.startswith(h_lc) for full in known_full)
        return False

    return [c for c in deduped if _is_known(c)]


def _build_export_bundle(workspace: Path, bundle_path: Path, since, branch, haves):
    """
    Build a git bundle, downloading only what the client needs.

    Strategy (most specific wins):
    1. branch + haves: bundle only `branch`, excluding commits the client already has.
    2. branch only: bundle the full history of just that branch.
    3. since (legacy): bundle --all excluding `since`.
    4. nothing: bundle --all (full).

    Only haves that actually exist on the server are used as exclusions.
    Returns the CompletedProcess from `git bundle`.
    """
    # Resolve which refs to include
    if branch:
        # Verify the branch exists on the server
        check_branch = _git(workspace, "rev-parse", "--verify", f"refs/heads/{branch}")
        include_refs = [f"refs/heads/{branch}"] if check_branch.returncode == 0 else ["--all"]
    else:
        include_refs = ["--all"]

    # Build exclusion list from valid haves (or legacy `since`)
    exclusions = []
    have_list = []
    if haves:
        have_list = [h.strip() for h in haves.split(",") if h.strip()]
    if since and since not in have_list:
        have_list.append(since.strip())

    for h in have_list:
        # Only exclude commits the server actually has
        if _git(workspace, "cat-file", "-e", f"{h}^{{commit}}").returncode == 0:
            exclusions.append(f"^{h}")

    args = ["bundle", "create", str(bundle_path)] + include_refs + exclusions
    proc = _git(workspace, *args)

    # If exclusions made the bundle empty/invalid, retry without exclusions
    if proc.returncode != 0 and "Refusing to create empty bundle" not in proc.stderr and exclusions:
        proc = _git(workspace, "bundle", "create", str(bundle_path), *include_refs)

    return proc


# ============ EXPORT BUNDLE CACHE ============
#
# D3: content-addressed cache for the expensive `git bundle create` history
# walk. We cache the PLAINTEXT bundle bytes keyed by the inputs that fully
# determine the output: (repo, source HEAD sha, branch, since, haves). The HEAD
# sha is the freshness token — when the branch tip moves the key changes and the
# cache naturally misses, so we never serve stale history (correctness over
# staleness).
#
# We deliberately do NOT cache the encrypted dump: encrypt_bundle_to_dump uses a
# random salt + nonce, so its output is not byte-stable and caching it would
# either force reuse of one salt/nonce (weakening crypto) or never hit. Caching
# the bundle keeps per-request fresh encryption while skipping the history walk.
#
# The cache is an optimization only: any read/write/corruption error falls back
# to rebuilding from scratch.

_EXPORT_CACHE_DIRNAME = "_export_cache"
_EXPORT_CACHE_TTL_SECONDS = 3600  # 1 hour
_EXPORT_CACHE_MAX_ENTRIES = 20    # LRU cap by mtime


def _export_cache_dir() -> Optional[Path]:
    """Return (creating if needed) the cache directory under storage, or None."""
    try:
        if not repo_manager or not getattr(repo_manager, "storage_path", None):
            return None
        storage = Path(repo_manager.storage_path)
        _lgm = storage / ".lgm" / _EXPORT_CACHE_DIRNAME
        _old = storage / _EXPORT_CACHE_DIRNAME
        cache_dir = _lgm if _lgm.exists() else (_old if _old.exists() else _lgm)
        cache_dir.mkdir(parents=True, exist_ok=True)
        return cache_dir
    except Exception:
        return None


def _export_cache_key(repo_name: str, head: str, branch, since, haves) -> str:
    """sha256 over the inputs that fully determine the bundle bytes."""
    parts = [
        repo_name or "",
        head or "",
        (branch or ""),
        (since or ""),
        (haves or ""),
    ]
    joined = "\x00".join(parts)
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()


def _export_cache_prune(cache_dir: Path) -> None:
    """Evict entries older than TTL, then enforce the LRU size cap (by mtime)."""
    try:
        entries = [p for p in cache_dir.iterdir() if p.is_file()]
    except Exception:
        return

    now = time.time()
    survivors = []
    for p in entries:
        try:
            mtime = p.stat().st_mtime
        except Exception:
            continue
        if now - mtime > _EXPORT_CACHE_TTL_SECONDS:
            try:
                p.unlink()
            except Exception:
                pass
        else:
            survivors.append((mtime, p))

    # LRU cap: keep the newest _EXPORT_CACHE_MAX_ENTRIES, drop the oldest.
    if len(survivors) > _EXPORT_CACHE_MAX_ENTRIES:
        survivors.sort(key=lambda t: t[0])  # oldest first
        for _mtime, p in survivors[: len(survivors) - _EXPORT_CACHE_MAX_ENTRIES]:
            try:
                p.unlink()
            except Exception:
                pass


def _export_cache_lookup(cache_dir: Path, key: str, dest: Path) -> bool:
    """Copy a cached bundle for `key` into `dest`. Return True on a usable hit."""
    try:
        cached = cache_dir / key
        if not cached.is_file() or cached.stat().st_size == 0:
            return False
        shutil.copyfile(cached, dest)
        # Refresh mtime so frequently-used entries survive LRU eviction.
        try:
            os.utime(cached, None)
        except Exception:
            pass
        return True
    except Exception:
        return False


def _export_cache_store(cache_dir: Path, key: str, bundle_path: Path) -> None:
    """Store the freshly built bundle under `key` (atomic, best-effort)."""
    tmp = cache_dir / f".tmp_{uuid.uuid4().hex[:8]}"
    try:
        if not bundle_path.is_file() or bundle_path.stat().st_size == 0:
            return
        target = cache_dir / key
        shutil.copyfile(bundle_path, tmp)
        os.replace(tmp, target)
    except Exception:
        # Best-effort: clean up a stray temp file if possible.
        try:
            if tmp.exists():
                tmp.unlink()
        except Exception:
            pass


# ============ ROUTES ============


@router.get("/health")
async def capabilities():
    return {
        "apiVersion": 1,
        "server": {
            "name": "DocCache",
            "version": "2026.03",
            "build": "dev",
        },
        "sync": {
            "protocolVersion": 1,
            "features": {
                "preflight": True,
                "dryRun": True,
                "passwordProbe": True,
                "uploadAndApply": True,
                "hasCommits": True,
                "applyKnown": True,
                "exportDump": True,
            },
            "modes": ["no-op", "pointer-only", "incremental", "full"],
        },
    }


@router.get("/auth/verify")
async def sync_password_probe():
    password = os.getenv("SYNC_PASSWORD", "")
    if not password:
        raise HTTPException(500, "SYNC_PASSWORD not configured in environment")

    # Probe content — plugins check for "SYNC-PROBE" or legacy "LGM-PROBE"
    probe_data = b"SYNC-PROBE\n"
    salt = os.urandom(16)
    nonce = os.urandom(12)
    from app.core.bundle_crypto import _derive_key
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    key = _derive_key(password, salt)
    aesgcm = AESGCM(key)
    ciphertext = aesgcm.encrypt(nonce, probe_data, None)

    payload = b"".join([
        MAGIC,
        salt,
        nonce,
        struct.pack(">Q", len(ciphertext)),
        ciphertext,
    ])

    return Response(
        content=payload,
        media_type="application/octet-stream",
    )


@router.post("/documents/check")
def sync_has_commits(request: EnvelopeRequest):
    # NOTE: sync def + threadpool. Old version was async + 1 subprocess per
    # hash (~80 hashes -> ~20s of forking). Now it's a single
    # `git cat-file --batch-check` over stdin (one subprocess regardless of
    # input size).
    password = _sync_password()
    params = _decrypt_params(request.e, password)

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
async def sync_apply_known(request: EnvelopeRequest):
    password = _sync_password()
    params = _decrypt_params(request.e, password)

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

    _ensure_clean_workspace(workspace)

    exists_proc = _git(workspace, "cat-file", "-e", f"{commit}^{{commit}}")
    if exists_proc.returncode != 0:
        return {"e": encrypt_envelope({"success": False, "message": f"Commit not found locally: {commit}", "repo": repo}, password)}

    # ── Collect all branch→hash mappings ──────────────────────
    branch_refs = {}  # branch_name -> commit_hash
    if branches_raw:
        branch_refs.update(branches_raw)

    # Ensure current HEAD commit is included for the primary branch
    branch_proc = _git(workspace, "rev-parse", "--abbrev-ref", "HEAD")
    primary_branch = (branch_proc.stdout or "").strip() if branch_proc.returncode == 0 else ""
    if primary_branch and primary_branch != "HEAD" and primary_branch not in branch_refs:
        branch_refs[primary_branch] = commit

    # ── Update workspace refs to match sender's branch tips ──
    _git(workspace, "checkout", "--detach")

    if bare.exists():
        _git(workspace, "fetch", str(bare), "+refs/heads/*:refs/fetched/*")

    pushed_branches = []
    for branch_name, branch_hash in branch_refs.items():
        check = _git(workspace, "cat-file", "-e", f"{branch_hash}^{{commit}}")
        if check.returncode != 0:
            if system_logger:
                system_logger.warning(
                    f"apply-known: commit {branch_hash[:12]} for branch {branch_name} not found in workspace or bare",
                    {"repo": repo}
                )
            continue

        _git(workspace, "update-ref", f"refs/heads/{branch_name}", branch_hash)

        if bare.exists():
            push = _git(workspace, "push", "--force", str(bare), f"refs/heads/{branch_name}:refs/heads/{branch_name}")
            if push.returncode == 0:
                pushed_branches.append(branch_name)
            elif system_logger:
                system_logger.warning(f"apply-known: failed to push {branch_name}", {"repo": repo, "error": push.stderr})

    _git(workspace, "for-each-ref", "--format=%(refname)", "refs/fetched/")

    preferred = primary_branch or (list(branch_refs.keys())[0] if branch_refs else "master")
    _git(workspace, "checkout", "-f", preferred)

    if system_logger:
        system_logger.info("apply-known result", {"repo": repo, "commit": commit, "branches": pushed_branches})

    # Auto-prune stale branches if client sent its local branch list
    local_branches = params.get("local_branches")
    pruned = []
    if local_branches:
        pruned = _prune_stale_branches(repo, local_branches, password)

    result = {
        "success": True,
        "repo": repo,
        "commit": commit,
        "branches": pushed_branches,
        "message": f"Applied known commit ({len(pushed_branches)} branch(es): {', '.join(pushed_branches)})",
    }
    if pruned:
        result["pruned"] = pruned

    return {"e": encrypt_envelope(result, password)}


@router.post("/documents/upload")
async def sync_upload_and_apply(e: str = Form(...), attachment: UploadFile = File(...)):
    password = _sync_password()
    params = _decrypt_params(e, password)

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
    if inferred and inferred != repo_name:
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
        safe_name = filename if (filename.endswith(".dmp") or filename.endswith(".bin")) else f"cache_{repo_name}_{ts}.bin"
        dump_path = tmp_dir / Path(safe_name).name

        payload = await attachment.read()
        dump_path.write_bytes(payload)

        result = _apply_dump_to_repo_and_sync_bare(
            dump_path=dump_path, repo_name=repo_name, dump_filename=dump_path.name
        )

        # Auto-prune stale branches if client sent its local branch list
        local_branches = params.get("local_branches")
        pruned = []
        if local_branches and result.get("success"):
            pruned = _prune_stale_branches(repo_name, local_branches, password)
        if pruned:
            result["pruned"] = pruned

        return {"e": encrypt_envelope(result, password)}


@router.post("/documents/list")
async def sync_refs(request: EnvelopeRequest):
    """
    Get all branch tips visible to the server.

    Branches can live in two places:
      - the bare repo (<repo>.git): where `git push` lands, AND where
        upload-and-apply pushes branches. This is the source of truth.
      - the workspace (<repo>): a single checked-out tree (web UI editing).

    We merge both, with the bare repo taking precedence.
    """
    password = _sync_password()
    params = _decrypt_params(request.e, password)

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

    return {"e": encrypt_envelope(
        {"success": True, "repo": repo_name, "head": head, "refs": refs},
        password,
    )}


@router.post("/documents/delete-ref")
async def delete_ref(request: EnvelopeRequest):
    """
    Delete a branch from the Mirror bare repo (and workspace if present).

    Guards:
      - Cannot delete the last remaining branch.
      - Cannot delete the branch that is currently HEAD in the bare repo
        (would orphan it).
      - Branch name must be a valid git refname.
    """
    password = _sync_password()
    params = _decrypt_params(request.e, password)

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
        system_logger.info("branch deleted", {"repo": repo_name, "branch": branch})

    return {"e": encrypt_envelope({"success": True, "repo": repo_name, "branch": branch}, password)}


@router.post("/documents/export")
def sync_export_dump(e: str = Form(...)):
    # NOTE: intentionally a sync `def` (not async). The body does heavy blocking
    # work (git bundle, encryption, base64 of a potentially large repo). As a
    # sync handler Starlette runs it in a threadpool, keeping the event loop free.
    password = _sync_password()
    params = _decrypt_params(e, password)

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
            bundle_proc = _build_export_bundle(source, bundle_path, since, branch_name, haves)

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
async def sync_preview_pull(request: EnvelopeRequest):
    """Lightweight preview: are there incoming commits to pull?"""
    password = _sync_password()
    params = _decrypt_params(request.e, password)

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
async def sync_preview_pull_details(request: EnvelopeRequest):
    """Get commit list and diffstat for incoming changes.

    Picks the source repo (bare or workspace) that actually has the requested
    branch, since `git push` lands branches in BARE while workspace is just
    one checkout. Without this, preview is empty for any branch the user has
    pushed but never had checked out.
    """
    password = _sync_password()
    params = _decrypt_params(request.e, password)

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
async def sync_workspace():
    """Sync workspace from bare repo (after push from work)"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    result = repo_manager.sync_workspace()
    if result["success"]:
        state.status = "processing"
        state.last_sync_time = datetime.now()
    return result


@router.post("/documents/apply")
async def apply_sync_bundle():
    """Apply latest sync dump to workspace with conflict resolution and repo verification"""
    if not shared_manager or not repo_manager:
        raise HTTPException(500, "Managers не инициализированы")

    try:
        # Find latest dump in shared folders.
        preferred = ["work-sync", "sync", "backups"]
        all_folders = []
        try:
            all_folders = [f.get("name") for f in (shared_manager.get_folders() or []) if f.get("name")]
        except Exception:
            all_folders = []

        # Normalize by including lowercase variants too.
        normalized_all = []
        for name in all_folders:
            normalized_all.append(name)
            lower = str(name).lower()
            if lower != name:
                normalized_all.append(lower)

        sync_folders = []
        for name in preferred + normalized_all:
            if name and name not in sync_folders:
                sync_folders.append(name)

        if not repo_manager.current_repo:
            return {"success": False, "message": "No repository selected"}

        # Collect all candidates first, then choose the newest dump matching the current repo.
        latest_dump = None
        folder_name = None
        scanned = []
        candidates = []
        current_repo_name = str(repo_manager.current_repo)

        for folder in sync_folders:
            scanned.append(folder)
            try:
                folder_path = shared_manager._get_folder_path(folder)
                if not folder_path.exists():
                    continue

                for dump in folder_path.glob("dump_*.dmp"):
                    candidates.append(
                        {"path": dump, "folder": folder, "name": dump.name, "mtime": dump.stat().st_mtime}
                    )
            except Exception:
                continue

        matching_candidates = [item for item in candidates if item["name"].startswith(f"dump_{current_repo_name}_")]

        if matching_candidates:
            selected = sorted(matching_candidates, key=lambda x: x["mtime"], reverse=True)[0]
            latest_dump = selected["path"]
            folder_name = selected["folder"]
        elif candidates:
            # Keep old behavior as fallback, but surface a clear mismatch error below.
            selected = sorted(candidates, key=lambda x: x["mtime"], reverse=True)[0]
            latest_dump = selected["path"]
            folder_name = selected["folder"]

        if not latest_dump:
            return {
                "success": False,
                "message": "No sync dumps found",
                "scanned_folders": scanned,
                "expected_pattern": "dump_*.dmp",
                "current_repo": current_repo_name,
            }

        # Extract project name from dump filename (e.g., dump_MyProject_20240520_1800.dmp)
        dump_name = latest_dump.name
        try:
            dump_stem = dump_name.replace("dump_", "").replace(".dmp", "")
            if dump_name.startswith(f"dump_{current_repo_name}_"):
                dump_repo = current_repo_name
            else:
                parts = dump_stem.split("_")
                dump_repo = parts[0]  # legacy fallback for older naming
        except (IndexError, ValueError):
            return {"success": False, "message": "Invalid dump filename format"}

        # Verify repo match
        if dump_repo != current_repo_name:
            return {
                "success": False,
                "message": f"Dump is for '{dump_repo}' but current repo is '{current_repo_name}'. Please select the correct repository.",
                "selected_dump": dump_name,
                "selected_folder": folder_name,
                "scanned_folders": scanned,
            }

        # Get workspace path
        workspace_path = repo_manager._get_workspace_path(repo_manager.current_repo)
        if not workspace_path or not workspace_path.exists():
            return {"success": False, "message": "Workspace not initialized"}

        # Check for uncommitted changes
        status_result = subprocess.run(
            ["git", "status", "--porcelain"], cwd=str(workspace_path), capture_output=True, text=True
        )

        if status_result.stdout.strip():
            return {
                "success": False,
                "message": "Uncommitted changes detected. Please commit or stash changes first before applying sync.",
            }

        # Get password from environment
        password = os.getenv("SYNC_PASSWORD", "")
        if not password:
            return {"success": False, "message": "SYNC_PASSWORD not configured in .env"}

        # OpSec: Add random delay 2-4 seconds before decryption
        delay = random.uniform(2, 4)
        await asyncio.sleep(delay)

        # Decrypt dump (native LGMSTRL1 only)
        temp_bundle = None
        temp_dir = Path(tempfile.mkdtemp(prefix="sync-tmp-", dir=str(latest_dump.parent)))
        bundle_candidate = temp_dir / "debug_info.tmp"
        try:
            with latest_dump.open("rb") as fh:
                header = fh.read(len(MAGIC))

            if header == MAGIC:
                decrypt_dump_to_bundle(latest_dump, bundle_candidate, password)
                temp_bundle = bundle_candidate
            else:
                return {"success": False, "message": "Unsupported dump format (expected native LGMSTRL1)"}

        except Exception as e:
            try:
                temp_dir.rmdir()
            except Exception:
                pass
            msg = str(e).strip() or e.__class__.__name__
            return {"success": False, "message": f"Decryption failed: {msg}"}

        try:
            # Get current branch
            branch_result = subprocess.run(
                ["git", "rev-parse", "--abbrev-ref", "HEAD"],
                cwd=str(workspace_path),
                capture_output=True,
                text=True,
                check=True,
            )
            current_branch = branch_result.stdout.strip()

            # Use fetch + reset instead of pull to ensure exact copy
            fetch_result = subprocess.run(
                ["git", "fetch", str(temp_bundle), f"{current_branch}:{current_branch}"],
                cwd=str(workspace_path),
                capture_output=True,
                text=True,
            )

            if fetch_result.returncode != 0:
                temp_bundle.unlink(missing_ok=True)
                try:
                    temp_dir.rmdir()
                except Exception:
                    pass
                return {"success": False, "message": f"Failed to fetch from bundle: {fetch_result.stderr}"}

            # Reset to FETCH_HEAD to ensure exact copy
            reset_result = subprocess.run(
                ["git", "reset", "--hard", "FETCH_HEAD"], cwd=str(workspace_path), capture_output=True, text=True
            )

            if reset_result.returncode != 0:
                temp_bundle.unlink(missing_ok=True)
                try:
                    temp_dir.rmdir()
                except Exception:
                    pass
                return {"success": False, "message": f"Failed to reset workspace: {reset_result.stderr}"}

            # Get latest commit info
            log_result = subprocess.run(
                ["git", "log", "-1", "--oneline"], cwd=str(workspace_path), capture_output=True, text=True, check=True
            )
            latest_commit = log_result.stdout.strip()

            # Cleanup
            temp_bundle.unlink(missing_ok=True)
            try:
                temp_dir.rmdir()
            except Exception:
                pass

            if system_logger:
                system_logger.info(
                    "Sync applied",
                    {
                        "dump": latest_dump.name,
                        "repo": repo_manager.current_repo,
                        "commit": latest_commit,
                        "delay_seconds": round(delay, 2),
                    },
                )

            return {
                "success": True,
                "message": "Sync applied successfully",
                "commit": latest_commit,
                "attachment": latest_dump.name,
                "repo": repo_manager.current_repo,
            }

        except Exception as e:
            if temp_bundle and temp_bundle.exists():
                temp_bundle.unlink(missing_ok=True)
            try:
                if "temp_dir" in locals() and temp_dir.exists():
                    for child in temp_dir.iterdir():
                        child.unlink(missing_ok=True)
                    temp_dir.rmdir()
            except Exception:
                pass
            raise e

    except Exception as e:
        if system_logger:
            system_logger.error("Failed to apply sync", {"error": str(e)})
        raise HTTPException(500, f"Failed to apply sync: {str(e)}")


@router.get("/session/state")
async def get_sync_state():
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


# ============ BACKWARD-COMPAT ALIASES ============


@router.get("/capabilities")
async def _alias_capabilities():
    return await capabilities()


@router.get("/sync/password-probe")
async def _alias_password_probe():
    return await sync_password_probe()


@router.post("/sync/has-commits")
async def _alias_has_commits(request: EnvelopeRequest):
    return sync_has_commits(request)


@router.post("/sync/apply-known")
async def _alias_apply_known(request: EnvelopeRequest):
    return await sync_apply_known(request)


@router.post("/sync/upload-and-apply")
async def _alias_upload_and_apply(e: str = Form(...), dump_file: UploadFile = File(...)):
    return await sync_upload_and_apply(e=e, attachment=dump_file)


@router.post("/sync/refs")
async def _alias_refs(request: EnvelopeRequest):
    return await sync_refs(request)


@router.post("/sync/export-dump")
async def _alias_export_dump(e: str = Form(...)):
    return sync_export_dump(e=e)


@router.post("/sync/preview-pull")
async def _alias_preview_pull(request: EnvelopeRequest):
    return await sync_preview_pull(request)


@router.post("/sync/preview-pull-details")
async def _alias_preview_pull_details(request: EnvelopeRequest):
    return await sync_preview_pull_details(request)


@router.post("/sync")
async def _alias_sync_workspace():
    return await sync_workspace()


@router.post("/sync/apply-bundle")
async def _alias_apply_bundle():
    return await apply_sync_bundle()


@router.get("/sync/state")
async def _alias_sync_state():
    return await get_sync_state()
