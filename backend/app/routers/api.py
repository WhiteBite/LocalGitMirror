"""
LocalGitMirror API Router
"""

import os
import subprocess
from datetime import datetime
from typing import List, Optional

from fastapi import APIRouter, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel

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
        "web_port": config.get("web_port", 8443),
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
        raise HTTPException(500, "Git handler not initialized")
    success = git_handler.start()
    if system_logger:
        if success:
            system_logger.info("Git server started via API", {"port": config.get("git_port")})
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


class RepoCreateRequest(BaseModel):
    name: str


class FileSaveRequest(BaseModel):
    path: str
    content: str


@router.post("/file/save")
async def save_file(request: FileSaveRequest):
    """Save file content to disk"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager not initialized")

    abs_path = repo_manager.get_absolute_path(request.path)

    try:
        # Create directories if they don't exist
        os.makedirs(os.path.dirname(abs_path), exist_ok=True)

        with open(abs_path, "w", encoding="utf-8") as f:
            f.write(request.content)

        if system_logger:
            system_logger.info("File saved", {"path": request.path})

        return {"success": True, "path": request.path}
    except Exception as e:
        if system_logger:
            system_logger.error("Failed to save file", {"error": str(e)})
        raise HTTPException(500, f"Failed to save file: {str(e)}")


@router.post("/repos/create")
async def create_repo(request: RepoCreateRequest):
    """Create a new repository"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager not initialized")

    result = repo_manager.create_repo(request.name)
    if result["success"]:
        if system_logger:
            system_logger.info(f"Created repository: {request.name}")
    else:
        if system_logger:
            system_logger.error(f"Failed to create repo: {result['message']}")
        raise HTTPException(400, result["message"])

    return result


@router.post("/repos/select")
async def select_repo(request: RepoSelectRequest):
    """Select a repository as active"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager not initialized")

    repo_manager.current_repo = request.repo
    _init_git_workspace()

    if system_logger:
        system_logger.info(f"Selected repository: {request.repo}")

    return {"success": True, "current": repo_manager.current_repo}


@router.post("/repos/delete")
async def delete_repo(request: RepoSelectRequest):
    """Delete a repository"""
    if not repo_manager:
        raise HTTPException(500, "Repo manager not initialized")

    # Prevent deleting current repo if possible, or handle it
    if repo_manager.current_repo == request.repo:
        repo_manager.current_repo = "default"

    result = repo_manager.delete_repo(request.repo)
    if result["success"]:
        if system_logger:
            system_logger.info(f"Deleted repository: {request.repo}")
    else:
        if system_logger:
            system_logger.error(f"Failed to delete repo: {result['message']}")
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
        state.last_sync_time = datetime.now()
        if system_logger:
            system_logger.info("Changes prepared for work")
    else:
        if system_logger:
            system_logger.error("Failed to prepare changes", {"error": result.get("message")})

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
        raise HTTPException(500, "Git workspace not initialized")

    return git_workspace.get_diff(file)


@router.get("/git/commit/{commit_hash}")
async def get_commit_details(commit_hash: str):
    """Get specific commit details"""
    global git_workspace

    if not git_workspace:
        _init_git_workspace()

    if not git_workspace:
        raise HTTPException(500, "Git workspace not initialized")

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
            system_logger.info("File opened in editor", {"file": file, "editor": "cursor"})
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
                system_logger.info("File opened in editor", {"file": file, "editor": "code"})
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


@router.post("/system/panic")
async def panic_mode():
    """Emergency shutdown"""
    if system_logger:
        system_logger.critical("PANIC BUTTON PRESSED. TERMINATING.")

    import os
    import threading
    import time

    def kill_self():
        time.sleep(0.5)
        os._exit(0)

    threading.Thread(target=kill_self, daemon=True).start()
    return {"message": "Terminating..."}


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
        raise HTTPException(500, "Repo manager not initialized")

    # Resolve repository
    repo_name = repo or repo_manager.current_repo
    if not repo_name or repo_name == "default":
        # Cannot search default/empty repo easily
        return {"matches": [], "query": q, "repo": repo_name}

    workspace_path = repo_manager._get_workspace_path(repo_name)
    if not workspace_path.exists():
        raise HTTPException(404, f"Repository '{repo_name}' workspace not found")

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
                system_logger.error("Git grep failed", {"error": result.stderr})
            # Don't crash, just return empty with error hint?
            # Or maybe it's just invalid regex.
            return {"matches": [], "error": "Search failed (invalid regex?)", "details": result.stderr}

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
            system_logger.error("Search exception", {"error": str(e)})
        raise HTTPException(500, f"Search failed: {str(e)}")


# ============ SHARED FOLDERS ============


@router.get("/shared/folders")
async def get_shared_folders():
    """Get list of shared folders with sizes"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    try:
        folders = shared_manager.get_folders()
        return {"success": True, "folders": folders}
    except Exception as e:
        if system_logger:
            system_logger.error("Failed to get shared folders", {"error": str(e)})
        raise HTTPException(500, f"Failed to get shared folders: {str(e)}")


class CreateFolderRequest(BaseModel):
    name: str


@router.post("/shared/folders")
async def create_shared_folder(request: CreateFolderRequest):
    """Create a new shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    result = shared_manager.create_folder(request.name)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Created shared folder", {"name": request.name})

    return result


@router.delete("/shared/folders/{name}")
async def delete_shared_folder(name: str):
    """Delete a shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    result = shared_manager.delete_folder(name)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Deleted shared folder", {"name": name})

    return result


@router.get("/shared/files")
async def get_shared_files(folder: str = Query(...), subfolder: Optional[str] = Query(None)):
    """Get files in shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

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
    """Upload file to shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    try:
        # Read file content
        content = await file.read()

        # Build file path
        file_path = file.filename
        if subfolder:
            file_path = f"{subfolder}/{file.filename}"

        # Parse tags
        tag_list = []
        if tags:
            tag_list = [t.strip() for t in tags.split(",") if t.strip()]

        result = shared_manager.save_file(folder, file_path, content, tag_list, description)
        if not result["success"]:
            raise HTTPException(400, result["message"])

        if system_logger:
            system_logger.info("File uploaded", {"folder": folder, "file": file.filename})

        return result
    except Exception as e:
        if system_logger:
            system_logger.error("Failed to upload file", {"error": str(e)})
        raise HTTPException(500, f"Failed to upload file: {str(e)}")


@router.delete("/shared/files")
async def delete_shared_file(folder: str = Query(...), path: str = Query(...)):
    """Delete file from shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    result = shared_manager.delete_file(folder, path)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("File deleted", {"folder": folder, "path": path})

    return result


@router.get("/shared/history")
async def get_file_history(folder: str = Query(...), path: str = Query(...)):
    """Get Git history for a file"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

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
        raise HTTPException(500, "Shared manager not initialized")

    result = shared_manager.restore_file(request.folder, request.path, request.commit_hash)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("File restored", {"folder": request.folder, "path": request.path, "commit": request.commit_hash})

    return result


class CreateSubfolderRequest(BaseModel):
    folder: str
    path: str


@router.post("/shared/subfolder")
async def create_shared_subfolder(request: CreateSubfolderRequest):
    """Create subfolder in shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    result = shared_manager.create_subfolder(request.folder, request.path)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Subfolder created", {"folder": request.folder, "path": request.path})

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
