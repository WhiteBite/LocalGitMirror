"""UDP LAN beacon for automatic server discovery.

Broadcasts a small JSON payload every few seconds on the local network
so that plugins on the same LAN can auto-discover the server address.
"""

import json
import socket
import threading
from typing import Optional

from app.core.system_monitor import SystemMonitor

BROADCAST_PORT = 37020
BROADCAST_INTERVAL_SEC = 5


class LanBeacon:
    def __init__(self, web_port: int, tls: bool = False):
        self._web_port = web_port
        self._tls = tls
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def _build_payload(self) -> bytes:
        ip = SystemMonitor.get_local_ip()
        data = {
            "id": "lgm",
            "ip": ip,
            "port": self._web_port,
            "tls": self._tls,
        }
        return json.dumps(data, separators=(",", ":")).encode("utf-8")

    def _run(self) -> None:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.settimeout(1.0)
        except Exception:
            return

        try:
            while not self._stop_event.is_set():
                try:
                    payload = self._build_payload()
                    sock.sendto(payload, ("<broadcast>", BROADCAST_PORT))
                except Exception:
                    pass
                self._stop_event.wait(BROADCAST_INTERVAL_SEC)
        finally:
            try:
                sock.close()
            except Exception:
                pass

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run, daemon=True, name="lgm-lan-beacon")
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=3)
            self._thread = None
