import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import lgm_core.ops_mr as opmod
from lgm_core.client import LgmError
from lgm_core.config import Config
from lgm_core.op_models import Ctx

_OPEN_MR = {"iid": 50, "state": "opened", "draft": False, "has_conflicts": False,
            "merge_status": "can_be_merged"}


class _FakeClient:
    def __init__(self, mr):
        self._mr = mr
        self.merge_calls = []
        self.sync_password = ""

    def gitlab_get_mr(self, iid, project=""):
        return self._mr

    def gitlab_merge_mr(self, iid, project="", *, sha="", when_pipeline_succeeds=False,
                        remove_source_branch=False):
        self.merge_calls.append({"iid": iid, "sha": sha, "when": when_pipeline_succeeds,
                                 "remove": remove_source_branch})
        return {"state": "merged", "merge_commit_sha": "abc123",
                "web_url": "https://gitlab.example.com/g/p/-/merge_requests/50"}


def _ctx(client) -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="", sync_password="pw")
    return Ctx(config=config, client=client)


def _patch(monkeypatch, role):
    values = {"GITLAB_URL": "https://gitlab.example.com", "GITLAB_TOKEN": "tok",
              "GITLAB_PROJECT": "g/p", "LGM_MACHINE_ROLE": role}
    monkeypatch.setattr(opmod, "cfg", lambda key: values.get(key, ""))
    monkeypatch.setattr(opmod, "_repo_gitlab_project", lambda repo: "g/p")


def test_merges_only_with_explicit_confirm(monkeypatch):
    _patch(monkeypatch, "work")
    client = _FakeClient(dict(_OPEN_MR))
    with pytest.raises(LgmError) as ei:
        opmod.op_mr_merge(_ctx(client), {"repo": "r", "iid": 50})
    assert ei.value.code == "config"
    assert ei.value.machine_code == "confirm-required"
    assert client.merge_calls == []


def test_home_role_refuses(monkeypatch):
    _patch(monkeypatch, "home")
    with pytest.raises(LgmError) as ei:
        opmod.op_mr_merge(_ctx(_FakeClient(dict(_OPEN_MR))), {"repo": "r", "iid": 50, "confirm": True})
    assert ei.value.machine_code == "wrong-machine"


def test_unset_role_refuses(monkeypatch):
    _patch(monkeypatch, "")
    with pytest.raises(LgmError) as ei:
        opmod.op_mr_merge(_ctx(_FakeClient(dict(_OPEN_MR))), {"repo": "r", "iid": 50, "confirm": True})
    assert ei.value.machine_code == "role-unset"


def test_refuses_on_not_opened(monkeypatch):
    _patch(monkeypatch, "work")
    client = _FakeClient({"iid": 50, "state": "closed", "draft": False, "has_conflicts": False})
    with pytest.raises(LgmError) as ei:
        opmod.op_mr_merge(_ctx(client), {"repo": "r", "iid": 50, "confirm": True})
    assert ei.value.machine_code == "not-mergeable"
    assert client.merge_calls == []


def test_refuses_on_draft(monkeypatch):
    _patch(monkeypatch, "work")
    client = _FakeClient({**_OPEN_MR, "draft": True})
    with pytest.raises(LgmError) as ei:
        opmod.op_mr_merge(_ctx(client), {"repo": "r", "iid": 50, "confirm": True})
    assert ei.value.machine_code == "not-mergeable"
    assert client.merge_calls == []


def test_refuses_on_conflict(monkeypatch):
    _patch(monkeypatch, "work")
    client = _FakeClient({**_OPEN_MR, "has_conflicts": True})
    with pytest.raises(LgmError) as ei:
        opmod.op_mr_merge(_ctx(client), {"repo": "r", "iid": 50, "confirm": True})
    assert ei.value.machine_code == "not-mergeable"
    assert client.merge_calls == []


def test_merge_passes_flags_and_returns_result(monkeypatch):
    _patch(monkeypatch, "work")
    client = _FakeClient(dict(_OPEN_MR))
    res = opmod.op_mr_merge(_ctx(client), {
        "repo": "r", "iid": 50, "confirm": True, "sha": "deadbeef",
        "when_pipeline_succeeds": True, "remove_source_branch": True})
    assert client.merge_calls == [{"iid": 50, "sha": "deadbeef", "when": True, "remove": True}]
    assert res["success"] is True
    assert res["state"] == "merged"
    assert res["merge_commit_sha"] == "abc123"
    assert res["when_pipeline_succeeds"] is True
