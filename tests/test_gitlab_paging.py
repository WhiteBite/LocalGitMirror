"""X-Next-Page pagination for gitlab_list_mrs."""
import sys
import pytest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core import config as config_mod
from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config


def _client(monkeypatch) -> MirrorClient:
    env = {"GITLAB_URL": "https://gitlab.corp", "GITLAB_TOKEN": "tok",
           "GITLAB_PROJECT": "p"}
    monkeypatch.setattr(config_mod, "_ENV", env)
    for k in ("GITLAB_URL", "GITLAB_TOKEN", "GITLAB_PROJECT"):
        monkeypatch.delenv(k, raising=False)
    config = Config(base_url="http://localhost:1", api_key="", sync_password="pw")
    return MirrorClient(base_url=config.base_url, api_key=config.api_key,
                        sync_password=config.sync_password)


def test_gitlab_list_mrs_follows_next_page(monkeypatch):
    c = _client(monkeypatch)
    calls = []
    pages = {1: ([{"iid": 1}, {"iid": 2}], "2"), 2: ([{"iid": 3}], "")}

    def fake_paged(url, token):
        calls.append(url)
        page = int(url.rsplit("page=", 1)[1])
        return pages[page]

    monkeypatch.setattr(c, "_gitlab_get_paged", fake_paged)
    result = c.gitlab_list_mrs()
    assert [m["iid"] for m in result] == [1, 2, 3]
    assert len(calls) == 2
    assert "page=1" in calls[0] and "page=2" in calls[1]


def test_gitlab_list_mrs_refuses_repeated_page(monkeypatch):
    c = _client(monkeypatch)
    calls = []

    def fake_paged(url, token):
        calls.append(url)
        return [{"iid": len(calls)}], "9"

    monkeypatch.setattr(c, "_gitlab_get_paged", fake_paged)
    with pytest.raises(LgmError, match="pagination"):
        c.gitlab_list_mrs()
    assert len(calls) == 2


def test_gitlab_list_mrs_empty_first_page(monkeypatch):
    c = _client(monkeypatch)
    calls = []

    def fake_paged(url, token):
        calls.append(url)
        return [], ""

    monkeypatch.setattr(c, "_gitlab_get_paged", fake_paged)
    assert c.gitlab_list_mrs() == []
    assert len(calls) == 1
