"""
Cross-machine clipboard buffer.

A lightweight pastebin for short snippets the user wants to ship between
machines without juggling Telegram. The server only stores opaque ciphertext
(encrypted client-side with the same SYNC_PASSWORD used by everything else);
``hint_enc`` is an equally opaque client-encrypted preview blob — the server
never sees a single plaintext byte of content.

Design choices:
  * Disk-backed store (<storage>/.lgm/buffer/, overridable via LGM_BUFFER_DIR):
    entries survive a server restart, RAM holds only metadata.
  * Hard caps: MAX_ITEMS entries, MAX_SIZE bytes per ciphertext, TTL seconds.
    Pinned entries are exempt from TTL and eviction.
  * Auth: mounted under the same Depends(get_api_key) as the rest of /api/*.
"""

from __future__ import annotations

import base64
import json
import os
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Optional

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel

router = APIRouter(prefix="/api/buffer", tags=["buffer"])

MAX_ITEMS = int(os.getenv("LGM_BUFFER_MAX_ITEMS", "50"))
MAX_SIZE = int(os.getenv("LGM_BUFFER_MAX_SIZE", str(8 * 1024 * 1024)))  # 8 MB
DEFAULT_TTL_SECONDS = int(os.getenv("LGM_BUFFER_TTL_SECONDS", str(24 * 3600)))

_lock = threading.Lock()
_items: dict[str, dict] = {}
_loaded = False

# Wired from main.py lifespan; falls back to LGM_BUFFER_DIR or ./storage.
storage_dir: Optional[Path] = None


def _dir() -> Path:
    if storage_dir is not None:
        d = storage_dir
    else:
        d = Path(os.getenv("LGM_BUFFER_DIR", "storage/.lgm/buffer"))
    d.mkdir(parents=True, exist_ok=True)
    return d


def _ttl() -> int:
    try:
        return int(os.getenv("LGM_BUFFER_TTL_SECONDS", str(DEFAULT_TTL_SECONDS)))
    except ValueError:
        return DEFAULT_TTL_SECONDS


def _meta_path(item_id: str) -> Path:
    return _dir() / f"{item_id}.json"


def _blob_path(item_id: str) -> Path:
    return _dir() / f"{item_id}.bin"


def _write_item(item: dict, ciphertext: bytes | None) -> None:
    if ciphertext is not None:
        _blob_path(item["id"]).write_bytes(ciphertext)
    _meta_path(item["id"]).write_text(
        json.dumps({k: item[k] for k in ("id", "ts", "hint", "hint_enc", "pinned", "size")},
                   ensure_ascii=False),
        encoding="utf-8",
    )


def _drop_locked(item_id: str) -> None:
    _items.pop(item_id, None)
    _blob_path(item_id).unlink(missing_ok=True)
    _meta_path(item_id).unlink(missing_ok=True)


def _load_locked() -> None:
    global _loaded
    if _loaded:
        return
    _loaded = True
    for meta in _dir().glob("*.json"):
        try:
            data = json.loads(meta.read_text(encoding="utf-8"))
            item = {
                "id": str(data["id"]),
                "ts": float(data["ts"]),
                "hint": str(data.get("hint") or ""),
                "hint_enc": str(data.get("hint_enc") or ""),
                "pinned": bool(data.get("pinned")),
                "size": int(data.get("size") or 0),
            }
        except (OSError, ValueError, KeyError, TypeError):
            continue
        if not _blob_path(item["id"]).exists():
            continue
        _items[item["id"]] = item
    _prune_locked()


def _prune_locked() -> None:
    ttl = _ttl()
    if ttl > 0:
        cutoff = time.time() - ttl
        for item_id in [i for i, it in _items.items()
                        if not it["pinned"] and it["ts"] < cutoff]:
            _drop_locked(item_id)
    overflow = len(_items) - MAX_ITEMS
    if overflow > 0:
        evictable = sorted((it for it in _items.values() if not it["pinned"]),
                           key=lambda it: it["ts"])
        for it in evictable[:overflow]:
            _drop_locked(it["id"])


