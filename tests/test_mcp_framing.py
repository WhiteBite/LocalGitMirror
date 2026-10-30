"""Test MCP stdio transport (NDJSON out; NDJSON + Content-Length in) and dispatch."""
import base64
import io
import json
import re
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

# Import lgm_mcp as a module (it's at repo root).
import importlib
spec = importlib.util.spec_from_file_location(
    "lgm_mcp", str(Path(__file__).resolve().parent.parent / "lgm_mcp.py")
)
lgm_mcp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lgm_mcp)


# ── Framing roundtrip ────────────────────────────────────────────────────────


def _encode(msg: dict) -> bytes:
    """Encode a message with Content-Length framing (binary)."""
    body = json.dumps(msg).encode("utf-8")
    return f"Content-Length: {len(body)}\r\n\r\n".encode("ascii") + body


def test_ndjson_roundtrip():
    """A newline-delimited JSON message (MCP spec) must decode back identically."""
    original = {"jsonrpc": "2.0", "id": 1, "method": "ping"}
    fake_stdin = io.BytesIO(json.dumps(original).encode("utf-8") + b"\n")
    decoded = lgm_mcp._read_message(fake_stdin)
    assert decoded is not None
    assert decoded == original


def test_content_length_input_still_supported():
    """Legacy LSP-style Content-Length framed input must still decode."""
    original = {"jsonrpc": "2.0", "id": 1, "method": "ping"}
    fake_stdin = io.BytesIO(_encode(original))
    decoded = lgm_mcp._read_message(fake_stdin)
    assert decoded is not None
    assert decoded == original


