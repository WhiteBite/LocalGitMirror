"""UDP discovery beacon payload: port(2) + flags(1) [+ ip4(4) [+ hmac(4)]]."""

import hashlib
import hmac

from app.core.lan_beacon import LanBeacon


def _parse_ip(payload: bytes) -> str:
    return ".".join(str(b) for b in payload[3:7])


def test_udp_payload_embeds_advertised_ip(monkeypatch):
    # app.main loads .env at import time; in a full-suite run SYNC_PASSWORD
    # would be set and the payload tagged (11 bytes). Pin the untagged case.
    monkeypatch.delenv("SYNC_PASSWORD", raising=False)
    beacon = LanBeacon(web_port=443, tls=True)
    beacon._advertised_ip = "192.168.0.101"
    payload = beacon._build_udp_payload()
    assert len(payload) == 7
    assert int.from_bytes(payload[:2], "big") == 443
    assert payload[2] & 0x01 == 1
    assert _parse_ip(payload) == "192.168.0.101"


def test_udp_payload_carries_port_and_tls_flag(monkeypatch):
    monkeypatch.delenv("SYNC_PASSWORD", raising=False)
    beacon = LanBeacon(web_port=8080, tls=False)
    beacon._advertised_ip = "10.0.0.5"
    payload = beacon._build_udp_payload()
    assert int.from_bytes(payload[:2], "big") == 8080
    assert payload[2] & 0x01 == 0
    assert _parse_ip(payload) == "10.0.0.5"


def test_udp_payload_omits_loopback_advertised_ip():
    # Loopback must never be embedded: remote clients would pin to their
    # own localhost. Legacy 3-byte shape keeps the client on packet.source.
    beacon = LanBeacon(web_port=443, tls=False)
    beacon._advertised_ip = "127.0.0.1"
    payload = beacon._build_udp_payload()
    assert len(payload) == 3


def test_udp_payload_tagged_when_password_set(monkeypatch):
    # With SYNC_PASSWORD the beacon carries a 4-byte HMAC tag so configured
    # clients can reject spoofed beacons from other LAN devices.
    monkeypatch.setenv("SYNC_PASSWORD", "dandan")
    beacon = LanBeacon(web_port=443, tls=True)
    beacon._advertised_ip = "192.168.0.101"
    payload = beacon._build_udp_payload()
    assert len(payload) == 11
    assert payload[2] & 0x02  # tag-present bit
    tag = hmac.new(b"dandan", payload[:7], hashlib.sha256).digest()[:4]
    assert payload[7:] == tag


def test_udp_payload_untagged_without_password(monkeypatch):
    monkeypatch.delenv("SYNC_PASSWORD", raising=False)
    beacon = LanBeacon(web_port=443, tls=True)
    beacon._advertised_ip = "192.168.0.101"
    payload = beacon._build_udp_payload()
    assert len(payload) == 7
    assert not payload[2] & 0x02


def test_start_reports_active_mode():
    beacon = LanBeacon(web_port=443, tls=True)
    try:
        mode = beacon.start()
        assert mode in ("mdns", "udp")
    finally:
        beacon.stop()
