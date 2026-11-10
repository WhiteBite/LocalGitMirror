"""TOFU TLS certificate pinning (mirrors the Kotlin plugin's TlsPinPolicy)."""
import hashlib
import http.server
import io
import json
import ssl
import threading
from contextlib import redirect_stdout
from pathlib import Path

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from generate_cert import generate_self_signed_cert
from lgm_core.client import MirrorClient
from lgm_core.config import Config
from lgm_core.tls_pin import TlsPinMismatchError, pin_https_opener


class _Handler(http.server.BaseHTTPRequestHandler):
    received: list = []

    def do_GET(self):
        type(self).received.append((self.command, self.path))
        body = b'{"ok": true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def _cert_sha256_hex(cert_path: Path) -> str:
    cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
    der = cert.public_bytes(serialization.Encoding.DER)
    return hashlib.sha256(der).hexdigest()


@pytest.fixture()
def https_server(tmp_path):
    cert_path = tmp_path / "cert.pem"
    key_path = tmp_path / "key.pem"
    with redirect_stdout(io.StringIO()):
        generate_self_signed_cert(str(cert_path), str(key_path))
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(str(cert_path), str(key_path))
    _Handler.received = []
    server = http.server.HTTPServer(("127.0.0.1", 0), _Handler)
    server.socket = ctx.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(
        target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
    thread.start()
    yield server, cert_path
    server.shutdown()
    server.server_close()
    thread.join(timeout=5)


@pytest.fixture()
def pin_env(tmp_path, monkeypatch):
    pin_file = tmp_path / "pins.json"
    monkeypatch.setenv("LGM_TLS_PIN_FILE", str(pin_file))
    monkeypatch.delenv("LGM_TLS_PIN_SHA256", raising=False)
    monkeypatch.delenv("LGM_INSECURE_TLS", raising=False)
    return pin_file


def _base_url(server) -> str:
    return f"https://127.0.0.1:{server.server_port}"


def test_tofu_first_connect_stores_pin(https_server, pin_env):
    server, cert_path = https_server
    opener = pin_https_opener()
    with opener.open(_base_url(server) + "/api/health", timeout=10) as r:
        assert json.loads(r.read()) == {"ok": True}
    pins = json.loads(pin_env.read_text(encoding="utf-8"))
    assert pins == {_base_url(server).removeprefix("https://"): _cert_sha256_hex(cert_path)}


def test_second_connect_verifies_against_stored_pin(https_server, pin_env):
    server, _ = https_server
    url = _base_url(server)
    assert MirrorClient(base_url=url, insecure_tls=False).capabilities() == {"ok": True}
    assert MirrorClient(base_url=url, insecure_tls=False).capabilities() == {"ok": True}
    assert len(_Handler.received) == 2


def test_tampered_pin_raises_before_request_bytes(https_server, pin_env):
    server, _ = https_server
    url = _base_url(server)
    MirrorClient(base_url=url, insecure_tls=False).capabilities()
    hostport = url.removeprefix("https://")
    pin_env.write_text(json.dumps({hostport: "0" * 64}), encoding="utf-8")
    _Handler.received.clear()
    with pytest.raises(TlsPinMismatchError):
        MirrorClient(base_url=url, insecure_tls=False).capabilities()
    assert _Handler.received == []


def test_env_pin_sha256_overrides_store(https_server, pin_env, monkeypatch):
    server, cert_path = https_server
    url = _base_url(server)
    hostport = url.removeprefix("https://")
    pin_env.write_text(json.dumps({hostport: "0" * 64}), encoding="utf-8")
    monkeypatch.setenv("LGM_TLS_PIN_SHA256", _cert_sha256_hex(cert_path))
    assert MirrorClient(base_url=url, insecure_tls=False).capabilities() == {"ok": True}


def test_insecure_tls_env_skips_pinning(https_server, pin_env, monkeypatch):
    server, _ = https_server
    monkeypatch.setenv("LGM_INSECURE_TLS", "1")
    cfg = Config.from_env()
    assert cfg.insecure_tls is True
    client = MirrorClient(
        base_url=_base_url(server),
        insecure_tls=cfg.insecure_tls,
        pin_file=cfg.pin_file,
    )
    assert client.capabilities() == {"ok": True}
    assert not pin_env.exists()


def test_config_secure_by_default(monkeypatch):
    monkeypatch.delenv("LGM_INSECURE_TLS", raising=False)
    cfg = Config.from_env()
    assert cfg.insecure_tls is False
    assert cfg.pin_sha256 == ""
    assert cfg.pin_file == ""
