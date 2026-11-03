import importlib
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import lgm
from lgm_core.config import Config
from lgm_core.ops import Ctx, get_op

spec = importlib.util.spec_from_file_location(
    "lgm_mcp", str(Path(__file__).resolve().parent.parent / "lgm_mcp.py")
)
lgm_mcp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lgm_mcp)


_RICH = {
    "success": False,
    "error": "precheck rejected the send",
    "code": "diverged",
    "check_only": True,
    "precheck": {"status": "rejected", "reasons": ["branch diverged from origin"]},
}

_SIMPLE = {
    "success": False,
    "error": "boom",
    "code": "diverged",
}

_MR_SYNC_PARTIAL = {
    "success": False,
    "dry_run": False,
    "notes_only": False,
    "repos": [
        {
            "repo": "phonyx",
            "gitlab": "group/phonyx",
            "open_mrs": 1,
            "mrs": [{"iid": 42, "title": "Fix thing", "branch": "feature/x",
                     "notes": "failed: postbox rejected the note"}],
            "branches_sent": [],
            "branches_skipped": [],
            "branch_warnings": [],
            "blocked": [],
            "review_snapshots": [],
            "closed": [],
        }
    ],
    "blocked": [],
    "summary": {
        "repos": 1,
        "mrs": 1,
        "branches_sent": 0,
        "branches_skipped": 0,
        "branch_warnings": 0,
        "notes_uploaded": 0,
        "notes_unchanged": 0,
        "closed_marked": 0,
        "review_prepared": 0,
        "review_skipped": 0,
        "review_failed": 0,
        "failures": [{"repo": "phonyx", "iid": 42, "stage": "notes",
                      "error": "postbox rejected the note"}],
        "errors": ["phonyx: MR !42 notes failed: postbox rejected the note"],
    },
    "message": "phonyx: MR !42 notes failed: postbox rejected the note",
}


def _run_cli(monkeypatch, capsys, argv):
    config = Config(base_url="http://localhost:1", api_key="", sync_password="pw")
    monkeypatch.setattr(lgm, "_resolve_config", lambda args: config)
    monkeypatch.setattr(
        lgm, "_build_ctx",
        lambda cfg: Ctx(config=cfg, client=SimpleNamespace(sync_password="pw")))
    monkeypatch.setattr(sys, "argv", ["lgm", *argv])
    code = lgm.main()
    captured = capsys.readouterr()
    return code, captured.out, captured.err


def _patch_run(monkeypatch, op_name, payload):
    monkeypatch.setattr(get_op(op_name), "run", lambda ctx, args: dict(payload))


def _mcp_envelope(result):
    assert result["isError"] is True, f"expected isError, got: {result}"
    return json.loads(result["content"][0]["text"])


def test_cli_rich_failure_preserves_full_result(monkeypatch, capsys):
    _patch_run(monkeypatch, "scan", _RICH)
    code, out, err = _run_cli(monkeypatch, capsys, ["scan", "--json"])
    assert code == 5, f"exit={code} stdout={out}"
    env = json.loads(out)
    assert env["success"] is False
    assert env["error"]["kind"] == "op_failure"
    assert env["error"]["code"] == "diverged"
    assert env["error"]["message"] == "precheck rejected the send"
    assert env["result"] == _RICH


def test_mcp_rich_failure_preserves_full_result(monkeypatch):
    _patch_run(monkeypatch, "scan", _RICH)
    env = _mcp_envelope(lgm_mcp._call_tool("scan", {}))
    assert env["success"] is False
    assert env["error"]["kind"] == "op_failure"
    assert env["error"]["code"] == "diverged"
    assert env["error"]["message"] == "precheck rejected the send"
    assert env["result"] == _RICH


