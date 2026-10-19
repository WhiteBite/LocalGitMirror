"""Repos, Git server and Storage config router."""

import subprocess
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from app.core.sync_envelope import _decrypt_params, _sync_password
from app.routers._rid import resolve_repo_identifier

router = APIRouter(prefix="/api", tags=["repos"])

# Injected from main.py
git_handler = None
repo_manager = None
git_workspace = None
config = {}
system_logger = None


# ============ MODELS ============


class RepoSelectRequest(BaseModel):
    repo: str


class CollectionCreateRequest(BaseModel):
    rid: str = ""
    name: str = ""
    e: Optional[str] = None
    k: Optional[str] = None


class StoragePathRequest(BaseModel):
    path: str


# ============ HELPERS ============


def _init_git_workspace():
    """Reinitialize git workspace for current repo"""
    global git_workspace
    if repo_manager:
        from app.core.git_utils import GitWorkspace

        workspace = repo_manager._get_workspace_path(repo_manager.current_repo)
        bare = repo_manager._get_bare_path(repo_manager.current_repo)
        git_workspace = GitWorkspace(workspace, bare)


# ============ GIT SERVER ============


@router.post("/git/start")
def start_git():
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
def stop_git():
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


@router.post("/documents/collection")
def create_collection(request: CollectionCreateRequest, raw_request: Request):
    """Create a new repository.

    Accepts either ``rid`` (obfuscated repo identifier) or ``name`` (plain
    repo name). The rid is resolved to a repo name via the shared helper;
    a plain name is used as-is. A rid that resolves to no existing repo may
    carry a sealed envelope (``e`` + optional ``k``) whose inner ``repo``
    field names the repository to create.
    """
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")
    rid = (request.rid or "").strip()
    # resolve_repo_identifier passes unknown values through; a differing result means the rid matched
    resolved = resolve_repo_identifier(rid, repo_manager)
    from_envelope = False
    if rid and resolved != rid:
        create_name = resolved
    elif request.e:
        params = _decrypt_params(request.e, _sync_password(), request.k)
        repo_param = params.get("repo")
        create_name = repo_param.strip() if isinstance(repo_param, str) else ""
        if not create_name:
            raise HTTPException(400, "Missing 'repo' in request envelope")
        from_envelope = True
    elif (request.name or "").strip():
        create_name = resolve_repo_identifier((request.name or "").strip(), repo_manager)
    elif rid:
        create_name = rid
    else:
        raise HTTPException(400, "Missing 'rid' or 'name'")
    author_name = raw_request.headers.get("X-User-Name")
    author_email = raw_request.headers.get("X-User-Email")
    result = repo_manager.create_repo(create_name, author_name=author_name, author_email=author_email)
    if result["success"]:
        if from_envelope:
            result = dict(result, message="Репозиторий создан")
        if system_logger:
            system_logger.info(f"Создан репозиторий: {create_name}")
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
    if request.repo not in repo_manager.get_repos():
        raise HTTPException(400, f"Репозиторий не найден: {request.repo}")
    repo_manager.current_repo = request.repo
    _init_git_workspace()
    if system_logger:
        system_logger.info(f"Выбран репозиторий: {request.repo}")
    return {"success": True, "current": repo_manager.current_repo}


@router.post("/repos/delete")
def delete_repo(request: RepoSelectRequest):
    """Delete a repository"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")
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


# ============ STORAGE CONFIG ============


_ENV_PATH = Path(__file__).resolve().parents[3] / ".env"


def _persist_storage_path(new_value: str) -> None:
    content = _ENV_PATH.read_text(encoding="utf-8") if _ENV_PATH.exists() else ""
    lines = [
        f"STORAGE_PATH={new_value}" if line.startswith("STORAGE_PATH=") else line
        for line in content.splitlines()
    ]
    if not any(line.startswith("STORAGE_PATH=") for line in lines):
        lines.append(f"STORAGE_PATH={new_value}")
    _ENV_PATH.parent.mkdir(parents=True, exist_ok=True)
    _ENV_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")


@router.post("/config/storage")
def set_storage_path(request: StoragePathRequest):
    """Persist a new storage directory; it takes effect only after a server restart.

    Live managers (repo/git/shared, v3 keys) must NOT be rebound here: rebinding
    a subset of routers splits the server's state while still reporting success.
    """
    new_path = Path(request.path)
    try:
        new_path.mkdir(parents=True, exist_ok=True)
    except Exception as e:
        raise HTTPException(400, f"Невозможно создать директорию: {str(e)}")

    _persist_storage_path(request.path)

    active = str(repo_manager.storage_path) if repo_manager else str(config.get("storage_path", ""))
    if system_logger:
        system_logger.info("Путь хранения изменен; вступит в силу после перезапуска", {"new": request.path})
    return {
        "success": True,
        "storage_path": str(new_path),
        "restart_required": True,
        "active_storage_path": active,
    }


@router.post("/config/browse")
def browse_folder():
    """Open folder browser dialog (Windows)"""
    try:
        ps_script = """
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
        Add-Type -AssemblyName System.Windows.Forms
        $browser = New-Object System.Windows.Forms.FolderBrowserDialog
        $browser.Description = "Выберите папку для хранения"
        $browser.ShowNewFolderButton = $true
        if ($browser.ShowDialog() -eq "OK") { $browser.SelectedPath }
        """
        result = subprocess.run(
            ["powershell", "-Command", ps_script], capture_output=True, text=True,
            encoding="utf-8", errors="replace",
        )
        path = result.stdout.strip()
        if path:
            return {"success": True, "path": path}
        return {"success": False, "path": ""}
    except Exception as e:
        raise HTTPException(500, f"Ошибка: {str(e)}")
