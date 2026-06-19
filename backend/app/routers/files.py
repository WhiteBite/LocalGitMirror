"""File browser + git workspace router."""

import base64
import os
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from app.routers.state import state

router = APIRouter(prefix="/api", tags=["files"])

# Injected from main.py
repo_manager = None
git_workspace = None
system_logger = None
config = {}


# ============ MODELS ============


class FileSaveRequest(BaseModel):
    path: str
    content: str


# ============ HELPERS ============


def _init_git_workspace():
    global git_workspace
    if repo_manager:
        from app.core.git_utils import GitWorkspace

        workspace = repo_manager._get_workspace_path(repo_manager.current_repo)
        bare = repo_manager._get_bare_path(repo_manager.current_repo)
        git_workspace = GitWorkspace(workspace, bare)


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


# ============ GIT WORKSPACE ============


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


@router.post("/git/save-and-sync")
async def save_and_sync(message: Optional[str] = Query("Sync from Home")):
    """Commit all changes to prepare for pull on work PC"""
    global git_workspace

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


# ============ EDITOR / EXPLORER ============


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
