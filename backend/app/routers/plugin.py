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

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import Response

from app.core.bundle_crypto import encrypt_bundle_bytes
from app.core.envelope_crypto import encrypt_envelope

router = APIRouter(prefix="/api/plugin", tags=["plugin"])

_PLUGIN_NAME_RE = re.compile(r"^localgitmirror-idea-plugin-(\d+)\.(\d+)\.(\d+)\.zip$")
_BUILD_LOCK = threading.Lock()
_BUILD_THREAD: Optional[threading.Thread] = None
_BUILD_ERROR: Optional[str] = None
_BUILD_THREAD_NAME = "lgm-plugin-build"

_PLAINTEXT_REFUSAL = {
    "code": "enc_required",
    "message": "Unencrypted plugin download is disabled; the sync password is required (request ?enc=1).",
}


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


def _build_in_flight() -> bool:
    thread = _BUILD_THREAD
    return thread is not None and thread.is_alive()


def _start_background_build() -> None:
    """Single-flight: at most one gradle buildPlugin thread at a time."""
    global _BUILD_THREAD, _BUILD_ERROR
    with _BUILD_LOCK:
        if _build_in_flight():
            return
        _BUILD_ERROR = None
        _BUILD_THREAD = threading.Thread(target=_background_build, daemon=True, name=_BUILD_THREAD_NAME)
        _BUILD_THREAD.start()


def _background_build() -> None:
    global _BUILD_ERROR
    gradle = _gradle_command()
    if gradle is None:
        _BUILD_ERROR = "Plugin ZIP is stale and Gradle is unavailable on the Mirror server."
        return
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
        _BUILD_ERROR = "Plugin build timed out after 15 minutes."
        return
    except OSError as error:
        _BUILD_ERROR = f"Plugin build could not start: {error}"
        return

    latest = _newest_zip()
    expected = _expected_version()
    if result.returncode != 0 or latest is None or (expected is not None and _version_key(latest) < expected):
        output = (result.stderr or result.stdout).strip().replace("\n", " ")[-300:]
        _BUILD_ERROR = f"Plugin build failed: {output or 'no ZIP produced'}"
        return
    _BUILD_ERROR = None


def _ensure_current_zip() -> tuple[Optional[Path], bool]:
    """Newest existing ZIP plus whether a refresh build is in flight.

    A stale or missing archive starts one background build; the request never
    waits for gradle (a 15-minute build must not live inside a GET).
    """
    latest = _newest_zip()
    expected = _expected_version()
    if expected is None or (latest is not None and _version_key(latest) >= expected):
        return latest, False
    _start_background_build()
    return latest, _build_in_flight()


def _current_zip() -> Path:
    archive, _building = _ensure_current_zip()
    if archive is None:
        raise HTTPException(status_code=404, detail="Plugin archive not built yet")
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


def _enc_password() -> str:
    password = os.getenv("SYNC_PASSWORD", "")
    if not password:
        raise HTTPException(status_code=503, detail="SYNC_PASSWORD not configured")
    return password


@router.get("/info")
def plugin_info(enc: bool = Query(False)):
    """Return the exact semantic-versioned archive available to the IDE.

    ``?enc=1`` hides the metadata (filename reveals the tool name) behind the
    standard envelope — the work network is hostile, the mirror is trusted.
    Plaintext (``enc=0`` or no flag) is refused: the sync password is
    required. A stale or missing archive is reported via ``building`` while
    one background rebuild runs.
    """
    if not enc:
        raise HTTPException(status_code=400, detail=_PLAINTEXT_REFUSAL)
    password = _enc_password()
    archive, building = _ensure_current_zip()
    metadata: dict = {"available": archive is not None, "building": building}
    if archive is not None:
        stat = archive.stat()
        metadata.update(
            {
                "version": _parse_version(archive.name),
                "filename": archive.name,
                "size": stat.st_size,
                "built_at": datetime.fromtimestamp(stat.st_mtime).isoformat(timespec="seconds"),
                "sha256": _sha256(archive),
            }
        )
    if _BUILD_ERROR:
        metadata["build_error"] = _BUILD_ERROR
    return {"e": encrypt_envelope(metadata, password)}


@router.get("/latest")
def plugin_latest(enc: bool = Query(False)):
    """Stream the same current archive described by ``/info``.

    ``?enc=1`` ships the archive as a bundle-format ciphertext (pure noise on
    the wire; the raw ZIP would expose the plugin's bytecode to traffic
    inspection). Plaintext (``enc=0`` or no flag) is refused: the sync
    password is required. The newest existing ZIP is served while a rebuild
    runs in the background.
    """
    if not enc:
        raise HTTPException(status_code=400, detail=_PLAINTEXT_REFUSAL)
    password = _enc_password()
    archive, _building = _ensure_current_zip()
    if archive is None:
        raise HTTPException(status_code=404, detail="Plugin archive not built yet")
    blob = encrypt_bundle_bytes(archive.read_bytes(), password)
    return Response(content=blob, media_type="application/octet-stream")
