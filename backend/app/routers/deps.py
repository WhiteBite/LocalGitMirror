"""
Gradle dependency-sync transport.

Two flows happen here:

  1. Dome  -> POST /documents/submit     : "I have these artifacts, send me what's
                                            missing for project X" (encrypted manifest)
  2. Work  -> GET  /documents/queue      : list outstanding requests
  3. Work  -> GET  /documents/queue-item : download a specific request blob
  4. Work  -> POST /documents/fulfill    : "here's the encrypted ZIP for request id"
  5. Dome  -> GET  /documents/ready      : list ready responses
  6. Dome  -> GET  /documents/ready-item : download the response ZIP
  7. Dome  -> DELETE /documents/ack     : confirm applied, server cleans up

Uploads arrive sealed either with the v3 relay (form field "k" carries the
client's ephemeral X25519 public key) or with the legacy shared password. The
server terminates the crypto: it decrypts on write, stores the plaintext
re-sealed at rest under an independent relay key (0x04 || nonce || AES-GCM),
and re-seals on read for whichever mode the reader speaks — "X-LGM-Epk"
request header selects the v3 relay, its absence falls back to the legacy
password bundle. The at-rest key never leaves the server, so leaking the
storage dir does not leak project deps.
"""
import hashlib
import os
import re
import time
import uuid
from pathlib import Path
from typing import List, Optional

from fastapi import APIRouter, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.responses import Response

from app.core import hybrid_crypto
from app.core.bundle_crypto import decrypt_dump_bytes, encrypt_bundle_bytes
from app.routers._rid import resolve_repo_identifier

router = APIRouter(prefix="/api/documents", tags=["documents"])

# Injected from main.py at startup, same pattern as the other routers.
repo_manager = None
system_logger = None
# v3 relay: X25519 wire key + independent at-rest key; None => password-only.
server_private_key = None
relay_key = None


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


# ─────────────────────────────────────────────────────────────────────────────
# Endpoints
# ─────────────────────────────────────────────────────────────────────────────

@router.post("/submit")
async def deps_submit(
    rid: str = Form(...),
    attachment: UploadFile = File(...),
    k: str = Form(""),
    x_doc_ref: Optional[str] = Header(None, alias="X-Doc-Ref"),
):
    """
    Dome side: post an encrypted manifest describing local artifacts.
    v3 uploads carry the client ephemeral public key as "k"; the plaintext is
    re-sealed at rest under the relay key.
    """
    repo = _resolve_repo(rid, x_doc_ref)
    payload = await attachment.read()
    if not payload:
        raise HTTPException(400, "Empty manifest")
    if len(payload) > 10 * 1024 * 1024:  # 10 MB hard cap on manifest
        raise HTTPException(413, "Manifest too large")

    at_rest = _decrypt_incoming(payload, k, hybrid_crypto.RELAY_AAD_DEPS_REQ)

    # D1: lazy cleanup before storing new request
    req_dir = _requests_dir(repo)
    n = _cleanup_stale(req_dir)
    if n > 0 and system_logger:
        system_logger.info("deps TTL cleanup", {"dir": str(req_dir), "deleted": n})

    item_id = uuid.uuid4().hex
    target = req_dir / f"{item_id}.bin"
    target.write_bytes(at_rest)

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
    x_lgm_epk: Optional[str] = Header(None, alias="X-LGM-Epk"),
):
    """Work side: download a specific request blob (encrypted manifest)."""
    repo = _resolve_repo(rid, x_doc_ref)
    item_id = _validate_id(id)
    path = _requests_dir(repo) / f"{item_id}.bin"
    if not path.exists():
        raise HTTPException(404, "Request not found")
    return _serve_blob(path, hybrid_crypto.RELAY_AAD_DEPS_REQ, x_lgm_epk)


@router.post("/fulfill")
async def deps_fulfill(
    rid: str = Form(...),
    request_id: str = Form(...),
    attachment: UploadFile = File(...),
    k: str = Form(""),
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

    at_rest = _decrypt_incoming(payload, k, hybrid_crypto.RELAY_AAD_DEPS_RESP)

    response_id = uuid.uuid4().hex
    target = _responses_dir(repo) / f"{response_id}.bin"
    target.write_bytes(at_rest)

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
    x_lgm_epk: Optional[str] = Header(None, alias="X-LGM-Epk"),
):
    """Dome side: download a response blob."""
    repo = _resolve_repo(rid, x_doc_ref)
    item_id = _validate_id(id)
    path = _responses_dir(repo) / f"{item_id}.bin"
    if not path.exists():
        raise HTTPException(404, "Response not found")
    return _serve_blob(path, hybrid_crypto.RELAY_AAD_DEPS_RESP, x_lgm_epk)


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
