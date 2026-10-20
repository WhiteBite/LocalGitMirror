"""CLI exit-code + JSON error envelope policy: refusals must not exit 0.

Drives ``lgm.main()`` in-process with a mocked client (no network):
an op-reported failure, a crash, and LgmError kinds each get a
non-zero exit and one parseable JSON error envelope on stdout.
"""
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import lgm
from lgm_core.client import LgmError
from lgm_core.config import Config
from lgm_core.ops import Ctx


def _run(monkeypatch, capsys, client, argv, sync_password="pw"):
    config = Config(base_url="http://localhost:1", api_key="",
                    sync_password=sync_password)
    monkeypatch.setattr(lgm, "_resolve_config", lambda args: config)
    monkeypatch.setattr(lgm, "_build_ctx", lambda cfg: Ctx(config=cfg, client=client))
    monkeypatch.setattr(sys, "argv", ["lgm", *argv])
    code = lgm.main()
    captured = capsys.readouterr()
    return code, captured.out, captured.err


class _ChatClient:
    def __init__(self, res=None, exc=None):
        self.sync_password = "pw"
        self._res = res
        self._exc = exc

    def buffer_send(self, ciphertext_b64, hint=""):
        if self._exc is not None:
            raise self._exc
        return self._res


_NOTES_MD = "\n".join([
    "# MR !7 — T",
    "",
    "- **Ветка:** `b`",
    "",
    "## ⚠ НЕ РЕШЕНО",
    "",
    "**ID треда:** `t1`",
    "",
    "**Место:** `Foo.java:75`",
])


class _NotesClient:
    def __init__(self):
        self.sync_password = ""

    def file_sync_list(self, repo):
        return {"items": [{"id": "n1", "path": "mr-notes/mr-!7.md", "mtime": 9}]}

    def file_sync_fetch(self, repo, item_id):
        return _NOTES_MD.encode("utf-8")


def test_rejected_reply_exits_nonzero_with_json_envelope(monkeypatch, capsys):
    code, out, err = _run(monkeypatch, capsys, _NotesClient(), [
        "mr_replies_send", "--repo", "r1", "--iid", "7",
        "--text", "## thread nope\nfix", "--json",
    ])
    assert code != 0, f"exit={code} stdout={out}"
    env = json.loads(out)
    assert env["success"] is False
    assert env["error"]["kind"] == "op_failure"
    assert "'## thread nope'" in env["error"]["message"]


def test_op_failure_message_key_exits_nonzero(monkeypatch, capsys):
    client = SimpleNamespace(sync_password="", _get_json=lambda path: {"general": {}})
    code, out, err = _run(monkeypatch, capsys, client,
                          ["config_get", "--section", "nope", "--json"])
    assert code != 0, f"exit={code} stdout={out}"
    env = json.loads(out)
    assert env["success"] is False
    assert "nope" in env["error"]["message"]


def test_crash_exception_prints_json_envelope_not_free_text(monkeypatch, capsys):
    code, out, err = _run(monkeypatch, capsys, _ChatClient(exc=RuntimeError("boom")),
                          ["chat_send", "--text", "hi", "--json"])
    assert code == 1
    env = json.loads(out)
    assert env["success"] is False
    assert env["error"]["kind"] == "crash"
    assert "RuntimeError" in env["error"]["message"]
    assert "boom" in env["error"]["message"]


def test_lgm_error_envelope_carries_kind(monkeypatch, capsys):
    code, out, err = _run(monkeypatch, capsys, _ChatClient(exc=LgmError(401, "bad key")),
                          ["chat_send", "--text", "hi", "--json"])
    assert code == 5
    env = json.loads(out)
    assert env["error"]["kind"] == 401
    assert "bad key" in env["error"]["message"]


def test_config_error_exit_code(monkeypatch, capsys):
    code, out, err = _run(monkeypatch, capsys, _ChatClient(),
                          ["chat_send", "--text", "hi", "--json"], sync_password="")
    assert code == 3
    env = json.loads(out)
    assert env["error"]["kind"] == "config"


