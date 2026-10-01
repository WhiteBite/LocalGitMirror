"""KAT for protocol v3 relay crypto — must stay byte-identical to HybridCrypto.kt."""

import json
import os
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.core import hybrid_crypto as hc

_VECTOR = json.loads((Path(__file__).parent / "vectors" / "v3_relay.json").read_text())


def _h(name: str) -> bytes:
    return bytes.fromhex(_VECTOR[name])


_AADS = [
    hc.RELAY_AAD_DEPS_REQ,
    hc.RELAY_AAD_DEPS_RESP,
    hc.RELAY_AAD_POSTBOX,
    hc.RELAY_AAD_BUFFER,
    hc.RELAY_AAD_VAULT,
]


def _kats():
    server_priv = X25519PrivateKey.from_private_bytes(_h("server_priv_hex"))
    client_priv = X25519PrivateKey.from_private_bytes(_h("client_priv_hex"))
    epk = _h("client_pub_hex")
    shared = client_priv.exchange(X25519PublicKey.from_public_bytes(_h("server_pub_hex")))
    return server_priv, client_priv, epk, shared


# ─────────────────────────── known-answer vectors ───────────────────────────


def test_kat_constants_match_vector():
    assert hc.INFO_RELAY_REQ == _h("info_req_hex")
    assert hc.INFO_RELAY_RESP == _h("info_resp_hex")
    assert hc.RELAY_AAD_DEPS_REQ == _h("aad_deps_req_hex")
    assert hc.RELAY_AAD_DEPS_RESP == _h("aad_deps_resp_hex")
    assert hc.RELAY_AAD_POSTBOX == _h("aad_postbox_hex")
    assert hc.RELAY_AAD_BUFFER == _h("aad_buffer_hex")
    assert hc.RELAY_AAD_VAULT == _h("aad_vault_hex")


def test_kat_shared_and_hkdf_keys_match_vector():
    server_priv, client_priv, epk, shared = _kats()
    assert server_priv.public_key().public_bytes_raw() == _h("server_pub_hex")
    assert client_priv.public_key().public_bytes_raw() == epk
    assert shared == _h("shared_hex")
    assert hc._derive(shared, epk, hc.INFO_RELAY_REQ) == _h("key_req_hex")
    assert hc._derive(shared, epk, hc.INFO_RELAY_RESP) == _h("key_resp_hex")


def test_kat_fixed_nonce_ciphertexts_match_vector():
    nonce = _h("nonce_hex")
    plaintext = _h("plaintext_hex")
    ct_req = nonce + AESGCM(_h("key_req_hex")).encrypt(nonce, plaintext, hc.RELAY_AAD_DEPS_REQ)
    ct_resp = nonce + AESGCM(_h("key_resp_hex")).encrypt(nonce, plaintext, hc.RELAY_AAD_DEPS_RESP)
    assert ct_req == _h("ct_req_hex")
    assert ct_resp == _h("ct_resp_hex")


# ───────────────────────────── relay wire round-trips ───────────────────────


@pytest.mark.parametrize("aad", _AADS, ids=lambda a: a.decode())
def test_relay_seal_open_round_trip_per_purpose(aad):
    _, _, epk, shared = _kats()
    payload = b"relay-payload" + bytes(range(64))
    for resp in (False, True):
        blob = hc.relay_seal(shared, epk, payload, aad, resp=resp)
        assert hc.relay_open(shared, epk, blob, aad, resp=resp) == payload


@pytest.mark.parametrize("aad", _AADS, ids=lambda a: a.decode())
def test_relay_round_trip_against_server_context(aad):
    server_priv, _, epk, shared = _kats()
    ctx = hc.HybridServerContext(server_priv, epk)
    payload = b"relay-payload" + bytes(range(64))

    sealed_req = hc.relay_seal(shared, epk, payload, aad)
    assert ctx.open_relay(sealed_req, aad) == payload

    sealed_resp = ctx.seal_relay(payload, aad)
    assert hc.relay_open(shared, epk, sealed_resp, aad, resp=True) == payload


# ───────────────────────────── relay at-rest format ─────────────────────────


@pytest.mark.parametrize("aad", _AADS, ids=lambda a: a.decode())
def test_relay_at_rest_round_trip_per_purpose(aad):
    relay_key = os.urandom(32)
    payload = b"at-rest-payload" + bytes(range(64))
    blob = hc.relay_encrypt_at_rest(relay_key, payload, aad)
    assert blob[0] == 0x04
    assert hc.relay_decrypt_at_rest(relay_key, blob, aad) == payload


def test_relay_at_rest_tampered_aad_fails():
    relay_key = os.urandom(32)
    blob = hc.relay_encrypt_at_rest(relay_key, b"payload", hc.RELAY_AAD_VAULT)
    with pytest.raises(Exception):
        hc.relay_decrypt_at_rest(relay_key, blob, hc.RELAY_AAD_BUFFER)


@pytest.mark.parametrize("first", [b"\x01", b"L", b"\x00", b""])
def test_relay_at_rest_rejects_non_v4_first_byte(first):
    relay_key = os.urandom(32)
    with pytest.raises(ValueError):
        hc.relay_decrypt_at_rest(relay_key, first + bytes(64), hc.RELAY_AAD_VAULT)


# ─────────────────────────────── sabotage gates ─────────────────────────────


def test_relay_wrong_label_fails():
    server_priv, _, epk, shared = _kats()
    ctx = hc.HybridServerContext(server_priv, epk)
    aad = hc.RELAY_AAD_POSTBOX
    payload = b"payload"

    req_blob = hc.relay_seal(shared, epk, payload, aad)
    with pytest.raises(Exception):
        hc.relay_open(shared, epk, req_blob, aad, resp=True)

    resp_blob = ctx.seal_relay(payload, aad)
    with pytest.raises(Exception):
        hc.relay_open(shared, epk, resp_blob, aad, resp=False)


def test_relay_flipped_aad_byte_fails():
    server_priv, _, epk, shared = _kats()
    ctx = hc.HybridServerContext(server_priv, epk)
    relay_key = os.urandom(32)
    aad = hc.RELAY_AAD_POSTBOX
    flipped = bytes([aad[0] ^ 0x01]) + aad[1:]
    payload = b"payload"

    wire = hc.relay_seal(shared, epk, payload, aad)
    with pytest.raises(Exception):
        hc.relay_open(shared, epk, wire, flipped)
    with pytest.raises(Exception):
        ctx.open_relay(wire, flipped)

    at_rest = hc.relay_encrypt_at_rest(relay_key, payload, aad)
    with pytest.raises(Exception):
        hc.relay_decrypt_at_rest(relay_key, at_rest, flipped)


# ───────────────────────────── relay key management ─────────────────────────


def test_load_or_create_relay_key_persists_raw_key(tmp_path):
    path = tmp_path / ".lgm" / "relay.key"
    key = hc.load_or_create_relay_key(path)
    assert path.exists()
    assert len(path.read_bytes()) == 32
    assert hc.load_or_create_relay_key(path) == key


def test_corrupt_relay_key_rejected(tmp_path):
    path = tmp_path / "relay.key"
    path.write_bytes(b"too-short")
    with pytest.raises(ValueError, match="Corrupt relay key"):
        hc.load_or_create_relay_key(path)