def test_framing_multiple_messages():
    """NDJSON and framed messages mixed in sequence must each decode correctly."""
    msg1 = {"jsonrpc": "2.0", "id": 1, "method": "ping"}
    msg2 = {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
    stream = json.dumps(msg1).encode("utf-8") + b"\n" + _encode(msg2)
    fake_stdin = io.BytesIO(stream)
    d1 = lgm_mcp._read_message(fake_stdin)
    d2 = lgm_mcp._read_message(fake_stdin)
    assert d1 == msg1
    assert d2 == msg2


def test_framing_eof_returns_none():
    """EOF (empty readline) must return None."""
    fake_stdin = io.BytesIO(b"")
    assert lgm_mcp._read_message(fake_stdin) is None


def test_write_message_produces_ndjson():
    """_write_message must emit one JSON line terminated by \\n (MCP stdio)."""
    msg = {"jsonrpc": "2.0", "id": 1, "result": {"ok": True}}
    fake_stdout = io.BytesIO()
    lgm_mcp._write_message(fake_stdout, msg)
    output = fake_stdout.getvalue()
    assert output.endswith(b"\n")
    assert b"Content-Length:" not in output
    assert json.loads(output.decode("utf-8")) == msg


def test_framing_unicode_content():
    """Unicode content must be framed with correct byte length."""
    original = {"jsonrpc": "2.0", "id": 1, "method": "test", "params": {"msg": "Привет"}}
    encoded = _encode(original)
    fake_stdin = io.BytesIO(encoded)
    decoded = lgm_mcp._read_message(fake_stdin)
    assert decoded is not None
    assert decoded["params"]["msg"] == "Привет"


# ── tools/list ───────────────────────────────────────────────────────────────


def test_tools_list_has_all_ops():
    """tools/list must include one tool per REGISTRY op."""
    tools = lgm_mcp._list_tools()
    from lgm_core.ops import REGISTRY
    assert len(tools) == len(REGISTRY)
    names = {t["name"] for t in tools}
    for op in REGISTRY:
        assert op.name in names, f"Op '{op.name}' missing from tools/list"


def test_tools_list_schema_shape():
    """Each tool must have name, description, inputSchema with correct structure."""
    tools = lgm_mcp._list_tools()
    for tool in tools:
        assert "name" in tool
        assert "description" in tool
        assert "inputSchema" in tool
        schema = tool["inputSchema"]
        assert schema["type"] == "object"
        assert "properties" in schema
        assert "required" in schema
        assert isinstance(schema["required"], list)


def test_tool_schema_param_types():
    """Param types must map to JSON Schema types correctly."""
    tools = lgm_mcp._list_tools()
    # Find the 'scan' tool and check its params.
    scan_tool = next(t for t in tools if t["name"] == "scan")
    props = scan_tool["inputSchema"]["properties"]
    assert props["verbose"]["type"] == "boolean"
    assert props["filter"]["type"] == "string"
    assert props["gradle_home"]["type"] == "string"


def test_tool_schema_required_fields():
    """Required params must appear in the required list."""
    tools = lgm_mcp._list_tools()
    # 'request' has --project required.
    request_tool = next(t for t in tools if t["name"] == "request")
    assert "project" in request_tool["inputSchema"]["required"]


# ── tool schema metadata (enum / bounds / pattern / required) ─────────────────


def _tool(name: str) -> dict:
    tools = lgm_mcp._list_tools()
    return next(t for t in tools if t["name"] == name)


def _props(name: str) -> dict:
    return _tool(name)["inputSchema"]["properties"]


def test_config_get_section_enum():
    """Enumerable params must expose their closed value set as enum."""
    assert _props("config_get")["section"]["enum"] == ["", "general", "git", "ui"]


def test_config_set_section_enum():
    assert _props("config_set")["section"]["enum"] == ["general", "git", "ui"]


def test_enum_params_include_their_default():
    """A default outside the enum is a contradiction the caller can hit."""
    for tool in lgm_mcp._list_tools():
        for name, prop in tool["inputSchema"]["properties"].items():
            if "enum" in prop and "default" in prop:
                assert prop["default"] in prop["enum"], (
                    f"{tool['name']}.{name} default {prop['default']!r} "
                    f"not in enum {prop['enum']}")


def test_chat_list_count_bounds():
    """Numeric params with a help-stated range must expose minimum/maximum."""
    assert _props("chat_list")["count"]["minimum"] == 1
    assert _props("chat_list")["count"]["maximum"] == 100


def test_integer_params_expose_minimum():
    floors = {
        ("prune", "older_days"): 0,
        ("mr_send", "iid"): 0,
        ("mr_notes", "iid"): 0,
        ("mr_replies_send", "iid"): 1,
        ("mr_replies_status", "iid"): 0,
        ("chat_list", "count"): 1,
    }
    for (op_name, param), floor in floors.items():
        assert _props(op_name)[param]["minimum"] == floor, (
            f"{op_name}.{param} minimum != {floor}")


def test_sha_params_expose_pattern():
    for op_name, param in (("pull", "since"), ("pull", "haves"),
                           ("has_commits", "commits")):
        assert _props(op_name)[param].get("pattern"), (
            f"{op_name}.{param} lacks a pattern")


def test_id_params_expose_pattern():
    for op_name in ("respond", "apply", "file_get", "file_ack"):
        assert _props(op_name)["id"].get("pattern"), f"{op_name}.id lacks a pattern"


def test_mr_send_iids_pattern():
    assert _props("mr_send")["iids"].get("pattern")


def test_patterns_match_real_values():
    since = re.compile(_props("pull")["since"]["pattern"])
    assert since.match("")  # optional: empty means unset
    assert since.match("0123abcd")
    assert since.match("f" * 40)
    assert not since.match("not-a-sha")
    file_id = re.compile(_props("file_get")["id"]["pattern"])
    assert file_id.match("0" * 32)
    assert not file_id.match("short")
    iids = re.compile(_props("mr_send")["iids"]["pattern"])
    assert iids.match("41,42,43")
    assert not iids.match("41,")


def test_required_params_advertise_no_default():
    """A required param with a default invites the caller to omit it."""
    for tool in lgm_mcp._list_tools():
        schema = tool["inputSchema"]
        for name in schema["required"]:
            assert "default" not in schema["properties"][name], (
                f"{tool['name']}.{name} is required but advertises a default")


def test_mr_replies_send_iid_required():
    schema = _tool("mr_replies_send")["inputSchema"]
    assert "iid" in schema["required"]
    assert "default" not in schema["properties"]["iid"]


def test_mr_projects_scan_root_required_array():
    schema = _tool("mr_projects_scan")["inputSchema"]
    assert "root" in schema["required"]
    assert schema["properties"]["root"]["type"] == "array"
    assert "default" not in schema["properties"]["root"]


# ── status: the real configured machine role ─────────────────────────────────


class _StatusClient:
    sync_password = "pw"

    def capabilities(self):
        return {"sync": {"features": {"v3": True}}}

    def repos(self):
        return {"repos": ["default", "other"]}

    def tools_version(self):
        return {"version": "0.1"}


def _status_ctx():
    from lgm_core.op_models import Ctx
    return Ctx(config=SimpleNamespace(base_url="https://localhost:1",
                                      sync_password="pw"),
               client=_StatusClient())


def test_status_reports_configured_machine_role(monkeypatch):
    """status must surface LGM_MACHINE_ROLE, not a heuristic placeholder."""
    import lgm_core.config as lgm_config
    from lgm_core.ops_git import op_status
    monkeypatch.setattr(lgm_config, "_ENV", {})
    monkeypatch.setenv("LGM_MACHINE_ROLE", "work")
    res = op_status(_status_ctx(), {})
    assert res["role_guess"] == "work"


def test_status_role_undetermined_when_unconfigured(monkeypatch):
    import lgm_core.config as lgm_config
    from lgm_core.ops_git import op_status
    monkeypatch.setattr(lgm_config, "_ENV", {})
    monkeypatch.delenv("LGM_MACHINE_ROLE", raising=False)
    res = op_status(_status_ctx(), {})
    assert "undetermined" in res["role_guess"]
    assert "home-or-work" not in res["role_guess"]


# ── In-process dispatch ───────────────────────────────────────────────────────


def test_handle_initialize():
    """initialize must return protocolVersion, serverInfo, capabilities."""
    msg = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}
    resp = lgm_mcp._handle_request(msg)
    assert resp is not None
    assert resp["jsonrpc"] == "2.0"
    assert resp["id"] == 1
    result = resp["result"]
    assert "protocolVersion" in result
    assert "serverInfo" in result
    assert result["serverInfo"]["name"] == "doccache-tools"
    assert "capabilities" in result
    assert "tools" in result["capabilities"]


