"""Lightweight envelope encryption for request/response metadata.

Hides field names and values (repo, branch, commit hashes) from DLP / TLS
inspection. The DLP sees only a single opaque base64 field ("e") instead of
plaintext names like repo, branch, haves.

Algorithm
---------
Key derivation:
  PBKDF2-HMAC-SHA256(password, random_salt[16], iterations=200_000) → 32 bytes
  Fresh random salt per message — no key caching.

Encryption:
  AES-256-GCM, fresh 12-byte random nonce per message.

Wire format:
  base64( salt[16] | nonce[12] | ciphertext_with_gcm_tag )

No version prefix — legacy v1 format is not supported.
Must stay byte-for-byte compatible with EnvelopeCrypto.kt.
"""

from __future__ import annotations

import base64
import json
import os

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

_ITERATIONS = 200_000
_SALT_SIZE  = 16
_NONCE_SIZE = 12
_KEY_SIZE   = 32


def _derive_key(password: str, salt: bytes) -> bytes:
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=_KEY_SIZE,
        salt=salt,
        iterations=_ITERATIONS,
    )
    return kdf.derive(password.encode("utf-8"))


def encrypt_envelope(payload: dict, password: str) -> str:
    """Encrypt *payload* dict.

    Returns base64( salt[16] | nonce[12] | AES-GCM-ciphertext ).
    Raises ValueError when password is empty.
    """
    if not password:
        raise ValueError("Sync password not configured")
    salt  = os.urandom(_SALT_SIZE)
    nonce = os.urandom(_NONCE_SIZE)
    key   = _derive_key(password, salt)
    plaintext  = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    ciphertext = AESGCM(key).encrypt(nonce, plaintext, None)
    return base64.b64encode(salt + nonce + ciphertext).decode("ascii")


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
    if len(raw) < _SALT_SIZE + _NONCE_SIZE + 16:
        raise ValueError("Envelope too short")
    salt       = raw[:_SALT_SIZE]
    nonce      = raw[_SALT_SIZE: _SALT_SIZE + _NONCE_SIZE]
    ciphertext = raw[_SALT_SIZE + _NONCE_SIZE:]
    key = _derive_key(password, salt)
    try:
        plaintext = AESGCM(key).decrypt(nonce, ciphertext, None)
    except Exception as exc:
        raise ValueError("Envelope decryption failed (wrong password or tampered)") from exc
    return json.loads(plaintext.decode("utf-8"))
