import os
import struct

import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

from app.core.bundle_crypto import decrypt_dump_bytes, encrypt_bundle_bytes


def _derive(password: str, salt: bytes) -> bytes:
    return PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=200_000).derive(
        password.encode("utf-8")
    )


def _ide_container(plaintext: bytes, password: str) -> bytes:
    """Mirror RepoFileSyncCrypto: 0x01 | salt | nonce | PLAINTEXT_SIZE | ct+tag."""
    salt = os.urandom(16)
    nonce = os.urandom(12)
    ct = AESGCM(_derive(password, salt)).encrypt(nonce, plaintext, None)
    return bytes([0x01]) + salt + nonce + struct.pack(">Q", len(plaintext)) + ct


@pytest.mark.parametrize("size", [0, 1, 1000])
def test_decrypts_bundle_v2_container(size):
    plain = os.urandom(size)
    blob = encrypt_bundle_bytes(plain, "pw")
    assert decrypt_dump_bytes(blob, "pw") == plain


@pytest.mark.parametrize("size", [0, 1, 1000])
def test_decrypts_ide_file_container(size):
    plain = os.urandom(size)
    blob = _ide_container(plain, "pw")
    assert decrypt_dump_bytes(blob, "pw") == plain


def test_ide_container_wrong_password_fails():
    blob = _ide_container(b"hello", "right")
    with pytest.raises(Exception):
        decrypt_dump_bytes(blob, "wrong")


def test_truncated_framing_rejected():
    blob = encrypt_bundle_bytes(b"x" * 64, "pw")
    with pytest.raises(ValueError):
        decrypt_dump_bytes(blob[:-8], "pw")