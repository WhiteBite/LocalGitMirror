"""TOFU certificate pinning for mirror HTTPS connections.

Mirrors the Kotlin plugin's ``TlsPinPolicy``: the TLS handshake always
completes, the leaf certificate's SHA-256 is compared against the
configured/stored pin, and a mismatch aborts before any request bytes flow.
Pin precedence: ``LGM_TLS_PIN_SHA256`` env > stored pin; no pin at all means
trust-on-first-use and the fingerprint is persisted.
"""
from __future__ import annotations

import functools
import hashlib
import http.client
import json
import os
import ssl
import urllib.request
from pathlib import Path


class TlsPinMismatchError(Exception):
    """Server certificate fingerprint differs from the pinned one."""


def _normalize_hex(raw: str) -> str:
    return "".join(c for c in raw if c != ":" and not c.isspace()).lower()


class TlsPinStore:
    """JSON map of ``host:port`` -> sha256 hex of the server leaf certificate."""

    def __init__(self, path: Path):
        self.path = Path(path)
        try:
            self._pins: dict = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self._pins = {}

    @classmethod
    def default(cls) -> "TlsPinStore":
        override = os.environ.get("LGM_TLS_PIN_FILE", "")
        return cls(Path(override) if override
                   else Path.home() / ".lgm" / "tls-pins.json")

    def get(self, hostport: str) -> str:
        return self._pins.get(hostport, "")

    def put(self, hostport: str, sha256_hex: str) -> None:
        self._pins[hostport] = sha256_hex
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(
            json.dumps(self._pins, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )


class PinnedHTTPSConnection(http.client.HTTPSConnection):
    """HTTPS connection enforcing the pin policy on the leaf certificate.

    The handshake runs under a CERT_NONE context so the self-signed mirror
    cert completes; the pin is verified before any request bytes are sent.
    """

    def __init__(self, host, port=None, *, pin_sha256="", pin_store=None, **kw):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        kw["context"] = ctx
        super().__init__(host, port, **kw)
        self._pin_sha256 = pin_sha256
        self._pin_store = pin_store

    def connect(self):
        super().connect()  # handshake only; request bytes are sent afterwards
        der = self.sock.getpeercert(binary_form=True)
        actual = hashlib.sha256(der).hexdigest()
        hostport = f"{self.host}:{self.port}"
        expected = _normalize_hex(
            self._pin_sha256
            or os.environ.get("LGM_TLS_PIN_SHA256", "")
            or (self._pin_store.get(hostport) if self._pin_store else "")
        )
        if not expected:
            if self._pin_store is not None:
                self._pin_store.put(hostport, actual)
            return
        if expected != actual:
            self.sock.close()
            raise TlsPinMismatchError(
                f"TLS pin mismatch for {hostport}: "
                f"expected {expected}, got {actual}"
            )


class _PinnedHTTPSHandler(urllib.request.HTTPSHandler):
    def __init__(self, *, pin_sha256="", pin_store=None, **kw):
        super().__init__(**kw)
        self._pin_sha256 = pin_sha256
        self._pin_store = pin_store

    def https_open(self, req):
        factory = functools.partial(
            PinnedHTTPSConnection,
            pin_sha256=self._pin_sha256,
            pin_store=self._pin_store,
        )
        return self.do_open(factory, req)


def pin_https_opener(*, pin_sha256: str = "",
                     pin_file: str = "") -> urllib.request.OpenerDirector:
    """Build a urllib opener whose HTTPS connections enforce the pin policy."""
    store = TlsPinStore(Path(pin_file)) if pin_file else TlsPinStore.default()
    return urllib.request.build_opener(
        _PinnedHTTPSHandler(pin_sha256=pin_sha256, pin_store=store))
