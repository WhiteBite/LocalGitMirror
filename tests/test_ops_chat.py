"""Unit tests for the chat / file transfer / config ops. No network, no live mirror."""
import base64
import json
import sys
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.crypto import decrypt_bundle, encrypt_bundle
from lgm_core.ops import REGISTRY, Ctx, get_op
from lgm_core.ops_chat import _parse_value


def _ctx(sync_password: str = "pw") -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="",
                    sync_password=sync_password)
    client = MirrorClient(base_url=config.base_url, api_key="",
                          sync_password=sync_password)
    return Ctx(config=config, client=client)


def test_new_ops_present():
    expected = {"chat_send", "chat_list", "file_send", "file_list",
                "file_get", "config_get", "config_set"}
    actual = {op.name for op in REGISTRY}
    missing = expected - actual
    assert not missing, f"Missing new ops: {missing}"


def test_chat_params():
    send = get_op("chat_send")
    params = {p.name: p for p in send.params}
    assert params["text"].required is True

    listing = get_op("chat_list")
    params = {p.name: p for p in listing.params}
    assert params["count"].type == "int"
    assert params["count"].default == 20


def test_file_params():
    send = get_op("file_send")
    params = {p.name: p for p in send.params}
    assert params["path"].required is True
    assert params["repo"].required is True

    get = get_op("file_get")
    params = {p.name: p for p in get.params}
    assert params["repo"].required is True
    assert params["id"].required is True
    assert params["save"].required is False

    listing = get_op("file_list")
    params = {p.name: p for p in listing.params}
    assert params["repo"].required is True


def test_config_params():
    get = get_op("config_get")
    params = {p.name: p for p in get.params}
    assert params["section"].required is False

    setter = get_op("config_set")
    params = {p.name: p for p in setter.params}
    assert params["section"].required is True
    assert params["key"].required is True
    assert params["value"].required is True


def test_chat_send_requires_text():
    from lgm_core.ops import op_chat_send
    with pytest.raises(LgmError) as ei:
        op_chat_send(_ctx(), {})
    assert "--text" in ei.value.message


def test_chat_send_requires_sync_password():
    from lgm_core.ops import op_chat_send
    with pytest.raises(LgmError) as ei:
        op_chat_send(_ctx(sync_password=""), {"text": "hi"})
    assert "SYNC_PASSWORD" in ei.value.message


def test_chat_send_encrypts_bundle_password_mode():
    from lgm_core.ops import op_chat_send
    ctx = _ctx("pw")
    captured = {}

    def fake_buffer_send(ciphertext_b64, hint=""):
        captured["ciphertext_b64"] = ciphertext_b64
        captured["hint"] = hint
        return {"id": "item1", "ts": time.time()}

    ctx.client.buffer_send = fake_buffer_send

    res = op_chat_send(ctx, {"text": "hello from work"})
    assert res["success"] is True
    assert res["id"] == "item1"
    assert res["text"] == "hello from work"
    assert captured["hint"] == "chat:mcp"

    blob = base64.b64decode(captured["ciphertext_b64"], validate=True)
    plain = decrypt_bundle(blob, "pw")
    data = json.loads(plain)
    assert data["text"] == "hello from work"
    assert isinstance(data["ts"], int)


def test_chat_list_filters_chat_items_and_decrypts():
    from lgm_core.ops import op_chat_list
    ctx = _ctx("pw")

    def payload(text, ts):
        return encrypt_bundle(
            json.dumps({"text": text, "ts": ts}).encode(), "pw")

    blobs = {
        "c1": payload("first msg", 1700000000),
        "c2": payload("second msg", 1700000100),
        "o1": payload("not chat", 1700000200),
    }
    ctx.client.buffer_list = lambda: {"items": [
        {"id": "c2", "hint": "chat:mcp", "ts": 1700000100},
        {"id": "o1", "hint": "clipboard:work", "ts": 1700000200},
        {"id": "c1", "hint": "chat:mcp", "ts": 1700000000},
    ]}
    ctx.client.buffer_get = lambda item_id: blobs[item_id]

    res = op_chat_list(ctx, {"count": 20})
    assert res["success"] is True
    assert res["count"] == 2
    assert [m["text"] for m in res["messages"]] == ["second msg", "first msg"]
    assert res["messages"][0]["id"] == "c2"


