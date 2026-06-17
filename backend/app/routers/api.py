"""API Router."""

import os
import re
import struct
import subprocess
import tempfile
from datetime import datetime
from pathlib import Path
from typing import List, Optional

from fastapi import APIRouter, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel

from app.core.bundle_crypto import decrypt_dump_to_bundle, encrypt_bundle_to_dump, MAGIC

router = APIRouter(prefix="/api", tags=["api"])

# Injected from main.py
git_handler = None
repo_manager = None
git_workspace = None
shared_manager = None
config = {}
system_logger = None


# State for "AI Processing" legend
class ServerState:
    status = "idle"  # idle, processing, ready
    last_push_time = None
    last_sync_time: Optional[datetime] = None


state = ServerState()


class ChatRequest(BaseModel):
    message: str
    model: Optional[str] = None
    use_context: Optional[bool] = True


class RepoSelectRequest(BaseModel):
    repo: str


class StoragePathRequest(BaseModel):
    path: str


# ============ STATUS ============


@router.get("/status")
async def get_status():
    """Get server status"""
    from app.core.system_monitor import SystemMonitor

    # Dynamic check of the current workspace
    current_git_workspace = git_workspace
    has_changes = False
    change_count = 0

    if repo_manager and (
        not current_git_workspace or current_git_workspace.workspace.name != repo_manager.current_repo
    ):
        from app.core.git_utils import GitWorkspace

        workspace = repo_manager._get_workspace_path(repo_manager.current_repo)
        bare = repo_manager._get_bare_path(repo_manager.current_repo)
        current_git_workspace = GitWorkspace(workspace, bare)

    if current_git_workspace:
        ws_status = current_git_workspace.get_status()
        has_changes = ws_status.get("has_changes", False)
        change_count = ws_status.get("change_count", 0)

    # Resolve last sync time
    sync_time = state.last_sync_time
    if not sync_time and current_git_workspace and repo_manager:
        # Fallback to last commit time if never synced via UI
        try:
            commits = repo_manager.get_recent_commits(repo_manager.current_repo)
            if commits:
                # commits[0].date is already a string or datetime from git log
                sync_time = commits[0].get("date")
        except:
            pass

    return {
        "git_running": True,
        "git_port": config.get("git_port", 8444),
        "web_port": config.get("web_port", 443),
        "local_ip": SystemMonitor.get_local_ip(),
        "current_repo": repo_manager.current_repo if repo_manager else "default",
        "workflow_status": state.status,
        "last_sync_time": sync_time,
        "has_changes": has_changes,
        "change_count": change_count,
        "storage_path": str(config.get("storage_path", "storage")),
    }


@router.get("/config")
async def get_config():
    """Get current configuration"""
    return {
        "storage_path": str(config.get("storage_path", "storage")),
        "git_port": config.get("git_port", 8081),
        "ollama_url": config.get("ollama_url", "http://localhost:11434"),
    }


@router.get("/connection-info")
async def get_connection_info():
    """Return connection info for IDEA plugin setup (mirror URL, API key, sync password)."""
    from app.core.system_monitor import SystemMonitor
    from pathlib import Path as _Path

    local_ip = SystemMonitor.get_local_ip()
    web_port = config.get("web_port", 443)
    tls_enabled = _Path("cert.pem").exists() and _Path("key.pem").exists()
    protocol = "https" if tls_enabled else "http"
    mirror_url = f"{protocol}://{local_ip}:{web_port}"

    api_key = os.getenv("API_KEY", "")
    sync_password = os.getenv("SYNC_PASSWORD", "")
    default_repo = repo_manager.current_repo if repo_manager else "default"

    # Build raw config line compatible with plugin's ConfigLineCodec.parseRawPayload()
    # Always set mirrorInsecureTls=true because auto-generated cert is self-signed
    config_line = (
        f"baseUrl={mirror_url}\n"
        f"repo={default_repo}\n"
        f"mirrorInsecureTls=true\n"
        f"offlineGenerateOnly=false\n"
        f"simpleUiMode=false\n"
        f"gitLabBaseUrl=\n"
        f"gitLabProject=\n"
        f"gitLabInsecureTls=false\n"
        f"gitRemoteName=origin\n"
        f"pullBackDefaultMode=new-branch\n"
        f"mirrorApiKey={api_key}\n"
        f"syncPassword={sync_password}\n"
        f"gitLabToken=\n"
        f"workMode=auto"
    )

    return {
        "mirror_url": mirror_url,
        "api_key": api_key,
        "sync_password": sync_password,
        "default_repo": default_repo,
        "protocol": protocol,
        "local_ip": local_ip,
        "web_port": web_port,
        "config_line": config_line,
    }


@router.post("/config/storage")
async def set_storage_path(request: StoragePathRequest):
    """Change storage directory"""
    from pathlib import Path

    global git_handler, repo_manager, git_workspace

    new_path = Path(request.path)

    # Validate path
    try:
        new_path.mkdir(parents=True, exist_ok=True)
    except Exception as e:
        raise HTTPException(400, f"Невозможно создать директорию: {str(e)}")

    # Stop git server if running
    if git_handler and git_handler.is_running:
        git_handler.stop()

    # Update config
    config["storage_path"] = new_path

    # Reinitialize components
    from app.core.git_handler import GitHandler
    from app.core.git_utils import GitWorkspace
    from app.core.repo_manager import RepoManager

    git_handler = GitHandler(storage_path=new_path, port=config.get("git_port", 8081))
    repo_manager = RepoManager(new_path)

    workspace = repo_manager._get_workspace_path(repo_manager.current_repo)
    bare = repo_manager._get_bare_path(repo_manager.current_repo)
    git_workspace = GitWorkspace(workspace, bare)

    # Update .env file
    env_path = Path(__file__).parent.parent.parent.parent / ".env"
    if env_path.exists():
        content = env_path.read_text()
        import re

        content = re.sub(r"STORAGE_PATH=.*", f"STORAGE_PATH={request.path}", content)
        env_path.write_text(content)

    return {"success": True, "storage_path": str(new_path)}


