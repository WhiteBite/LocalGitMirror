"""ECIES v3 relay client — X25519 + HKDF-SHA256 + AES-256-GCM.

Byte-compatible with ``backend/app/core/hybrid_crypto.py`` and the Kotlin
``HybridCrypto.kt``: HKDF info ``b"lgm/v3/relay/req"`` seals client→server
payloads, ``b"lgm/v3/relay/resp"`` opens server→client responses; the wire
blob is ``nonce[12] || AES-GCM(ct+tag)`` and the ephemeral public key travels
separately (form field "k" / header "X-LGM-Epk"), never prefixed to the
ciphertext.
"""
from __future__ import annotations

import base64
import os
import sys

INFO_RELAY_REQ = b"lgm/v3/relay/req"
INFO_RELAY_RESP = b"lgm/v3/relay/resp"
RELAY_AAD_POSTBOX = b"lgm/v3/relay/postbox"

_NONCE_SIZE = 12
_KEY_SIZE = 32
_PUB_SIZE = 32


def decode_pub_b64(pub_b64: str) -> bytes:
    """Decode the server public key from /api/auth/pubkey (url-safe base64)."""
    s = pub_b64.strip().replace("-", "+").replace("_", "/")
    s += "=" * (-len(s) % 4)
    raw = base64.b64decode(s)
    if len(raw) != _PUB_SIZE:
        raise ValueError("invalid server public key length")
    return raw


class RelaySession:
    """One ephemeral X25519 keypair bound to one API call against the pinned server key."""

    def __init__(self, server_pub: bytes):
        try:
            from cryptography.hazmat.primitives.asymmetric.x25519 import (
                X25519PrivateKey,
                X25519PublicKey,
            )
        except ImportError:
            sys.exit("pip install cryptography")
        if len(server_pub) != _PUB_SIZE:
            raise ValueError("invalid server public key length")
        self._eph = X25519PrivateKey.generate()
        self.epk = self._eph.public_key().public_bytes_raw()
        self._shared = self._eph.exchange(X25519PublicKey.from_public_bytes(server_pub))

    def epk_b64(self) -> str:
        """URL-safe base64 of the ephemeral public key, no padding (wire field "k")."""
        return base64.urlsafe_b64encode(self.epk).decode("ascii").rstrip("=")

    def _key(self, info: bytes) -> bytes:
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.kdf.hkdf import HKDF
        return HKDF(
            algorithm=hashes.SHA256(),
            length=_KEY_SIZE,
            salt=self.epk,
            info=info,
        ).derive(self._shared)

    def seal(self, plaintext: bytes, aad: bytes) -> bytes:
        """Seal a client→server relay payload: nonce[12] || AES-GCM(plaintext, aad)."""
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        nonce = os.urandom(_NONCE_SIZE)
        ct = AESGCM(self._key(INFO_RELAY_REQ)).encrypt(nonce, plaintext, aad)
        return nonce + ct

    def open(self, blob: bytes, aad: bytes) -> bytes:
        """Open a server→client relay response sealed with INFO_RELAY_RESP."""
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        if len(blob) < _NONCE_SIZE + 16:
            raise ValueError("relay blob too short")
        nonce, ct = blob[:_NONCE_SIZE], blob[_NONCE_SIZE:]
        return AESGCM(self._key(INFO_RELAY_RESP)).decrypt(nonce, ct, aad)
