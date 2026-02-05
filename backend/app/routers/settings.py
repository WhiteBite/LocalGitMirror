from typing import Any, Dict

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

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


@router.post("/browse")
async def browse_folder():
    """Open a native directory picker on the host machine"""
    import tkinter as tk
    from tkinter import filedialog

    root = tk.Tk()
    root.withdraw()
    root.attributes("-topmost", True)

    folder_selected = filedialog.askdirectory()
    root.destroy()

    if folder_selected:
        return {"path": folder_selected}
    return {"path": None}


@router.get("/defaults")
async def get_defaults():
    if not settings_manager:
        raise HTTPException(500, "Settings manager not initialized")
    return settings_manager.defaults