@router.post("/config/browse")
async def browse_folder():
    """Open folder browser dialog (Windows)"""
    import subprocess

    try:
        # PowerShell folder browser
        ps_script = """
        Add-Type -AssemblyName System.Windows.Forms
        $browser = New-Object System.Windows.Forms.FolderBrowserDialog
        $browser.Description = "Выберите папку для хранения"
        $browser.ShowNewFolderButton = $true
        if ($browser.ShowDialog() -eq "OK") { $browser.SelectedPath }
        """
        result = subprocess.run(["powershell", "-Command", ps_script], capture_output=True, text=True)
        path = result.stdout.strip()
        if path:
            return {"success": True, "path": path}
        return {"success": False, "path": ""}
    except Exception as e:
        raise HTTPException(500, f"Ошибка: {str(e)}")


@router.get("/workflow/status")
async def get_workflow_status():
    """Get workflow status for legend"""
    has_changes = False
    change_count = 0
    if git_workspace:
        ws_status = git_workspace.get_status()
        has_changes = ws_status["has_changes"]
        change_count = ws_status["change_count"]

    return {
        "status": state.status,
        "has_changes": has_changes,
        "change_count": change_count,
        "status_text": {
            "idle": "⚪ Idle",
            "processing": "🟠 AI Processing...",
            "ready": "🟢 Response Ready",
        }.get(state.status, "⚪ Idle"),
    }


# ============ GIT SERVER ============


@router.post("/git/start")
async def start_git():
    if not git_handler:
        raise HTTPException(500, "Git handler не инициализирован")
    success = git_handler.start()
    if system_logger:
        if success:
            system_logger.info("Git сервер запущен через API", {"port": config.get("git_port")})
        else:
            system_logger.warning("Не удалось запустить Git сервер через API")
    return {"success": success, "running": git_handler.is_running}


@router.post("/git/stop")
async def stop_git():
    if not git_handler:
        raise HTTPException(500, "Git handler не инициализирован")
    git_handler.stop()
    if system_logger:
        system_logger.info("Git сервер остановлен через API")
    return {"success": True, "running": git_handler.is_running}


# ============ REPOS ============


@router.get("/repos")
async def get_repos():
    if not repo_manager:
        return {"repos": ["default"], "current": "default"}
    return {"repos": repo_manager.get_repos(), "current": repo_manager.current_repo}


class RepoCreateRequest(BaseModel):
    name: str


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


class FileSaveRequest(BaseModel):
    path: str
    content: str


@router.post("/file/save")
async def save_file(request: FileSaveRequest):
    """Save file content to disk"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    abs_path = repo_manager.get_absolute_path(request.path)

    try:
        # Create directories if they don't exist
        os.makedirs(os.path.dirname(abs_path), exist_ok=True)

        with open(abs_path, "w", encoding="utf-8") as f:
            f.write(request.content)

        if system_logger:
            system_logger.info("Файл сохранен", {"path": request.path})

        return {"success": True, "path": request.path}
    except Exception as e:
        if system_logger:
            system_logger.error("Не удалось сохранить файл", {"error": str(e)})
        raise HTTPException(500, f"Не удалось сохранить файл: {str(e)}")


@router.post("/repos/create")
async def create_repo(request: RepoCreateRequest, raw_request: Request):
    """Create a new repository"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    # Read git identity from plugin headers (best-effort)
    author_name = raw_request.headers.get("X-User-Name")
    author_email = raw_request.headers.get("X-User-Email")

    result = repo_manager.create_repo(request.name, author_name=author_name, author_email=author_email)
    if result["success"]:
        if system_logger:
            system_logger.info(f"Создан репозиторий: {request.name}")
    else:
        if system_logger:
            system_logger.error(f"Не удалось создать репозиторий: {result['message']}")
        raise HTTPException(400, result["message"])

    return result


@router.post("/repos/select")
async def select_repo(request: RepoSelectRequest):
    """Select a repository as active"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo_manager.current_repo = request.repo
    _init_git_workspace()

    if system_logger:
        system_logger.info(f"Выбран репозиторий: {request.repo}")

    return {"success": True, "current": repo_manager.current_repo}


@router.post("/repos/delete")
async def delete_repo(request: RepoSelectRequest):
    """Delete a repository"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    # Prevent deleting current repo if possible, or handle it
    if repo_manager.current_repo == request.repo:
        repo_manager.current_repo = "default"

    result = repo_manager.delete_repo(request.repo)
    if result["success"]:
        if system_logger:
            system_logger.info(f"Удален репозиторий: {request.repo}")
    else:
        if system_logger:
            system_logger.error(f"Не удалось удалить репозиторий: {result['message']}")
        raise HTTPException(400, result["message"])

    return result


