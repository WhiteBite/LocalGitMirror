"""op_status reports tools version drift between the deployed copy and the mirror."""
from types import SimpleNamespace

from lgm_core.client import LgmError
from lgm_core.op_models import Ctx
from lgm_core.ops_git import _local_tools_version, op_status


class _Client:
    def __init__(self, mirror="0.9.9", fail_version=False):
        self._mirror = mirror
        self._fail = fail_version

    def capabilities(self):
        return {"service": "DocCache"}

    def repos(self):
        return {"repos": ["default"], "current": "default"}

    def tools_version(self):
        if self._fail:
            raise LgmError("network", "boom")
        return {"version": self._mirror}


def _ctx(client):
    return Ctx(config=SimpleNamespace(base_url="https://localhost:1", sync_password="pw"), client=client)


def test_local_tools_version_reads_deployed_version_file(tmp_path):
    (tmp_path / "VERSION").write_text("0.1.2\n", encoding="utf-8")
    assert _local_tools_version(tmp_path) == "0.1.2"
    assert _local_tools_version(tmp_path / "missing") == "dev"


def test_op_status_marks_outdated(monkeypatch):
    monkeypatch.setattr("lgm_core.ops_git._local_tools_version", lambda root=None: "0.1.0")
    res = op_status(_ctx(_Client(mirror="0.9.9")), {})
    assert res["tools"]["local"] == "0.1.0"
    assert res["tools"]["mirror"] == "0.9.9"
    assert res["tools"]["outdated"] is True


def test_op_status_dev_checkout_is_never_outdated():
    res = op_status(_ctx(_Client()), {})
    assert res["tools"]["local"] == "dev"
    assert res["tools"]["outdated"] is False


def test_op_status_survives_version_probe_failure():
    res = op_status(_ctx(_Client(fail_version=True)), {})
    assert res["tools"] == {"local": "dev"}
    assert res["role_guess"]
