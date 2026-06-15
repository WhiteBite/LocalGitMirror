"""Python-only sync encryption/decryption helpers (no 7-Zip required).

Supports two formats:

Format v2 (current, default for writes):
  version(1) = 0x01
  salt(16)
  nonce(12)
  ciphertext_len(8, big-endian)
  ciphertext (AES-GCM encrypted original bundle bytes)

Format v1 (legacy, read-only support):
  magic(8) = b'LGMSTRL1'
  salt(16)
  nonce(12)
  ciphertext_len(8, big-endian)
  ciphertext

Auto-detection: if first byte == ord('L') -> v1, else -> v2.

Key derivation: PBKDF2-HMAC-SHA256(password, salt, 200_000) -> 32 bytes.
Cipher: AES-256-GCM.
"""

from __future__ import annotations

import os
import struct
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes


# Legacy magic kept only for read-back compatibility
MAGIC = b"LGMSTRL1"
FORMAT_VERSION = 0x01
SALT_SIZE = 16
NONCE_SIZE = 12
PBKDF2_ITERATIONS = 200_000
KEY_SIZE = 32


def _derive_key(password: str, salt: bytes) -> bytes:
    if not password:
        raise ValueError("Password cannot be empty")
    kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=KEY_SIZE, salt=salt, iterations=PBKDF2_ITERATIONS)
    return kdf.derive(password.encode("utf-8"))


def encrypt_bundle_to_dump(bundle_path: Path, dump_path: Path, password: str) -> None:
    """Encrypt bundle to v2 format (no magic bytes — pure random noise to scanners)."""
    data = bundle_path.read_bytes()
    salt = os.urandom(SALT_SIZE)
    nonce = os.urandom(NONCE_SIZE)
    key = _derive_key(password, salt)
    aesgcm = AESGCM(key)
    ciphertext = aesgcm.encrypt(nonce, data, None)

    payload = b"".join(
        [
            bytes([FORMAT_VERSION]),
            salt,
            nonce,
            struct.pack(">Q", len(ciphertext)),
            ciphertext,
        ]
    )
    dump_path.write_bytes(payload)


def decrypt_dump_to_bundle(dump_path: Path, out_bundle_path: Path, password: str) -> None:
    """Decrypt dump file — auto-detects v1 (LGMSTRL1 magic) or v2 (version byte) format."""
    raw = dump_path.read_bytes()

    min_len = 1 + SALT_SIZE + NONCE_SIZE + 8 + 16  # minimum overhead
    if len(raw) < min_len:
        raise ValueError("File too small")

    cursor = 0
    first_byte = raw[0]

    if first_byte == ord("L"):
        # Legacy v1 format with LGMSTRL1 magic
        if len(raw) < len(MAGIC) + SALT_SIZE + NONCE_SIZE + 8 + 16:
            raise ValueError("File too small for v1 format")
        magic = raw[cursor : cursor + len(MAGIC)]
        cursor += len(MAGIC)
        if magic != MAGIC:
            raise ValueError("Unsupported format")
    elif first_byte == FORMAT_VERSION:
        # v2 format
        cursor += 1
    else:
        raise ValueError("Unsupported format version")

    salt = raw[cursor : cursor + SALT_SIZE]
    cursor += SALT_SIZE
    nonce = raw[cursor : cursor + NONCE_SIZE]
    cursor += NONCE_SIZE
    (ciphertext_len,) = struct.unpack(">Q", raw[cursor : cursor + 8])
    cursor += 8
    ciphertext = raw[cursor : cursor + ciphertext_len]

    if len(ciphertext) != ciphertext_len:
        raise ValueError("Corrupted payload")

    key = _derive_key(password, salt)
    aesgcm = AESGCM(key)
    plaintext = aesgcm.decrypt(nonce, ciphertext, None)
    out_bundle_path.write_bytes(plaintext)
