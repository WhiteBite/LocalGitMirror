import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core import ops
from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.ops import Ctx, get_op

_REPO_ROOT = Path(__file__).resolve().parent.parent

_spec = importlib.util.spec_from_file_location(
    "lgm_mcp", str(_REPO_ROOT / "lgm_mcp.py"))
lgm_mcp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(lgm_mcp)

REPO = "phonyx"
GITLAB = "kryptonit/phonyx"
CHECKOUT = "D:\\Sources\\kryptonit\\phonyx"


def _ctx() -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="", sync_password="")
    client = MirrorClient(base_url=config.base_url, api_key=config.api_key)
    return Ctx(config=config, client=client)


def _args(project="", checkout="", repo=REPO, iid=7) -> dict:
    return {"repo": repo, "iid": iid, "project": project, "checkout": checkout,
            "notes_only": False, "work_dir": ""}


def _capture(monkeypatch) -> dict:
    calls: dict = {}

    def fake(client, repo, iid, project, checkout, **kwargs):
        calls.update({"repo": repo, "iid": iid, "project": project,
                      "checkout": checkout, **kwargs})
        return {"status": "prepared", "repo": repo, "iid": iid}

    monkeypatch.setattr(ops, "mr_review_prepare", fake)
    return calls


def _registry_file(monkeypatch, tmp_path, projects) -> Path:
    import lgm_core.ops_projects as opmod
    reg = tmp_path / "mr-projects.json"
    reg.write_text(json.dumps({"projects": projects}), encoding="utf-8")
    monkeypatch.setattr(opmod, "_REGISTRY_PATH", reg)
    return reg


def _entry(**overrides) -> dict:
    entry = {"repo": REPO, "path": CHECKOUT, "gitlab": GITLAB}
    entry.update(overrides)
    return entry


def _run_op(args) -> dict:
    return get_op("mr_review_prepare").run(_ctx(), args)


def test_explicit_both_used_verbatim_without_registry_read(monkeypatch):
    def no_read():
        raise AssertionError("registry must not be read when both are explicit")

    monkeypatch.setattr(ops, "_load_registry", no_read)
    calls = _capture(monkeypatch)

    result = _run_op(_args(project="other/group", checkout="C:\\other"))

    assert result["status"] == "prepared"
    assert calls["project"] == "other/group"
    assert calls["checkout"] == "C:\\other"


def test_unique_entry_fills_both_missing(monkeypatch, tmp_path):
    reg = _registry_file(monkeypatch, tmp_path, [_entry()])
    calls = _capture(monkeypatch)

    result = _run_op(_args())

    assert result["status"] == "prepared"
    assert calls["project"] == GITLAB
    assert calls["checkout"] == CHECKOUT
    assert json.loads(reg.read_text(encoding="utf-8")) == {"projects": [_entry()]}


def test_unique_entry_fills_missing_project(monkeypatch, tmp_path):
    _registry_file(monkeypatch, tmp_path, [_entry()])
    calls = _capture(monkeypatch)

    _run_op(_args(checkout="C:\\explicit"))

    assert calls["project"] == GITLAB
    assert calls["checkout"] == "C:\\explicit"


def test_unique_entry_fills_missing_checkout(monkeypatch, tmp_path):
    _registry_file(monkeypatch, tmp_path, [_entry()])
    calls = _capture(monkeypatch)

    _run_op(_args(project="explicit/proj"))

    assert calls["project"] == "explicit/proj"
    assert calls["checkout"] == CHECKOUT


def test_unknown_repo_is_config_error(monkeypatch, tmp_path):
    _registry_file(monkeypatch, tmp_path, [_entry(repo="other-repo")])

    with pytest.raises(LgmError) as exc:
        _run_op(_args())

    assert exc.value.code == "config"
    assert REPO in exc.value.message
    assert "--project" in exc.value.message
    assert "--checkout" in exc.value.message


@pytest.mark.parametrize("bad", ["missing", "blank"])
def test_entry_without_gitlab_field_is_config_error(monkeypatch, tmp_path, bad):
    entry = _entry()
    if bad == "missing":
        del entry["gitlab"]
    else:
        entry["gitlab"] = ""
    _registry_file(monkeypatch, tmp_path, [entry])

    with pytest.raises(LgmError) as exc:
        _run_op(_args())

    assert exc.value.code == "config"
    assert "gitlab" in exc.value.message
    assert "--project" in exc.value.message


@pytest.mark.parametrize("bad", ["missing", "blank"])
def test_entry_without_path_field_is_config_error(monkeypatch, tmp_path, bad):
    entry = _entry()
    if bad == "missing":
        del entry["path"]
    else:
        entry["path"] = ""
    _registry_file(monkeypatch, tmp_path, [entry])

    with pytest.raises(LgmError) as exc:
        _run_op(_args())

    assert exc.value.code == "config"
    assert "path" in exc.value.message
    assert "--checkout" in exc.value.message


def test_duplicate_entries_without_selector_is_config_error(monkeypatch, tmp_path):
    _registry_file(monkeypatch, tmp_path, [
        _entry(), _entry(path="D:\\other", gitlab="kryptonit/other")])

    with pytest.raises(LgmError) as exc:
        _run_op(_args())

    assert exc.value.code == "config"
    assert "--project" in exc.value.message
    assert "--checkout" in exc.value.message


def test_explicit_selector_matching_no_entry_is_config_error(monkeypatch, tmp_path):
    _registry_file(monkeypatch, tmp_path, [
        _entry(), _entry(path="D:\\other", gitlab="kryptonit/other")])

    with pytest.raises(LgmError) as exc:
        _run_op(_args(project="nobody/here"))

    assert exc.value.code == "config"
    assert "--project" in exc.value.message
    assert "--checkout" in exc.value.message


def test_explicit_project_narrows_duplicate_entries(monkeypatch, tmp_path):
    _registry_file(monkeypatch, tmp_path, [
        _entry(), _entry(path="D:\\other", gitlab="kryptonit/other")])
    calls = _capture(monkeypatch)

    _run_op(_args(project="kryptonit/other"))

    assert calls["project"] == "kryptonit/other"
    assert calls["checkout"] == "D:\\other"


def test_explicit_checkout_narrows_duplicate_entries(monkeypatch, tmp_path):
    _registry_file(monkeypatch, tmp_path, [
        _entry(), _entry(path="D:\\other", gitlab="kryptonit/other")])
    calls = _capture(monkeypatch)

    _run_op(_args(checkout="D:\\other"))

    assert calls["project"] == "kryptonit/other"
    assert calls["checkout"] == "D:\\other"


def test_cli_no_longer_requires_project_checkout_flags():
    proc = subprocess.run(
        [sys.executable, str(_REPO_ROOT / "lgm.py"), "--json",
         "mr_review_prepare", "--repo", "lgm-test-unknown-repo", "--iid", "1"],
        capture_output=True, text=True, timeout=60, cwd=str(_REPO_ROOT))
    assert proc.returncode == 3, f"exit={proc.returncode} stderr={proc.stderr}"
    env = json.loads(proc.stdout)
    assert env["success"] is False
    assert env["error"]["kind"] == "config"


def test_mcp_schema_requires_only_repo_and_iid():
    op = get_op("mr_review_prepare")
    schema = lgm_mcp._build_tool_schema(op)
    assert schema["required"] == ["repo", "iid"]
    assert "project" in schema["properties"]
    assert "checkout" in schema["properties"]
    assert lgm_mcp._validate_arguments(op, {"repo": REPO, "iid": 7}) == []
