"""MirrorClient normalizes a scheme-less BASE_URL instead of failing in urlopen."""
from lgm_core.client import MirrorClient


def test_scheme_prepended_when_omitted():
    assert MirrorClient(base_url="192.168.0.12").base_url == "https://192.168.0.12"
    assert MirrorClient(base_url=" 192.168.0.12/ ").base_url == "https://192.168.0.12"


def test_existing_scheme_preserved():
    assert MirrorClient(base_url="http://lord.local:443").base_url == "http://lord.local:443"
    assert MirrorClient(base_url="https://x").base_url == "https://x"
    assert MirrorClient(base_url="").base_url == ""
