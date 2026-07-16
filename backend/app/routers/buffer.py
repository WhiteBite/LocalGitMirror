"""
Cross-machine clipboard buffer.

A lightweight pastebin for short snippets the user wants to ship between
machines without juggling Telegram. The server only stores opaque ciphertext
(encrypted client-side with the same SYNC_PASSWORD used by everything else),
plus a tiny unencrypted hint so the UI can show a preview without decrypting
every entry on list.

Design choices:
  * In-memory ring buffer (deque) — no DB, no persistence. A server restart
    clears the buffer; that's acceptable for an ephemeral clipboard.
  * Hard caps: MAX_ITEMS most recent entries, MAX_SIZE bytes per ciphertext,
    TTL seconds (pruned on every access). Defaults match the user-approved
    plan: 50 items / 1 MB / 24h.
  * Auth: mounted under the same Depends(get_api_key) as the rest of /api/*.
"""

from __future__ import annotations

import base64
import os
import threading
import time
import uuid
from typing import Any, Deque, Optional
from collections import deque

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import Response
from pydantic import BaseModel

router = APIRouter(prefix="/api/buffer", tags=["buffer"])

# ── Tunables ─────────────────────────────────────────────────────────────
# Read at import time but each endpoint re-reads MAX_TTL_SECONDS via _ttl()
# so users can override via env without restart-of-restart.
MAX_ITEMS = int(os.getenv("LGM_BUFFER_MAX_ITEMS", "50"))
MAX_SIZE = int(os.getenv("LGM_BUFFER_MAX_SIZE", str(1 * 1024 * 1024)))  # 1 MB
DEFAULT_TTL_SECONDS = int(os.getenv("LGM_BUFFER_TTL_SECONDS", str(24 * 3600)))

_lock = threading.Lock()
_items: Deque[dict] = deque(maxlen=MAX_ITEMS)


def _ttl() -> int:
    """Re-read TTL on each access so it can be tuned without server restart."""
    try:
        return int(os.getenv("LGM_BUFFER_TTL_SECONDS", str(DEFAULT_TTL_SECONDS)))
    except ValueError:
        return DEFAULT_TTL_SECONDS


def _prune_locked() -> None:
    """Drop entries older than TTL. Must be called with _lock held."""
    ttl = _ttl()
    if ttl <= 0:
        return
    cutoff = time.time() - ttl
    # Walk from oldest end (left) and discard while expired.
    while _items and _items[0]["ts_epoch"] < cutoff:
        _items.popleft()


def _find_locked(item_id: str) -> Optional[dict]:
    for it in _items:
        if it["id"] == item_id:
            return it
    return None


# ── Pydantic ─────────────────────────────────────────────────────────────


class BufferPutRequest(BaseModel):
    # Ciphertext base64-encoded for transport; the server treats it as opaque.
    ciphertext_b64: str
    # Optional plain-text preview (kept short; truncated server-side). Leaving
    # the hint unencrypted is deliberate: it powers the list UI without a
    # round-trip per row. Anyone who can hit /api/buffer already has the API
    # key, so this hint reveals nothing extra.
    hint: Optional[str] = None


class BufferPutResponse(BaseModel):
    id: str
    ts: float


# ── Endpoints ────────────────────────────────────────────────────────────


@router.post("", response_model=BufferPutResponse)
async def buffer_put(req: BufferPutRequest):
    """Append a new entry. Oldest entries fall off when the cap is reached."""
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

    item = {
        "id": uuid.uuid4().hex[:12],
        "ts_epoch": time.time(),
        "hint": (req.hint or "")[:120],
        "size": len(ciphertext),
        "ciphertext": ciphertext,
    }
    with _lock:
        _prune_locked()
        _items.append(item)

    return BufferPutResponse(id=item["id"], ts=item["ts_epoch"])


@router.get("")
async def buffer_list():
    """Metadata only (newest first) — ciphertexts are fetched per-id on demand."""
    with _lock:
        _prune_locked()
        out: list[dict[str, Any]] = []
        # newest first
        for it in reversed(_items):
            out.append({
                "id": it["id"],
                "ts": it["ts_epoch"],
                "size": it["size"],
                "hint": it["hint"],
            })
    return {"items": out, "limits": {"max_items": MAX_ITEMS, "max_size": MAX_SIZE, "ttl": _ttl()}}


@router.get("/{item_id}")
async def buffer_get(item_id: str):
    """Return raw ciphertext bytes for a single entry."""
    with _lock:
        _prune_locked()
        it = _find_locked(item_id)
        if it is None:
            raise HTTPException(status_code=404, detail="Not Found")
        data = it["ciphertext"]
    return Response(content=data, media_type="application/octet-stream")


@router.delete("/{item_id}", status_code=204)
async def buffer_delete(item_id: str):
    with _lock:
        _prune_locked()
        for i, it in enumerate(_items):
            if it["id"] == item_id:
                del _items[i]
                return Response(status_code=204)
    raise HTTPException(status_code=404, detail="Not Found")


@router.delete("", status_code=204)
async def buffer_clear():
    with _lock:
        _items.clear()
    return Response(status_code=204)
