"""
LocalGitMirror FastAPI Application
Main application setup and configuration
"""

import os
import stat
from contextlib import asynccontextmanager
from pathlib import Path

import uvicorn
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, HTTPException, Security
from fastapi.security.api_key import APIKeyHeader
from fastapi.staticfiles import StaticFiles
from rich.console import Console
from starlette.status import HTTP_403_FORBIDDEN

# Import core modules
from app.core import (
    GitHandler,
    GitWorkspace,
    RepoManager,
    SettingsManager,
    SystemMonitor,
    get_logger,
)

# Load environment variables
load_dotenv()
console = Console()

# Configuration
CONFIG = {
    "web_port": int(os.getenv("WEB_PORT", 8443)),
    "git_port": int(os.getenv("GIT_PORT", 8444)),
    "storage_path": Path(os.getenv("STORAGE_PATH", "storage")),
}

# Global instances
git_handler = None
repo_manager = None
git_workspace = None
settings_manager = None
system_logger = None


def ensure_post_receive_hook(repo_name: str, storage_path: Path):
    """
    Remove legacy post-receive hooks that cause Win32 errors.
    We rely on the Python callback in WSGIFixMiddleware instead.
    """
    repo_path = storage_path / repo_name
    git_dir = repo_path / ".git"

    if not git_dir.exists():
        if repo_path.suffix == ".git" and repo_path.exists():
            git_dir = repo_path
        else:
            return

    hooks_dir = git_dir / "hooks"
    hook_file = hooks_dir / "post-receive"

    if hook_file.exists():
        try:
            os.remove(hook_file)
            console.print(f"[yellow] Removed legacy hook for {repo_name}[/yellow]")
        except Exception:
            pass


def on_repo_receive(repo_name: str):
    """Callback when git push is received"""
    global repo_manager, git_workspace
    import threading
    import time

    from app.core import GitWorkspace

    console.print(f"[cyan][>>] Push received: {repo_name}[/cyan]")

    # Ensure hook is present (self-healing)
    if repo_manager:
        ensure_post_receive_hook(repo_name, repo_manager.storage_path)

    if repo_manager:

        def run_sync(mgr):
            # Fallback sync in case hook fails or for non-git-bash envs
            time.sleep(1.0)
            result = mgr.sync_workspace(repo_name)
            if result["success"]:
                console.print(f"[green]* Synced: {result['message']}[/green]")
                workspace = mgr._get_workspace_path(repo_name)
                bare = mgr._get_bare_path(repo_name)
                global git_workspace
                git_workspace = GitWorkspace(workspace, bare)

        threading.Thread(target=run_sync, args=(repo_manager,), daemon=True).start()


@asynccontextmanager
async def lifespan(app: FastAPI):
    global git_handler, repo_manager, git_workspace, settings_manager, system_logger

    base_storage = Path(CONFIG["storage_path"])
    settings_manager = SettingsManager(base_storage)
    saved_settings = settings_manager.get_all()
    actual_storage_path = Path(saved_settings.get("general", {}).get("storage_path", CONFIG["storage_path"]))

    if actual_storage_path != base_storage:
        settings_manager = SettingsManager(actual_storage_path)

    system_logger = get_logger(actual_storage_path)
    git_handler = GitHandler(actual_storage_path, port=CONFIG["git_port"], on_receive=on_repo_receive)
    repo_manager = RepoManager(actual_storage_path)

    # Inject dependencies into routers
    from app.routers import api, settings, websocket

    api.git_handler = git_handler
    api.repo_manager = repo_manager
    api.system_logger = system_logger
    api.config = CONFIG

    settings.settings_manager = settings_manager

    websocket.system_logger = system_logger

    # Install hooks for ALL existing repos on startup
    if actual_storage_path.exists():
        for item in actual_storage_path.iterdir():
            if item.is_dir():
                if (item / ".git").exists():
                    ensure_post_receive_hook(item.name, actual_storage_path)

    # Initialize Git HTTP Bridge (Standard WSGI mount)
    from app.routers.git_http import init_git_http

    init_git_http(app, actual_storage_path)

    console.print("[bold green]LocalGitMirror is ready![/bold green]")
    console.print(f"[blue]Storage: {actual_storage_path.absolute()}[/blue]")

    # Show HTTPS if cert exists
    protocol = "http"
    if Path("cert.pem").exists() and Path("key.pem").exists():
        protocol = "https"

    console.print(f"[blue]Web UI:  {protocol}://0.0.0.0:{CONFIG['web_port']}[/blue]")

    yield
    if git_handler:
        git_handler.stop()


# Security
API_KEY_NAME = "X-API-Key"
api_key_header = APIKeyHeader(name=API_KEY_NAME, auto_error=False)


async def get_api_key(api_key_header: str = Security(api_key_header)):
    expected_key = os.getenv("API_KEY")
    if not expected_key or api_key_header == expected_key:
        return api_key_header
    raise HTTPException(status_code=HTTP_403_FORBIDDEN, detail="Could not validate credentials")


# App
app = FastAPI(title="LocalGitMirror", version="3.2.0", lifespan=lifespan)

# Include routers
from app.routers import api_router, settings_router, web_router, websocket_router

app.include_router(api_router, dependencies=[Depends(get_api_key)])
app.include_router(web_router)
app.include_router(settings_router, dependencies=[Depends(get_api_key)])
app.include_router(websocket_router)

# Frontend
frontend_dist = Path(__file__).parent.parent.parent / "frontend" / "dist"
if frontend_dist.exists():
    # Mount assets for CSS/JS
    app.mount("/assets", StaticFiles(directory=str(frontend_dist / "assets")), name="assets")
    # Mount root for favicon, vite.svg etc
    app.mount("/", StaticFiles(directory=str(frontend_dist), html=True), name="frontend")

if __name__ == "__main__":
    # Check for SSL
    ssl_keyfile = "key.pem"
    ssl_certfile = "cert.pem"

    kwargs = {"host": "0.0.0.0", "port": CONFIG["web_port"], "reload": False}

    if os.path.exists(ssl_keyfile) and os.path.exists(ssl_certfile):
        kwargs["ssl_keyfile"] = ssl_keyfile
        kwargs["ssl_certfile"] = ssl_certfile
        console.print("[green]🔒 SSL Enabled[/green]")
    else:
        console.print("[yellow]⚠️  SSL Certificates not found. Running in HTTP mode.[/yellow]")

    uvicorn.run("app.main:app", **kwargs)