def test_chat_list_skips_undecryptable_items():
    from lgm_core.ops import op_chat_list
    ctx = _ctx("pw")
    good = encrypt_bundle(
        json.dumps({"text": "ok", "ts": 1}).encode(), "pw")

    ctx.client.buffer_list = lambda: {"items": [
        {"id": "bad", "hint": "chat:mcp", "ts": 2},
        {"id": "good", "hint": "chat:mcp", "ts": 1},
    ]}
    ctx.client.buffer_get = lambda item_id: b"garbage" if item_id == "bad" else good

    res = op_chat_list(ctx, {"count": 20})
    assert res["count"] == 1
    assert res["messages"][0]["text"] == "ok"


def test_chat_list_count_clamped():
    from lgm_core.ops import op_chat_list
    ctx = _ctx("pw")
    ctx.client.buffer_list = lambda: {"items": []}
    ctx.client.buffer_get = lambda item_id: b""

    for raw in (0, -5, 1000, "abc", None):
        res = op_chat_list(ctx, {"count": raw})
        assert res["success"] is True
        assert res["count"] == 0


def test_file_send_requires_path_and_repo():
    from lgm_core.ops import op_file_send
    with pytest.raises(LgmError) as ei:
        op_file_send(_ctx(), {"repo": "r"})
    assert "--path" in ei.value.message
    with pytest.raises(LgmError) as ei:
        op_file_send(_ctx(), {"path": "x.txt"})
    assert "--repo" in ei.value.message


def test_file_send_missing_file():
    from lgm_core.ops import op_file_send
    with pytest.raises(LgmError) as ei:
        op_file_send(_ctx(), {"path": "Z:/no/such/file.bin", "repo": "r"})
    assert "file not found" in ei.value.message


def test_file_send_uploads_with_name_and_size(tmp_path):
    from lgm_core.ops import op_file_send
    ctx = _ctx()
    f = tmp_path / "report.txt"
    f.write_bytes(b"file-body-123")

    captured = {}

    def fake_send(repo, rel, plain_size, data):
        captured.update(repo=repo, rel=rel, plain_size=plain_size, data=data)
        return {"success": True, "repo": repo, "id": "f1",
                "path": rel, "size": plain_size}

    ctx.client.file_sync_send = fake_send

    res = op_file_send(ctx, {"path": str(f), "repo": "phonyx"})
    assert res["success"] is True
    assert res["id"] == "f1"
    assert res["file"] == "report.txt"
    assert res["size"] == 13
    assert captured["repo"] == "phonyx"
    assert captured["rel"] == "report.txt"
    assert captured["plain_size"] == 13
    assert captured["data"] == b"file-body-123"


def test_file_send_reports_server_failure(tmp_path):
    from lgm_core.ops import op_file_send
    ctx = _ctx()
    f = tmp_path / "x.bin"
    f.write_bytes(b"data")
    ctx.client.file_sync_send = lambda *a: {"success": False, "detail": "boom"}

    res = op_file_send(ctx, {"path": str(f), "repo": "r"})
    assert res["success"] is False


def test_file_list_requires_repo():
    from lgm_core.ops import op_file_list
    with pytest.raises(LgmError) as ei:
        op_file_list(_ctx(), {})
    assert "--repo" in ei.value.message


def test_file_list_formats_items():
    from lgm_core.ops import op_file_list
    ctx = _ctx()
    ctx.client.file_sync_list = lambda repo: {"success": True, "repo": repo, "items": [
        {"id": "a1", "path": "logs/run.txt", "plain_size": 42},
        {"id": "a2", "path": "logs/other.bin", "size": 7},
    ]}

    res = op_file_list(ctx, {"repo": "r"})
    assert res["success"] is True
    assert res["count"] == 2
    assert res["files"][0] == {"id": "a1", "path": "logs/run.txt", "size": 42}
    assert res["files"][1]["size"] == 7


