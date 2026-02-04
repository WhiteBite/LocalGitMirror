from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Dict, Any

router = APIRouter(prefix="/api/settings", tags=["settings"])

# Injected from main.py
settings_manager = None


class SettingsUpdate(BaseModel):
    general: Dict[str, Any]
    git: Dict[str, Any]
    ui: Dict[str, Any]


@router.get("")
async def get_settings():
    if not settings_manager:
        raise HTTPException(500, "Settings manager not initialized")
    return settings_manager.get_all()


@router.post("")
async def save_settings(settings: SettingsUpdate):
    if not settings_manager:
        raise HTTPException(500, "Settings manager not initialized")

    # Handle storage path change if requested
    if "storage_path" in settings.general:
        # Note: changing storage path requires backend restart or complex reloading
        # For now we just save it, but we might want to validate it exists
        pass

    success = settings_manager.save_settings(settings.model_dump())
    return {"success": success, "settings": settings_manager.get_all()}


@router.get("/defaults")
async def get_defaults():
    if not settings_manager:
        raise HTTPException(500, "Settings manager not initialized")
    return settings_manager.defaults
