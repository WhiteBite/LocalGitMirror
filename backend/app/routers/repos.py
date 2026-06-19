"""Repos, Git server and Storage config router."""

import os
import re
import subprocess
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

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


class RepoCreateRequest(BaseModel):
    name: str


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


@router.post("/repos/create")
async def create_repo(request: RepoCreateRequest, raw_request: Request):
    """Create a new repository"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager не инициализирован")
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


@router.post("/config/storage")
async def set_storage_path(request: StoragePathRequest):
    """Change storage directory"""
    global git_handler, repo_manager, git_workspace

    new_path = Path(request.path)
    try:
        new_path.mkdir(parents=True, exist_ok=True)
    except Exception as e:
        raise HTTPException(400, f"Невозможно создать директорию: {str(e)}")

    if git_handler and git_handler.is_running:
        git_handler.stop()

    config["storage_path"] = new_path

    from app.core.git_handler import GitHandler
    from app.core.git_utils import GitWorkspace
    from app.core.repo_manager import RepoManager

    git_handler = GitHandler(storage_path=new_path, port=config.get("git_port", 8081))
    repo_manager = RepoManager(new_path)

    workspace = repo_manager._get_workspace_path(repo_manager.current_repo)
    bare = repo_manager._get_bare_path(repo_manager.current_repo)
    git_workspace = GitWorkspace(workspace, bare)

    env_path = Path(__file__).parent.parent.parent.parent / ".env"
    if env_path.exists():
        content = env_path.read_text()
        content = re.sub(r"STORAGE_PATH=.*", f"STORAGE_PATH={request.path}", content)
        env_path.write_text(content)

    return {"success": True, "storage_path": str(new_path)}


@router.post("/config/browse")
async def browse_folder():
    """Open folder browser dialog (Windows)"""
    try:
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
