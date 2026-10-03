"""Git bundle / workspace helpers for the sync API."""

import hashlib
import os
import re
import subprocess
import tempfile
import threading
import time
import uuid
from pathlib import Path
from typing import Optional

from app.core.bundle_crypto import decrypt_dump_to_bundle
from app.core.repo_lock import repo_lock
from app.core.sync_envelope import _hybrid_bundle_ctx

# Injected from main.py
repo_manager = None
system_logger = None

# Redaction patterns for git command/output logged to system.log.
_SHA_RE = re.compile(r"\b[0-9a-f]{7,40}\b", re.IGNORECASE)
_REFS_HEADS_RE = re.compile(r"refs/heads/[^\s:]+")


def _redact_git_text(s: str) -> str:
    s = _SHA_RE.sub("<sha>", s)
    s = _REFS_HEADS_RE.sub("refs/heads/<branch>", s)
    return s


def _git(cwd: Path, *args: str, timeout: int = 600) -> subprocess.CompletedProcess:
    cmd = ["git", *args]
    try:
        proc = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired:
        if system_logger:
            system_logger.error(f"Git timed out after {timeout}s: git {cmd[1] if len(cmd) > 1 else ''}")
        return subprocess.CompletedProcess(
            cmd, returncode=124, stdout="",
            stderr=f"git command timed out after {timeout}s",
        )

    if system_logger and proc.returncode != 0:
        system_logger.error(f"Git Failed ({proc.returncode}): {_redact_git_text(proc.stdout.strip())}")

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


def _attached_branch(path: Path) -> Optional[str]:
    """Currently checked-out branch name, or None if HEAD is detached."""
    proc = _git(path, "symbolic-ref", "--short", "-q", "HEAD")
    if proc.returncode != 0:
        return None
    name = (proc.stdout or "").strip()
    return name or None


def _is_junk_branch_name(name: str) -> bool:
    # a branch with a HEAD component poisons `rev-parse --abbrev-ref HEAD` and git dwim on every machine it lands
    return not name or "HEAD" in name.split("/")


def _cleanup_lgm_refs(path: Path, namespace: str = "refs/lgm/"):
    """Delete scratch refs left behind by sync flows."""
    proc = _git(path, "for-each-ref", "--format=%(refname)", namespace)
    if proc.returncode != 0:
        return
    for line in (proc.stdout or "").splitlines():
        refname = line.strip()
        if refname:
            _git(path, "update-ref", "-d", refname)


def _attach_workspace(workspace: Path, preferred: str, target_hash: Optional[str]) -> subprocess.CompletedProcess:
    """Land HEAD on *preferred* without ever detaching it first.

    When the workspace is already on *preferred*, the caller must have skipped
    ref updates for it and passes its target hash so ref+tree move together via
    reset --hard. Otherwise checkout -f attaches (creating the branch if the
    workspace somehow lost it).
    """
    current = _attached_branch(workspace)
    if current == preferred and target_hash:
        return _git(workspace, "reset", "--hard", target_hash)
    result = _git(workspace, "checkout", "-f", preferred)
    if result.returncode != 0 and target_hash:
        result = _git(workspace, "checkout", "-f", "-B", preferred, target_hash)
    return result


