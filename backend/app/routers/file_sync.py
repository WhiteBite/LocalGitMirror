"""
Repo-scoped encrypted file postbox.

The backend stores encrypted file containers uploaded by the plugin. Uploads
arrive sealed either with the v3 relay (form field "k" carries the client's
ephemeral X25519 public key) or with the legacy shared password. A v3 upload
carries its routing metadata (path, plain_size) relay-sealed in the "meta"
form field; the cleartext path/plain_size fields then hold only neutral
values. The server
terminates the crypto: it decrypts on write, stores the plaintext re-sealed at
rest under an independent relay key (0x04 || nonce || AES-GCM), and re-seals on
read for whichever mode the reader speaks — the "X-LGM-Epk" request header
selects the v3 relay, its absence falls back to the legacy password bundle.
"""
import base64
import hashlib
import json
import os
import re
import time
import uuid
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.responses import Response

from app.core import hybrid_crypto
from app.core.bundle_crypto import decrypt_dump_bytes, encrypt_bundle_bytes
from app.routers._rid import resolve_repo_identifier

router = APIRouter(prefix="/api/documents", tags=["documents"])

repo_manager = None
system_logger = None
# v3 relay: X25519 wire key + independent at-rest key; None => password-only.
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
                "path_enc": data.get("path_enc", ""),
                "size": int(st.st_size),
                "plain_size": int(data.get("plain_size") or 0),
                "mtime": int(st.st_mtime),
            })
        except (OSError, ValueError, TypeError):
            continue
    items.sort(key=lambda item: item["mtime"], reverse=True)
    return items


def _sync_password() -> str:
    return os.getenv("SYNC_PASSWORD", "")


def _decrypt_incoming(payload: bytes, k: str, aad: bytes) -> bytes:
    """Open an uploaded blob and return the bytes to store at rest."""
    password = _sync_password()
    if k and server_private_key is not None:
        if relay_key is None:
            raise HTTPException(503, "relay key not initialised")
        try:
            ctx = hybrid_crypto.HybridServerContext(server_private_key, hybrid_crypto.decode_epk(k))
            plaintext = ctx.open_relay(payload, aad)
        except Exception:
            raise HTTPException(400, "Failed to decrypt blob (v3): wrong server key or corrupted data")
        return hybrid_crypto.relay_encrypt_at_rest(relay_key, plaintext, aad)
    if password:
        try:
            plaintext = decrypt_dump_bytes(payload, password)
        except Exception:
            raise HTTPException(400, "Failed to decrypt blob: check that SYNC_PASSWORD matches")
        if relay_key is None:
            return payload
        return hybrid_crypto.relay_encrypt_at_rest(relay_key, plaintext, aad)
    raise HTTPException(400, "Blob is not v3-sealed and SYNC_PASSWORD is not set — nothing to decrypt with")


def _serve_blob(path: Path, aad: bytes, epk: Optional[str]) -> Response:
    """Read a stored blob and re-seal it for the requesting reader."""
    blob = path.read_bytes()
    password = _sync_password()
    if blob[:1] == b"\x04":
        try:
            plaintext = hybrid_crypto.relay_decrypt_at_rest(relay_key, blob, aad)
        except Exception:
            raise HTTPException(400, "Stored blob failed to decrypt: corrupted or sealed under a different AAD")
    elif blob[:1] in (b"\x01", b"L") and password:
        try:
            plaintext = decrypt_dump_bytes(blob, password)
        except Exception:
            raise HTTPException(400, "Stored legacy blob failed to decrypt: wrong SYNC_PASSWORD")
    else:
        raise HTTPException(409, "Stored blob is in a legacy format the reader cannot decrypt (no password on server)")
    if epk and server_private_key is not None:
        try:
            ctx = hybrid_crypto.HybridServerContext(server_private_key, hybrid_crypto.decode_epk(epk))
        except Exception:
            raise HTTPException(400, "Invalid ephemeral key (X-LGM-Epk)")
        return Response(ctx.seal_relay(plaintext, aad), media_type="application/octet-stream")
    if password:
        return Response(encrypt_bundle_bytes(plaintext, password), media_type="application/octet-stream")
    raise HTTPException(400, "Reader sent no X-LGM-Epk and SYNC_PASSWORD is not set — nothing to seal with")


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


@router.post("/attachment-upload")
async def docs_attachment_upload(
    rid: str = Form(""),
    path: str = Form(""),
    plain_size: int = Form(0),
    path_enc: str = Form(""),
    k: str = Form(""),
    meta: str = Form(""),
    attachment: UploadFile = File(...),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    repo = _resolve_repo(rid, x_doc_ref)
    if meta:
        rel_path, plain_size = _open_sealed_meta(k, meta)
    else:
        rel_path = _validate_rel_path(path)
        if plain_size < 0 or plain_size > _MAX_FILE_SIZE:
            raise HTTPException(400, "Invalid file size")

    payload = await attachment.read()
    if not payload:
        raise HTTPException(400, "Empty file")
    if len(payload) > _MAX_FILE_SIZE:
        raise HTTPException(413, "File too large")

    at_rest = _decrypt_incoming(payload, k, hybrid_crypto.RELAY_AAD_POSTBOX)

    directory = _repo_dir(repo)
    _cleanup_stale(directory)
    item_id = uuid.uuid4().hex
    target = directory / f"{item_id}.bin"
    tmp = directory / f"{item_id}.tmp"

    try:
        tmp.write_bytes(at_rest)
        tmp.replace(target)
        item_meta = {"path": rel_path, "plain_size": plain_size}
        if path_enc:
            item_meta["path_enc"] = path_enc
        _meta_path(repo, item_id).write_text(
            json.dumps(item_meta, ensure_ascii=False),
            encoding="utf-8",
        )
    except OSError as exc:
        tmp.unlink(missing_ok=True)
        target.unlink(missing_ok=True)
        raise HTTPException(500, f"Failed to store file: {exc}")

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
    return _serve_blob(blob, hybrid_crypto.RELAY_AAD_POSTBOX, x_lgm_epk)


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
