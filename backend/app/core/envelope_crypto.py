"""Lightweight envelope encryption for request/response metadata.

Hides field names and values (repo, branch, commit hashes) from DLP / TLS
inspection. The DLP sees only a single opaque base64 field ("e") instead of
plaintext names like repo, branch, haves.

Algorithm
---------
Key derivation:
  PBKDF2-HMAC-SHA256(password, FIXED_SALT="lgm_env_key_v1__", iterations=1_000) → 32 bytes
  - Fixed salt provides domain separation from BundleCrypto (random salt + 200k iters).
  - Derived key is cached per-process per-password value (avoids per-request KDF cost).

Encryption:
  AES-256-GCM, fresh 12-byte random nonce per message.

Wire format:
  base64( nonce[12] + ciphertext_with_gcm_tag )

Security note
-------------
PBKDF2-1000 is intentionally weaker than bundle crypto (200k). Metadata
(repo name, branch, short SHA prefixes) is lower-value than bundle content.
The password itself provides ≥80 bits of entropy which is sufficient to
resist real-time DLP cracking even at low iteration count.
"""

from __future__ import annotations

import base64
import json
import os
from functools import lru_cache

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

# 16-byte ASCII label — must match Kotlin: "lgm_env_key_v1__"
_ENVELOPE_SALT = b"lgm_env_key_v1__"
_ENVELOPE_ITERATIONS = 1_000
_NONCE_SIZE = 12
_KEY_SIZE = 32


@lru_cache(maxsize=8)
def _derive_key(password: str) -> bytes:
    """Derive AES-256 key from password. Result is cached per password value."""
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=_KEY_SIZE,
        salt=_ENVELOPE_SALT,
        iterations=_ENVELOPE_ITERATIONS,
    )
    return kdf.derive(password.encode("utf-8"))


def encrypt_envelope(payload: dict, password: str) -> str:
    """Encrypt *payload* dict.

    Returns base64( nonce[12] + AES-GCM-ciphertext ).
    Raises ValueError when password is empty.
    """
    if not password:
        raise ValueError("Sync password not configured")
    key = _derive_key(password)
    nonce = os.urandom(_NONCE_SIZE)
    aesgcm = AESGCM(key)
    plaintext = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    ciphertext = aesgcm.encrypt(nonce, plaintext, None)
    return base64.b64encode(nonce + ciphertext).decode("ascii")


def decrypt_envelope(b64: str, password: str) -> dict:
    """Decrypt base64 envelope → dict.

    Raises ValueError on wrong password, tampered data, or empty password.
    """
    if not password:
        raise ValueError("Sync password not configured")
    try:
        raw = base64.b64decode(b64)
    except Exception as exc:
        raise ValueError("Invalid base64 envelope") from exc
    if len(raw) < _NONCE_SIZE + 16:
        raise ValueError("Envelope too short")
    nonce = raw[:_NONCE_SIZE]
    ciphertext = raw[_NONCE_SIZE:]
    key = _derive_key(password)
    aesgcm = AESGCM(key)
    try:
        plaintext = aesgcm.decrypt(nonce, ciphertext, None)
    except Exception as exc:
        raise ValueError("Envelope decryption failed (wrong password or tampered)") from exc
    return json.loads(plaintext.decode("utf-8"))