def _init_git_workspace():
    """Reinitialize git workspace for current repo"""
    global git_workspace
    if repo_manager:
        from app.core.git_utils import GitWorkspace

        workspace = repo_manager._get_workspace_path(repo_manager.current_repo)
        bare = repo_manager._get_bare_path(repo_manager.current_repo)
        git_workspace = GitWorkspace(workspace, bare)


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
        # Surface as a non-zero CompletedProcess so callers handle it uniformly.
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
        # (git refuses to update the currently checked-out branch via fetch)
        _git(workspace_path, "checkout", "--detach")

        # Use refspec to map bundle's refs/heads/* into local refs/heads/*
        fetch_proc = _git(workspace_path, "fetch", str(bundle_path), "+refs/heads/*:refs/heads/*")
        if fetch_proc.returncode != 0:
            fetch_err = (fetch_proc.stderr or "").strip()
            if "prerequisite" in fetch_err.lower():
                # Incremental bundle without prerequisites — try fetching HEAD only
                if system_logger:
                    system_logger.warning("Bundle has prerequisite commits, trying HEAD fetch", {"repo": repo_name})
                # Try fetching individual refs that might work
                head_fetch = _git(workspace_path, "fetch", str(bundle_path), "HEAD")
                if head_fetch.returncode != 0:
                    return {
                        "success": False,
                        "message": f"Bundle requires prerequisite commits not present on mirror. "
                                   f"Try a full sync (clear .git/.cache/ on sender). Details: {fetch_err}"
                    }
            else:
                # Try a bare fetch (no refspec) as fallback
                fetch_bare = _git(workspace_path, "fetch", str(bundle_path))
                if fetch_bare.returncode != 0:
                    return {"success": False, "message": fetch_err or "Failed to fetch bundle"}

        # ── Step 3: Identify branches to push ──────────────────────────
        # Parse bundle refs for refs/heads/* branches
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
        # refs/heads/* already updated by fetch; just switch working tree
        checkout = _git(workspace_path, "checkout", "-f", preferred_branch)
        if checkout.returncode != 0:
            # Branch might not exist locally yet — create from bundle ref hash
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


@router.post("/documents/check")
async def sync_has_commits(request: SyncHasCommitsRequest):
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo = (request.repo or "").strip()
    commits = [c.strip() for c in (request.commits or []) if c and c.strip()]

    if not repo:
        raise HTTPException(400, "Repository name is required")

    if repo not in repo_manager.get_repos():
        return {"success": True, "repo": repo, "known": []}

    workspace = repo_manager._get_workspace_path(repo)
    if not workspace.exists():
        return {"success": True, "repo": repo, "known": []}

    known = []
    for h in commits:
        proc = _git(workspace, "cat-file", "-e", f"{h}^{{commit}}")
        if proc.returncode == 0:
            known.append(h)

    head = None
    head_proc = _git(workspace, "rev-parse", "HEAD")
    if head_proc.returncode == 0:
        head = (head_proc.stdout or "").strip() or None

    return {"success": True, "repo": repo, "known": known, "head": head}


@router.post("/documents/link")
async def sync_apply_known(request: SyncApplyKnownRequest):
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo = (request.repo or "").strip()
    commit = (request.commit or "").strip()
    if not repo or not commit:
        raise HTTPException(400, "Repository and commit are required")

    if repo not in repo_manager.get_repos():
        return {"success": False, "message": f"Repository '{repo}' not found", "repo": repo}

    workspace = repo_manager._get_workspace_path(repo)
    bare = repo_manager._get_bare_path(repo)
    if not workspace.exists():
        return {"success": False, "message": f"Workspace for '{repo}' not found", "repo": repo}

    _ensure_clean_workspace(workspace)

    exists_proc = _git(workspace, "cat-file", "-e", f"{commit}^{{commit}}")
    if exists_proc.returncode != 0:
        return {"success": False, "message": f"Commit not found locally: {commit}", "repo": repo}

    # ── Collect all branch→hash mappings ──────────────────────
    branch_refs = {}  # branch_name -> commit_hash
    if request.branches:
        branch_refs.update(request.branches)

    # Ensure current HEAD commit is included for the primary branch
    branch_proc = _git(workspace, "rev-parse", "--abbrev-ref", "HEAD")
    primary_branch = (branch_proc.stdout or "").strip() if branch_proc.returncode == 0 else ""
    if primary_branch and primary_branch != "HEAD" and primary_branch not in branch_refs:
        branch_refs[primary_branch] = commit

    # ── Update workspace refs to match sender's branch tips ──
    # Detach HEAD so we can update all branch refs freely
    _git(workspace, "checkout", "--detach")

    # Ensure workspace has all objects from bare (some branches may have been
    # fetched only into bare by a previous upload-and-apply but not workspace)
    if bare.exists():
        _git(workspace, "fetch", str(bare), "+refs/heads/*:refs/fetched/*")

    pushed_branches = []
    for branch_name, branch_hash in branch_refs.items():
        # Verify this commit exists in the workspace object store
        check = _git(workspace, "cat-file", "-e", f"{branch_hash}^{{commit}}")
        if check.returncode != 0:
            if system_logger:
                system_logger.warning(f"apply-known: commit {branch_hash[:12]} for branch {branch_name} not found in workspace or bare", {"repo": repo})
            continue

        # Update the branch ref to point to the correct commit
        _git(workspace, "update-ref", f"refs/heads/{branch_name}", branch_hash)

        # Push to bare repo
        if bare.exists():
            push = _git(workspace, "push", "--force", str(bare), f"refs/heads/{branch_name}:refs/heads/{branch_name}")
            if push.returncode == 0:
                pushed_branches.append(branch_name)
            elif system_logger:
                system_logger.warning(f"apply-known: failed to push {branch_name}", {"repo": repo, "error": push.stderr})

    # Clean up temp fetched refs
    _git(workspace, "for-each-ref", "--format=%(refname)", "refs/fetched/")

    # Checkout the primary branch on workspace (for web UI)
    preferred = primary_branch or (list(branch_refs.keys())[0] if branch_refs else "master")
    _git(workspace, "checkout", "-f", preferred)

    if system_logger:
        system_logger.info("apply-known result", {"repo": repo, "commit": commit, "branches": pushed_branches})

    return {
        "success": True,
        "repo": repo,
        "commit": commit,
        "branches": pushed_branches,
        "message": f"Applied known commit ({len(pushed_branches)} branch(es): {', '.join(pushed_branches)})",
    }


