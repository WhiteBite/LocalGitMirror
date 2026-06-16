"""LAN discovery service for automatic server detection.

Uses mDNS (Bonjour/Zeroconf) for standard service discovery — ignored by EDR.
Falls back to low-frequency UDP broadcast if zeroconf is unavailable.
"""

import json
import socket
import threading
from typing import Optional

from app.core.system_monitor import SystemMonitor

MDNS_SERVICE_TYPE = "_http._tcp.local."
MDNS_SERVICE_NAME = "DocCache"
UDP_FALLBACK_PORT = 37020
UDP_FALLBACK_INTERVAL_SEC = 30


class LanBeacon:
    def __init__(self, web_port: int, tls: bool = False):
        self._web_port = web_port
        self._tls = tls
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._zeroconf = None

    # ── mDNS (preferred) ──────────────────────────────────────────────

    def _start_mdns(self) -> bool:
        """Register service via mDNS. Returns True if successful."""
        try:
            from zeroconf import Zeroconf, ServiceInfo
            import ipaddress

            ip = SystemMonitor.get_local_ip()
            ip_bytes = ipaddress.ip_address(ip).packed

            properties = {
                "tls": "true" if self._tls else "false",
                "v": "1",
            }

            service_name = f"{MDNS_SERVICE_NAME}.{MDNS_SERVICE_TYPE}"
            info = ServiceInfo(
                MDNS_SERVICE_TYPE,
                service_name,
                addresses=[ip_bytes],
                port=self._web_port,
                properties=properties,
                server=f"{socket.gethostname()}.local.",
            )

            self._zeroconf = Zeroconf()
            self._zeroconf.register_service(info)
            return True
        except ImportError:
            return False
        except Exception:
            return False

    def _stop_mdns(self) -> None:
        if self._zeroconf:
            try:
                self._zeroconf.close()
            except Exception:
                pass
            self._zeroconf = None

    # ── UDP fallback (low-frequency, no JSON) ─────────────────────────

    def _build_udp_payload(self) -> bytes:
        """Compact binary payload: port(2) + flags(1) — no JSON, no identifiers."""
        port_bytes = self._web_port.to_bytes(2, "big")
        flags = 0x01 if self._tls else 0x00
        return port_bytes + bytes([flags])

    def _run_udp_fallback(self) -> None:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.settimeout(1.0)
        except Exception:
            return

        try:
            while not self._stop_event.is_set():
                try:
                    payload = self._build_udp_payload()
                    sock.sendto(payload, ("<broadcast>", UDP_FALLBACK_PORT))
                except Exception:
                    pass
                self._stop_event.wait(UDP_FALLBACK_INTERVAL_SEC)
        finally:
            try:
                sock.close()
            except Exception:
                pass

    # ── Public API ────────────────────────────────────────────────────

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop_event.clear()

        # Try mDNS first (standard, EDR-invisible)
        if self._start_mdns():
            # mDNS handles discovery — no background thread needed
            return

        # Fallback to low-frequency UDP
        self._thread = threading.Thread(
            target=self._run_udp_fallback, daemon=True, name="svc-discovery"
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=3)
            self._thread = None
        self._stop_mdns()
