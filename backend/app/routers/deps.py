"""
Gradle dependency-sync transport.

The server is a dumb postbox: it stores opaque encrypted blobs and never
inspects their content. Two flows happen here:

  1. Dome  -> POST /documents/submit     : "I have these artifacts, send me what's
                                            missing for project X" (encrypted manifest)
  2. Work  -> GET  /documents/queue      : list outstanding requests
  3. Work  -> GET  /documents/queue-item : download a specific request blob
  4. Work  -> POST /documents/fulfill    : "here's the encrypted ZIP for request id"
  5. Dome  -> GET  /documents/ready      : list ready responses
  6. Dome  -> GET  /documents/ready-item : download the response ZIP
  7. Dome  -> DELETE /documents/ack     : confirm applied, server cleans up

All payloads are pre-encrypted by the plugin (BundleCrypto), so leaking
the storage dir does not leak project deps.
"""
import hashlib
import re
import time
import uuid
from pathlib import Path
from typing import List, Optional

from fastapi import APIRouter, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse

from app.routers._rid import resolve_repo_identifier

router = APIRouter(prefix="/api/documents", tags=["documents"])

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


def _resolve_repo(rid_query: Optional[str], doc_ref_header: Optional[str]) -> str:
    value = (rid_query or "").strip() or (doc_ref_header or "").strip()
    resolved = resolve_repo_identifier(value, repo_manager)
    return _validate_repo(resolved)


def _validate_id(item_id: str) -> str:
    item_id = (item_id or "").strip()
    if not item_id or not _SAFE_ID.match(item_id) or len(item_id) > 64:
        raise HTTPException(400, "Invalid id")
    return item_id


def _deps_root() -> Path:
    if not repo_manager:
        raise HTTPException(500, "Repo manager not initialised")
    storage = repo_manager.storage_path
    _lgm = storage / ".lgm" / "deps"
    _old = storage / "deps"
    root = _lgm if _lgm.exists() else (_old if _old.exists() else _lgm)
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
    # Work in integer nanoseconds end-to-end. Float seconds (time.time() /
    # os.path.getmtime) don't round-trip through the filesystem exactly, so a
    # file aged *exactly* max_age_seconds could read back as a few ns over the
    # limit and be wrongly deleted. Integer ns (time.time_ns / st_mtime_ns) is
    # exact and deterministic on every platform.
    now_ns = time.time_ns() if now is None else int(now * 1_000_000_000)
    max_age_ns = int(max_age_seconds * 1_000_000_000)
    deleted = 0
    for p in directory.iterdir():
        if not p.is_file() or not p.name.endswith(".bin"):
            continue
        try:
            age_ns = now_ns - p.stat().st_mtime_ns
            if age_ns > max_age_ns:
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

@router.post("/submit")
async def deps_submit(
    rid: str = Form(...),
    attachment: UploadFile = File(...),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    """
    Dome side: post an encrypted manifest describing local artifacts.
    The blob is stored as-is; we never read it.
    """
    repo = _resolve_repo(rid, x_doc_ref)
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


@router.get("/queue")
def deps_queue(
    rid: Optional[str] = Query(None),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    """Work side: list outstanding requests for this repo."""
    repo = _resolve_repo(rid, x_doc_ref)
    req_dir = _requests_dir(repo)
    # D1: lazy cleanup before listing
    n = _cleanup_stale(req_dir)
    if n > 0 and system_logger:
        system_logger.info("deps TTL cleanup", {"dir": str(req_dir), "deleted": n})
    return {"success": True, "repo": repo, "items": _list_blobs(req_dir)}


@router.get("/queue-item")
def deps_queue_item(
    rid: Optional[str] = Query(None),
    id: str = Query(...),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    """Work side: download a specific request blob (encrypted manifest)."""
    repo = _resolve_repo(rid, x_doc_ref)
    item_id = _validate_id(id)
    path = _requests_dir(repo) / f"{item_id}.bin"
    if not path.exists():
        raise HTTPException(404, "Request not found")
    return FileResponse(path, media_type="application/octet-stream", filename=f"{item_id}.bin")


@router.post("/fulfill")
async def deps_fulfill(
    rid: str = Form(...),
    request_id: str = Form(...),
    attachment: UploadFile = File(...),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    """
    Work side: upload an encrypted archive in response to a manifest.
    Once accepted, the original request blob is deleted (one-shot).
    """
    repo = _resolve_repo(rid, x_doc_ref)
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


@router.get("/ready")
def deps_ready(
    rid: Optional[str] = Query(None),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    """Dome side: list ready responses for this repo."""
    repo = _resolve_repo(rid, x_doc_ref)
    resp_dir = _responses_dir(repo)
    # D1: lazy cleanup before listing
    n = _cleanup_stale(resp_dir)
    if n > 0 and system_logger:
        system_logger.info("deps TTL cleanup", {"dir": str(resp_dir), "deleted": n})
    return {"success": True, "repo": repo, "items": _list_blobs(resp_dir)}


@router.get("/ready-item")
def deps_ready_item(
    rid: Optional[str] = Query(None),
    id: str = Query(...),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    """Dome side: download a response blob."""
    repo = _resolve_repo(rid, x_doc_ref)
    item_id = _validate_id(id)
    path = _responses_dir(repo) / f"{item_id}.bin"
    if not path.exists():
        raise HTTPException(404, "Response not found")
    return FileResponse(path, media_type="application/octet-stream", filename=f"{item_id}.bin")


@router.delete("/ack")
def deps_ack(
    rid: Optional[str] = Query(None),
    id: str = Query(...),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    """Dome side: confirm a response has been applied; server deletes it."""
    repo = _resolve_repo(rid, x_doc_ref)
    item_id = _validate_id(id)
    path = _responses_dir(repo) / f"{item_id}.bin"
    if path.exists():
        try:
            path.unlink()
        except OSError as e:
            raise HTTPException(500, f"Failed to delete: {e}")
    return {"success": True, "repo": repo, "id": item_id}
