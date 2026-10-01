"""
Repo-scoped encrypted file postbox — protocol v3 relay plus a loopback
plaintext path for the home-host SPA.

Remote uploads arrive relay-sealed to the server's long-term X25519 key: the
"k" form field carries the client's ephemeral public key, "meta" the
relay-sealed routing metadata {"path", "plain_size"}, and the attachment body
is a relay-sealed blob. The server terminates the crypto: it decrypts on
write, stores the plaintext re-sealed at rest under the independent relay key
(0x04 || nonce || AES-GCM), and re-seals on read for the ephemeral key sent
in the "X-LGM-Epk" request header.

Loopback clients (the SPA served on the HOME host, a fully trusted machine)
may omit "k"/"meta" and send the legacy-shaped "path" + "plain_size" fields
with a plaintext attachment; it is sealed at rest the same way and served
back as plaintext on reads without "X-LGM-Epk". Non-loopback clients without
the v3 fields fail closed.
"""
import base64
import hashlib
import json
import re
import time
import uuid
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, File, Form, Header, HTTPException, Query, Request, UploadFile
from fastapi.responses import Response
from starlette.concurrency import run_in_threadpool

from app.core import hybrid_crypto
from app.core.mirror_dataplane import _is_loopback
from app.routers._rid import resolve_repo_identifier

router = APIRouter(prefix="/api/documents", tags=["documents"])

repo_manager = None
system_logger = None
server_private_key = None
relay_key = None

_SAFE_REPO = re.compile(r"^[A-Za-z0-9_-][A-Za-z0-9_.-]*$")
_SAFE_ID = re.compile(r"^[A-Za-z0-9-]+$")
_MAX_REL_PATH = 512
_MAX_FILE_SIZE = 2 * 1024 * 1024 * 1024  # 2 GB safety cap


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


def _decrypt_incoming(payload: bytes, k: str, aad: bytes) -> bytes:
    """Open a v3 relay-sealed upload and return the bytes to store at rest."""
    if relay_key is None:
        raise HTTPException(503, "relay key not initialised")
    try:
        ctx = hybrid_crypto.HybridServerContext(server_private_key, hybrid_crypto.decode_epk(k))
        plaintext = ctx.open_relay(payload, aad)
    except Exception:
        raise HTTPException(400, "Failed to decrypt blob (v3): wrong server key or corrupted data")
    return hybrid_crypto.relay_encrypt_at_rest(relay_key, plaintext, aad)


def _serve_blob(path: Path, aad: bytes, epk: Optional[str]) -> Response:
    """Read a stored v3 blob and re-seal it for the requesting reader's ephemeral key."""
    if not epk:
        raise HTTPException(400, "X-LGM-Epk header is required (v3-only postbox)")
    if server_private_key is None:
        raise HTTPException(503, "Server hybrid key not initialised")
    if relay_key is None:
        raise HTTPException(503, "relay key not initialised")
    blob = path.read_bytes()
    try:
        plaintext = hybrid_crypto.relay_decrypt_at_rest(relay_key, blob, aad)
    except Exception:
        raise HTTPException(400, "Stored blob failed to decrypt: not a v3 at-rest blob, corrupted, or sealed under a different AAD")
    try:
        ctx = hybrid_crypto.HybridServerContext(server_private_key, hybrid_crypto.decode_epk(epk))
    except Exception:
        raise HTTPException(400, "Invalid ephemeral key (X-LGM-Epk)")
    return Response(ctx.seal_relay(plaintext, aad), media_type="application/octet-stream")


def _open_sealed_meta(k: str, meta_b64: str) -> tuple[str, int]:
    """Open the v3-sealed upload metadata {"path", "plain_size"}; 400 on any tamper."""
    if server_private_key is None:
        raise HTTPException(400, "Sealed metadata requires the v3 server key")
    try:
        ctx = hybrid_crypto.HybridServerContext(server_private_key, hybrid_crypto.decode_epk(k))
        blob = base64.b64decode(meta_b64, validate=True)
        data = json.loads(ctx.open_relay(blob, hybrid_crypto.RELAY_AAD_POSTBOX).decode("utf-8"))
        path = _validate_rel_path(data["path"])
        size = int(data["plain_size"])
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(400, "Invalid sealed metadata")
    if size < 0 or size > _MAX_FILE_SIZE:
        raise HTTPException(400, "Invalid file size")
    return path, size


