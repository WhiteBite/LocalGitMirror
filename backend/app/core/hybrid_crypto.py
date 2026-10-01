"""Hybrid (asymmetric) envelope/bundle encryption — protocol v3 (ECIES).

Why this exists
---------------
The legacy envelope/bundle crypto derives its AES key from a SHARED password
(SYNC_PASSWORD) that must live on BOTH machines. On the work PC that shared
secret is exposed to anyone who can read the process/config, and once it leaks
*all* recorded traffic (past and future) is decryptable. There is no forward
secrecy.

v3 replaces the shared secret on the *upload* path with public-key crypto:

  * The home server owns a long-term X25519 static keypair. Only its PUBLIC
    key is distributed to the work PC (pinned). A public key is not a secret.
  * For every API call the work PC generates a throwaway (ephemeral) X25519
    keypair, does ECDH against the pinned server public key, and derives the
    session keys from the shared secret. The ephemeral private key is discarded
    after the call.

Consequences for the threat model (adversary on the work PC):
  * Reading everything on the work PC later (config, disk image, even RAM
    *between* syncs) yields only the server PUBLIC key + discarded ephemeral
    publics — useless to decrypt any recorded upload. This is the headline win.
  * Forward secrecy holds with respect to the work PC: each call uses a fresh
    ephemeral that is gone afterwards.
  * NOTE: this is ECIES (ephemeral-static). It is *not* forward-secret against
    compromise of the home server's STATIC private key — an attacker holding
    that key plus recorded traffic can recompute the shared secret (the
    ephemeral public is on the wire). The home PC is the trusted recipient, so
    that is an accepted tradeoff. Full FS against the recipient key would need
    an interactive ephemeral-ephemeral handshake (extra round trip).
  * Client authentication is still provided by the API key (Bearer). Leaking
    the API key grants read+write over the transport (the holder can post its
    own ephemeral key and receive sealed responses); it does not, however,
    break confidentiality of previously recorded ciphertext, which stays
    sealed to the server X25519 key.
  * Unbreakable wall (unchanged): plaintext + an active session key must
    coexist in RAM *during* a sync. A memory dump taken mid-sync defeats any
    scheme. v3 does not claim to fix that.

Primitives (all available in both Python `cryptography` and JDK 17 JCA):
  * X25519 ECDH
  * HKDF-SHA256  (salt = ephemeral public key, info = direction/purpose label)
  * AES-256-GCM  (fresh 12-byte nonce per message)

Wire formats
------------
Envelope (the "e" string), base64:  nonce[12] || AES-GCM(ct+tag)
Bundle (raw bytes / base64 "d"):     nonce[12] || AES-GCM(ct+tag)
The ephemeral public key is transported *separately* (JSON field "epk" or
multipart form field "k"), NOT prefixed onto the ciphertext. Presence of the
ephemeral public key is what selects v3 over the legacy password path, so the
discriminator is unambiguous (legacy requests never carry "epk"/"k").

HKDF info labels (must match the Kotlin client byte-for-byte):
  request  envelope (client -> server):  b"lgm/v3/env/req"
  response envelope (server -> client):  b"lgm/v3/env/resp"
  request  bundle   (client -> server):  b"lgm/v3/bundle/req"
  response bundle   (server -> client):  b"lgm/v3/bundle/resp"
  request  relay    (client -> server):  b"lgm/v3/relay/req"
  response relay    (server -> client):  b"lgm/v3/relay/resp"

Relay (v3 postbox flows) additionally binds a per-purpose AAD into the GCM
tag, so a blob sealed for one purpose (deps/postbox/buffer/vault) cannot be
replayed as another. At rest the server stores relay plaintexts re-encrypted
under an independent random RELAY_KEY (NOT derived from the X25519 key):
  0x04 || nonce[12] || AES-256-GCM(plaintext, key=RELAY_KEY, aad=PURPOSE_AAD)
Legacy at-rest blobs (first byte 0x01 or 'L') are unrelated to this format.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import stat
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

_NONCE_SIZE = 12
_KEY_SIZE = 32
_X25519_PUB_SIZE = 32

# Direction/purpose labels for HKDF — keep in sync with HybridCrypto.kt.
INFO_ENV_REQ = b"lgm/v3/env/req"
INFO_ENV_RESP = b"lgm/v3/env/resp"
INFO_BUNDLE_REQ = b"lgm/v3/bundle/req"
INFO_BUNDLE_RESP = b"lgm/v3/bundle/resp"
INFO_RELAY_REQ = b"lgm/v3/relay/req"
INFO_RELAY_RESP = b"lgm/v3/relay/resp"

# Per-purpose AAD bound into the relay GCM tag — keep in sync with HybridCrypto.kt.
RELAY_AAD_DEPS_REQ = b"lgm/v3/relay/deps/req"
RELAY_AAD_DEPS_RESP = b"lgm/v3/relay/deps/resp"
RELAY_AAD_POSTBOX = b"lgm/v3/relay/postbox"
RELAY_AAD_BUFFER = b"lgm/v3/relay/buffer"
RELAY_AAD_VAULT = b"lgm/v3/relay/vault"

# First byte of a relay at-rest blob (0x01/'L' are legacy bundle formats).
RELAY_AT_REST_VERSION = 0x04


# ─────────────────────────── server key management ──────────────────────────


def load_or_create_server_key(path: Path) -> X25519PrivateKey:
    """Load the server's long-term X25519 private key, creating it on first use.

    The key is stored as 32 raw bytes with 0600 permissions. Returns the
    private key object.
    """
    path = Path(path)
    if path.exists():
        raw = path.read_bytes()
        if len(raw) != 32:
            raise ValueError(f"Corrupt server key at {path}: expected 32 bytes, got {len(raw)}")
        return X25519PrivateKey.from_private_bytes(raw)

    priv = X25519PrivateKey.generate()
    raw = priv.private_bytes_raw()
    path.parent.mkdir(parents=True, exist_ok=True)
    # Write then tighten perms (no-op on Windows but harmless).
    path.write_bytes(raw)
    try:
        os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
    except Exception:
        pass
    return priv


def load_or_create_relay_key(path: Path) -> bytes:
    """Load the independent relay at-rest key, creating it on first use.

    Raw 32 random bytes, 0600 permissions, deliberately NOT derived from the
    server X25519 key: sealing at rest must stay independent of the wire
    hybrid so rotating one never silently rewrites the other's trust model.
    """
    path = Path(path)
    if path.exists():
        raw = path.read_bytes()
        if len(raw) != 32:
            raise ValueError(f"Corrupt relay key at {path}: expected 32 bytes, got {len(raw)}")
        return raw

    raw = os.urandom(_KEY_SIZE)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)
    try:
        os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
    except Exception:
        pass
    return raw


def public_bytes(priv: X25519PrivateKey) -> bytes:
    """Raw 32-byte X25519 public key."""
    return priv.public_key().public_bytes_raw()


def public_b64(priv: X25519PrivateKey) -> str:
    """URL-safe base64 of the raw public key (no padding stripped — standard)."""
    return base64.urlsafe_b64encode(public_bytes(priv)).decode("ascii")


def fingerprint(pub: bytes) -> str:
    """Short human-comparable fingerprint: first 8 bytes of SHA-256, hex, grouped."""
    digest = hashlib.sha256(pub).hexdigest()[:16]
    return " ".join(digest[i : i + 4] for i in range(0, 16, 4))


# ─────────────────────────────── core ECIES ─────────────────────────────────


def _derive(shared: bytes, epk: bytes, info: bytes) -> bytes:
    """HKDF-SHA256 → 32-byte AES key, salted with the ephemeral public key."""
    return HKDF(
        algorithm=hashes.SHA256(),
        length=_KEY_SIZE,
        salt=epk,
        info=info,
    ).derive(shared)


def _seal(key: bytes, plaintext: bytes) -> bytes:
    nonce = os.urandom(_NONCE_SIZE)
    ct = AESGCM(key).encrypt(nonce, plaintext, None)
    return nonce + ct


def _open(key: bytes, blob: bytes) -> bytes:
    if len(blob) < _NONCE_SIZE + 16:
        raise ValueError("ciphertext too short")
    nonce, ct = blob[:_NONCE_SIZE], blob[_NONCE_SIZE:]
    return AESGCM(key).decrypt(nonce, ct, None)


def _seal_aad(key: bytes, plaintext: bytes, aad: bytes) -> bytes:
    nonce = os.urandom(_NONCE_SIZE)
    ct = AESGCM(key).encrypt(nonce, plaintext, aad)
    return nonce + ct


def _open_aad(key: bytes, blob: bytes, aad: bytes) -> bytes:
    if len(blob) < _NONCE_SIZE + 16:
        raise ValueError("ciphertext too short")
    nonce, ct = blob[:_NONCE_SIZE], blob[_NONCE_SIZE:]
    return AESGCM(key).decrypt(nonce, ct, aad)


class HybridServerContext:
    """Server-side per-request crypto context bound to one client ephemeral key.

    The same shared secret encrypts the response, so a single context handles
    both directions of one API call.
    """

    def __init__(self, server_priv: X25519PrivateKey, epk: bytes):
        if len(epk) != _X25519_PUB_SIZE:
            raise ValueError("invalid ephemeral public key length")
        self.epk = epk
        self._shared = server_priv.exchange(X25519PublicKey.from_public_bytes(epk))

    # — metadata envelope —
    def open_envelope(self, b64: str) -> dict:
        key = _derive(self._shared, self.epk, INFO_ENV_REQ)
        plaintext = _open(key, base64.b64decode(b64))
        return json.loads(plaintext.decode("utf-8"))

    def seal_envelope(self, payload: dict) -> str:
        key = _derive(self._shared, self.epk, INFO_ENV_RESP)
        plaintext = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        return base64.b64encode(_seal(key, plaintext)).decode("ascii")

    # — git bundle bytes —
    def open_bundle(self, blob: bytes) -> bytes:
        key = _derive(self._shared, self.epk, INFO_BUNDLE_REQ)
        return _open(key, blob)

    def seal_bundle(self, plaintext: bytes) -> bytes:
        key = _derive(self._shared, self.epk, INFO_BUNDLE_RESP)
        return _seal(key, plaintext)

    # — relay payloads (purpose-bound AAD) —
    def open_relay(self, blob: bytes, aad: bytes) -> bytes:
        key = _derive(self._shared, self.epk, INFO_RELAY_REQ)
        return _open_aad(key, blob, aad)

    def seal_relay(self, plaintext: bytes, aad: bytes) -> bytes:
        key = _derive(self._shared, self.epk, INFO_RELAY_RESP)
        return _seal_aad(key, plaintext, aad)


def decode_epk(epk_b64: str) -> bytes:
    """Decode a base64 (standard or url-safe) ephemeral public key to 32 bytes."""
    s = epk_b64.strip()
    # Accept both alphabets; add padding if the client stripped it.
    s = s.replace("-", "+").replace("_", "/")
    s += "=" * (-len(s) % 4)
    raw = base64.b64decode(s)
    if len(raw) != _X25519_PUB_SIZE:
        raise ValueError("invalid ephemeral public key length")
    return raw


# ───────────────────────────── relay wire + at-rest ──────────────────────────


def relay_seal(shared: bytes, epk: bytes, plaintext: bytes, aad: bytes, resp: bool = False) -> bytes:
    """Client-side relay seal: nonce[12] || AES-GCM(plaintext, aad).

    resp=False seals a request (INFO_RELAY_REQ, opened by the server via
    HybridServerContext.open_relay); resp=True seals a response
    (INFO_RELAY_RESP, the server side of the wire).
    """
    info = INFO_RELAY_RESP if resp else INFO_RELAY_REQ
    return _seal_aad(_derive(shared, epk, info), plaintext, aad)


def relay_open(shared: bytes, epk: bytes, blob: bytes, aad: bytes, resp: bool = False) -> bytes:
    """Client-side relay open, mirroring relay_seal's label selection.

    resp=False opens a request (server side); resp=True opens a response
    sealed by HybridServerContext.seal_relay.
    """
    info = INFO_RELAY_RESP if resp else INFO_RELAY_REQ
    return _open_aad(_derive(shared, epk, info), blob, aad)


def relay_encrypt_at_rest(relay_key: bytes, plaintext: bytes, aad: bytes) -> bytes:
    """Seal under the independent RELAY_KEY: 0x04 || nonce[12] || AES-GCM."""
    nonce = os.urandom(_NONCE_SIZE)
    ct = AESGCM(relay_key).encrypt(nonce, plaintext, aad)
    return bytes([RELAY_AT_REST_VERSION]) + nonce + ct


def relay_decrypt_at_rest(relay_key: bytes, blob: bytes, aad: bytes) -> bytes:
    if len(blob) < 1 + _NONCE_SIZE + 16 or blob[0] != RELAY_AT_REST_VERSION:
        raise ValueError("not a v3 relay at-rest blob (expected first byte 0x04)")
    nonce, ct = blob[1 : 1 + _NONCE_SIZE], blob[1 + _NONCE_SIZE :]
    return AESGCM(relay_key).decrypt(nonce, ct, aad)
