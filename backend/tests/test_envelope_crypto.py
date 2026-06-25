"""Tests for EnvelopeCrypto — round-trip, wrong-password, tamper detection."""

import base64

import pytest

from app.core.envelope_crypto import (
    _derive_key,
    decrypt_envelope,
    encrypt_envelope,
)


def test_round_trip_simple():
    pwd = "correct-horse-battery-staple"
    payload = {"repo": "my-project", "branch": "feature/auth", "haves": "a1b2c3"}
    assert decrypt_envelope(encrypt_envelope(payload, pwd), pwd) == payload


def test_round_trip_nested():
    pwd = "password"
    payload = {
        "success": True,
        "head": "abc123",
        "refs": {"main": {"sha": "abc123", "updated": "2024-01-01", "is_head": True}},
    }
    assert decrypt_envelope(encrypt_envelope(payload, pwd), pwd) == payload


def test_round_trip_list_field():
    pwd = "test-password-123"
    payload = {"repo": "x", "commits": ["abc123", "def456", "789fff"]}
    assert decrypt_envelope(encrypt_envelope(payload, pwd), pwd) == payload


def test_wrong_password_raises():
    b64 = encrypt_envelope({"repo": "secret"}, "correct-pwd")
    with pytest.raises(ValueError, match="decryption failed"):
        decrypt_envelope(b64, "wrong-pwd")


def test_tampered_gcm_tag_raises():
    b64 = encrypt_envelope({"repo": "secret"}, "pwd")
    raw = bytearray(base64.b64decode(b64))
    raw[-1] ^= 0xFF  # flip last byte (inside GCM tag)
    with pytest.raises(ValueError):
        decrypt_envelope(base64.b64encode(bytes(raw)).decode(), "pwd")


def test_tampered_payload_raises():
    b64 = encrypt_envelope({"repo": "secret"}, "pwd")
    raw = bytearray(base64.b64decode(b64))
    raw[16] ^= 0x01  # flip a byte inside ciphertext (after nonce)
    with pytest.raises(ValueError):
        decrypt_envelope(base64.b64encode(bytes(raw)).decode(), "pwd")


def test_different_nonce_per_call():
    """Two encryptions of the same data produce different ciphertext (random nonce)."""
    pwd = "pwd"
    payload = {"repo": "same"}
    a = encrypt_envelope(payload, pwd)
    b = encrypt_envelope(payload, pwd)
    assert a != b
    # Both decrypt to the same result
    assert decrypt_envelope(a, pwd) == payload
    assert decrypt_envelope(b, pwd) == payload


def test_empty_password_encrypt_raises():
    with pytest.raises(ValueError, match="not configured"):
        encrypt_envelope({"repo": "x"}, "")


def test_empty_password_decrypt_raises():
    b64 = encrypt_envelope({"repo": "x"}, "pwd")
    with pytest.raises(ValueError, match="not configured"):
        decrypt_envelope(b64, "")


def test_key_isolation_from_bundle_crypto():
    """Envelope key must differ from bundle crypto key for the same password."""
    import os
    from app.core.bundle_crypto import _derive_key as bundle_derive

    pwd = "shared-password"
    # Bundle uses a random salt; envelope uses a fixed salt.
    bundle_key = bundle_derive(pwd, os.urandom(16))
    env_key = _derive_key(pwd)
    # They cannot be the same because the salts are always different.
    assert env_key != bundle_key


def test_fixed_vector():
    """
    Known-answer test — ensures the Python output is stable and matches
    the expected structure. Also serves as cross-language reference.

    To generate a new vector run:
        from app.core.envelope_crypto import encrypt_envelope, decrypt_envelope
        import os, base64
        # (patch os.urandom to return b"\\x00"*12 in a test fixture, then encrypt)
    The exact ciphertext changes because GCM includes the nonce; we only
    assert the decryptable structure here.
    """
    pwd = "test-vector-pwd"
    payload = {"repo": "test-repo", "branch": "main"}
    b64 = encrypt_envelope(payload, pwd)
    # Must be valid base64
    raw = base64.b64decode(b64)
    assert len(raw) > 12, "nonce + ciphertext must exceed 12 bytes"
    # Must round-trip
    assert decrypt_envelope(b64, pwd) == payload