@router.post("/documents/upload")
async def sync_upload_and_apply(repo: str = Form(...), attachment: UploadFile = File(...)):
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo_name = (repo or "").strip()
    if not repo_name:
        raise HTTPException(400, "Repository name is required")

    if repo_name not in repo_manager.get_repos():
        return {"success": False, "message": f"Repository '{repo_name}' not found", "repo": repo_name}

    filename = attachment.filename or ""
    inferred = _infer_repo_from_dump_filename(filename)
    if inferred and inferred != repo_name:
        return {
            "success": False,
            "repo": repo_name,
            "message": f"Uploaded filename indicates repo '{inferred}' but request repo is '{repo_name}'",
        }

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
        return result


@router.get("/documents/list")
async def sync_refs(repo: str = Query(...)):
    """Get all branch tips from the server workspace."""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo_name = (repo or "").strip()
    if not repo_name:
        raise HTTPException(400, "Repository name is required")
    if repo_name not in repo_manager.get_repos():
        raise HTTPException(404, "Repository not found")

    workspace = repo_manager._get_workspace_path(repo_name)
    if not workspace.exists():
        raise HTTPException(404, "Workspace not found")

    refs_proc = _git(workspace, "for-each-ref", "--format=%(refname:short) %(objectname)", "refs/heads/")
    refs = {}
    if refs_proc.returncode == 0:
        for line in (refs_proc.stdout or "").strip().split("\n"):
            if not line:
                continue
            parts = line.split(" ", 1)
            if len(parts) == 2:
                refs[parts[0]] = parts[1]

    head_proc = _git(workspace, "rev-parse", "HEAD")
    head = (head_proc.stdout or "").strip() if head_proc.returncode == 0 else ""

    return {
        "success": True,
        "repo": repo_name,
        "head": head,
        "refs": refs
    }


@router.post("/documents/export")
def sync_export_dump(repo: str = Form(...), since: Optional[str] = Form(None)):
    # NOTE: intentionally a sync `def` (not async). The body does heavy blocking
    # work (git bundle, encryption, base64 of a potentially large repo). As a
    # sync handler Starlette runs it in a threadpool, keeping the event loop free
    # so keep-alive/connection handling stays healthy and the client can finish
    # reading the response instead of hitting "channel was closed".
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo_name = (repo or "").strip()
    if not repo_name:
        raise HTTPException(400, "Repository name is required")
    if repo_name not in repo_manager.get_repos():
        raise HTTPException(404, "Repository not found")

    workspace = repo_manager._get_workspace_path(repo_name)
    if not workspace.exists():
        raise HTTPException(404, "Workspace not found")

    head_proc = _git(workspace, "rev-parse", "HEAD")
    if head_proc.returncode != 0:
        raise HTTPException(400, "Repository has no commits")
    head = (head_proc.stdout or "").strip()

    with tempfile.TemporaryDirectory(prefix="idea-sync-") as tmp:
        tmp_dir = Path(tmp)
        bundle_path = tmp_dir / "export.bundle"

        if since:
            check_since = _git(workspace, "cat-file", "-e", f"{since}^{{commit}}")
            if check_since.returncode == 0:
                bundle_proc = _git(workspace, "bundle", "create", str(bundle_path), f"^{since}", "--all")
            else:
                bundle_proc = _git(workspace, "bundle", "create", str(bundle_path), "--all")
        else:
            bundle_proc = _git(workspace, "bundle", "create", str(bundle_path), "--all")

        if bundle_proc.returncode != 0:
            if "Refusing to create empty bundle" in bundle_proc.stderr:
                return {"status": "no_content", "head": head, "repo": repo_name}
            raise HTTPException(500, bundle_proc.stderr.strip() or "Failed to create bundle")

        password = os.getenv("SYNC_PASSWORD", "")
        if not password:
            raise HTTPException(500, "SYNC_PASSWORD not configured in environment")

        ts = datetime.now().strftime("%Y%m%d_%H%M")
        import uuid as _uuid
        dump_path = tmp_dir / f".tmp_{_uuid.uuid4().hex[:8]}"
        try:
            encrypt_bundle_to_dump(bundle_path, dump_path, password)
        except Exception as e:
            raise HTTPException(500, f"Failed to create sync package: {e}")

        import base64
        return {
            "status": "ok",
            "head": head,
            "repo": repo_name,
            "filename": dump_path.name,
            "data": base64.b64encode(dump_path.read_bytes()).decode("ascii"),
        }


