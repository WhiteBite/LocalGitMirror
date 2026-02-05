"""
API routers
"""

from app.routers.api import router as api_router
from app.routers.settings import router as settings_router
from app.routers.web import router as web_router
from app.routers.websocket import router as websocket_router

__all__ = [
    "api_router",
    "web_router",
    "settings_router",
    "websocket_router",
]