def test_cli_and_mcp_identical_nested_payload(monkeypatch, capsys):
    _patch_run(monkeypatch, "scan", _RICH)
    code, out, err = _run_cli(monkeypatch, capsys, ["scan", "--json"])
    cli_env = json.loads(out)
    mcp_env = _mcp_envelope(lgm_mcp._call_tool("scan", {}))
    assert cli_env["result"] == mcp_env["result"] == _RICH
    assert set(cli_env) == set(mcp_env)
    assert set(cli_env["error"]) == set(mcp_env["error"])


def test_cli_simple_failure_has_no_result_key(monkeypatch, capsys):
    _patch_run(monkeypatch, "scan", _SIMPLE)
    code, out, err = _run_cli(monkeypatch, capsys, ["scan", "--json"])
    assert code == 5, f"exit={code} stdout={out}"
    env = json.loads(out)
    assert env["success"] is False
    assert env["error"]["kind"] == "op_failure"
    assert env["error"]["code"] == "diverged"
    assert "result" not in env


def test_mcp_simple_failure_has_no_result_key(monkeypatch):
    _patch_run(monkeypatch, "scan", _SIMPLE)
    env = _mcp_envelope(lgm_mcp._call_tool("scan", {}))
    assert env["success"] is False
    assert env["error"]["code"] == "diverged"
    assert "result" not in env


def test_cli_mr_sync_all_note_failure_preserves_partial(monkeypatch, capsys):
    _patch_run(monkeypatch, "mr_sync_all", _MR_SYNC_PARTIAL)
    code, out, err = _run_cli(monkeypatch, capsys, ["mr_sync_all", "--json"])
    assert code == 5, f"exit={code} stdout={out}"
    env = json.loads(out)
    assert env["success"] is False
    assert env["error"]["kind"] == "op_failure"
    assert env["error"]["message"] == _MR_SYNC_PARTIAL["message"]
    assert env["result"] == _MR_SYNC_PARTIAL


def test_mcp_mr_sync_all_note_failure_preserves_partial(monkeypatch):
    _patch_run(monkeypatch, "mr_sync_all", _MR_SYNC_PARTIAL)
    env = _mcp_envelope(lgm_mcp._call_tool("mr_sync_all", {}))
    assert env["success"] is False
    assert env["error"]["kind"] == "op_failure"
    assert env["error"]["message"] == _MR_SYNC_PARTIAL["message"]
    assert env["result"] == _MR_SYNC_PARTIAL


def test_cli_human_rich_failure_renders_partial_summary(monkeypatch, capsys):
    _patch_run(monkeypatch, "mr_sync_all", _MR_SYNC_PARTIAL)
    code, out, err = _run_cli(monkeypatch, capsys, ["mr_sync_all"])
    assert code == 5, f"exit={code} stdout={out!r} stderr={err!r}"
    assert "!42" in out
    assert "phonyx" in out
    assert "[FAILED]" in out
    assert not out.lstrip().startswith("{")
    assert "ERROR" in err


def test_cli_human_simple_failure_keeps_stdout_empty(monkeypatch, capsys):
    _patch_run(monkeypatch, "scan", _SIMPLE)
    code, out, err = _run_cli(monkeypatch, capsys, ["scan"])
    assert code == 5, f"exit={code} stdout={out!r}"
    assert out == ""
    assert "ERROR" in err and "boom" in err


def test_cli_rich_failure_serializes_path(monkeypatch, capsys):
    payload = {"success": False, "error": "x", "path": Path("some/dir")}
    _patch_run(monkeypatch, "scan", payload)
    code, out, err = _run_cli(monkeypatch, capsys, ["scan", "--json"])
    assert code == 5, f"exit={code} stdout={out}"
    env = json.loads(out)
    assert isinstance(env["result"]["path"], str)


def test_mcp_rich_failure_serializes_path(monkeypatch):
    payload = {"success": False, "error": "x", "path": Path("some/dir")}
    _patch_run(monkeypatch, "scan", payload)
    env = _mcp_envelope(lgm_mcp._call_tool("scan", {}))
    assert isinstance(env["result"]["path"], str)


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
