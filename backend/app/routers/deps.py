"""
Gradle dependency-sync transport.

The server is a dumb postbox: it stores opaque encrypted blobs and never
inspects their content. Two flows happen here:

  1. Dome  -> POST /deps/request  : "I have these artifacts, send me what's
                                    missing for project X" (encrypted manifest)
  2. Work  -> GET  /deps/pending  : list outstanding requests
  3. Work  -> GET  /deps/manifest : download a specific request blob
  4. Work  -> POST /deps/respond  : "here's the encrypted ZIP for request id"
  5. Dome  -> GET  /deps/responses: list ready responses
  6. Dome  -> GET  /deps/fetch    : download the response ZIP
  7. Dome  -> DELETE /deps/ack    : confirm applied, server cleans up

All payloads are pre-encrypted by the plugin (BundleCrypto), so leaking
the storage dir does not leak project deps.
"""
import hashlib
import os
import re
import time
import uuid
from pathlib import Path
from typing import List, Optional

from fastapi import APIRouter, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel

router = APIRouter(prefix="/api/deps", tags=["deps"])

# Injected from main.py at startup, same pattern as the other routers.
repo_manager = None
system_logger = None


# ─────────────────────────────────────────────────────────────────────────────
# Storage helpers
# ─────────────────────────────────────────────────────────────────────────────

_SAFE_REPO = re.compile(r"^[A-Za-z0-9_-][A-Za-z0-9_.-]*$")
_SAFE_ID = re.compile(r"^[A-Za-z0-9-]+$")


def _validate_repo(repo: str) -> str:
    repo = (repo or "").strip()
    if not repo or ".." in repo or not _SAFE_REPO.match(repo):
        raise HTTPException(400, "Invalid repo name")
    return repo


def _validate_id(item_id: str) -> str:
    item_id = (item_id or "").strip()
    if not item_id or not _SAFE_ID.match(item_id) or len(item_id) > 64:
        raise HTTPException(400, "Invalid id")
    return item_id


def _deps_root() -> Path:
    if not repo_manager:
        raise HTTPException(500, "Repo manager not initialised")
    root = repo_manager.storage_path / "deps"
    root.mkdir(parents=True, exist_ok=True)
    return root


# ─────────────────────────────────────────────────────────────────────────────
# D2: Hashed repo directory names for stealth
# ─────────────────────────────────────────────────────────────────────────────

def _deps_repo_hash(repo: str) -> str:
    """Return a short deterministic hash of the repo name for filesystem stealth."""
    return hashlib.sha256(repo.encode()).hexdigest()[:16]


def _requests_dir(repo: str) -> Path:
    hashed = _deps_repo_hash(repo)
    root = _deps_root()

    # Backward-compat migration: if hashed dir doesn't exist but plain-name dir does, rename it.
    plain_dir = root / repo
    hashed_dir = root / hashed
    if not hashed_dir.exists() and plain_dir.exists():
        plain_dir.rename(hashed_dir)

    d = hashed_dir / "requests"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _responses_dir(repo: str) -> Path:
    hashed = _deps_repo_hash(repo)
    root = _deps_root()

    # Backward-compat migration: if hashed dir doesn't exist but plain-name dir does, rename it.
    plain_dir = root / repo
    hashed_dir = root / hashed
    if not hashed_dir.exists() and plain_dir.exists():
        plain_dir.rename(hashed_dir)

    d = hashed_dir / "responses"
    d.mkdir(parents=True, exist_ok=True)
    return d


# ─────────────────────────────────────────────────────────────────────────────
# D1: Auto-TTL cleanup of stale blobs
# ─────────────────────────────────────────────────────────────────────────────

def _cleanup_stale(directory: Path, max_age_seconds: int = 7 * 24 * 3600,
                   now: Optional[float] = None) -> int:
    """Delete .bin files strictly older than max_age_seconds. Returns count deleted.

    `now` is injectable so tests can pin the reference time and assert the exact
    boundary deterministically (otherwise wall-clock drift between setting a
    file's mtime and reading time.time() makes the "exactly at TTL" case flaky).
    """
    if not directory.exists():
        return 0
    if now is None:
        now = time.time()
    deleted = 0
    for p in directory.iterdir():
        if not p.is_file() or not p.name.endswith(".bin"):
            continue
        try:
            age = now - os.path.getmtime(p)
            if age > max_age_seconds:
                p.unlink()
                deleted += 1
        except OSError:
            continue
    return deleted


def _list_blobs(directory: Path) -> List[dict]:
    """Return [{id, size, mtime}] for all .bin files in a deps subfolder."""
    out: List[dict] = []
    if not directory.exists():
        return out
    for p in sorted(directory.iterdir()):
        if not p.is_file() or not p.name.endswith(".bin"):
            continue
        try:
            st = p.stat()
            out.append({
                "id": p.stem,                      # uuid without .bin
                "size": st.st_size,
                "mtime": int(st.st_mtime),
            })
        except OSError:
            continue
    # Newest first
    out.sort(key=lambda x: x["mtime"], reverse=True)
    return out


