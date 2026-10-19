from typing import Any, Dict, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict

router = APIRouter(prefix="/api/settings", tags=["settings"])

# Injected from main.py
settings_manager = None


class SettingsUpdate(BaseModel):
    # forbid extras: a typo'd section name must 422, not silently persist nothing
    model_config = ConfigDict(extra="forbid")

    general: Optional[Dict[str, Any]] = None
    git: Optional[Dict[str, Any]] = None
    ui: Optional[Dict[str, Any]] = None


@router.get("")
async def get_settings():
    if not settings_manager:
        raise HTTPException(500, "Settings manager not initialized")
    return settings_manager.get_all()


@router.post("")
async def save_settings(settings: SettingsUpdate):
    if not settings_manager:
        raise HTTPException(500, "Settings manager not initialized")

    sections = settings.model_dump(exclude_none=True)
    if not sections:
        raise HTTPException(422, "No settings sections provided: send one or more of general, git, ui")

    success = settings_manager.update_sections(sections)
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
