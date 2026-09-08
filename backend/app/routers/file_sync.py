"""
Repo-scoped encrypted file postbox.

The backend stores opaque encrypted file containers uploaded by the plugin. It
validates repo/id/path metadata and streams bytes to disk, but never decrypts or
inspects the file payload.
"""
import hashlib
import json
import re
import time
import uuid
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse

from app.routers._rid import resolve_repo_identifier

router = APIRouter(prefix="/api/documents", tags=["documents"])

repo_manager = None
system_logger = None

_SAFE_REPO = re.compile(r"^[A-Za-z0-9_-][A-Za-z0-9_.-]*$")
_SAFE_ID = re.compile(r"^[A-Za-z0-9-]+$")
_MAX_REL_PATH = 512
_MAX_FILE_SIZE = 2 * 1024 * 1024 * 1024  # 2 GB safety cap
_CHUNK_SIZE = 1024 * 1024


def _validate_repo(repo: str) -> str:
    repo = (repo or "").strip()
    if not repo or repo in {".", ".."} or ".." in repo or not _SAFE_REPO.fullmatch(repo):
        raise HTTPException(400, "Invalid repo name")
    return repo


def _resolve_repo(rid_query: Optional[str], doc_ref_header: Optional[str]) -> str:
    value = (rid_query or "").strip() or (doc_ref_header or "").strip()
    resolved = resolve_repo_identifier(value, repo_manager)
    return _validate_repo(resolved)


def _validate_id(value: str) -> str:
    value = (value or "").strip()
    if not value or len(value) > 64 or not _SAFE_ID.fullmatch(value):
        raise HTTPException(400, "Invalid id")
    return value


def _validate_rel_path(value: str) -> str:
    value = (value or "").replace("\\", "/").strip()
    if not value or value.startswith("/") or len(value) > _MAX_REL_PATH:
        raise HTTPException(400, "Invalid relative path")
    parts = [p for p in value.split("/") if p]
    if not parts or any(p in {".", ".."} for p in parts):
        raise HTTPException(400, "Invalid relative path")
    return "/".join(parts)


def _root() -> Path:
    if repo_manager is None:
        raise HTTPException(500, "Repo manager not initialised")
    root = Path(repo_manager.storage_path) / ".lgm" / "files"
    root.mkdir(parents=True, exist_ok=True)
    return root


def _repo_hash(repo: str) -> str:
    return hashlib.sha256(repo.encode("utf-8")).hexdigest()[:16]


def _repo_dir(repo: str) -> Path:
    path = _root() / _repo_hash(repo)
    path.mkdir(parents=True, exist_ok=True)
    return path


def _meta_path(repo: str, item_id: str) -> Path:
    return _repo_dir(repo) / f"{item_id}.json"


def _blob_path(repo: str, item_id: str) -> Path:
    return _repo_dir(repo) / f"{item_id}.bin"


def _cleanup_stale(directory: Path, max_age_seconds: int = 7 * 24 * 3600) -> None:
    now = time.time()
    for path in directory.glob("*.bin"):
        try:
            if now - path.stat().st_mtime <= max_age_seconds:
                continue
            item_id = path.stem
            path.unlink(missing_ok=True)
            (directory / f"{item_id}.json").unlink(missing_ok=True)
        except OSError:
            pass


def _list_items(repo: str) -> list[dict]:
    directory = _repo_dir(repo)
    _cleanup_stale(directory)
    items: list[dict] = []
    for meta in directory.glob("*.json"):
        try:
            data = json.loads(meta.read_text(encoding="utf-8"))
            blob = directory / f"{meta.stem}.bin"
            if not blob.exists():
                continue
            st = blob.stat()
            items.append({
                "id": meta.stem,
                "path": data.get("path", ""),
                "size": int(st.st_size),
                "plain_size": int(data.get("plain_size") or 0),
                "mtime": int(st.st_mtime),
            })
        except (OSError, ValueError, TypeError):
            continue
    items.sort(key=lambda item: item["mtime"], reverse=True)
    return items


@router.post("/attachment-upload")
async def docs_attachment_upload(
    rid: str = Form(""),
    path: str = Form(""),
    plain_size: int = Form(0),
    attachment: UploadFile = File(...),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    repo = _resolve_repo(rid, x_doc_ref)
    rel_path = _validate_rel_path(path)
    if plain_size < 0 or plain_size > _MAX_FILE_SIZE:
        raise HTTPException(400, "Invalid file size")

    directory = _repo_dir(repo)
    _cleanup_stale(directory)
    item_id = uuid.uuid4().hex
    target = directory / f"{item_id}.bin"
    tmp = directory / f"{item_id}.tmp"

    total = 0
    try:
        with tmp.open("wb") as out:
            while True:
                chunk = await attachment.read(_CHUNK_SIZE)
                if not chunk:
                    break
                total += len(chunk)
                if total > _MAX_FILE_SIZE:
                    raise HTTPException(413, "File too large")
                out.write(chunk)
        if total <= 0:
            raise HTTPException(400, "Empty file")
        tmp.replace(target)
        _meta_path(repo, item_id).write_text(
            json.dumps({"path": rel_path, "plain_size": plain_size}, ensure_ascii=False),
            encoding="utf-8",
        )
    except HTTPException:
        tmp.unlink(missing_ok=True)
        target.unlink(missing_ok=True)
        raise
    except OSError as exc:
        tmp.unlink(missing_ok=True)
        target.unlink(missing_ok=True)
        raise HTTPException(500, f"Failed to store file: {exc}")

    if system_logger:
        system_logger.info("file-sync item stored", {"repo": repo, "id": item_id, "bytes": total})
    return {"success": True, "repo": repo, "id": item_id, "path": rel_path, "size": total}


@router.get("/attachment-list")
def docs_attachment_list(
    rid: Optional[str] = Query(None),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    repo = _resolve_repo(rid, x_doc_ref)
    return {"success": True, "repo": repo, "items": _list_items(repo)}


@router.get("/attachment-get")
def docs_attachment_get(
    rid: Optional[str] = Query(None),
    id: str = Query(...),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    repo = _resolve_repo(rid, x_doc_ref)
    item_id = _validate_id(id)
    blob = _blob_path(repo, item_id)
    meta = _meta_path(repo, item_id)
    if not blob.exists() or not meta.exists():
        raise HTTPException(404, "File not found")
    return FileResponse(blob, media_type="application/octet-stream", filename=f"{item_id}.bin")


@router.delete("/attachment-ack")
def docs_attachment_ack(
    rid: Optional[str] = Query(None),
    id: str = Query(...),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    repo = _resolve_repo(rid, x_doc_ref)
    item_id = _validate_id(id)
    blob = _blob_path(repo, item_id)
    meta = _meta_path(repo, item_id)
    existed = blob.exists() or meta.exists()
    try:
        blob.unlink(missing_ok=True)
        meta.unlink(missing_ok=True)
    except OSError as exc:
        raise HTTPException(500, f"Failed to delete file: {exc}")
    return {"success": True, "deleted": existed, "repo": repo, "id": item_id}
