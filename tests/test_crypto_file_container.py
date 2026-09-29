"""decrypt_bundle must read the IDE file container (RepoFileSyncCrypto).

The IDE container and the lgm bundle share version byte 0x01 but disagree on
the 8-byte length field: the container stores the PLAINTEXT size, the bundle
stores the CIPHERTEXT size (plain + 16-byte GCM tag).
"""
import os
import struct

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes

from lgm_core.crypto import decrypt_bundle, encrypt_bundle

PASSWORD = "test-sync-password"


def _derive_key(password: str, salt: bytes) -> bytes:
    kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=200_000)
    return kdf.derive(password.encode("utf-8"))


def _ide_file_container(plaintext: bytes, password: str) -> bytes:
    salt = os.urandom(16)
    nonce = os.urandom(12)
    ct = AESGCM(_derive_key(password, salt)).encrypt(nonce, plaintext, None)
    return b"\x01" + salt + nonce + struct.pack(">Q", len(plaintext)) + ct


def test_decrypt_bundle_reads_ide_file_container():
    plaintext = b"## thread abc\nresolve: yes\nanswer body\n"
    blob = _ide_file_container(plaintext, PASSWORD)
    assert decrypt_bundle(blob, PASSWORD) == plaintext


def test_decrypt_bundle_still_reads_native_bundle():
    plaintext = b"native bundle payload"
    assert decrypt_bundle(encrypt_bundle(plaintext, PASSWORD), PASSWORD) == plaintext


def test_decrypt_bundle_rejects_truncated_payload():
    blob = encrypt_bundle(b"x" * 64, PASSWORD)
    try:
        decrypt_bundle(blob[: len(blob) - 20], PASSWORD)
    except ValueError:
        return
    raise AssertionError("truncated blob must not decrypt")
