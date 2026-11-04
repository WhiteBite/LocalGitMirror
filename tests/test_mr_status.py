import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import lgm_core.ops_mr as opmod
from lgm_core.client import LgmError
from lgm_core.config import Config
from lgm_core.op_models import Ctx
from lgm_core.render import _render_mr_status


class _FakeClient:
    def __init__(self, mrs=None, get=None, approvals=None):
        self._mrs = mrs or []
        self._get = get or {}
        self._approvals = approvals or {}
        self.sync_password = ""

    def gitlab_list_mrs(self, project=""):
        return self._mrs

    def gitlab_get_mr(self, iid, project=""):
        v = self._get[iid]
        if isinstance(v, Exception):
            raise v
        return v

    def gitlab_mr_approvals(self, iid, project=""):
        v = self._approvals.get(iid)
        if isinstance(v, Exception):
            raise v
        return v


def _ctx(client) -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="", sync_password="pw")
    return Ctx(config=config, client=client)


def _patch(monkeypatch, role):
    values = {"GITLAB_URL": "https://gitlab.example.com", "GITLAB_TOKEN": "tok",
              "GITLAB_PROJECT": "g/p", "LGM_MACHINE_ROLE": role}
    monkeypatch.setattr(opmod, "cfg", lambda key: values.get(key, ""))
    monkeypatch.setattr(opmod, "_repo_gitlab_project", lambda repo: "g/p")


def test_home_role_refuses(monkeypatch):
    _patch(monkeypatch, "home")
    with pytest.raises(LgmError) as ei:
        opmod.op_mr_status(_ctx(_FakeClient()), {"repo": "r"})
    assert ei.value.code == "config"
    assert ei.value.machine_code == "wrong-machine"


def test_unset_role_refuses(monkeypatch):
    _patch(monkeypatch, "")
    with pytest.raises(LgmError) as ei:
        opmod.op_mr_status(_ctx(_FakeClient()), {"repo": "r"})
    assert ei.value.code == "config"
    assert ei.value.machine_code == "role-unset"


def test_all_blockers_reported_at_once(monkeypatch):
    _patch(monkeypatch, "work")
    mr = {"iid": 50, "title": "Big", "state": "opened", "draft": False,
          "web_url": "https://gitlab.example.com/g/p/-/merge_requests/50",
          "merge_status": "cannot_be_merged", "detailed_merge_status": "not_approved",
          "has_conflicts": True,
          "head_pipeline": {"status": "failed", "web_url": "https://ci/50"}}
    client = _FakeClient(mrs=[{"iid": 50}], get={50: mr},
                         approvals={50: {"approved": False, "approvals_required": 2,
                                         "approvals_left": 2}})

    res = opmod.op_mr_status(_ctx(client), {"repo": "r"})

    item = res["items"][0]
    assert item["ready"] is False
    assert "not_approved" in item["blockers"]
    assert "conflict" in item["blockers"]
    assert "ci_must_pass" in item["blockers"]
    assert item["approvals"] == {"approved": False, "required": 2, "left": 2}


def test_clean_mr_is_ready(monkeypatch):
    _patch(monkeypatch, "work")
    mr = {"iid": 7, "title": "Clean", "state": "opened", "draft": False,
          "merge_status": "can_be_merged", "detailed_merge_status": "mergeable",
          "has_conflicts": False,
          "head_pipeline": {"status": "success", "web_url": "https://ci/7"}}
    client = _FakeClient(mrs=[{"iid": 7}], get={7: mr},
                         approvals={7: {"approved": True, "approvals_required": 1,
                                        "approvals_left": 0}})

    res = opmod.op_mr_status(_ctx(client), {"repo": "r"})

    item = res["items"][0]
    assert item["ready"] is True
    assert item["blockers"] == []


def test_missing_mergeability_never_ready(monkeypatch):
    _patch(monkeypatch, "work")
    mr = {"iid": 9, "title": "Unknown", "state": "opened"}
    client = _FakeClient(mrs=[{"iid": 9}], get={9: mr}, approvals={})

    res = opmod.op_mr_status(_ctx(client), {"repo": "r"})

    item = res["items"][0]
    assert item["ready"] is False
    assert "mergeability_unknown" in item["blockers"]


