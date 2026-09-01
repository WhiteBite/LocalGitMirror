"""Test MCP framing (Content-Length encode/decode) and in-process dispatch."""
import io
import json
import sys
from pathlib import Path

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


def test_framing_encode_decode_roundtrip():
    """A message encoded with Content-Length framing must decode back identically."""
    original = {"jsonrpc": "2.0", "id": 1, "method": "ping"}
    encoded = _encode(original)
    # Simulate binary stdin.
    fake_stdin = io.BytesIO(encoded)
    decoded = lgm_mcp._read_message(fake_stdin)
    assert decoded is not None
    assert decoded == original


def test_framing_multiple_messages():
    """Multiple framed messages in sequence must each decode correctly."""
    msg1 = {"jsonrpc": "2.0", "id": 1, "method": "ping"}
    msg2 = {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
    encoded = _encode(msg1) + _encode(msg2)
    fake_stdin = io.BytesIO(encoded)
    d1 = lgm_mcp._read_message(fake_stdin)
    d2 = lgm_mcp._read_message(fake_stdin)
    assert d1 == msg1
    assert d2 == msg2


def test_framing_eof_returns_none():
    """EOF (empty readline) must return None."""
    fake_stdin = io.BytesIO(b"")
    assert lgm_mcp._read_message(fake_stdin) is None


def test_write_message_produces_framed_output():
    """_write_message must produce Content-Length-framed output."""
    msg = {"jsonrpc": "2.0", "id": 1, "result": {"ok": True}}
    fake_stdout = io.BytesIO()
    lgm_mcp._write_message(fake_stdout, msg)
    output = fake_stdout.getvalue()
    assert b"Content-Length:" in output
    assert b"\r\n\r\n" in output
    # The body after the header block must be valid JSON.
    body = output.split(b"\r\n\r\n", 1)[1]
    assert json.loads(body) == msg


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
    assert result["serverInfo"]["name"] == "lgm-mcp"
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
