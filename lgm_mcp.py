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
import re
import sys
import threading
from pathlib import Path
from typing import Any

# Ensure the repo root (where lgm_core/ lives) is importable.
_REPO_ROOT = Path(__file__).resolve().parent
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.mr_review_models import mcp_result_bytes
from lgm_core.ops import REGISTRY, Ctx, get_op

# ── Version ──────────────────────────────────────────────────────────────────

_SERVER_NAME = "doccache-tools"
_PROTOCOL_VERSION = "2024-11-05"

# notifications/progress cadence for long tools/call runs (tests shrink this)
_PROGRESS_INTERVAL_SECONDS = 2.0


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
    "list": "array",
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
        if param.enum is not None:
            prop["enum"] = list(param.enum)
        if param.minimum is not None:
            prop["minimum"] = param.minimum
        if param.maximum is not None:
            prop["maximum"] = param.maximum
        if param.pattern:
            prop["pattern"] = param.pattern
        # A required param advertising a default invites the caller to omit it.
        if param.default is not None and param.type != "bool" and not param.required:
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


def _validate_arguments(op, arguments: dict) -> list[str]:
    """Schema violations for the op's own generated inputSchema
    (required / type / enum / minimum / maximum / pattern)."""
    violations: list[str] = []
    for param in op.params:
        missing = param.name not in arguments or arguments[param.name] is None
        if missing:
            if param.required:
                violations.append(f"missing required parameter '{param.name}'")
            continue
        value = arguments[param.name]
        if param.type == "int":
            if isinstance(value, bool) or not isinstance(value, int):
                violations.append(
                    f"parameter '{param.name}' must be an integer, "
                    f"got {type(value).__name__}")
                continue
            if param.minimum is not None and value < param.minimum:
                violations.append(
                    f"parameter '{param.name}' must be >= {param.minimum}, "
                    f"got {value}")
            if param.maximum is not None and value > param.maximum:
                violations.append(
                    f"parameter '{param.name}' must be <= {param.maximum}, "
                    f"got {value}")
        elif param.type == "bool":
            if not isinstance(value, bool):
                violations.append(
                    f"parameter '{param.name}' must be a boolean, "
                    f"got {type(value).__name__}")
        elif param.type == "list":
            if not isinstance(value, list):
                violations.append(
                    f"parameter '{param.name}' must be an array, "
                    f"got {type(value).__name__}")
        else:
            if not isinstance(value, str):
                violations.append(
                    f"parameter '{param.name}' must be a string, "
                    f"got {type(value).__name__}")
                continue
            if param.enum is not None and value not in param.enum:
                violations.append(
                    f"parameter '{param.name}' must be one of {param.enum}, "
                    f"got {value!r}")
            if param.pattern and re.fullmatch(param.pattern, value) is None:
                violations.append(
                    f"parameter '{param.name}' does not match pattern "
                    f"{param.pattern!r}, got {value!r}")
    return violations


_FAILURE_ENVELOPE_KEYS = {"success", "error", "message", "code"}


def _error_result(envelope: dict) -> dict:
    """An isError tool result whose text is the CLI's JSON error envelope."""
    return {
        "isError": True,
        "content": [{"type": "text",
                     "text": json.dumps(envelope, ensure_ascii=False, indent=2,
                                        default=str)}],
    }


def _run_op_with_progress(op, ctx, args: dict, progress) -> tuple[dict, Exception | None]:
    """Run the op on a worker thread, calling progress() every interval until it ends."""
    box: dict = {}

    def _runner() -> None:
        try:
            box["result"] = op.run(ctx, args)
        except Exception as e:
            box["error"] = e

    worker = threading.Thread(target=_runner, daemon=True)
    worker.start()
    while worker.is_alive():
        worker.join(_PROGRESS_INTERVAL_SECONDS)
        if worker.is_alive():
            progress()
    return box.get("result", {}), box.get("error")


