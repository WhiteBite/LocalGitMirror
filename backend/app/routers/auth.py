"""Auth and capabilities endpoints for the sync API."""

import os
import struct

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response

from app.core import hybrid_crypto, sync_envelope
from app.core.bundle_crypto import MAGIC

router = APIRouter(prefix="/api", tags=["auth"])


@router.get("/health")
async def capabilities():
    # v3=True lets v3-capable clients skip the password probe entirely.
    has_v3 = sync_envelope.get_server_private_key() is not None
    has_password = bool(os.getenv("SYNC_PASSWORD", ""))
    return {
        "apiVersion": 1,
        "server": {
            "name": "DocCache",
            "version": "2026.03",
            "build": "dev",
        },
        "sync": {
            "protocolVersion": 1,
            "features": {
                "preflight": True,
                "dryRun": True,
                # passwordProbe=False on v3-only servers so clients skip the probe.
                "passwordProbe": has_password,
                "uploadAndApply": True,
                "hasCommits": True,
                "applyKnown": True,
                "exportDump": True,
                # v3: hybrid ECIES available; clients with the key pinned skip the probe.
                "v3": has_v3,
            },
            "modes": ["no-op", "pointer-only", "incremental", "full"],
        },
    }


@router.get("/auth/verify")
def sync_password_probe():
    """Password-based probe. Returns 503 with a JSON hint when the server runs
    in v3-only mode (no SYNC_PASSWORD). Clients that see capabilities.v3=True
    and have the server key pinned should skip this endpoint entirely.
    """
    password = os.getenv("SYNC_PASSWORD", "")
    if not password:
        # v3-only server: tell the client explicitly so it can skip the probe.
        if sync_envelope.get_server_private_key() is not None:
            from fastapi.responses import JSONResponse
            return JSONResponse(
                status_code=503,
                content={"detail": "Password probe disabled — server is v3-only. Use /api/auth/pubkey."},
            )
        raise HTTPException(503, "SYNC_PASSWORD not configured in environment")

    # Probe content — plugins check for "SYNC-PROBE" or legacy "LGM-PROBE"
    probe_data = b"SYNC-PROBE\n"
    salt = os.urandom(16)
    nonce = os.urandom(12)
    from app.core.bundle_crypto import _derive_key
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    key = _derive_key(password, salt)
    aesgcm = AESGCM(key)
    ciphertext = aesgcm.encrypt(nonce, probe_data, None)

    payload = b"".join([
        MAGIC,
        salt,
        nonce,
        struct.pack(">Q", len(ciphertext)),
        ciphertext,
    ])

    return Response(
        content=payload,
        media_type="application/octet-stream",
    )


@router.get("/auth/pubkey")
async def sync_server_pubkey():
    """Return the server's long-term X25519 public key (protocol v3).

    The work PC pins this key and uses it to encrypt uploads via an ephemeral
    key (ECIES). The key is public — distributing it grants no decryption
    ability. The fingerprint lets the user verify it out-of-band against the
    value printed in the server console at startup.
    """
    server_private_key = sync_envelope.get_server_private_key()
    if server_private_key is None:
        raise HTTPException(503, "Server hybrid key not configured")
    pub = hybrid_crypto.public_bytes(server_private_key)
    return {
        "alg": "x25519",
        "pub": hybrid_crypto.public_b64(server_private_key),
        "fp": hybrid_crypto.fingerprint(pub),
    }
