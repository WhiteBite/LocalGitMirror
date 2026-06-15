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
    SharedManager,
    SystemMonitor,
    get_logger,
)

# Load environment variables
load_dotenv()
console = Console()

# Configuration
CONFIG = {
    "web_port": int(os.getenv("WEB_PORT", 443)),
    "git_port": int(os.getenv("GIT_PORT", 8444)),
    "storage_path": Path(os.getenv("STORAGE_PATH", "storage")),
}

# Global instances (will be initialized in lifespan but defined here)
git_handler = None
repo_manager = None
git_workspace = None
settings_manager = None
shared_manager = None
system_logger = None
lan_beacon = None


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
            console.print(f"[yellow][!] Removed legacy hook for {repo_name} to prevent Win32 errors[/yellow]")
        except Exception:
            pass


def on_repo_receive(repo_name: str):
    """Callback when git push is received"""
    global repo_manager, git_workspace
    import threading
    import time
    import subprocess

    from app.core import GitWorkspace

    console.print(f"[cyan][>>] Push detected for: {repo_name}[/cyan]")

    if repo_manager:
        # Auto-checkout to the latest branch pushed
        def run_sync(mgr, name):
            time.sleep(0.5)
            workspace = mgr._get_workspace_path(name)

            if not workspace.exists():
                return

            try:
                # 1. Detect which branch was just updated in the bare repo
                bare_path = mgr._get_bare_path(name)
                proc = subprocess.run(
                    [
                        "git",
                        "for-each-ref",
                        "--sort=-committerdate",
                        "--format=%(refname:short)",
                        "--count=1",
                        "refs/heads/",
                    ],
                    cwd=str(bare_path),
                    capture_output=True,
                    text=True,
                )
                latest_branch = proc.stdout.strip()

                if not latest_branch:
                    latest_branch = "main"

                console.print(f"[cyan][i] Latest branch on {name}: {latest_branch}[/cyan]")

                # 2. Perform sync and checkout to that branch
                result = mgr.sync_workspace(name, branch=latest_branch)

                if result["success"]:
                    console.print(f"[green][v] Auto-switched {name} to: {latest_branch}[/green]")
                    # Update global workspace reference
                    global git_workspace
                    bare = mgr._get_bare_path(name)
                    git_workspace = GitWorkspace(workspace, bare)
            except Exception as e:
                console.print(f"[red][!] Auto-switch failed: {e}[/red]")

        threading.Thread(target=run_sync, args=(repo_manager, repo_name), daemon=True).start()


@asynccontextmanager
async def lifespan(app: FastAPI):
    global git_handler, repo_manager, git_workspace, settings_manager, shared_manager, system_logger, lan_beacon

    # 1. Initialize core managers
    base_storage = Path(CONFIG["storage_path"])
    settings_manager = SettingsManager(base_storage)
    saved_settings = settings_manager.get_all()
    actual_storage_path = Path(saved_settings.get("general", {}).get("storage_path", CONFIG["storage_path"]))

    if actual_storage_path != base_storage:
        settings_manager = SettingsManager(actual_storage_path)

    system_logger = get_logger(actual_storage_path)
    git_handler = GitHandler(actual_storage_path, port=CONFIG["git_port"], on_receive=on_repo_receive)
    repo_manager = RepoManager(actual_storage_path)
    shared_manager = SharedManager(actual_storage_path)

    # 2. Inject dependencies into routers
    from app.routers import api, settings

    api.git_handler = git_handler
    api.repo_manager = repo_manager
    api.shared_manager = shared_manager
    api.system_logger = system_logger
    api.config = CONFIG
    settings.settings_manager = settings_manager

    # 3. Clean up legacy hooks
    if actual_storage_path.exists():
        for item in actual_storage_path.iterdir():
            if item.is_dir() and (item / ".git").exists():
                ensure_post_receive_hook(item.name, actual_storage_path)

    # Auto-start git server
    try:
        git_handler.start()
        console.print(f"[green]Git сервер запущен на порту {CONFIG['git_port']}[/green]")
    except Exception as e:
        console.print(f"[red][!] Не удалось запустить Git сервер: {e}[/red]")

    console.print("[bold green]LocalGitMirror is ready![/bold green]")
    console.print(f"[blue]Storage: {actual_storage_path.absolute()}[/blue]")

    protocol = "https" if (Path("cert.pem").exists() and Path("key.pem").exists()) else "http"

    # Auto-generate self-signed TLS certificate if missing
    if not Path("cert.pem").exists() or not Path("key.pem").exists():
        try:
            import sys as _sys
            _sys.path.insert(0, str(Path(__file__).parent.parent.parent))
            from generate_cert import generate_self_signed_cert
            generate_self_signed_cert()
            console.print("[green]Auto-generated self-signed TLS certificate (cert.pem + key.pem)[/green]")
            protocol = "https"
        except Exception as e:
            console.print(f"[yellow][!] Failed to auto-generate TLS cert: {e}[/yellow]")
            console.print("[yellow]    Run 'python generate_cert.py' manually for HTTPS support[/yellow]")
    console.print(f"[blue]Web UI:  {protocol}://0.0.0.0:{CONFIG['web_port']}[/blue]")

    # Auto-start LAN beacon for plugin auto-discovery
    from app.core.lan_beacon import LanBeacon
    tls_enabled = Path("cert.pem").exists() and Path("key.pem").exists()
    lan_beacon = LanBeacon(web_port=CONFIG["web_port"], tls=tls_enabled)
    try:
        lan_beacon.start()
        console.print(f"[green]LAN beacon started (broadcast on UDP 37020)[/green]")
    except Exception as e:
        console.print(f"[yellow][!] LAN beacon failed to start: {e}[/yellow]")

    yield
    # Non-blocking shutdown
    if lan_beacon:
        try:
            lan_beacon.stop()
        except Exception:
            pass
    if git_handler:
        try:
            git_handler.stop()
        except Exception:
            pass