def _apply_dump_to_repo_and_sync_bare(dump_path: Path, repo_name: str, dump_filename: str) -> dict:
    if not repo_manager:
        return {"success": False, "message": "Repo manager is not initialized"}

    workspace_path = repo_manager._get_workspace_path(repo_name)
    bare_path = repo_manager._get_bare_path(repo_name)

    if not workspace_path.exists():
        return {"success": False, "message": f"Workspace '{repo_name}' is not found"}

    with repo_lock(repo_name):
        _ensure_clean_workspace(workspace_path)

        hybrid_bundle_ctx = _hybrid_bundle_ctx.get()
        password = os.getenv("SYNC_PASSWORD", "")
        if not password and hybrid_bundle_ctx is None:
            return {"success": False, "message": "SYNC_PASSWORD not configured in environment"}

        with tempfile.TemporaryDirectory(prefix="idea-sync-") as tmp:
            tmp_dir = Path(tmp)
            bundle_path = tmp_dir / "incoming.bundle"

            try:
                if hybrid_bundle_ctx is not None:
                    # v3: the attachment was sealed via the dedicated "kb" ephemeral.
                    bundle_path.write_bytes(hybrid_bundle_ctx.open_bundle(dump_path.read_bytes()))
                else:
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

            list_proc = _git(workspace_path, "bundle", "list-heads", str(bundle_path))
            bundle_refs = {}  # ref_name -> commit_hash
            if list_proc.returncode == 0:
                for line in (list_proc.stdout or "").splitlines():
                    parts = line.strip().split()
                    if len(parts) >= 2:
                        commit_hash, ref_name = parts[0], parts[1]
                        bundle_refs[ref_name] = commit_hash

            if system_logger:
                system_logger.info("Bundle refs", {"repo": repo_name, "ref_count": len(bundle_refs)})

            # Invariant: refs/lgm/incoming/* never collides with the checked-out branch.
            fetch_proc = _git(workspace_path, "fetch", str(bundle_path), "+refs/heads/*:refs/lgm/incoming/*")
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

            incoming = {}  # branch_name -> commit_hash (list-heads order preserved)
            for ref_name, commit_hash in bundle_refs.items():
                if not ref_name.startswith("refs/heads/"):
                    continue
                branch_name = ref_name[len("refs/heads/"):]
                if _is_junk_branch_name(branch_name):
                    continue
                if _git(workspace_path, "cat-file", "-e", f"{commit_hash}^{{commit}}").returncode == 0:
                    incoming[branch_name] = commit_hash

            if not incoming:
                return {"success": False, "message": "No applicable refs found in bundle"}

            current_branch = _attached_branch(workspace_path)
            if _is_junk_branch_name(current_branch):
                current_branch = None

            # The workspace keeps its own branch; the sender's order only heals detached/unborn HEAD
            preferred_branch = current_branch or next(iter(incoming))

            push_errors = []
            pushed_branches = []
            for branch_name, commit_hash in incoming.items():
                _git(workspace_path, "update-ref", f"refs/lgm/incoming/{branch_name}", commit_hash)

            if bare_path.exists():
                refspecs = [f"refs/lgm/incoming/{b}:refs/heads/{b}" for b in incoming]
                push_proc = _git(workspace_path, "push", "--force", str(bare_path), *refspecs)
                push_lines = ((push_proc.stderr or "") + "\n" + (push_proc.stdout or "")).splitlines()
                branch_results = {}
                for line in push_lines:
                    if "->" not in line:
                        continue
                    left = line.partition("->")[0].split()
                    right = line.partition("->")[2].split()
                    src = left[-1] if left else ""
                    dst = right[0] if right else ""
                    for b in incoming:
                        if src in (f"refs/lgm/incoming/{b}", b) or dst == b:
                            branch_results[b] = (not line.strip().startswith("!"), line.strip())
                            break
                overall_err = (push_proc.stderr or "").strip()
                for branch_name in incoming:
                    result = branch_results.get(branch_name)
                    if result is None:
                        ok = push_proc.returncode == 0
                        detail = overall_err
                    else:
                        ok, detail = result
                    if ok:
                        pushed_branches.append(branch_name)
                    else:
                        push_errors.append(f"{branch_name}: {detail}")

                if push_errors and system_logger:
                    system_logger.warning("Failed to push branches", {
                        "repo": repo_name,
                        "branches": [e.split(":", 1)[0] for e in push_errors],
                        "error": _redact_git_text(overall_err),
                    })

            if not pushed_branches and push_errors:
                _cleanup_lgm_refs(workspace_path)
                return {"success": False, "message": f"Failed to push any branch to bare repo: {'; '.join(push_errors)}"}

            for branch_name, commit_hash in incoming.items():
                if branch_name == preferred_branch and current_branch == preferred_branch:
                    continue  # moved together with the working tree by reset below
                _git(workspace_path, "update-ref", f"refs/heads/{branch_name}", commit_hash)

            attach = _attach_workspace(workspace_path, preferred_branch, incoming.get(preferred_branch))
            _cleanup_lgm_refs(workspace_path)
            if attach.returncode != 0 or not _attached_branch(workspace_path):
                return {
                    "success": False,
                    "message": f"Failed to attach workspace to '{preferred_branch}': "
                               f"{_redact_git_text((attach.stderr or '').strip())}",
                }

            log_proc = _git(workspace_path, "log", "-1", "--oneline")
            commit = (log_proc.stdout or "").strip() if log_proc.returncode == 0 else ""

            if system_logger:
                system_logger.info(
                    "upload-and-apply result",
                    {
                        "repo": repo_name, "success": True,
                        "attachment": dump_filename,
                        "branches_pushed_count": len(pushed_branches),
                        "branches_failed_count": len(push_errors),
                    },
                )

            try:
                threading.Thread(
                    target=_post_apply_maintenance,
                    args=(repo_name, workspace_path, bare_path),
                    daemon=True,
                ).start()
            except Exception:
                pass

            return {
                "success": True,
                "repo": repo_name,
                "attachment": dump_filename,
                "commit": commit,
                "message": f"Sync applied successfully ({len(pushed_branches)} branch(es): {', '.join(pushed_branches)})",
                "branches": pushed_branches,
            }