def _find(item_id: str) -> Optional[dict]:
    return _items.get(item_id)


# ── Pydantic ─────────────────────────────────────────────────────────────


class BufferPutRequest(BaseModel):
    # Ciphertext base64-encoded for transport; the server treats it as opaque.
    ciphertext_b64: str
    # Legacy plaintext preview (old clients). Ignored when hint_enc is present.
    hint: Optional[str] = None
    # Client-encrypted preview blob (bundle-format, base64) — opaque to us.
    hint_enc: Optional[str] = None
    pinned: bool = False


class BufferPutResponse(BaseModel):
    id: str
    ts: float


class BufferPinRequest(BaseModel):
    pinned: bool


# ── Endpoints ────────────────────────────────────────────────────────────


@router.post("", response_model=BufferPutResponse)
async def buffer_put(req: BufferPutRequest):
    """Append a new entry. Oldest non-pinned entries fall off at the cap."""
    try:
        ciphertext = base64.b64decode(req.ciphertext_b64, validate=True)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid base64")

    if not ciphertext:
        raise HTTPException(status_code=400, detail="Empty ciphertext")
    if len(ciphertext) > MAX_SIZE:
        raise HTTPException(
            status_code=413,
            detail=f"Too large: {len(ciphertext)} > {MAX_SIZE}",
        )

    hint_enc = (req.hint_enc or "")[:2048]
    item = {
        "id": uuid.uuid4().hex[:12],
        "ts": time.time(),
        # Plaintext hint is stored only when no encrypted preview was supplied.
        "hint": "" if hint_enc else (req.hint or "")[:120],
        "hint_enc": hint_enc,
        "pinned": bool(req.pinned),
        "size": len(ciphertext),
    }
    with _lock:
        _load_locked()
        _write_item(item, ciphertext)
        _items[item["id"]] = item
        _prune_locked()

    return BufferPutResponse(id=item["id"], ts=item["ts"])


@router.get("")
async def buffer_list():
    """Metadata only (newest first) — ciphertexts are fetched per-id on demand."""
    with _lock:
        _load_locked()
        _prune_locked()
        out: list[dict[str, Any]] = []
        for it in sorted(_items.values(), key=lambda i: i["ts"], reverse=True):
            out.append({
                "id": it["id"],
                "ts": it["ts"],
                "size": it["size"],
                "hint": it["hint"],
                "hint_enc": it["hint_enc"],
                "pinned": it["pinned"],
            })
    return {"items": out, "limits": {"max_items": MAX_ITEMS, "max_size": MAX_SIZE, "ttl": _ttl()}}


@router.get("/{item_id}")
async def buffer_get(item_id: str):
    """Return raw ciphertext bytes for a single entry."""
    with _lock:
        _load_locked()
        _prune_locked()
        it = _find(item_id)
        if it is None:
            raise HTTPException(status_code=404, detail="Not Found")
    blob = _blob_path(item_id)
    if not blob.exists():
        raise HTTPException(status_code=404, detail="Not Found")
    return Response(content=blob.read_bytes(), media_type="application/octet-stream")


@router.post("/{item_id}/pin")
async def buffer_pin(item_id: str, req: BufferPinRequest):
    with _lock:
        _load_locked()
        it = _find(item_id)
        if it is None:
            raise HTTPException(status_code=404, detail="Not Found")
        it["pinned"] = bool(req.pinned)
        _write_item(it, None)
    return {"success": True, "id": item_id, "pinned": it["pinned"]}


@router.delete("/{item_id}", status_code=204)
async def buffer_delete(item_id: str):
    with _lock:
        _load_locked()
        if _find(item_id) is None:
            raise HTTPException(status_code=404, detail="Not Found")
        _drop_locked(item_id)
    return Response(status_code=204)


@router.delete("", status_code=204)
async def buffer_clear():
    with _lock:
        _load_locked()
        for item_id in list(_items):
            _drop_locked(item_id)
    return Response(status_code=204)
