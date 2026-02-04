import os
import subprocess
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel
from typing import Optional

router = APIRouter(prefix="/api", tags=["api"])

# Injected from main.py
git_handler = None
repo_manager = None
git_workspace = None
config = {}
system_logger = None


# State for "AI Processing" legend
class ServerState:
    status = "idle"  # idle, processing, ready
    last_push_time = None
    last_sync_time = None


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
        not current_git_workspace
        or current_git_workspace.workspace.name != repo_manager.current_repo
    ):
        from app.core.git_utils import GitWorkspace

        workspace = repo_manager._get_workspace_path(repo_manager.current_repo)
        bare = repo_manager._get_bare_path(repo_manager.current_repo)
        current_git_workspace = GitWorkspace(workspace, bare)

    if current_git_workspace:
        ws_status = current_git_workspace.get_status()
        has_changes = ws_status.get("has_changes", False)
        change_count = ws_status.get("change_count", 0)

    return {
        "git_running": git_handler.is_running if git_handler else False,
        "git_port": config.get("git_port", 8081),
        "local_ip": SystemMonitor.get_local_ip(),
        "current_repo": repo_manager.current_repo if repo_manager else "default",
        "workflow_status": state.status,
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
    from app.core.repo_manager import RepoManager
    from app.core.git_utils import GitWorkspace

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
        result = subprocess.run(
            ["powershell", "-Command", ps_script], capture_output=True, text=True
        )
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
        raise HTTPException(500, "Git handler not initialized")
    success = git_handler.start()
    if system_logger:
        if success:
            system_logger.info(
                "Git server started via API", {"port": config.get("git_port")}
            )
        else:
            system_logger.warning("Git server start failed via API")
    return {"success": success, "running": git_handler.is_running}


@router.post("/git/stop")
async def stop_git():
    if not git_handler:
        raise HTTPException(500, "Git handler not initialized")
    git_handler.stop()
    if system_logger:
        system_logger.info("Git server stopped via API")
    return {"success": True, "running": git_handler.is_running}


# ============ REPOS ============


@router.get("/repos")
async def get_repos():
    if not repo_manager:
        return {"repos": ["default"], "current": "default"}
    return {"repos": repo_manager.get_repos(), "current": repo_manager.current_repo}


@router.post("/repos/select")
async def select_repo(request: RepoSelectRequest):
    if not repo_manager:
        raise HTTPException(500, "Repo manager not initialized")
    repo_manager.current_repo = request.repo

    # Reinitialize git_workspace globally for this session
    global git_workspace
    workspace = repo_manager._get_workspace_path(request.repo)
    bare = repo_manager._get_bare_path(request.repo)
    from app.core.git_utils import GitWorkspace

    git_workspace = GitWorkspace(workspace, bare)

    return {"success": True, "current": repo_manager.current_repo}


def _init_git_workspace():
    """Reinitialize git workspace for current repo"""
    global git_workspace
    if repo_manager:
        from app.core.git_utils import GitWorkspace

        workspace = repo_manager._get_workspace_path(repo_manager.current_repo)
        bare = repo_manager._get_bare_path(repo_manager.current_repo)
        git_workspace = GitWorkspace(workspace, bare)


# ============ SYNC ============


@router.post("/sync")
async def sync_workspace():
    """Sync workspace from bare repo (after push from work)"""
    global state
    if not repo_manager:
        raise HTTPException(500, "Repo manager not initialized")

    result = repo_manager.sync_workspace()
    if result["success"]:
        state.status = "processing"
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
        raise HTTPException(500, "Repo manager not initialized")

    workspace = repo_manager._get_workspace_path(repo_manager.current_repo)
    abs_path = str(workspace.absolute())

    if not workspace.exists():
        raise HTTPException(404, "Workspace not found")

    try:
        os.startfile(abs_path)
        return {"success": True, "path": abs_path}
    except Exception as e:
        raise HTTPException(500, f"Failed to open explorer: {str(e)}")


@router.post("/git/save-and-sync")
async def save_and_sync(message: Optional[str] = Query("Sync from Home")):
    """Commit all changes to prepare for pull on work PC"""
    global state, git_workspace

    if not git_workspace:
        _init_git_workspace()

    if not git_workspace:
        raise HTTPException(500, "Git workspace not initialized")

    if system_logger:
        system_logger.info("Prepare for work initiated")

    # Just commit changes. Since it's a non-bare repo,
    # work PC will pull these commits.
    result = git_workspace.commit_all(message)

    if result["success"]:
        state.status = "ready"
        if system_logger:
            system_logger.info("Changes prepared for work")
    else:
        if system_logger:
            system_logger.error(
                "Failed to prepare changes", {"error": result.get("message")}
            )

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
        raise HTTPException(500, "Repo manager not initialized")

    abs_path = repo_manager.get_absolute_path(file)

    if not os.path.exists(abs_path):
        if system_logger:
            system_logger.error("File not found", {"file": file})
        raise HTTPException(404, f"File not found: {file}")

    try:
        # Try Cursor first
        subprocess.Popen(
            ["cursor", abs_path],
            shell=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if system_logger:
            system_logger.info(
                "File opened in editor", {"file": file, "editor": "cursor"}
            )
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
                system_logger.info(
                    "File opened in editor", {"file": file, "editor": "code"}
                )
            return {"success": True, "editor": "code", "path": abs_path}
        except Exception:
            os.startfile(abs_path)
            if system_logger:
                system_logger.info("File opened in default app", {"file": file})
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
        raise HTTPException(500, "Repo manager not initialized")

    abs_path = repo_manager.get_absolute_path(file)

    if not os.path.exists(abs_path):
        raise HTTPException(404, f"File not found: {file}")

    if os.path.isdir(abs_path):
        raise HTTPException(400, "Cannot view directory")

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
            raise HTTPException(500, f"Error reading binary file: {str(e)}")

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
        raise HTTPException(500, f"Error reading file: {str(e)}")


@router.get("/file/pdf")
async def view_pdf(file: str = Query(..., description="Relative file path")):
    """Get PDF file as base64 for viewing in browser"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager not initialized")

    abs_path = repo_manager.get_absolute_path(file)

    if not os.path.exists(abs_path):
        raise HTTPException(404, f"File not found: {file}")

    if not file.lower().endswith(".pdf"):
        raise HTTPException(400, "Not a PDF file")

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
        raise HTTPException(500, f"Error reading PDF: {str(e)}")


@router.get("/commits")
async def get_commits(repo: Optional[str] = None):
    if not repo_manager:
        return {"commits": []}
    return {"commits": repo_manager.get_recent_commits(repo)}


@router.get("/metrics")
async def get_metrics():
    from app.core.system_monitor import SystemMonitor

    return SystemMonitor.get_metrics()


@router.get("/logs")
async def get_logs(limit: int = 50):
    if system_logger:
        return {"logs": system_logger.get_recent_logs(limit)}
    return {"logs": []}


@router.get("/context/status")
async def get_context_status():
    return {"indexed": False, "size": 0}
