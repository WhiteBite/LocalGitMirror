"""
Plugin distribution router.

Serves the latest built IDEA plugin .zip to clients that already trust this
Mirror (auth-gated, same as the rest of /api/*). The IDE plugin uses this to
self-update without juggling files through Telegram or shared folders.

Layout (no extra deploy step needed if the server runs next to the sources):
  <repo>/idea-plugin/build/distributions/*.zip   ← `gradle buildPlugin` output

Override the search location via env LGM_PLUGIN_DIST when the dist directory
is elsewhere (e.g. CI-built artifacts dropped into a custom path).
"""

from __future__ import annotations

import os
import re
from datetime import datetime
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

router = APIRouter(prefix="/api/plugin", tags=["plugin"])


def _dist_dir() -> Path:
    """Resolve where to look for plugin .zip artifacts."""
    override = os.getenv("LGM_PLUGIN_DIST")
    if override:
        return Path(override)
    # backend/app/routers/plugin.py -> repo root is 4 levels up
    repo_root = Path(__file__).resolve().parent.parent.parent.parent
    return repo_root / "idea-plugin" / "build" / "distributions"


_VERSION_RE = re.compile(r"(\d+(?:\.\d+){1,3})")


def _parse_version(filename: str) -> Optional[str]:
    """Pick the longest dotted-number sequence from the file name."""
    matches = _VERSION_RE.findall(filename)
    return max(matches, key=len) if matches else None


def _newest_zip() -> Optional[Path]:
    """Newest .zip in dist dir by mtime, or None if absent/empty."""
    d = _dist_dir()
    if not d.exists() or not d.is_dir():
        return None
    zips = [p for p in d.iterdir() if p.is_file() and p.suffix.lower() == ".zip"]
    if not zips:
        return None
    return max(zips, key=lambda p: p.stat().st_mtime)


@router.get("/info")
def plugin_info():
    """
    Lightweight metadata — plugin uses this to decide whether to offer an
    update. 404 (not 503) keeps the response shape identical to "no such
    endpoint", in line with the project's scanner-resistant posture.
    """
    z = _newest_zip()
    if z is None:
        raise HTTPException(status_code=404, detail="Not Found")
    stat = z.stat()
    return {
        "available": True,
        "version": _parse_version(z.name),
        "filename": z.name,
        "size": stat.st_size,
        "built_at": datetime.fromtimestamp(stat.st_mtime).isoformat(timespec="seconds"),
    }


@router.get("/latest")
def plugin_latest():
    """Stream the newest plugin .zip as an attachment."""
    z = _newest_zip()
    if z is None:
        raise HTTPException(status_code=404, detail="Not Found")
    return FileResponse(
        path=str(z),
        media_type="application/zip",
        filename=z.name,
    )