def test_handle_ping():
    msg = {"jsonrpc": "2.0", "id": 2, "method": "ping"}
    resp = lgm_mcp._handle_request(msg)
    assert resp is not None
    assert resp["id"] == 2
    assert resp["result"] == {}


def test_handle_notifications_initialized():
    """notifications/initialized must return None (no response)."""
    msg = {"jsonrpc": "2.0", "method": "notifications/initialized"}
    resp = lgm_mcp._handle_request(msg)
    assert resp is None


def test_handle_tools_list():
    msg = {"jsonrpc": "2.0", "id": 3, "method": "tools/list"}
    resp = lgm_mcp._handle_request(msg)
    assert resp is not None
    assert "tools" in resp["result"]
    assert len(resp["result"]["tools"]) > 0


def test_handle_tools_call_unknown():
    """tools/call with an unknown tool must return isError."""
    msg = {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
           "params": {"name": "nonexistent", "arguments": {}}}
    resp = lgm_mcp._handle_request(msg)
    assert resp is not None
    result = resp["result"]
    assert result["isError"] is True


def test_handle_tools_call_scan():
    """tools/call for 'scan' must return text content with JSON."""
    msg = {"jsonrpc": "2.0", "id": 5, "method": "tools/call",
           "params": {"name": "scan", "arguments": {}}}
    resp = lgm_mcp._handle_request(msg)
    assert resp is not None
    result = resp["result"]
    assert "content" in result
    assert result["content"][0]["type"] == "text"
    # The text must be valid JSON.
    data = json.loads(result["content"][0]["text"])
    assert "roots" in data


def test_handle_unknown_method():
    """Unknown method must return a JSON-RPC error."""
    msg = {"jsonrpc": "2.0", "id": 6, "method": "unknown/method"}
    resp = lgm_mcp._handle_request(msg)
    assert resp is not None
    assert "error" in resp
    assert resp["error"]["code"] == -32601


# ── argument validation against the generated schema ─────────────────────────


def _envelope(result: dict) -> dict:
    assert result["isError"] is True, f"expected isError, got: {result}"
    return json.loads(result["content"][0]["text"])


def test_tools_call_missing_required_argument_is_schema_error():
    res = lgm_mcp._call_tool("chat_send", {})
    env = _envelope(res)
    assert env["success"] is False
    assert env["error"]["kind"] == "schema"
    assert env["error"]["code"] == "invalid-arguments"
    assert "text" in env["error"]["message"]


def test_tools_call_enum_violation_is_schema_error():
    res = lgm_mcp._call_tool("config_get", {"section": "bogus"})
    env = _envelope(res)
    assert env["error"]["kind"] == "schema"
    assert "section" in env["error"]["message"]


