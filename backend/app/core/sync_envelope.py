"""v3/password envelope transport for the sync API."""

import os
from contextvars import ContextVar
from typing import Optional

from fastapi import HTTPException

from app.core import hybrid_crypto
from app.core.envelope_crypto import decrypt_envelope as _pw_decrypt_envelope
from app.core.envelope_crypto import encrypt_envelope as _pw_encrypt_envelope

# Injected from main.py during lifespan; None = legacy password path only.
server_private_key = None


def get_server_private_key():
    return server_private_key


# Per-request v3 context: set by _decrypt_params, read by the envelope/bundle helpers.
_hybrid_ctx: ContextVar = ContextVar("lgm_hybrid_ctx", default=None)

# Separate v3 context for the upload attachment: "kb" ephemeral, decoupled from the envelope's "k"/"epk".
_hybrid_bundle_ctx: ContextVar = ContextVar("lgm_hybrid_bundle_ctx", default=None)


def encrypt_envelope(payload: dict, password: str) -> str:
    """Seal a response envelope.

    Uses the per-request v3 hybrid context when one is active (client spoke v3),
    otherwise falls back to the legacy password envelope. Handler code calls
    this exactly as before — the mode is selected transparently.
    """
    ctx = _hybrid_ctx.get()
    if ctx is not None:
        return ctx.seal_envelope(payload)
    return _pw_encrypt_envelope(payload, password)


def decrypt_envelope(b64: str, password: str) -> dict:
    """Open a request envelope (legacy password path only).

    v3 requests are opened in _decrypt_params via the hybrid context; this
    wrapper exists for any remaining direct callers and the password path.
    """
    ctx = _hybrid_ctx.get()
    if ctx is not None:
        return ctx.open_envelope(b64)
    return _pw_decrypt_envelope(b64, password)


def _sync_password() -> str:
    """Return SYNC_PASSWORD from env (may be empty).

    Intentionally does NOT raise: a server that only serves v3 (hybrid) clients
    needs no shared password at all. The legacy-path requirement is enforced in
    _decrypt_params instead, which knows whether the request is hybrid.
    """
    return os.getenv("SYNC_PASSWORD", "")


def _decrypt_params(e: str, password: str, epk: Optional[str] = None) -> dict:
    """Decrypt envelope field. Raises 400 on invalid/tampered data.

    When `epk` (the client's ephemeral X25519 public key) is supplied, the
    request MUST take the v3 path: 503 if the server has no static key
    configured, 400 if the ephemeral key or envelope fails to open. Only
    requests without `epk` use the legacy password path.
    """
    if epk:
        if server_private_key is None:
            raise HTTPException(503, "v3 not available: server hybrid key not configured")
        try:
            ctx = hybrid_crypto.HybridServerContext(
                server_private_key, hybrid_crypto.decode_epk(epk)
            )
            params = ctx.open_envelope(e)
        except Exception:
            raise HTTPException(400, "Invalid ephemeral key")
        _hybrid_ctx.set(ctx)
        return params

    # Legacy password path — ensure no stale hybrid context leaks in.
    _hybrid_ctx.set(None)
    if not password:
        raise HTTPException(503, "Sync password not configured on server")
    try:
        return _pw_decrypt_envelope(e, password)
    except Exception:
        raise HTTPException(400, "Invalid request envelope")
