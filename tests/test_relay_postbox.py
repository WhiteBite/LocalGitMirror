"""v3 postbox round-trip: the lgm_core relay client is byte-compatible with the server crypto."""
import base64
import importlib.util
import json
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey

from lgm_core.client import MirrorClient
from lgm_core.relay_crypto import RELAY_AAD_POSTBOX, decode_pub_b64

_HC_PATH = Path(__file__).resolve().parent.parent / "backend" / "app" / "core" / "hybrid_crypto.py"


def _load_server_hybrid_crypto():
    spec = importlib.util.spec_from_file_location("lgm_test_server_hybrid_crypto", _HC_PATH)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


hc = _load_server_hybrid_crypto()


def test_decode_pub_b64_accepts_server_format():
    priv = X25519PrivateKey.generate()
    raw = priv.public_key().public_bytes_raw()
    assert decode_pub_b64(base64.urlsafe_b64encode(raw).decode("ascii")) == raw
    assert decode_pub_b64(base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")) == raw


def test_v3_postbox_round_trip_against_server_context(monkeypatch):
    server_priv = X25519PrivateKey.generate()
    pub_b64 = base64.urlsafe_b64encode(
        server_priv.public_key().public_bytes_raw()).decode("ascii")

    client = MirrorClient(base_url="http://mirror", api_key="k")
    pubkey_calls = []

    def fake_get_json(path, timeout=None):
        if path == "/api/auth/pubkey":
            pubkey_calls.append(path)
            return {"alg": "x25519", "pub": pub_b64, "fp": "0000 0000 0000 0000"}
        raise AssertionError(f"unexpected GET {path}")

    monkeypatch.setattr(client, "_get_json", fake_get_json)

    captured = {}

    def fake_post_multipart(path, fields, files, timeout=None):
        captured["path"] = path
        captured["fields"] = fields
        captured["files"] = files
        return {"success": True, "id": "item1", "path": "mr-replies/mr-!21.md"}

    monkeypatch.setattr(client, "_post_multipart", fake_post_multipart)

    plaintext = b"# MR !21\n\nreply body" * 32
    client.file_sync_send("onyx", "mr-replies/mr-!21.md", len(plaintext), plaintext)

    assert captured["path"] == "/api/documents/attachment-upload"
    fields = captured["fields"]
    assert set(fields) == {"rid", "k", "meta"}

    ctx = hc.HybridServerContext(server_priv, hc.decode_epk(fields["k"]))
    meta = json.loads(ctx.open_relay(base64.b64decode(fields["meta"]), RELAY_AAD_POSTBOX))
    assert meta == {"path": "mr-replies/mr-!21.md", "plain_size": len(plaintext)}
    assert ctx.open_relay(captured["files"]["attachment"][1], RELAY_AAD_POSTBOX) == plaintext

    downloads = {}

    def fake_download_bytes(path, headers=None):
        downloads["path"] = path
        downloads["headers"] = headers
        reader_ctx = hc.HybridServerContext(server_priv, hc.decode_epk(headers["X-LGM-Epk"]))
        return reader_ctx.seal_relay(plaintext, RELAY_AAD_POSTBOX)

    monkeypatch.setattr(client, "_download_bytes", fake_download_bytes)

    assert client.file_sync_fetch("onyx", "item1") == plaintext
    assert "id=item1" in downloads["path"]
    assert downloads["headers"]["X-LGM-Epk"]

    assert len(pubkey_calls) == 1