def test_tools_call_minimum_violation_is_schema_error():
    res = lgm_mcp._call_tool("chat_list", {"count": 0})
    env = _envelope(res)
    assert env["error"]["kind"] == "schema"
    assert "count" in env["error"]["message"]


def test_tools_call_pattern_violation_is_schema_error():
    res = lgm_mcp._call_tool("file_get", {"repo": "r", "id": "short"})
    env = _envelope(res)
    assert env["error"]["kind"] == "schema"
    assert "id" in env["error"]["message"]


def test_tools_call_type_violation_is_schema_error():
    res = lgm_mcp._call_tool("chat_list", {"count": "5"})
    env = _envelope(res)
    assert env["error"]["kind"] == "schema"
    assert "count" in env["error"]["message"]


def test_tools_call_non_object_arguments_is_schema_error():
    res = lgm_mcp._call_tool("chat_list", ["not", "an", "object"])
    env = _envelope(res)
    assert env["error"]["kind"] == "schema"


# ── op failures and LgmError map to isError with the CLI envelope ───────────


def test_tools_call_op_failure_maps_to_is_error_with_envelope():
    res = lgm_mcp._call_tool("mr_replies_send",
                             {"repo": "r1", "iid": 7, "text": "no sections here"})
    env = _envelope(res)
    assert env["success"] is False
    assert env["error"]["kind"] == "op_failure"
    assert "no reply sections" in env["error"]["message"]


def test_tools_call_lgm_error_maps_to_envelope():
    res = lgm_mcp._call_tool("mr_replies_send",
                             {"repo": "", "iid": 7, "text": "## new\nx"})
    env = _envelope(res)
    assert env["success"] is False
    assert env["error"]["kind"] == "config"
    assert "--repo" in env["error"]["message"]


def test_tools_call_success_result_has_no_is_error():
    res = lgm_mcp._call_tool("scan", {})
    assert "isError" not in res
    data = json.loads(res["content"][0]["text"])
    assert "roots" in data


# ── review ops: one CLI/MCP result contract, bounded surfaces ────────────────

REVIEW_OPS = ["mr_review_prepare", "mr_review_open", "mr_review_read",
              "mr_review_search", "mr_review_context", "mr_review_progress",
              "mr_review_refresh", "mr_review_materialize",
              "mr_review_findings"]

BOUNDED_OPS = ["mr_review_open", "mr_review_read", "mr_review_search",
               "mr_review_context"]

_REPO_ROOT = Path(__file__).resolve().parent.parent
_SNAP64 = "ab" * 32


def _mcp_subprocess(messages: list[dict]) -> list[dict]:
    payload = "".join(json.dumps(m) + "\n" for m in messages)
    proc = subprocess.run(
        [sys.executable, str(_REPO_ROOT / "lgm_mcp.py")],
        input=payload.encode("utf-8"), capture_output=True, timeout=120,
        cwd=str(_REPO_ROOT))
    assert proc.returncode == 0, proc.stderr.decode("utf-8", "replace")
    responses = []
    for line in proc.stdout.decode("utf-8").splitlines():
        line = line.strip()
        if line.startswith("{"):
            responses.append(json.loads(line))
    return responses


def test_cli_help_subprocess_lists_the_nine_review_ops():
    proc = subprocess.run(
        [sys.executable, str(_REPO_ROOT / "lgm.py"), "--help"],
        capture_output=True, text=True, timeout=120, cwd=str(_REPO_ROOT))
    assert proc.returncode == 0, proc.stderr
    for name in REVIEW_OPS:
        assert name in proc.stdout, f"{name} missing from lgm.py --help"