def _call_tool(name: str, arguments: dict, progress=None) -> dict:
    """Execute an op and return the MCP tools/call result.

    Schema violations, LgmErrors, crashes and op results with
    ``success is False`` all return ``isError: true`` with the same JSON
    error envelope the CLI prints under ``--json``. When ``progress`` is given,
    a long op runs on a worker thread and ``progress`` fires every
    ``_PROGRESS_INTERVAL_SECONDS`` until it finishes.
    """
    op = get_op(name)
    if op is None:
        return _error_result({"success": False, "error": {
            "kind": "unknown-tool", "code": None,
            "message": f"Unknown tool: {name}"}})

    if not isinstance(arguments, dict):
        return _error_result({"success": False, "error": {
            "kind": "schema", "code": "invalid-arguments",
            "message": "arguments must be a JSON object"}})
    violations = _validate_arguments(op, arguments)
    if violations:
        return _error_result({"success": False, "error": {
            "kind": "schema", "code": "invalid-arguments",
            "message": "; ".join(violations)}})

    config = Config.from_env()
    client = MirrorClient(
        base_url=config.base_url,
        api_key=config.api_key,
        insecure_tls=config.insecure_tls,
        sync_password=config.sync_password,
        pin_sha256=config.pin_sha256,
        pin_file=config.pin_file,
        timeout=config.timeout,
    )
    ctx = Ctx(config=config, client=client)

    # Fill in defaults for missing params.
    args = {}
    for param in op.params:
        args[param.name] = arguments.get(param.name, param.default)

    if progress is None:
        try:
            result = op.run(ctx, args)
        except LgmError as e:
            return _error_result({"success": False, "error": {
                "kind": e.code, "code": e.machine_code, "message": e.message}})
        except Exception as e:
            return _error_result({"success": False, "error": {
                "kind": "crash", "code": None,
                "message": f"{type(e).__name__}: {e}"}})
    else:
        result, error = _run_op_with_progress(op, ctx, args, progress)
        if isinstance(error, LgmError):
            return _error_result({"success": False, "error": {
                "kind": error.code, "code": error.machine_code,
                "message": error.message}})
        if error is not None:
            return _error_result({"success": False, "error": {
                "kind": "crash", "code": None,
                "message": f"{type(error).__name__}: {error}"}})

    if isinstance(result, dict) and result.get("success") is False:
        detail = result.get("error") or result.get("message") \
            or "operation reported success=false"
        code = result.get("code")
        envelope = {"success": False, "error": {
            "kind": "op_failure",
            "code": code if isinstance(code, str) else None,
            "message": str(detail)}}
        if set(result) - _FAILURE_ENVELOPE_KEYS:
            envelope["result"] = result
        return _error_result(envelope)

    if any(p.name == "budget_bytes" for p in op.params):
        # mcp_result_bytes measures the real re-escaping; no fixed surcharge model
        measured = mcp_result_bytes(result)
        budget = args.get("budget_bytes")
        if measured > budget:
            return _error_result({"success": False, "error": {
                "kind": "op_failure", "code": "budget-exceeded",
                "message": f"serialized tools/call result is {measured} bytes, "
                           f"above the requested budget_bytes={budget}"}})

    text = json.dumps(result, ensure_ascii=False, indent=2, default=str)
    return {
        "content": [{"type": "text", "text": text}],
    }


# ── JSON-RPC dispatch ────────────────────────────────────────────────────────


def _handle_request(msg: dict, write=None) -> dict | None:
    """Process one JSON-RPC request. Returns a response dict or None (notification).

    ``write`` (a callable taking a message dict) enables progress notifications
    for a long ``tools/call`` when the client sent a progressToken; None
    (single-arg callers, tests) keeps the synchronous behaviour.
    """
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
        progress = None
        token = (params.get("_meta") or {}).get("progressToken")
        if write is not None and token is not None:
            counter = {"n": 0}

            def progress() -> None:
                counter["n"] += 1
                elapsed = counter["n"] * _PROGRESS_INTERVAL_SECONDS
                write({"jsonrpc": "2.0", "method": "notifications/progress",
                       "params": {"progressToken": token, "progress": counter["n"],
                                  "message": f"{tool_name} still running (~{elapsed:.0f}s)"}})
        result = _call_tool(tool_name, arguments, progress=progress)
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

        response = _handle_request(msg, write=lambda m: _write_message(stdout_bin, m))
        if response is not None:
            _write_message(stdout_bin, response)

    print(f"[{_SERVER_NAME}] shutting down", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(serve())
