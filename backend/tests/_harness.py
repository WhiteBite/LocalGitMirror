"""
Shared test harness for the modular API routers.

After api.py was decomposed into system / repos / sync / files / shared,
tests build their app from these modules. This harness injects dependencies
into every sub-module (each keeps its own injectable module globals, exactly
as main.py wires them at startup) and returns a FastAPI app with all routers
mounted.

Usage:
    from tests import _harness

    app = _harness.build_app(repo_manager=rm, system_logger=None,
                             config={"git_port": 0, "web_port": 0,
                                     "storage_path": storage})
    client = TestClient(app)

Sync helpers (_git, _apply_dump_to_repo_and_sync_bare, _pick_bundle_ref,
_infer_repo_from_dump_filename, sync_export_dump, MAGIC, decrypt_dump_to_bundle)
now live in app.routers.sync — patch/import them from there.
"""
import sys
from pathlib import Path

# Ensure `app` package is importable when tests are launched from repo root.
BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from fastapi import FastAPI

from app.routers import files, repos, shared, sync, system

# All modular routers, in mount order.
_MODULES = (system, repos, sync, files, shared)

_UNSET = object()


def inject(
    *,
    repo_manager=_UNSET,
    shared_manager=_UNSET,
    git_handler=_UNSET,
    git_workspace=_UNSET,
    system_logger=_UNSET,
    config=_UNSET,
):
    """Set the given dependencies on every sub-module that declares them.

    Only attributes that were explicitly passed are touched (sentinel-guarded),
    so passing system_logger=None intentionally clears it everywhere.
    """
    values = {
        "repo_manager": repo_manager,
        "shared_manager": shared_manager,
        "git_handler": git_handler,
        "git_workspace": git_workspace,
        "system_logger": system_logger,
        "config": config,
    }
    for module in _MODULES:
        for attr, val in values.items():
            if val is not _UNSET and hasattr(module, attr):
                setattr(module, attr, val)


def build_app(**kwargs) -> FastAPI:
    """Inject deps and return a FastAPI app with all modular routers mounted."""
    inject(**kwargs)
    app = FastAPI()
    for module in _MODULES:
        app.include_router(module.router)
    return app