# Security: accept both Authorization: Bearer and legacy X-Session-ID
API_KEY_NAME = "X-Session-ID"
api_key_header = APIKeyHeader(name=API_KEY_NAME, auto_error=False)
auth_bearer_header = APIKeyHeader(name="Authorization", auto_error=False)


async def get_api_key(
    legacy_key: str = Security(api_key_header),
    auth_header: str = Security(auth_bearer_header),
):
    expected_key = os.getenv("API_KEY")
    # Allow requests without API key (for development/local use)
    if not expected_key:
        return legacy_key or auth_header
    # Check Authorization: Bearer <key>
    if auth_header:
        token = auth_header.removeprefix("Bearer ").strip() if auth_header.startswith("Bearer ") else auth_header
        if token == expected_key:
            return token
    # Check legacy X-Session-ID
    if legacy_key == expected_key:
        return legacy_key
    # Return 404 instead of 403 to hide server existence from scanners
    raise HTTPException(status_code=404, detail="Not Found")


# --- INITIALIZE APP ---
app = FastAPI(title="LocalGitMirror", version="3.2.0", lifespan=lifespan)

# --- MOUNT GIT HTTP (CRITICAL: MUST BE BEFORE START) ---
# We use a placeholder path for now, it will use absolute paths inside the middleware
from app.routers.git_http import init_git_http

# Load storage path from env for immediate mounting
initial_storage = Path(os.getenv("STORAGE_PATH", "storage"))
init_git_http(app, initial_storage)


# Include routers
from app.routers import api_router, settings_router, web_router, websocket_router

app.include_router(api_router, dependencies=[Depends(get_api_key)])
app.include_router(web_router)
app.include_router(settings_router, dependencies=[Depends(get_api_key)])
app.include_router(websocket_router)

# Frontend
frontend_dist = Path(__file__).parent.parent.parent / "frontend" / "dist"
if frontend_dist.exists():
    app.mount("/assets", StaticFiles(directory=str(frontend_dist / "assets")), name="assets")
    app.mount("/", StaticFiles(directory=str(frontend_dist), html=True), name="frontend")

if __name__ == "__main__":
    ssl_keyfile = "key.pem"
    ssl_certfile = "cert.pem"
    kwargs = {"host": "0.0.0.0", "port": CONFIG["web_port"], "reload": False, "log_level": "error"}
    if os.path.exists(ssl_keyfile) and os.path.exists(ssl_certfile):
        kwargs["ssl_keyfile"] = ssl_keyfile
        kwargs["ssl_certfile"] = ssl_certfile

    try:
        uvicorn.run("app.main:app", **kwargs)
    except PermissionError:
        console.print("[bold red]❌ Permission denied: Port 443 requires administrator privileges[/bold red]")
        console.print("[yellow]Please run the terminal as Administrator and try again:[/yellow]")
        console.print("[cyan]  1. Right-click on Terminal/PowerShell[/cyan]")
        console.print("[cyan]  2. Select 'Run as Administrator'[/cyan]")
        console.print("[cyan]  3. Navigate to project directory and run again[/cyan]")
        raise
    except OSError as e:
        if "address already in use" in str(e).lower() or e.errno == 10048:
            console.print(f"[bold red]❌ Port {CONFIG['web_port']} is already in use[/bold red]")
            console.print("[yellow]Another application is using this port. Options:[/yellow]")
            console.print("[cyan]  1. Stop the other application[/cyan]")
            console.print("[cyan]  2. Change WEB_PORT in .env file[/cyan]")
            console.print("[cyan]  3. Run as Administrator if permission is the issue[/cyan]")
            raise
        raise
