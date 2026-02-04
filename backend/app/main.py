"""
LocalGitMirror FastAPI Application
Main application setup and configuration
"""

import os
from pathlib import Path
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from rich.console import Console
import uvicorn

# Load environment variables
load_dotenv()
console = Console()

# Configuration
CONFIG = {
    "web_port": int(os.getenv("WEB_PORT", 8000)),
    "git_port": int(os.getenv("GIT_PORT", 8081)),
    "storage_path": Path(os.getenv("STORAGE_PATH", "storage")),
    "ollama_url": os.getenv("OLLAMA_URL", "http://localhost:11434"),
    "ollama_model": os.getenv("OLLAMA_MODEL", "llama3.2"),
}

# Import core modules
from app.core import (
    GitHandler,
    RepoManager,
    GitWorkspace,
    SystemMonitor,
    SettingsManager,
    get_logger,
)

# Global instances
git_handler = None
repo_manager = None
git_workspace = None
settings_manager = None
system_logger = None


def on_repo_receive(repo_name: str):
    """Callback when git push is received"""
    global repo_manager, git_workspace
    import app.routers.api as api_module
    import threading
    import time

    console.print(f"[cyan]📥 Push received: {repo_name}[/cyan]")

    if repo_manager:
        # Run sync in background to avoid blocking the git handshake
        def run_sync(mgr):
            # Small delay to let git process finish its work
            time.sleep(1.0)
            # Auto-sync workspace
            result = mgr.sync_workspace(repo_name)
            if result["success"]:
                console.print(f"[green]* Synced: {result['message']}[/green]")

                # Update workflow status
                api_module.state.status = "ready"

                # Reinit git workspace
                workspace = mgr._get_workspace_path(repo_name)
                bare = mgr._get_bare_path(repo_name)
                from app.core import GitWorkspace

                api_module.git_workspace = GitWorkspace(workspace, bare)

        # We start the thread, but we don't join it.
        # This MUST NOT block the parent process.
        t = threading.Thread(target=run_sync, args=(repo_manager,), daemon=True)
        t.start()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan manager"""
    global git_handler, repo_manager, git_workspace, settings_manager, system_logger

    # 1. Initialize settings manager first to get the actual storage path
    base_storage = Path(CONFIG["storage_path"])
    settings_manager = SettingsManager(base_storage)

    # Update storage path from settings if available
    saved_settings = settings_manager.get_all()
    actual_storage_path = Path(
        saved_settings.get("general", {}).get("storage_path", CONFIG["storage_path"])
    )

    # Re-init settings manager if storage path changed (it stores settings.json inside storage)
    if actual_storage_path != base_storage:
        settings_manager = SettingsManager(actual_storage_path)

    local_ip = SystemMonitor.get_local_ip()

    # 2. Init system logger with the real path
    system_logger = get_logger(actual_storage_path)
    system_logger.info(
        "LocalGitMirror starting",
        {
            "version": "3.2.0",
            "web_port": CONFIG["web_port"],
            "git_port": CONFIG["git_port"],
            "local_ip": local_ip,
            "storage_path": str(actual_storage_path),
        },
    )

    # 3. Init handlers with the real path
    git_handler = GitHandler(
        storage_path=actual_storage_path,
        port=CONFIG["git_port"],
        on_receive=on_repo_receive,
    )
    repo_manager = RepoManager(actual_storage_path)

    # Init git workspace for current repo
    workspace = repo_manager._get_workspace_path(repo_manager.current_repo)
    bare = repo_manager._get_bare_path(repo_manager.current_repo)
    git_workspace = GitWorkspace(workspace, bare)

    # Inject into routers
    import app.routers.api as api_module
    import app.routers.settings as settings_module

    # Inject dependencies into api router
    api_module.git_handler = git_handler
    api_module.repo_manager = repo_manager
    api_module.git_workspace = git_workspace
    api_module.config = CONFIG
    api_module.system_logger = system_logger

    # Inject dependencies into settings router
    settings_module.settings_manager = settings_manager

    # Start git server automatically if requested
    if settings_manager.get_all()["git"].get("auto_start", True):
        git_handler.start()

    console.print("[bold green]LocalGitMirror is ready![/bold green]")
    console.print(f"[blue]Storage: {actual_storage_path.absolute()}[/blue]")
    console.print(f"[blue]Web UI:  http://0.0.0.0:{CONFIG['web_port']}[/blue]")
    console.print(f"[blue]Git Srv: git://0.0.0.0:{CONFIG['git_port']}[/blue]")

    yield

    system_logger.info("LocalGitMirror shutting down")
    if git_handler and git_handler.is_running:
        git_handler.stop()

    console.print("[yellow]Shutdown complete[/yellow]")


from fastapi import FastAPI, Depends, Security, HTTPException
from fastapi.security.api_key import APIKeyHeader
from starlette.status import HTTP_403_FORBIDDEN

# API Key Security
API_KEY_NAME = "X-API-Key"
api_key_header = APIKeyHeader(name=API_KEY_NAME, auto_error=False)


async def get_api_key(api_key_header: str = Security(api_key_header)):
    expected_key = os.getenv("API_KEY")
    if not expected_key:
        return None  # No key set, allow access

    if api_key_header == expected_key:
        return api_key_header
    raise HTTPException(
        status_code=HTTP_403_FORBIDDEN, detail="Could not validate credentials"
    )


# Create FastAPI app
app = FastAPI(
    title="LocalGitMirror",
    version="3.2.0",
    description="Git-based project synchronization with file browser",
    lifespan=lifespan,
)

# Application dependencies - add manually to routers that need it
# to avoid breaking WebSockets which don't support headers

# Include routers
from app.routers import api_router, web_router, settings_router, websocket_router

app.include_router(api_router, dependencies=[Depends(get_api_key)])
app.include_router(web_router)  # Frontend usually needs to load first
app.include_router(settings_router, dependencies=[Depends(get_api_key)])
app.include_router(
    websocket_router
)  # WS handles auth differently or not at all for logs


# Serve frontend static files (if built)
frontend_dist = Path(__file__).parent.parent.parent / "frontend" / "dist"
if frontend_dist.exists():
    app.mount(
        "/assets", StaticFiles(directory=str(frontend_dist / "assets")), name="assets"
    )
    console.print(f"[green]* Serving frontend from {frontend_dist}[/green]")


# Ensure templates directory exists
templates_dir = Path(__file__).parent.parent.parent / "templates"
templates_dir.mkdir(exist_ok=True)


if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=CONFIG["web_port"],
        reload=False,
        log_level="info",
    )