# ─────────────────────────────────────────────────────────────────────────────
# Endpoints
# ─────────────────────────────────────────────────────────────────────────────

@router.post("/request")
async def deps_request(
    repo: str = Form(...),
    attachment: UploadFile = File(...),
):
    """
    Dome side: post an encrypted manifest describing local artifacts.
    The blob is stored as-is; we never read it.
    """
    repo = _validate_repo(repo)
    payload = await attachment.read()
    if not payload:
        raise HTTPException(400, "Empty manifest")
    if len(payload) > 10 * 1024 * 1024:  # 10 MB hard cap on manifest
        raise HTTPException(413, "Manifest too large")

    # D1: lazy cleanup before storing new request
    req_dir = _requests_dir(repo)
    n = _cleanup_stale(req_dir)
    if n > 0 and system_logger:
        system_logger.info("deps TTL cleanup", {"dir": str(req_dir), "deleted": n})

    item_id = uuid.uuid4().hex
    target = req_dir / f"{item_id}.bin"
    target.write_bytes(payload)

    if system_logger:
        system_logger.info("deps request stored", {"repo": repo, "id": item_id, "bytes": len(payload)})

    return {"success": True, "repo": repo, "id": item_id, "size": len(payload)}


@router.get("/pending")
def deps_pending(repo: str = Query(...)):
    """Work side: list outstanding requests for this repo."""
    repo = _validate_repo(repo)
    req_dir = _requests_dir(repo)
    # D1: lazy cleanup before listing
    n = _cleanup_stale(req_dir)
    if n > 0 and system_logger:
        system_logger.info("deps TTL cleanup", {"dir": str(req_dir), "deleted": n})
    return {"success": True, "repo": repo, "items": _list_blobs(req_dir)}


@router.get("/manifest")
def deps_manifest(repo: str = Query(...), id: str = Query(...)):
    """Work side: download a specific request blob (encrypted manifest)."""
    repo = _validate_repo(repo)
    item_id = _validate_id(id)
    path = _requests_dir(repo) / f"{item_id}.bin"
    if not path.exists():
        raise HTTPException(404, "Request not found")
    return FileResponse(path, media_type="application/octet-stream", filename=f"{item_id}.bin")


@router.post("/respond")
async def deps_respond(
    repo: str = Form(...),
    request_id: str = Form(...),
    attachment: UploadFile = File(...),
):
    """
    Work side: upload an encrypted archive in response to a manifest.
    Once accepted, the original request blob is deleted (one-shot).
    """
    repo = _validate_repo(repo)
    request_id = _validate_id(request_id)

    payload = await attachment.read()
    if not payload:
        raise HTTPException(400, "Empty archive")
    if len(payload) > 2 * 1024 * 1024 * 1024:  # 2 GB safety cap
        raise HTTPException(413, "Archive too large")

    response_id = uuid.uuid4().hex
    target = _responses_dir(repo) / f"{response_id}.bin"
    target.write_bytes(payload)

    # Remove the matching request — it's been answered.
    req_path = _requests_dir(repo) / f"{request_id}.bin"
    if req_path.exists():
        try:
            req_path.unlink()
        except OSError:
            pass

    if system_logger:
        system_logger.info("deps response stored", {"repo": repo, "id": response_id, "bytes": len(payload)})

    return {"success": True, "repo": repo, "id": response_id, "size": len(payload)}


@router.get("/responses")
def deps_responses(repo: str = Query(...)):
    """Dome side: list ready responses for this repo."""
    repo = _validate_repo(repo)
    resp_dir = _responses_dir(repo)
    # D1: lazy cleanup before listing
    n = _cleanup_stale(resp_dir)
    if n > 0 and system_logger:
        system_logger.info("deps TTL cleanup", {"dir": str(resp_dir), "deleted": n})
    return {"success": True, "repo": repo, "items": _list_blobs(resp_dir)}


@router.get("/fetch")
def deps_fetch(repo: str = Query(...), id: str = Query(...)):
    """Dome side: download a response blob."""
    repo = _validate_repo(repo)
    item_id = _validate_id(id)
    path = _responses_dir(repo) / f"{item_id}.bin"
    if not path.exists():
        raise HTTPException(404, "Response not found")
    return FileResponse(path, media_type="application/octet-stream", filename=f"{item_id}.bin")


class DepsAckRequest(BaseModel):
    repo: str
    id: str


@router.delete("/ack")
def deps_ack(repo: str = Query(...), id: str = Query(...)):
    """Dome side: confirm a response has been applied; server deletes it."""
    repo = _validate_repo(repo)
    item_id = _validate_id(id)
    path = _responses_dir(repo) / f"{item_id}.bin"
    if path.exists():
        try:
            path.unlink()
        except OSError as e:
            raise HTTPException(500, f"Failed to delete: {e}")
    return {"success": True, "repo": repo, "id": item_id}