@router.post("/documents/preview")
async def sync_preview_pull(request: PreviewPullRequest):
    """Lightweight preview: are there incoming commits to pull?"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    repo_name = (request.repo or "").strip()
    if not repo_name:
        raise HTTPException(400, "Repository name is required")

    if repo_name not in repo_manager.get_repos():
        return {
            "success": True,
            "repo": repo_name,
            "remoteHead": None,
            "hasUpdates": False,
            "reason": "repo-not-found",
        }

    workspace = repo_manager._get_workspace_path(repo_name)
    if not workspace.exists():
        return {
            "success": True,
            "repo": repo_name,
            "remoteHead": None,
            "hasUpdates": False,
            "reason": "workspace-not-found",
        }

    head_proc = _git(workspace, "rev-parse", "HEAD")
    if head_proc.returncode != 0:
        return {
            "success": True,
            "repo": repo_name,
            "remoteHead": None,
            "hasUpdates": False,
            "reason": "no-commits",
        }

    head = (head_proc.stdout or "").strip()
    since = (request.since or "").strip()

    if not since:
        return {
            "success": True,
            "repo": repo_name,
            "remoteHead": head,
            "hasUpdates": True,
            "reason": "full-sync-needed",
        }

    if since == head:
        return {
            "success": True,
            "repo": repo_name,
            "remoteHead": head,
            "hasUpdates": False,
            "reason": "no-new-commits",
        }

    check_since = _git(workspace, "cat-file", "-e", f"{since}^{{commit}}")
    if check_since.returncode != 0:
        return {
            "success": True,
            "repo": repo_name,
            "remoteHead": head,
            "hasUpdates": True,
            "reason": "since-not-found",
        }

    return {
        "success": True,
        "repo": repo_name,
        "remoteHead": head,
        "hasUpdates": True,
        "reason": "ahead",
    }


@router.post("/documents/preview-details")
async def sync_preview_pull_details(request: PreviewPullDetailsRequest):
    """Get commit list and diffstat for incoming changes"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager not initialized")

    repo_name = (request.repo or "").strip()
    if repo_name not in repo_manager.get_repos():
        raise HTTPException(404, "Repository not found")

    workspace = repo_manager._get_workspace_path(repo_name)
    if not workspace.exists():
        raise HTTPException(404, "Workspace not found")

    since = (request.since or "").strip()
    rev_range = f"{since}..HEAD" if since else "HEAD"

    # Get commits
    log_proc = _git(workspace, "log", "--oneline", "-n", "30", rev_range)
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
    diff_proc = _git(workspace, "diff", "--stat", rev_range)
    diffstat = diff_proc.stdout or ""

    return {
        "success": True,
        "repo": repo_name,
        "commits": commits,
        "diffstat": diffstat
    }


# ============ SYNC ============


@router.post("/documents/process")
async def sync_workspace():
    """Sync workspace from bare repo (after push from work)"""
    global state
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    result = repo_manager.sync_workspace()
    if result["success"]:
        state.status = "processing"
        state.last_sync_time = datetime.now()
        _init_git_workspace()
    return result


@router.post("/notify-push")
async def notify_push():
    """Called when push is received"""
    global state
    state.status = "ready"
    return {"success": True, "status": state.status}


# ============ MAIN WORKFLOW BUTTONS ============


