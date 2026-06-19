"""
API routers
"""

from app.routers.deps import router as deps_router
from app.routers.files import router as files_router
from app.routers.repos import router as repos_router
from app.routers.settings import router as settings_router
from app.routers.shared import router as shared_router
from app.routers.sync import router as sync_router
from app.routers.system import router as system_router
from app.routers.web import router as web_router
from app.routers.websocket import router as websocket_router

__all__ = [
    "deps_router",
    "files_router",
    "repos_router",
    "settings_router",
    "shared_router",
    "sync_router",
    "system_router",
    "web_router",
    "websocket_router",
]
