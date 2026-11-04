"""Long tools/call emits notifications/progress when the client sent a progressToken."""
import importlib
import json
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

spec = importlib.util.spec_from_file_location(
    "lgm_mcp", str(Path(__file__).resolve().parent.parent / "lgm_mcp.py")
)
lgm_mcp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lgm_mcp)

from lgm_core.client import LgmError


@pytest.fixture(autouse=True)
def _fast_interval(monkeypatch):
    monkeypatch.setattr(lgm_mcp, "_PROGRESS_INTERVAL_SECONDS", 0.05)


def _op_run(seconds: float, result=None, error=None):
    def run(ctx, args):
        time.sleep(seconds)
        if error is not None:
            raise error
        return result if result is not None else {"ok": True}
    return run


def _request(token, *, include_token: bool):
    params = {"name": "status", "arguments": {}}
    if include_token:
        params["_meta"] = {"progressToken": token}
    return {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": params}


def test_progress_notifications_precede_response(monkeypatch):
    monkeypatch.setattr(lgm_mcp.get_op("status"), "run", _op_run(0.15))
    collected: list = []

    resp = lgm_mcp._handle_request(_request(7, include_token=True),
                                   write=collected.append)

    assert collected, "expected at least one progress notification"
    for m in collected:
        assert m["method"] == "notifications/progress"
        assert m["params"]["progressToken"] == 7
    assert [m["params"]["progress"] for m in collected] == \
        list(range(1, len(collected) + 1))
    assert resp["id"] == 1
    assert resp["result"]["content"][0]["type"] == "text"


def test_no_token_means_no_progress(monkeypatch):
    monkeypatch.setattr(lgm_mcp.get_op("status"), "run", _op_run(0.15))
    collected: list = []

    resp = lgm_mcp._handle_request(_request(7, include_token=False),
                                   write=collected.append)

    assert collected == []
    assert resp["result"]["content"][0]["type"] == "text"


def test_no_writer_means_no_progress(monkeypatch):
    monkeypatch.setattr(lgm_mcp.get_op("status"), "run", _op_run(0.15))

    resp = lgm_mcp._handle_request(_request(7, include_token=True))

    assert resp["result"]["content"][0]["type"] == "text"


def test_error_envelope_unchanged_with_token(monkeypatch):
    monkeypatch.setattr(lgm_mcp.get_op("status"), "run",
                        _op_run(0.15, error=LgmError("network", "boom")))
    collected: list = []

    resp = lgm_mcp._handle_request(_request(9, include_token=True),
                                   write=collected.append)

    assert collected
    assert resp["result"]["isError"] is True
    env = json.loads(resp["result"]["content"][0]["text"])
    assert env["success"] is False
    assert env["error"]["kind"] == "network"
    assert "boom" in env["error"]["message"]


def test_fast_op_still_returns_result(monkeypatch):
    monkeypatch.setattr(lgm_mcp.get_op("status"), "run", _op_run(0.0, result={"ok": 1}))

    resp = lgm_mcp._handle_request(_request(1, include_token=True), write=lambda m: None)

    assert json.loads(resp["result"]["content"][0]["text"]) == {"ok": 1}


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