def test_unmergeable_without_known_blocker_names_the_reason(monkeypatch):
    _patch(monkeypatch, "work")
    mr = {"iid": 11, "title": "Checking", "state": "opened",
          "merge_status": "unchecked", "has_conflicts": False}
    client = _FakeClient(mrs=[{"iid": 11}], get={11: mr}, approvals={})

    res = opmod.op_mr_status(_ctx(client), {"repo": "r"})

    item = res["items"][0]
    assert item["ready"] is False
    assert item["blockers"] == ["unchecked"]


def test_one_bad_mr_does_not_abort_batch(monkeypatch):
    _patch(monkeypatch, "work")
    good = {"iid": 2, "title": "Good", "state": "opened",
            "merge_status": "can_be_merged", "detailed_merge_status": "mergeable",
            "has_conflicts": False, "head_pipeline": {"status": "success"}}
    client = _FakeClient(mrs=[{"iid": 1}, {"iid": 2}],
                         get={1: LgmError("network", "boom"), 2: good},
                         approvals={2: {"approved": True, "approvals_required": 1,
                                        "approvals_left": 0}})

    res = opmod.op_mr_status(_ctx(client), {"repo": "r"})

    assert res["count"] == 1
    assert res["items"][0]["iid"] == 2
    assert res["errors"][0]["iid"] == 1
    assert "boom" in res["errors"][0]["error"]


def test_render_mr_status_prints_blockers():
    r = {"repo": "r", "project": "g/p", "count": 1, "errors": [], "items": [
        {"iid": 50, "title": "Big", "state": "opened", "ready": False,
         "detailed_merge_status": "not_approved", "merge_status": "cannot_be_merged",
         "pipeline": {"status": "failed"},
         "approvals": {"approved": False, "required": 2, "left": 2},
         "blockers": ["not_approved", "conflict", "ci_must_pass"]}]}

    out = "\n".join(_render_mr_status(r))

    assert "ready=no" in out
    assert "[blocker] not_approved" in out
    assert "[blocker] conflict" in out
    assert "[blocker] ci_must_pass" in out
    assert "approvals: 0/2" in out


def test_explicit_project_arg_wins(monkeypatch):
    _patch(monkeypatch, "work")
    captured = {}
    mr = {"iid": 7, "title": "Clean", "state": "opened", "draft": False,
          "merge_status": "can_be_merged", "detailed_merge_status": "mergeable",
          "has_conflicts": False, "head_pipeline": {"status": "success"}}

    class _Recording(_FakeClient):
        def gitlab_get_mr(self, iid, project=""):
            captured["project"] = project
            return self._get[iid]

    client = _Recording(mrs=[{"iid": 7}], get={7: mr},
                        approvals={7: {"approved": True, "approvals_required": 1,
                                       "approvals_left": 0}})

    res = opmod.op_mr_status(_ctx(client), {"repo": "r", "project": "other/group"})

    assert res["project"] == "other/group"
    assert captured["project"] == "other/group"


def test_blocking_discussions_mark_the_mr_unready(monkeypatch):
    _patch(monkeypatch, "work")
    mr = {"iid": 12, "title": "Threads", "state": "opened", "draft": False,
          "merge_status": "can_be_merged", "has_conflicts": False,
          "blocking_discussions_resolved": False,
          "head_pipeline": {"status": "success"}}
    client = _FakeClient(mrs=[{"iid": 12}], get={12: mr},
                         approvals={12: {"approved": True, "approvals_required": 1,
                                         "approvals_left": 0}})

    res = opmod.op_mr_status(_ctx(client), {"repo": "r"})

    item = res["items"][0]
    assert item["ready"] is False
    assert "discussions_not_resolved" in item["blockers"]
    assert item["blocking_discussions_resolved"] is False


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