def test_file_get_saves_to_disk(tmp_path):
    from lgm_core.ops import op_file_get
    ctx = _ctx()
    ctx.client.file_sync_fetch = lambda repo, item_id: b"bytes-from-server"

    dest = tmp_path / "sub" / "out.bin"
    res = op_file_get(ctx, {"repo": "r", "id": "a1", "save": str(dest)})
    assert res["success"] is True
    assert res["saved"] == str(dest.resolve())
    assert res["size"] == 17
    assert dest.read_bytes() == b"bytes-from-server"


def test_file_get_without_save_returns_metadata():
    from lgm_core.ops import op_file_get
    ctx = _ctx()
    ctx.client.file_sync_fetch = lambda repo, item_id: b"12345"
    res = op_file_get(ctx, {"repo": "r", "id": "a1"})
    assert res["success"] is True
    assert res["size"] == 5
    assert "--save" in res["message"]


def test_file_get_requires_repo_and_id():
    from lgm_core.ops import op_file_get
    with pytest.raises(LgmError):
        op_file_get(_ctx(), {"id": "a1"})
    with pytest.raises(LgmError):
        op_file_get(_ctx(), {"repo": "r"})


_SETTINGS = {
    "general": {"storage_path": "storage", "retention_days": 7},
    "git": {"git_port": 8444},
    "ui": {"theme": "dark"},
}


def test_config_get_all_sections():
    from lgm_core.ops import op_config_get
    ctx = _ctx()
    ctx.client._get_json = lambda path: dict(_SETTINGS)

    res = op_config_get(ctx, {})
    assert res["success"] is True
    assert res["settings"] == _SETTINGS


def test_config_get_single_section():
    from lgm_core.ops import op_config_get
    ctx = _ctx()
    ctx.client._get_json = lambda path: dict(_SETTINGS)

    res = op_config_get(ctx, {"section": "git"})
    assert res["success"] is True
    assert res["section"] == "git"
    assert res["value"] == {"git_port": 8444}


def test_config_get_unknown_section():
    from lgm_core.ops import op_config_get
    ctx = _ctx()
    ctx.client._get_json = lambda path: dict(_SETTINGS)

    res = op_config_get(ctx, {"section": "nope"})
    assert res["success"] is False
    assert "general" in res["message"]


def test_config_set_requires_section_and_key():
    from lgm_core.ops import op_config_set
    with pytest.raises(LgmError) as ei:
        op_config_set(_ctx(), {"key": "k", "value": "v"})
    assert "--section" in ei.value.message


def test_config_set_unknown_section():
    from lgm_core.ops import op_config_set
    ctx = _ctx()
    ctx.client._get_json = lambda path: dict(_SETTINGS)
    with pytest.raises(LgmError) as ei:
        op_config_set(ctx, {"section": "nope", "key": "k", "value": "v"})
    assert "not found" in ei.value.message


def test_config_set_posts_all_sections_with_update():
    from lgm_core.ops import op_config_set
    ctx = _ctx()
    captured = {}

    ctx.client._get_json = lambda path: dict(_SETTINGS)

    def fake_post(path, body, content_type="application/json", timeout=None):
        captured["path"] = path
        captured["body"] = json.loads(body.decode())
        return {"success": True, "settings": dict(_SETTINGS)}

    ctx.client._post_json = fake_post

    res = op_config_set(ctx, {"section": "general", "key": "retention_days",
                              "value": "30"})
    assert res["success"] is True
    assert res["value"] == 30
    assert captured["path"] == "/api/settings"
    assert captured["body"]["general"]["retention_days"] == 30
    assert captured["body"]["general"]["storage_path"] == "storage"
    assert captured["body"]["git"] == {"git_port": 8444}
    assert captured["body"]["ui"] == {"theme": "dark"}


@pytest.mark.parametrize("raw,expected", [
    ("true", True),
    ("false", False),
    ("42", 42),
    ("3.14", 3.14),
    ("plain text", "plain text"),
    ('{"a": 1}', {"a": 1}),
    ("[1, 2]", [1, 2]),
    ("", ""),
])
def test_parse_value(raw, expected):
    assert _parse_value(raw) == expected