def test_mcp_subprocess_tools_list_exposes_bounded_review_ops():
    responses = _mcp_subprocess([
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
    ])
    assert len(responses) == 2
    assert responses[0]["result"]["serverInfo"]["name"] == "doccache-tools"
    tools = {t["name"]: t for t in responses[1]["result"]["tools"]}
    from lgm_core.ops import REGISTRY
    assert len(tools) == len(REGISTRY), "tools/list must derive from REGISTRY"
    for name in REVIEW_OPS:
        assert name in tools, f"{name} missing from MCP tools/list"
    for name in BOUNDED_OPS:
        props = tools[name]["inputSchema"]["properties"]
        assert props["budget_bytes"]["minimum"] == 4096
        assert props["budget_bytes"]["maximum"] == 48000
        assert props["budget_bytes"]["default"] == 24000
    read = tools["mr_review_read"]["inputSchema"]
    assert read["properties"]["view"]["enum"] == ["diff", "file", "discussions"]
    assert read["properties"]["side"]["enum"] == ["old", "new", "target"]
    assert read["properties"]["snapshot_id"]["pattern"] == "^[0-9a-f]{64}$"
    assert read["properties"]["context_lines"]["minimum"] == 0
    assert set(read["required"]) == {"repo", "snapshot_id", "view"}
    search = tools["mr_review_search"]["inputSchema"]
    assert search["properties"]["scope"]["enum"] == \
        ["tree", "added", "removed", "changed"]
    assert search["properties"]["side"]["enum"] == ["", "old", "new", "target"]
    materialize = tools["mr_review_materialize"]["inputSchema"]
    assert materialize["properties"]["action"]["enum"] == \
        ["create", "status", "release"]
    assert materialize["properties"]["target_project_id"]["minimum"] == 1
    findings = tools["mr_review_findings"]["inputSchema"]
    assert findings["properties"]["action"]["enum"] == \
        ["list", "save", "create_batch"]
    progress = tools["mr_review_progress"]["inputSchema"]
    assert progress["properties"]["action"]["enum"] == ["list", "mark"]
    assert progress["properties"]["expected_revision"]["minimum"] == 0
    assert tools["mr_review_open"]["inputSchema"]["properties"]["snapshot_id"] \
        ["pattern"] == "^$|^[0-9a-f]{64}$"
    assert tools["mr_review_refresh"]["inputSchema"]["properties"] \
        ["previous_snapshot_id"]["pattern"] == "^$|^[0-9a-f]{64}$"


def test_mcp_invalid_budget_is_schema_error_and_writes_nothing(tmp_path):
    home = tmp_path / "reviews"
    res = lgm_mcp._call_tool("mr_review_read", {
        "repo": "r1", "snapshot_id": _SNAP64, "view": "diff",
        "budget_bytes": 100, "home": str(home)})
    env = _envelope(res)
    assert env["success"] is False
    assert env["error"]["kind"] == "schema"
    assert env["error"]["code"] == "invalid-arguments"
    assert "budget_bytes" in env["error"]["message"]
    assert not home.exists()


def test_mcp_invalid_type_is_schema_error_and_writes_nothing(tmp_path):
    home = tmp_path / "reviews"
    res = lgm_mcp._call_tool("mr_review_context", {
        "repo": "r1", "snapshot_id": _SNAP64, "path": "a.py",
        "line": "12", "home": str(home)})
    env = _envelope(res)
    assert env["error"]["kind"] == "schema"
    assert env["error"]["code"] == "invalid-arguments"
    assert "line" in env["error"]["message"]
    assert not home.exists()


def test_mcp_invalid_paths_element_is_typed_and_writes_nothing(tmp_path):
    home = tmp_path / "reviews"
    res = lgm_mcp._call_tool("mr_review_read", {
        "repo": "r1", "snapshot_id": _SNAP64, "view": "diff",
        "paths": [123], "home": str(home)})
    env = _envelope(res)
    assert env["error"]["kind"] == "op_failure"
    assert env["error"]["code"] == "invalid-schema"
    assert "paths" in env["error"]["message"]
    assert not home.exists()


def test_mcp_invalid_cursor_is_typed_and_writes_nothing(tmp_path):
    home = tmp_path / "reviews"
    res = lgm_mcp._call_tool("mr_review_open", {
        "repo": "r1", "iid": 7, "cursor": "not-a-cursor", "home": str(home)})
    env = _envelope(res)
    assert env["error"]["kind"] == "op_failure"
    assert env["error"]["code"] == "invalid-schema"
    assert "cursor" in env["error"]["message"]
    assert not home.exists()


def test_mcp_tampered_cursor_is_hash_mismatch_and_writes_nothing(tmp_path):
    from lgm_core.mr_review_reader import CURSOR_VERSION
    home = tmp_path / "reviews"
    token = base64.urlsafe_b64encode(json.dumps({
        "v": CURSOR_VERSION, "sid": _SNAP64, "q": "0" * 64,
        "s": {"e": 0, "g": 0}, "d": "0" * 64}).encode("utf-8")).decode("ascii")
    res = lgm_mcp._call_tool("mr_review_open", {
        "repo": "r1", "iid": 7, "cursor": token, "home": str(home)})
    env = _envelope(res)
    assert env["error"]["kind"] == "op_failure"
    assert env["error"]["code"] == "hash-mismatch"
    assert not home.exists()


