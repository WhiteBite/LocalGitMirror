"""Serve the current IntelliJ plugin distribution from the Mirror server."""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import threading
from datetime import datetime
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

router = APIRouter(prefix="/api/plugin", tags=["plugin"])

_PLUGIN_NAME_RE = re.compile(r"^localgitmirror-idea-plugin-(\d+)\.(\d+)\.(\d+)\.zip$")
_BUILD_LOCK = threading.Lock()


def _repo_root() -> Path:
    # backend/app/routers/plugin.py -> repository root is four levels up.
    return Path(__file__).resolve().parent.parent.parent.parent


def _dist_dir() -> Path:
    """Resolve where plugin ZIP artifacts are stored."""
    override = os.getenv("LGM_PLUGIN_DIST")
    return Path(override) if override else _repo_root() / "idea-plugin" / "build" / "distributions"


def _version_key(path: Path) -> Optional[tuple[int, int, int]]:
    match = _PLUGIN_NAME_RE.match(path.name)
    if match is None:
        return None
    return tuple(int(part) for part in match.groups())


def _parse_version(filename: str) -> Optional[str]:
    key = _version_key(Path(filename))
    return ".".join(map(str, key)) if key is not None else None


def _newest_zip() -> Optional[Path]:
    """Return the highest semantic-version plugin ZIP, never the newest mtime."""
    directory = _dist_dir()
    if not directory.is_dir():
        return None
    candidates = [path for path in directory.iterdir() if path.is_file() and _version_key(path) is not None]
    return max(candidates, key=lambda path: (_version_key(path), path.stat().st_mtime), default=None)


def _expected_version() -> Optional[tuple[int, int, int]]:
    """Plugin version is 0.<git commit count>.0; None for source archives."""
    try:
        count = subprocess.run(
            ["git", "rev-list", "--count", "HEAD"],
            cwd=_repo_root(),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=True,
            timeout=10,
        ).stdout.strip()
        return (0, int(count), 0) if count.isdigit() else None
    except (OSError, subprocess.SubprocessError):
        return None


def _gradle_command() -> Optional[list[str]]:
    plugin_dir = _repo_root() / "idea-plugin"
    wrapper = plugin_dir / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if wrapper.is_file():
        return [str(wrapper)]
    executable = shutil.which("gradle.bat" if os.name == "nt" else "gradle") or shutil.which("gradle")
    return [executable] if executable else None


def _ensure_current_zip() -> tuple[Optional[Path], Optional[str]]:
    """Build a stale/missing ZIP on demand instead of serving an old plugin."""
    latest = _newest_zip()
    expected = _expected_version()
    if expected is None or (latest is not None and _version_key(latest) >= expected):
        return latest, None

    with _BUILD_LOCK:
        latest = _newest_zip()
        if latest is not None and _version_key(latest) >= expected:
            return latest, None

        gradle = _gradle_command()
        if gradle is None:
            return None, "Plugin ZIP is stale and Gradle is unavailable on the Mirror server."
        try:
            result = subprocess.run(
                [*gradle, "buildPlugin", "--no-daemon"],
                cwd=_repo_root() / "idea-plugin",
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=900,
            )
        except subprocess.TimeoutExpired:
            return None, "Plugin build timed out after 15 minutes."
        except OSError as error:
            return None, f"Plugin build could not start: {error}"

        latest = _newest_zip()
        if result.returncode != 0 or latest is None or _version_key(latest) < expected:
            output = (result.stderr or result.stdout).strip().replace("\n", " ")[-300:]
            return None, f"Plugin build failed: {output or 'no ZIP produced'}"
        return latest, None


def _current_zip() -> Path:
    archive, error = _ensure_current_zip()
    if error:
        raise HTTPException(status_code=503, detail=error)
    if archive is None:
        raise HTTPException(status_code=404, detail="Not Found")
    return archive


_SHA256_CACHE: dict[str, tuple[float, str]] = {}


def _sha256(path: Path) -> str:
    """SHA-256 of the archive, cached by mtime (clients verify downloads)."""
    mtime = path.stat().st_mtime
    cached = _SHA256_CACHE.get(str(path))
    if cached is not None and cached[0] == mtime:
        return cached[1]
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    digest = h.hexdigest()
    _SHA256_CACHE[str(path)] = (mtime, digest)
    return digest


@router.get("/info")
def plugin_info():
    """Return the exact semantic-versioned archive available to the IDE."""
    archive = _current_zip()
    stat = archive.stat()
    return {
        "available": True,
        "version": _parse_version(archive.name),
        "filename": archive.name,
        "size": stat.st_size,
        "built_at": datetime.fromtimestamp(stat.mtime).isoformat(timespec="seconds"),
        "sha256": _sha256(archive),
    }


@router.get("/latest")
def plugin_latest():
    """Stream the same current archive described by ``/info``."""
    archive = _current_zip()
    return FileResponse(path=str(archive), media_type="application/zip", filename=archive.name)