_MAINTENANCE_INTERVAL_SECONDS = 24 * 3600
_MAINTENANCE_REPACK_PACKS = 10


def _post_apply_maintenance(repo_name: str, workspace: Optional[Path], bare: Optional[Path]) -> None:
    """Best-effort commit-graph write + throttled repack; never raises (runs on a daemon thread)."""
    try:
        if not repo_manager or not getattr(repo_manager, "storage_path", None):
            return
        stamp_dir = Path(repo_manager.storage_path) / ".lgm" / "maintenance"
        stamp = stamp_dir / f"{repo_name}.stamp"
        if stamp.exists() and (time.time() - stamp.stat().st_mtime) < _MAINTENANCE_INTERVAL_SECONDS:
            return
        for git_dir in (workspace, bare):
            if not git_dir or not git_dir.exists():
                continue
            _git(git_dir, "commit-graph", "write", "--reachable")
            # workspace checkouts keep their object DB under .git; bare repos are their own git dir
            objects = git_dir / ".git" / "objects" if (git_dir / ".git").exists() else git_dir / "objects"
            pack_dir = objects / "pack"
            if not pack_dir.exists():
                continue
            if len(list(pack_dir.glob("*.pack"))) > _MAINTENANCE_REPACK_PACKS:
                _git(git_dir, "repack", "-adq")
        stamp_dir.mkdir(parents=True, exist_ok=True)
        stamp.touch()
    except Exception:
        pass


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


class BranchNotFoundError(Exception):
    def __init__(self, branch: str):
        super().__init__(f"branch '{branch}' not found on mirror")
        self.branch = branch


def _build_export_bundle(workspace: Path, bundle_path: Path, since, branch, haves):
    """
    Build a git bundle, downloading only what the client needs.

    Strategy (most specific wins):
    1. branch + haves: bundle only `branch`, excluding commits the client already has.
    2. branch only: bundle the full history of just that branch.
    3. since (legacy): bundle --all excluding `since`.
    4. nothing: bundle --all (full).

    Only haves that actually exist on the server are used as exclusions.
    Raises BranchNotFoundError when an explicitly requested branch is absent.
    Returns the CompletedProcess from `git bundle`.
    """
    # Resolve which refs to include
    if branch:
        # Verify the branch exists on the server
        check_branch = _git(workspace, "rev-parse", "--verify", f"refs/heads/{branch}")
        if check_branch.returncode != 0:
            raise BranchNotFoundError(branch)
        include_refs = [f"refs/heads/{branch}"]
    else:
        include_refs = ["--all"]

    # Build exclusion list from valid haves (or legacy `since`)
    have_list = []
    if haves:
        have_list = [h.strip() for h in haves.split(",") if h.strip()]
    if since and since not in have_list:
        have_list.append(since.strip())

    exclusions = [f"^{h}" for h in _batch_check_commits([workspace], have_list)]

    args = ["bundle", "create", str(bundle_path)] + include_refs + exclusions
    proc = _git(workspace, *args)

    # If exclusions made the bundle empty/invalid, retry without exclusions
    if proc.returncode != 0 and "Refusing to create empty bundle" not in proc.stderr and exclusions:
        if system_logger:
            system_logger.warning("Export bundle with haves failed, retrying without exclusions", {
                "workspace": workspace.name,
                "branch": branch or "--all",
                "dropped_exclusions": len(exclusions),
                "error": _redact_git_text((proc.stderr or "").strip()),
            })
        proc = _git(workspace, "bundle", "create", str(bundle_path), *include_refs)

    return proc


# Content-addressed plaintext bundle cache; any error is a miss (rebuild).
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
    have_list = sorted({h.strip() for h in (haves or "").split(",") if h.strip()})
    parts = [
        repo_name or "",
        head or "",
        (branch or ""),
        (since or "").strip(),
        ",".join(have_list),
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
    """Copy a cached bundle for `key` into `dest`. Return True on a usable hit.

    The caller validates the bytes with `git bundle list-heads`; a corrupt or
    legacy-encrypted entry fails there and is treated as a cache miss — the
    caller rebuilds from scratch.
    """
    try:
        cached = cache_dir / key
        if not cached.is_file() or cached.stat().st_size == 0:
            return False
        dest.write_bytes(cached.read_bytes())
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
        tmp.write_bytes(bundle_path.read_bytes())
        target = cache_dir / key
        os.replace(tmp, target)
    except Exception:
        try:
            if tmp.exists():
                tmp.unlink()
        except Exception:
            pass