@router.post("/system/open-editor")
async def open_explorer():
    """Open current workspace in system explorer"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    workspace = repo_manager._get_workspace_path(repo_manager.current_repo)
    abs_path = str(workspace.absolute())

    if not workspace.exists():
        raise HTTPException(404, "Workspace не найден")

    try:
        os.startfile(abs_path)
        return {"success": True, "path": abs_path}
    except Exception as e:
        raise HTTPException(500, f"Не удалось открыть проводник: {str(e)}")


@router.post("/git/save-and-sync")
async def save_and_sync(message: Optional[str] = Query("Sync from Home")):
    """Commit all changes to prepare for pull on work PC"""
    global state, git_workspace

    if not git_workspace:
        _init_git_workspace()

    if not git_workspace:
        raise HTTPException(500, "Git workspace не инициализирован")

    if system_logger:
        system_logger.info("Инициирована подготовка к работе")

    # Just commit changes. Since it's a non-bare repo,
    # work PC will pull these commits.
    result = git_workspace.commit_all(message)

    if result["success"]:
        state.status = "ready"
        state.last_sync_time = datetime.now()
        if system_logger:
            system_logger.info("Изменения подготовлены для работы")
    else:
        if system_logger:
            system_logger.error("Не удалось подготовить изменения", {"error": result.get("message")})

    return result


@router.get("/git/changes")
async def get_changes():
    """Get list of uncommitted changes"""
    global git_workspace

    if not git_workspace:
        _init_git_workspace()

    if not git_workspace:
        return {"has_changes": False, "changes": [], "change_count": 0}

    return git_workspace.get_status()


@router.get("/git/diff")
async def get_diff(file: Optional[str] = None):
    """Get git diff for specific file"""
    global git_workspace

    if not git_workspace:
        _init_git_workspace()

    if not git_workspace:
        raise HTTPException(500, "Git workspace не инициализирован")

    return git_workspace.get_diff(file)


@router.get("/git/commit/{commit_hash}")
async def get_commit_details(commit_hash: str):
    """Get specific commit details"""
    global git_workspace

    if not git_workspace:
        _init_git_workspace()

    if not git_workspace:
        raise HTTPException(500, "Git workspace не инициализирован")

    return git_workspace.get_commit_details(commit_hash)


# ============ FILES ============


@router.get("/files")
async def get_files(repo: Optional[str] = None):
    if not repo_manager:
        return {"files": []}
    return {"files": repo_manager.get_file_tree(repo)}


@router.post("/editor/open")
async def open_file(file: str = Query(..., description="Relative file path")):
    """Open specific file in Cursor/VS Code (Alias for frontend compatibility)"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    abs_path = repo_manager.get_absolute_path(file)

    if not os.path.exists(abs_path):
        if system_logger:
            system_logger.error("Файл не найден", {"file": file})
        raise HTTPException(404, f"Файл не найден: {file}")

    try:
        # Try Cursor first
        subprocess.Popen(
            ["cursor", abs_path],
            shell=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if system_logger:
            system_logger.info("Файл открыт в редакторе", {"file": file, "editor": "cursor"})
        return {"success": True, "editor": "cursor", "path": abs_path}
    except Exception:
        try:
            subprocess.Popen(
                ["code", abs_path],
                shell=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            if system_logger:
                system_logger.info("Файл открыт в редакторе", {"file": file, "editor": "code"})
            return {"success": True, "editor": "code", "path": abs_path}
        except Exception:
            os.startfile(abs_path)
            if system_logger:
                system_logger.info("Файл открыт в приложении по умолчанию", {"file": file})
            return {"success": True, "editor": "default", "path": abs_path}


@router.get("/open")
async def open_file_legacy(file: str = Query(..., description="Relative file path")):
    """Legacy open file endpoint"""
    return await open_file(file)


@router.get("/file/view")
async def view_file(file: str = Query(..., description="Relative file path")):
    """Get file content for viewing in browser"""
    return await _get_file_content(file)


@router.get("/files/content")
async def get_file_content(path: str = Query(..., description="Relative file path")):
    """Get file content (alias for frontend compatibility)"""
    return await _get_file_content(path)


async def _get_file_content(file: str):
    """Internal function to get file content"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    abs_path = repo_manager.get_absolute_path(file)

    if not os.path.exists(abs_path):
        raise HTTPException(404, f"Файл не найден: {file}")

    if os.path.isdir(abs_path):
        raise HTTPException(400, "Невозможно просмотреть директорию")

    # Handle binary files (images, icons)
    ext = os.path.splitext(file)[1].lower()
    binary_extensions = {
        ".png",
        ".jpg",
        ".jpeg",
        ".gif",
        ".ico",
        ".pdf",
        ".zip",
        ".exe",
    }

    if ext in binary_extensions:
        import base64

        try:
            with open(abs_path, "rb") as f:
                content_bytes = f.read()

            file_type = "image" if ext != ".pdf" else "pdf"

            return {
                "success": True,
                "path": file,
                "content": base64.b64encode(content_bytes).decode("utf-8"),
                "is_binary": True,
                "type": file_type,
                "extension": ext,
                "size": len(content_bytes),
                "metadata": {
                    "path": file,
                    "name": os.path.basename(file),
                    "size": len(content_bytes),
                },
            }
        except Exception as e:
            raise HTTPException(500, f"Ошибка чтения бинарного файла: {str(e)}")

    try:
        # Try to read as text
        with open(abs_path, "r", encoding="utf-8") as f:
            content = f.read()

        # Detect file type
        file_type = "text"
        if ext in [".md", ".markdown"]:
            file_type = "markdown"
        elif ext in [
            ".py",
            ".js",
            ".ts",
            ".jsx",
            ".tsx",
            ".java",
            ".cpp",
            ".c",
            ".h",
            ".cs",
            ".go",
            ".rs",
            ".rb",
            ".php",
        ]:
            file_type = "code"
        elif ext in [".json", ".xml", ".yaml", ".yml", ".toml"]:
            file_type = "data"
        elif ext in [".html", ".htm", ".css", ".scss", ".sass"]:
            file_type = "web"

        return {
            "success": True,
            "path": file,
            "content": content,
            "is_binary": False,
            "type": file_type,
            "extension": ext,
            "size": len(content),
            "metadata": {
                "path": file,
                "name": os.path.basename(file),
                "size": len(content),
            },
        }
    except UnicodeDecodeError:
        # Fallback for unexpected binary content
        import base64

        with open(abs_path, "rb") as f:
            content_bytes = f.read()
        return {
            "success": True,
            "path": file,
            "content": base64.b64encode(content_bytes).decode("utf-8"),
            "is_binary": True,
            "type": "binary",
            "extension": ext,
            "size": len(content_bytes),
            "metadata": {
                "path": file,
                "name": os.path.basename(file),
                "size": len(content_bytes),
            },
        }
    except Exception as e:
        raise HTTPException(500, f"Ошибка чтения файла: {str(e)}")


@router.get("/file/pdf")
async def view_pdf(file: str = Query(..., description="Relative file path")):
    """Get PDF file as base64 for viewing in browser"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    abs_path = repo_manager.get_absolute_path(file)

    if not os.path.exists(abs_path):
        raise HTTPException(404, f"Файл не найден: {file}")

    if not file.lower().endswith(".pdf"):
        raise HTTPException(400, "Это не PDF файл")

    try:
        import base64

        with open(abs_path, "rb") as f:
            content = f.read()

        return {
            "success": True,
            "path": file,
            "content": base64.b64encode(content).decode("utf-8"),
            "size": len(content),
        }
    except Exception as e:
        raise HTTPException(500, f"Ошибка чтения PDF: {str(e)}")


@router.get("/commits")
async def get_commits(repo: Optional[str] = None):
    if not repo_manager:
        return {"commits": []}
    return {"commits": repo_manager.get_recent_commits(repo)}


@router.get("/metrics")
async def get_metrics():
    from app.core.system_monitor import SystemMonitor

    storage_path = config.get("storage_path", "storage")
    return SystemMonitor.get_metrics(str(storage_path))


@router.get("/logs")
async def get_logs(limit: int = 50):
    if system_logger:
        return {"logs": system_logger.get_recent_logs(limit)}
    return {"logs": []}


@router.get("/context/status")
async def get_context_status():
    return {"indexed": False, "size": 0}


# ============ GLOBAL SEARCH ============


@router.get("/search")
async def search_repo(
    q: str = Query(..., description="Query string to search for"),
    repo: Optional[str] = Query(None, description="Repository name"),
    limit: int = Query(100, description="Max results"),
):
    """
    Search for code in the repository using 'git grep'.
    """
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")

    # Resolve repository
    repo_name = repo or repo_manager.current_repo
    if not repo_name or repo_name == "default":
        # Cannot search default/empty repo easily
        return {"matches": [], "query": q, "repo": repo_name}

    workspace_path = repo_manager._get_workspace_path(repo_name)
    if not workspace_path.exists():
        raise HTTPException(404, f"Workspace репозитория '{repo_name}' не найден")

    try:
        # Run git grep
        # -n: show line numbers
        # -I: ignore binary files
        # --heading: group matches by file (easier parsing? actually simpler with one line per match for now)
        # --break: print newline between files
        # We stick to simple parsing: filename:line:content

        # NOTE: git grep requires being inside a git repo or using --git-dir/--work-tree
        # But our workspace IS a git repo (checked out).

        # Security check: strictly alphanumeric query is too restrictive for code.
        # But we must prevent command injection. subprocess with shell=False does this.

        cmd = ["git", "grep", "-n", "-I", str(q)]

        # On Windows, we need to handle encoding carefully
        result = subprocess.run(
            cmd, cwd=str(workspace_path), capture_output=True, text=True, encoding="utf-8", errors="replace"
        )

        if result.returncode == 1:
            # grep found nothing
            return {"matches": [], "query": q, "repo": repo_name, "count": 0}
        elif result.returncode > 1:
            # git grep failed
            if system_logger:
                system_logger.error("Git grep завершился с ошибкой", {"error": result.stderr})
            # Don't crash, just return empty with error hint?
            # Or maybe it's just invalid regex.
            return {
                "matches": [],
                "error": "Поиск не удался (неверное регулярное выражение?)",
                "details": result.stderr,
            }

        # Parse output
        matches = []
        lines = result.stdout.splitlines()
        for line in lines[:limit]:
            parts = line.split(":", 2)
            if len(parts) >= 3:
                filename = parts[0]
                linenum = int(parts[1])
                content = parts[2]

                matches.append(
                    {
                        "file": filename,
                        "line": linenum,
                        "content": content.strip(),
                        # Preview could be context, but git grep default is just the line
                    }
                )

        return {"matches": matches, "query": q, "repo": repo_name, "count": len(matches), "total_found": len(lines)}

    except Exception as e:
        if system_logger:
            system_logger.error("Исключение при поиске", {"error": str(e)})
        raise HTTPException(500, f"Поиск не удался: {str(e)}")


# ============ SHARED FOLDERS ============


@router.get("/shared/folders")
async def get_shared_folders():
    """Get list of shared folders with sizes"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    try:
        folders = shared_manager.get_folders()
        return {"success": True, "folders": folders}
    except Exception as e:
        if system_logger:
            system_logger.error("Не удалось получить список общих папок", {"error": str(e)})
        raise HTTPException(500, f"Не удалось получить список общих папок: {str(e)}")


class CreateFolderRequest(BaseModel):
    name: str


@router.post("/shared/folders")
async def create_shared_folder(request: CreateFolderRequest):
    """Create a new shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.create_folder(request.name)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Создана общая папка", {"name": request.name})

    return result


@router.delete("/shared/folders/{name}")
async def delete_shared_folder(name: str):
    """Delete a shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.delete_folder(name)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Удалена общая папка", {"name": name})

    return result


@router.get("/shared/files")
async def get_shared_files(folder: str = Query(...), subfolder: Optional[str] = Query(None)):
    """Get files in shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.get_files(folder, subfolder)
    if not result["success"]:
        raise HTTPException(404, result["message"])

    return result


@router.post("/shared/upload")
async def upload_shared_file(
    folder: str = Form(...),
    file: UploadFile = File(...),
    subfolder: Optional[str] = Form(None),
    tags: Optional[str] = Form(None),
    description: Optional[str] = Form(""),
):
    """Upload file to shared folder using async streaming"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    try:
        # Build file path
        file_path = file.filename
        if subfolder:
            file_path = f"{subfolder}/{file.filename}"

        # Parse tags
        tag_list = []
        if tags:
            tag_list = [t.strip() for t in tags.split(",") if t.strip()]

        # Use streaming method to avoid loading entire file into RAM
        result = await shared_manager.save_upload_file_streaming(folder, file_path, file, tag_list, description)
        if not result["success"]:
            raise HTTPException(400, result["message"])

        if system_logger:
            system_logger.info("Файл загружен", {"folder": folder, "file": file.filename})

        return result
    except Exception as e:
        if system_logger:
            system_logger.error("Не удалось загрузить файл", {"error": str(e)})
        raise HTTPException(500, f"Не удалось загрузить файл: {str(e)}")


@router.delete("/shared/files")
async def delete_shared_file(folder: str = Query(...), path: str = Query(...)):
    """Delete file from shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.delete_file(folder, path)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Файл удален", {"folder": folder, "path": path})

    return result


@router.get("/shared/history")
async def get_file_history(folder: str = Query(...), path: str = Query(...)):
    """Get Git history for a file"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.get_file_history(folder, path)
    if not result["success"]:
        raise HTTPException(404, result["message"])

    return result


class RestoreFileRequest(BaseModel):
    folder: str
    path: str
    commit_hash: str


@router.post("/shared/restore")
async def restore_shared_file(request: RestoreFileRequest):
    """Restore file to specific commit"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.restore_file(request.folder, request.path, request.commit_hash)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info(
            "Файл восстановлен", {"folder": request.folder, "path": request.path, "commit": request.commit_hash}
        )

    return result


class CreateSubfolderRequest(BaseModel):
    folder: str
    path: str


@router.post("/shared/subfolder")
async def create_shared_subfolder(request: CreateSubfolderRequest):
    """Create subfolder in shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.create_subfolder(request.folder, request.path)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Создана подпапка", {"folder": request.folder, "path": request.path})

    return result


@router.get("/shared/search")
async def search_shared_files(folder: str = Query(...), query: str = Query(...)):
    """Search files in shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    result = shared_manager.search_files(folder, query)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    return result


class BulkDeleteRequest(BaseModel):
    folder: str
    paths: List[str]


@router.post("/shared/bulk-delete")
async def bulk_delete_shared_files(request: BulkDeleteRequest):
    """Delete multiple files at once"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    result = shared_manager.bulk_delete_files(request.folder, request.paths)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Bulk delete completed", {"folder": request.folder, "count": len(request.paths)})

    return result


@router.get("/shared/download")
async def download_shared_file(folder: str = Query(...), path: str = Query(...)):
    """Download file from shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    result = shared_manager.get_file_content(folder, path)
    if not result["success"]:
        raise HTTPException(404, result["message"])

    # Return file as download
    return Response(
        content=result["content"],
        media_type="application/octet-stream",
        headers={"Content-Disposition": f'attachment; filename="{result["filename"]}"'},
    )


class UpdateTagsRequest(BaseModel):
    folder: str
    path: str
    tags: List[str]


@router.post("/shared/tags")
async def update_file_tags(request: UpdateTagsRequest):
    """Update file tags"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    result = shared_manager.update_tags(request.folder, request.path, request.tags)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Tags updated", {"folder": request.folder, "path": request.path})

    return result


@router.get("/shared/metadata")
async def get_file_metadata(folder: str = Query(...), path: str = Query(...)):
    """Get file metadata"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    try:
        metadata = shared_manager._get_file_metadata(folder, path)
        return {"success": True, "metadata": metadata}
    except Exception as e:
        if system_logger:
            system_logger.error("Failed to get metadata", {"error": str(e)})
        raise HTTPException(500, f"Failed to get metadata: {str(e)}")


@router.post("/documents/apply")
async def apply_sync_bundle():
    """Apply latest sync dump to workspace with conflict resolution and repo verification"""
    if not shared_manager or not repo_manager:
        raise HTTPException(500, "Managers не инициализированы")

    try:
        import random
        import asyncio

        # Find latest dump in shared folders.
        # We prefer common well-known sync folders, but also scan all existing shared folders
        # so users can pick their own (e.g. "onyx").
        preferred = ["work-sync", "sync", "backups"]
        all_folders = []
        try:
            all_folders = [f.get("name") for f in (shared_manager.get_folders() or []) if f.get("name")]
        except Exception:
            all_folders = []

        # Folder names are case-sensitive on some filesystems.
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
# Old routes preserved for transition. Forward internally to new handlers.

@router.get("/capabilities")
async def _alias_capabilities():
    return await capabilities()

@router.get("/sync/password-probe")
async def _alias_password_probe():
    return await sync_password_probe()

@router.post("/sync/has-commits")
async def _alias_has_commits(request: SyncHasCommitsRequest):
    return await sync_has_commits(request)

@router.post("/sync/apply-known")
async def _alias_apply_known(request: SyncApplyKnownRequest):
    return await sync_apply_known(request)

@router.post("/sync/upload-and-apply")
async def _alias_upload_and_apply(repo: str = Form(...), dump_file: UploadFile = File(...)):
    return await sync_upload_and_apply(repo=repo, attachment=dump_file)

@router.get("/sync/refs")
async def _alias_refs(repo: str = Query(...)):
    return await sync_refs(repo=repo)

@router.post("/sync/export-dump")
async def _alias_export_dump(repo: str = Form(...), since: Optional[str] = Form(None)):
    return await sync_export_dump(repo=repo, since=since)

@router.post("/sync/preview-pull")
async def _alias_preview_pull(request: PreviewPullRequest):
    return await sync_preview_pull(request)

@router.post("/sync/preview-pull-details")
async def _alias_preview_pull_details(request: PreviewPullDetailsRequest):
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