def test_network_error_exit_code(monkeypatch, capsys):
    code, out, err = _run(monkeypatch, capsys,
                          _ChatClient(exc=LgmError("network", "unreachable")),
                          ["chat_send", "--text", "hi", "--json"])
    assert code == 4
    env = json.loads(out)
    assert env["error"]["kind"] == "network"


def test_human_mode_error_line_on_stderr(monkeypatch, capsys):
    code, out, err = _run(monkeypatch, capsys, _ChatClient(exc=LgmError(401, "bad key")),
                          ["chat_send", "--text", "hi"])
    assert code == 5
    assert out == ""
    assert "ERROR" in err and "bad key" in err


def test_success_still_exits_zero_and_json_parses(monkeypatch, capsys):
    code, out, err = _run(monkeypatch, capsys, _ChatClient(res={"id": "item1"}),
                          ["chat_send", "--text", "hi", "--json"])
    assert code == 0
    data = json.loads(out)
    assert data["success"] is True
    assert data["id"] == "item1"


def test_lgm_error_envelope_carries_machine_code(monkeypatch, capsys):
    code, out, err = _run(monkeypatch, capsys,
                          _ChatClient(exc=LgmError("server", "no fast-forward",
                                                   machine_code="diverged")),
                          ["chat_send", "--text", "hi", "--json"])
    assert code == 5
    env = json.loads(out)
    assert env["error"]["kind"] == "server"
    assert env["error"]["code"] == "diverged"
    assert "no fast-forward" in env["error"]["message"]


class _ClosedNotesClient:
    sync_password = ""

    def file_sync_list(self, repo):
        return {"items": [
            {"id": "n1", "path": "mr-notes/mr-!7.md", "mtime": 9},
            {"id": "c1", "path": "mr-notes/mr-!7.closed.md", "mtime": 99},
        ]}

    def file_sync_fetch(self, repo, item_id):
        return _NOTES_MD.encode("utf-8")


def test_op_failure_envelope_carries_machine_code(monkeypatch, capsys):
    code, out, err = _run(monkeypatch, capsys, _ClosedNotesClient(), [
        "mr_replies_send", "--repo", "r1", "--iid", "7",
        "--text", "## thread t1\nfix", "--json",
    ])
    assert code == 5, f"exit={code} stdout={out}"
    env = json.loads(out)
    assert env["success"] is False
    assert env["error"]["kind"] == "op_failure"
    assert env["error"]["code"] == "closed-mr"
    assert "closed" in env["error"]["message"]


def test_mr_sync_all_note_failure_exits_nonzero(monkeypatch, capsys, tmp_path):
    import lgm_core.ops_projects as opmod
    monkeypatch.setattr(opmod, "_REGISTRY_PATH", tmp_path / "reg.json")
    monkeypatch.setattr(opmod, "_STATE_PATH", tmp_path / "state.json")
    opmod._save_registry({"projects": [
        {"repo": "r1", "path": str(tmp_path), "gitlab": "g/p"}]})
    monkeypatch.setattr(opmod, "_fetch_mr_branches", lambda proj, branches: [])
    monkeypatch.setattr(opmod, "_mirror_refs_safe", lambda c, repo: {})
    monkeypatch.setattr(opmod, "send_branches",
                        lambda *a, **k: {"bundle_size": 1})

    class _SyncClient:
        sync_password = "pw"

        def gitlab_list_mrs(self, project=""):
            return [{"iid": 24, "title": "T", "source_branch": "",
                     "updated_at": "u"}]

        def gitlab_list_discussions(self, iid, project=""):
            raise LgmError(500, "boom")

        def sync_negotiate(self, repo, commits, probe=False):
            return {"known": []}

    code, out, err = _run(monkeypatch, capsys, _SyncClient(),
                          ["mr_sync_all", "--json"])
    assert code == 5, f"exit={code} stdout={out}"
    env = json.loads(out)
    assert env["success"] is False
    assert env["error"]["kind"] == "op_failure"
    assert "boom" in env["error"]["message"]


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