def test_handler_maps_review_error_to_typed_envelope_without_writes(tmp_path):
    from lgm_core.client import MirrorClient
    from lgm_core.config import Config
    from lgm_core.ops import Ctx, get_op
    home = tmp_path / "reviews"
    config = Config(base_url="http://localhost:1", api_key="",
                    sync_password="")
    client = MirrorClient(base_url=config.base_url, api_key=config.api_key)
    ctx = Ctx(config=config, client=client)
    result = get_op("mr_review_read").run(ctx, {
        "repo": "r1", "snapshot_id": _SNAP64, "view": "diff", "paths": [],
        "group": "", "side": "new", "cursor": "", "budget_bytes": 100,
        "context_lines": 20, "home": str(home)})
    assert result["success"] is False
    assert result["code"] == "invalid-budget"
    assert not home.exists()


def test_cli_json_subprocess_shares_the_mcp_error_contract(tmp_path):
    home = tmp_path / "reviews"
    proc = subprocess.run(
        [sys.executable, str(_REPO_ROOT / "lgm.py"), "--json",
         "mr_review_read", "--repo", "r1", "--snapshot-id", _SNAP64,
         "--view", "diff", "--budget-bytes", "100", "--home", str(home)],
        capture_output=True, text=True, timeout=120, cwd=str(_REPO_ROOT))
    assert proc.returncode == 5, proc.stderr
    env = json.loads(proc.stdout)
    assert env["success"] is False
    assert env["error"]["kind"] == "op_failure"
    assert env["error"]["code"] == "invalid-budget"
    assert not home.exists()
    mcp_env = _envelope(lgm_mcp._call_tool("mr_review_read", {
        "repo": "r1", "snapshot_id": _SNAP64, "view": "diff",
        "paths": [123], "home": str(home)}))
    assert set(mcp_env) == set(env)
    assert set(mcp_env["error"]) == set(env["error"])


def test_budget_guard_measures_serialization_not_a_linear_surcharge(monkeypatch):
    from lgm_core.mr_review_models import mcp_result_bytes
    from lgm_core.ops import get_op
    payload = {"blob": "\"" * 3000}
    assert len(payload["blob"]) + 20 < 4096 < mcp_result_bytes(payload)
    monkeypatch.setattr(get_op("mr_review_read"), "run",
                        lambda ctx, args: dict(payload))
    res = lgm_mcp._call_tool("mr_review_read", {
        "repo": "r1", "snapshot_id": _SNAP64, "view": "diff",
        "budget_bytes": 4096})
    env = _envelope(res)
    assert env["error"]["kind"] == "op_failure"
    assert env["error"]["code"] == "budget-exceeded"
    assert "4096" in env["error"]["message"]


def test_budget_guard_passes_result_within_budget(monkeypatch):
    from lgm_core.ops import get_op
    payload = {"view": "diff", "complete": True}
    monkeypatch.setattr(get_op("mr_review_read"), "run",
                        lambda ctx, args: dict(payload))
    res = lgm_mcp._call_tool("mr_review_read", {
        "repo": "r1", "snapshot_id": _SNAP64, "view": "diff",
        "budget_bytes": 4096})
    assert "isError" not in res
    assert json.loads(res["content"][0]["text"]) == payload


def test_budget_excludes_outer_jsonrpc_framing_checked_separately(monkeypatch):
    from lgm_core.mr_review_models import mcp_result_bytes
    from lgm_core.ops import get_op
    payload = {"view": "diff", "complete": True}
    monkeypatch.setattr(get_op("mr_review_read"), "run",
                        lambda ctx, args: dict(payload))
    resp = lgm_mcp._handle_request({
        "jsonrpc": "2.0", "id": 9, "method": "tools/call",
        "params": {"name": "mr_review_read",
                   "arguments": {"repo": "r1", "snapshot_id": _SNAP64,
                                 "view": "diff", "budget_bytes": 4096}}})
    inner = json.dumps(resp["result"], ensure_ascii=False).encode("utf-8")
    framed = json.dumps(resp, ensure_ascii=False).encode("utf-8")
    assert len(inner) == mcp_result_bytes(payload)
    assert len(framed) > len(inner)
    assert set(resp) == {"jsonrpc", "id", "result"}
