"""System/Info API Router."""

import os

from fastapi import APIRouter

from app.routers.state import state

router = APIRouter(prefix="/api", tags=["system"])

# Injected from main.py
git_handler = None
repo_manager = None
git_workspace = None
shared_manager = None
config = {}
system_logger = None


@router.get("/status")
async def get_status():
    """Get server status"""
    from app.core.system_monitor import SystemMonitor

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

    sync_time = state.last_sync_time
    if not sync_time and current_git_workspace and repo_manager:
        try:
            commits = repo_manager.get_recent_commits(repo_manager.current_repo)
            if commits:
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
    """Return connection info for IDEA plugin setup."""
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


@router.post("/notify-push")
async def notify_push():
    """Called when push is received"""
    state.status = "ready"
    return {"success": True, "status": state.status}
