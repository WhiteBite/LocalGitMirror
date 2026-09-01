"""Crypto helpers for lgm — bundle (v1/v2 AES-GCM) + envelope encryption.

Bundle crypto is moved **verbatim** from lgm.py to guarantee byte-compatibility
with the existing on-disk format and with ``backend/app/core/bundle_crypto.py``.

Envelope crypto mirrors ``backend/app/core/envelope_crypto.py`` — needed for
the ``/api/documents/*`` sync endpoints that hide request metadata (repo,
branch, commit hashes) behind an opaque base64 ``e`` field.
"""
from __future__ import annotations

import base64
import json
import os
import struct
import sys

# ── Bundle format constants (identical to bundle_crypto.py) ──────────────────
#
# Format v2 (current, default for writes):
#   version(1) = 0x01 | salt(16) | nonce(12) | ciphertext_len(8 BE) | ciphertext
# Format v1 (legacy, read-only):
#   magic(8)='LGMSTRL1' | salt(16) | nonce(12) | ciphertext_len(8 BE) | ciphertext
#
# KDF: PBKDF2-HMAC-SHA256(password, salt, 200_000 iters) -> 32 bytes
_MAGIC_V1 = b"LGMSTRL1"
_FORMAT_V2 = 0x01
_PBKDF2_ITERS = 200_000


def _derive_key(password: str, salt: bytes) -> bytes:
    try:
        from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
        from cryptography.hazmat.primitives import hashes
    except ImportError:
        sys.exit("pip install cryptography")
    import hashlib as _hl  # noqa: F401  (kept for parity with original)
    kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=_PBKDF2_ITERS)
    return kdf.derive(password.encode("utf-8"))


def decrypt_bundle(data: bytes, password: str) -> bytes:
    """AES-256-GCM decrypt — mirrors bundle_crypto.decrypt_dump_to_bundle."""
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        sys.exit("pip install cryptography")

    min_len = 1 + 16 + 12 + 8 + 16  # version + salt + nonce + len + min tag
    if len(data) < min_len:
        raise ValueError("Payload too small")

    cursor = 0
    first_byte = data[0]

    if first_byte == ord("L"):
        # v1 legacy
        if data[:8] != _MAGIC_V1:
            raise ValueError("Unsupported format")
        cursor = 8
    elif first_byte == _FORMAT_V2:
        cursor = 1
    else:
        raise ValueError(f"Unsupported format version: 0x{first_byte:02x}")

    salt  = data[cursor:cursor + 16]; cursor += 16
    nonce = data[cursor:cursor + 12]; cursor += 12
    (ct_len,) = struct.unpack(">Q", data[cursor:cursor + 8]); cursor += 8
    ct = data[cursor:cursor + ct_len]

    if len(ct) != ct_len:
        raise ValueError("Corrupted payload")

    key = _derive_key(password, salt)
    return AESGCM(key).decrypt(nonce, ct, None)


def encrypt_bundle(plaintext: bytes, password: str) -> bytes:
    """AES-256-GCM encrypt — mirrors bundle_crypto.encrypt_bundle_to_dump."""
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        sys.exit("pip install cryptography")
    import secrets as _sec
    salt  = _sec.token_bytes(16)
    nonce = _sec.token_bytes(12)
    key   = _derive_key(password, salt)
    ct    = AESGCM(key).encrypt(nonce, plaintext, None)
    return (
        bytes([_FORMAT_V2])
        + salt
        + nonce
        + struct.pack(">Q", len(ct))
        + ct
    )


# ── Envelope crypto (mirrors envelope_crypto.py) ─────────────────────────────
#
# Wire format: base64( salt[16] | nonce[12] | AES-GCM-ciphertext )
# No version prefix.  Used by /api/documents/* to hide metadata from DLP.
_ITER = 200_000
_SALT = 16
_NONCE = 12


def _envelope_derive_key(password: str, salt: bytes) -> bytes:
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    from cryptography.hazmat.primitives import hashes
    kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=_ITER)
    return kdf.derive(password.encode("utf-8"))


def encrypt_envelope(payload: dict, password: str) -> str:
    """Encrypt *payload* dict → base64 envelope string."""
    if not password:
        raise ValueError("Sync password not configured")
    salt  = os.urandom(_SALT)
    nonce = os.urandom(_NONCE)
    key   = _envelope_derive_key(password, salt)
    plaintext  = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    ciphertext = _aesgcm_encrypt(key, nonce, plaintext)
    return base64.b64encode(salt + nonce + ciphertext).decode("ascii")


def decrypt_envelope(b64: str, password: str) -> dict:
    """Decrypt base64 envelope → dict."""
    if not password:
        raise ValueError("Sync password not configured")
    try:
        raw = base64.b64decode(b64)
    except Exception as exc:
        raise ValueError("Invalid base64 envelope") from exc
    if len(raw) < _SALT + _NONCE + 16:
        raise ValueError("Envelope too short")
    salt       = raw[:_SALT]
    nonce      = raw[_SALT:_SALT + _NONCE]
    ciphertext = raw[_SALT + _NONCE:]
    key = _envelope_derive_key(password, salt)
    try:
        plaintext = _aesgcm_decrypt(key, nonce, ciphertext)
    except Exception as exc:
        raise ValueError("Envelope decryption failed (wrong password or tampered)") from exc
    return json.loads(plaintext.decode("utf-8"))


def _aesgcm_encrypt(key: bytes, nonce: bytes, plaintext: bytes) -> bytes:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(key).encrypt(nonce, plaintext, None)


def _aesgcm_decrypt(key: bytes, nonce: bytes, ciphertext: bytes) -> bytes:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM(key).decrypt(nonce, ciphertext, None)
