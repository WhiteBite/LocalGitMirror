"""Chat, file transfer and config ops for the LGM MCP/CLI.

chat_send/chat_list — cross-machine messaging via the buffer.
file_send/file_list/file_get — file transfer via the postbox.
config_get/config_set — server settings for debugging and reconfiguration.
"""
from __future__ import annotations

import base64
import json
import time
from pathlib import Path

from .client import LgmError
from .crypto import encrypt_bundle, decrypt_bundle
from .op_models import Ctx, _client


def _chat_payload(text: str) -> bytes:
    return json.dumps({"text": text, "ts": int(time.time())}, ensure_ascii=False).encode("utf-8")


def op_chat_send(ctx: Ctx, args: dict) -> dict:
    """Send a chat message to the other machine via the exchange buffer."""
    text = args.get("text", "").strip()
    if not text:
        raise LgmError("config", "--text is required")
    if not ctx.config.sync_password:
        raise LgmError("config", "SYNC_PASSWORD is not set — chat uses password-mode crypto")
    c = _client(ctx)
    encrypted = encrypt_bundle(_chat_payload(text), ctx.config.sync_password)
    ciphertext_b64 = base64.b64encode(encrypted).decode("ascii")
    res = c.buffer_send(ciphertext_b64, hint="chat:mcp")
    if "id" not in res:
        return {"success": False, "message": str(res)[:300]}
    return {"success": True, "id": res["id"], "text": text[:200]}


def op_chat_list(ctx: Ctx, args: dict) -> dict:
    """Read chat messages from the exchange buffer."""
    try:
        count = int(args.get("count", 20) or 20)
    except (TypeError, ValueError):
        count = 20
    if count < 1:
        count = 1
    if count > 100:
        count = 100

    c = _client(ctx)
    listing = c.buffer_list()
    items = listing.get("items") or []
    chat_items = [i for i in items if (i.get("hint") or "").startswith("chat:")]

    messages = []
    for item in chat_items[:count]:
        try:
            raw = c.buffer_get(item["id"])
            plain = decrypt_bundle(raw, ctx.config.sync_password)
            data = json.loads(plain)
            messages.append({
                "id": item["id"],
                "text": data.get("text", ""),
                "ts": data.get("ts", 0),
                "hint": item.get("hint", ""),
            })
        except (LgmError, ValueError, json.JSONDecodeError):
            continue

    return {"success": True, "count": len(messages), "messages": messages}


def op_file_send(ctx: Ctx, args: dict) -> dict:
    """Send a file to the other machine via the encrypted postbox."""
    path = args.get("path", "")
    repo = args.get("repo", "")
    if not path:
        raise LgmError("config", "--path is required")
    if not repo:
        raise LgmError("config", "--repo is required")

    p = Path(path).resolve()
    if not p.is_file():
        raise LgmError("config", f"file not found: {p}")
    data = p.read_bytes()
    if len(data) > 100 * 1024 * 1024:
        raise LgmError("config", "file too large (max 100 MB)")

    c = _client(ctx)
    rel = p.name
    res = c.file_sync_send(repo, rel, len(data), data)
    if not res.get("success"):
        return {"success": False, "message": str(res)[:300]}
    return {"success": True, "id": res.get("id", ""), "file": rel, "size": len(data)}


def op_file_list(ctx: Ctx, args: dict) -> dict:
    """List files in the postbox for a repo."""
    repo = args.get("repo", "")
    if not repo:
        raise LgmError("config", "--repo is required")
    c = _client(ctx)
    res = c.file_sync_list(repo)
    items = res.get("items") or []
    return {
        "success": True,
        "repo": repo,
        "count": len(items),
        "files": [
            {"id": i["id"], "path": i["path"], "size": i.get("plain_size", i.get("size", 0))}
            for i in items
        ],
    }


def op_file_get(ctx: Ctx, args: dict) -> dict:
    """Download a file from the postbox and save to disk."""
    repo = args.get("repo", "")
    item_id = args.get("id", "")
    save_path = args.get("save", "")
    if not repo:
        raise LgmError("config", "--repo is required")
    if not item_id:
        raise LgmError("config", "--id is required")

    c = _client(ctx)
    data = c.file_sync_fetch(repo, item_id)

    if save_path:
        dest = Path(save_path).resolve()
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        return {"success": True, "id": item_id, "saved": str(dest), "size": len(data)}

    return {"success": True, "id": item_id, "size": len(data),
            "message": f"Downloaded {len(data)} bytes. Pass --save to write to disk."}


def op_config_get(ctx: Ctx, args: dict) -> dict:
    """Read server settings (all, or a specific section)."""
    section = args.get("section", "")
    c = _client(ctx)
    settings = c._get_json("/api/settings")
    if section:
        value = settings.get(section)
        if value is None:
            available = ", ".join(settings.keys())
            return {"success": False, "message": f"section '{section}' not found. Available: {available}"}
        return {"success": True, "section": section, "value": value}
    return {"success": True, "settings": settings}


def op_config_set(ctx: Ctx, args: dict) -> dict:
    """Update a server setting. section=key=value or JSON."""
    section = args.get("section", "")
    key = args.get("key", "")
    value = args.get("value", "")
    if not section or not key:
        raise LgmError("config", "--section and --key are required (use --value for the new value)")

    c = _client(ctx)
    current = c._get_json("/api/settings")
    if section not in current:
        available = ", ".join(current.keys())
        raise LgmError("config", f"section '{section}' not found. Available: {available}")

    existing = dict(current.get(section) or {})
    existing[key] = _parse_value(value)

    body = {
        "general": current.get("general", {}),
        "git": current.get("git", {}),
        "ui": current.get("ui", {}),
    }
    body[section] = existing

    result = c._post_json("/api/settings", json.dumps(body).encode("utf-8"),
                          "application/json")
    return {"success": result.get("success", False), "section": section, "key": key, "value": existing[key]}


def _parse_value(raw: str):
    raw = raw.strip()
    if raw.lower() in ("true", "false"):
        return raw.lower() == "true"
    try:
        return int(raw)
    except ValueError:
        pass
    try:
        return float(raw)
    except ValueError:
        pass
    if raw.startswith("{") or raw.startswith("["):
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            pass
    return raw
