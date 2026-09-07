#!/usr/bin/env python3
"""
lgm_mcp — dependency-free MCP server over stdio for LocalGitMirror.

Implements a JSON-RPC 2.0 subset.  Tools are generated from
``lgm_core.ops.REGISTRY`` — the SAME registry that drives the ``lgm.py`` CLI.

Protocol (MCP stdio):
  - Outgoing: one JSON message per line (newline-delimited), per MCP spec.
  - Incoming: newline-delimited JSON; LSP-style Content-Length framed input
    is also accepted for clients that use that style.
  - stdout is the protocol channel — nothing else may write to stdout.
  - Diagnostics go to stderr only.

Methods: initialize, notifications/initialized, ping, tools/list, tools/call.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any

# Ensure the repo root (where lgm_core/ lives) is importable.
_REPO_ROOT = Path(__file__).resolve().parent
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from lgm_core.config import Config
from lgm_core.client import MirrorClient, LgmError
from lgm_core.ops import REGISTRY, Ctx, get_op

# ── Version ──────────────────────────────────────────────────────────────────

_SERVER_NAME = "lgm-mcp"
_PROTOCOL_VERSION = "2024-11-05"


def _server_version() -> str:
    """Best-effort version from git commit count, fallback to static."""
    try:
        import subprocess
        proc = subprocess.run(
            ["git", "rev-list", "--count", "HEAD"],
            cwd=str(_REPO_ROOT),
            capture_output=True, text=True, timeout=5,
        )
        if proc.returncode == 0 and proc.stdout.strip().isdigit():
            return f"0.{proc.stdout.strip()}"
    except Exception:
        pass
    return "1.0.0"


# ── stdio transport (binary I/O) ─────────────────────────────────────────────
#
# Binary I/O (sys.stdin.buffer / sys.stdout.buffer) avoids Windows text-mode
# newline translation.  Outgoing messages are newline-delimited JSON (the MCP
# stdio standard); incoming accepts NDJSON and, for compatibility, LSP-style
# Content-Length framed messages.


def _read_message(stdin_bin) -> dict | None:
    """Read one JSON-RPC message from a binary stream.

    NDJSON first (MCP spec); falls back to Content-Length framing when the
    line looks like an LSP header.  Returns the parsed dict, or None on EOF.
    """
    while True:
        raw_line = stdin_bin.readline()
        if not raw_line:
            return None  # EOF
        line = raw_line.decode("utf-8", errors="replace").strip()
        if not line:
            continue  # skip blank lines between messages

        if line.startswith("{"):
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                continue  # garbage line — keep reading

        if not line.lower().startswith("content-length:"):
            continue  # unknown non-JSON line — ignore
        try:
            length = int(line.split(":", 1)[1].strip())
        except ValueError:
            continue
        # Consume the rest of the header block (until blank line).
        while True:
            header_line = stdin_bin.readline()
            if not header_line or not header_line.strip():
                break
        body = stdin_bin.read(length)
        if len(body) < length:
            return None  # truncated
        try:
            return json.loads(body.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            continue


def _write_message(stdout_bin, msg: dict) -> None:
    """Write one newline-delimited JSON message (MCP stdio standard)."""
    payload = json.dumps(msg, ensure_ascii=False).encode("utf-8")
    stdout_bin.write(payload + b"\n")
    stdout_bin.flush()


# ── Tool schema generation from REGISTRY ─────────────────────────────────────

_PARAM_TYPE_MAP = {
    "str": "string",
    "int": "integer",
    "bool": "boolean",
}


def _build_tool_schema(op) -> dict:
    """Build a JSON Schema inputSchema from an Op's params."""
    properties: dict[str, dict] = {}
    required: list[str] = []
    for param in op.params:
        prop: dict[str, Any] = {
            "type": _PARAM_TYPE_MAP.get(param.type, "string"),
            "description": param.help or param.name,
        }
        if param.default is not None and param.type != "bool":
            prop["default"] = param.default
        properties[param.name] = prop
        if param.required:
            required.append(param.name)
    return {
        "type": "object",
        "properties": properties,
        "required": required,
    }


def _list_tools() -> list[dict]:
    """Generate the tools/list response from REGISTRY."""
    tools = []
    for op in REGISTRY:
        tools.append({
            "name": op.name,
            "description": op.summary,
            "inputSchema": _build_tool_schema(op),
        })
    return tools


# ── Tool dispatch ─────────────────────────────────────────────────────────────


def _call_tool(name: str, arguments: dict) -> dict:
    """Execute an op and return the MCP tools/call result."""
    op = get_op(name)
    if op is None:
        return {
            "isError": True,
            "content": [{"type": "text", "text": f"Unknown tool: {name}"}],
        }

    config = Config.from_env()
    client = MirrorClient(
        base_url=config.base_url,
        api_key=config.api_key,
        insecure_tls=config.insecure_tls,
        sync_password=config.sync_password,
    )
    ctx = Ctx(config=config, client=client)

    # Fill in defaults for missing params.
    args = {}
    for param in op.params:
        args[param.name] = arguments.get(param.name, param.default)

    try:
        result = op.run(ctx, args)
    except LgmError as e:
        return {
            "isError": True,
            "content": [{"type": "text", "text": f"[{e.code}] {e.message}"}],
        }
    except Exception as e:
        return {
            "isError": True,
            "content": [{"type": "text", "text": f"{type(e).__name__}: {e}"}],
        }

    text = json.dumps(result, ensure_ascii=False, indent=2, default=str)
    return {
        "content": [{"type": "text", "text": text}],
    }


# ── JSON-RPC dispatch ────────────────────────────────────────────────────────


def _handle_request(msg: dict) -> dict | None:
    """Process one JSON-RPC request. Returns a response dict or None (notification)."""
    method = msg.get("method", "")
    msg_id = msg.get("id")
    params = msg.get("params") or {}

    # Notifications (no id) — never get a response.
    if msg_id is None and method.startswith("notifications/"):
        return None

    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {
                "protocolVersion": _PROTOCOL_VERSION,
                "serverInfo": {
                    "name": _SERVER_NAME,
                    "version": _server_version(),
                },
                "capabilities": {
                    "tools": {},
                },
            },
        }

    if method == "ping":
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {},
        }

    if method == "tools/list":
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {"tools": _list_tools()},
        }

    if method == "tools/call":
        tool_name = params.get("name", "")
        arguments = params.get("arguments") or {}
        result = _call_tool(tool_name, arguments)
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": result,
        }

    # Unknown method.
    return {
        "jsonrpc": "2.0",
        "id": msg_id,
        "error": {
            "code": -32601,
            "message": f"Method not found: {method}",
        },
    }


def serve() -> int:
    """Main loop: read framed messages from stdin, write responses to stdout."""
    # Use binary I/O to avoid Windows text-mode newline translation.
    stdin_bin = sys.stdin.buffer
    stdout_bin = sys.stdout.buffer

    # Diagnostics only to stderr.
    print(f"[{_SERVER_NAME}] v{_server_version()} starting (NDJSON stdio)", file=sys.stderr)

    while True:
        msg = _read_message(stdin_bin)
        if msg is None:
            # EOF — client disconnected.
            break

        response = _handle_request(msg)
        if response is not None:
            _write_message(stdout_bin, response)

    print(f"[{_SERVER_NAME}] shutting down", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(serve())