def _open_plaintext_meta(path: str, plain_size: str) -> tuple[str, int]:
    """Validate loopback plaintext upload fields; 400 on any bad value."""
    try:
        size = int((plain_size or "").strip())
    except ValueError:
        raise HTTPException(400, "Invalid plain_size")
    if size < 0 or size > _MAX_FILE_SIZE:
        raise HTTPException(400, "Invalid file size")
    return _validate_rel_path(path), size


def _serve_plaintext(path: Path) -> Response:
    """Loopback read: return the stored plaintext without the v3 re-seal."""
    blob = path.read_bytes()
    if blob[:1] == bytes([hybrid_crypto.RELAY_AT_REST_VERSION]):
        if relay_key is None:
            raise HTTPException(503, "relay key not initialised")
        try:
            blob = hybrid_crypto.relay_decrypt_at_rest(relay_key, blob, hybrid_crypto.RELAY_AAD_POSTBOX)
        except Exception:
            raise HTTPException(400, "Stored blob failed to decrypt: not a v3 at-rest blob, corrupted, or sealed under a different AAD")
    return Response(blob, media_type="application/octet-stream")


@router.post("/attachment-upload")
async def docs_attachment_upload(
    request: Request,
    rid: str = Form(""),
    k: str = Form(""),
    meta: str = Form(""),
    path: str = Form(""),
    plain_size: str = Form(""),
    attachment: UploadFile = File(...),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    repo = _resolve_repo(rid, x_doc_ref)
    v3 = bool(k and meta)
    if v3:
        rel_path, size_meta = _open_sealed_meta(k, meta)
    elif _is_loopback(request.client.host if request.client else ""):
        rel_path, size_meta = _open_plaintext_meta(path, plain_size)
    else:
        raise HTTPException(400, "v3 upload requires form fields 'k' and 'meta'")

    payload = await attachment.read()
    if not payload:
        raise HTTPException(400, "Empty file")
    if len(payload) > _MAX_FILE_SIZE:
        raise HTTPException(413, "File too large")

    def _store() -> str:
        if v3:
            at_rest = _decrypt_incoming(payload, k, hybrid_crypto.RELAY_AAD_POSTBOX)
        elif relay_key is not None:
            at_rest = hybrid_crypto.relay_encrypt_at_rest(
                relay_key, payload, hybrid_crypto.RELAY_AAD_POSTBOX)
        else:
            at_rest = payload

        directory = _repo_dir(repo)
        _cleanup_stale(directory)
        item_id = uuid.uuid4().hex
        target = directory / f"{item_id}.bin"
        tmp = directory / f"{item_id}.tmp"

        try:
            tmp.write_bytes(at_rest)
            tmp.replace(target)
            _meta_path(repo, item_id).write_text(
                json.dumps({"path": rel_path, "plain_size": size_meta}, ensure_ascii=False),
                encoding="utf-8",
            )
        except OSError as exc:
            tmp.unlink(missing_ok=True)
            target.unlink(missing_ok=True)
            raise HTTPException(500, f"Failed to store file: {exc}")
        return item_id

    item_id = await run_in_threadpool(_store)

    if system_logger:
        system_logger.info("file-sync item stored", {"repo": repo, "id": item_id, "bytes": len(payload)})
    return {"success": True, "repo": repo, "id": item_id, "path": rel_path, "size": len(payload)}


@router.get("/attachment-list")
def docs_attachment_list(
    rid: Optional[str] = Query(None),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    repo = _resolve_repo(rid, x_doc_ref)
    return {"success": True, "repo": repo, "items": _list_items(repo)}


@router.get("/attachment-get")
def docs_attachment_get(
    request: Request,
    rid: Optional[str] = Query(None),
    id: str = Query(...),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
    x_lgm_epk: Optional[str] = Header(None, alias="X-LGM-Epk"),
):
    repo = _resolve_repo(rid, x_doc_ref)
    item_id = _validate_id(id)
    blob = _blob_path(repo, item_id)
    meta = _meta_path(repo, item_id)
    if not blob.exists() or not meta.exists():
        raise HTTPException(404, "File not found")
    if x_lgm_epk:
        return _serve_blob(blob, hybrid_crypto.RELAY_AAD_POSTBOX, x_lgm_epk)
    if not _is_loopback(request.client.host if request.client else ""):
        raise HTTPException(400, "X-LGM-Epk header is required (v3-only postbox)")
    return _serve_plaintext(blob)


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
