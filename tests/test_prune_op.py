"""Unit tests for the branch-management / GitLab MR ops (prune, branch_delete,
mr_list, mr_send) and their client methods. No network, no live mirror."""
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.crypto import decrypt_envelope, encrypt_envelope
from lgm_core.ops import REGISTRY, Ctx, get_op
from lgm_core.render import render


def _ctx(sync_password: str = "pw") -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="",
                    sync_password=sync_password)
    client = MirrorClient(base_url=config.base_url, api_key="",
                          sync_password=sync_password)
    return Ctx(config=config, client=client)


# ── registry presence & param defaults ───────────────────────────────────────

def test_new_ops_present():
    expected = {"branch_delete", "prune", "mr_list", "mr_send"}
    actual = {op.name for op in REGISTRY}
    missing = expected - actual
    assert not missing, f"Missing new ops: {missing}"


def test_prune_param_defaults():
    op = get_op("prune")
    params = {p.name: p for p in op.params}
    assert params["repo"].required is True
    assert params["bases"].default == "master,develop,plan_fix"
    assert params["older_days"].type == "int"
    assert params["older_days"].default == 0
    assert params["keep"].default == ""
    assert params["apply"].type == "bool"
    assert params["apply"].default is False  # dry-run by default


def test_branch_delete_params():
    op = get_op("branch_delete")
    params = {p.name: p for p in op.params}
    assert params["repo"].required is True
    assert params["branches"].required is True
    assert params["branches"].default == ""


def test_mr_send_params():
    op = get_op("mr_send")
    params = {p.name: p for p in op.params}
    assert params["iid"].type == "int" and params["iid"].default == 0
    assert params["branch"].default == ""
    assert params["project"].required is True
    assert params["repo"].required is True


def test_mr_list_no_params():
    op = get_op("mr_list")
    assert op.params == []


# ── op validation (no network needed) ────────────────────────────────────────

def test_prune_requires_repo():
    from lgm_core.ops import op_prune
    with pytest.raises(LgmError) as ei:
        op_prune(_ctx(), {})
    assert "--repo" in ei.value.message


def test_branch_delete_requires_branches():
    from lgm_core.ops import op_branch_delete
    with pytest.raises(LgmError) as ei:
        op_branch_delete(_ctx(), {"repo": "r"})
    assert "--branches" in ei.value.message


def test_mr_send_requires_project():
    from lgm_core.ops import op_mr_send
    with pytest.raises(LgmError) as ei:
        op_mr_send(_ctx(), {"repo": "r", "project": ""})
    assert "--project" in ei.value.message


# ── client.prune_branches envelope round-trip (stub server) ──────────────────

class _StubServer:
    """Captures the last request and replies with a canned envelope."""

    def __init__(self, reply: dict):
        self.reply = reply
        self.last_path = None
        self.last_payload = None

    def __call__(self, path, body, content_type="application/json", timeout=None):
        self.last_path = path
        self.last_payload = json.loads(body.decode())
        return {"e": encrypt_envelope(self.reply, "pw")}


def test_client_prune_branches_builds_correct_envelope(monkeypatch):
    reply = {"success": True, "repo": "phonyx", "apply": False,
             "candidates": ["merged"], "pruned": [], "protected": ["master"],
             "message": "1 candidate(s) for pruning (dry-run)"}
    stub = _StubServer(reply)
    c = MirrorClient(base_url="https://localhost:1", sync_password="pw")
    monkeypatch.setattr(c, "_post_json", stub)

    res = c.prune_branches("phonyx", bases=["master", "develop"],
                           older_days=30, keep=["keepme"], apply=False)

    assert stub.last_path == "/api/documents/prune-branches"
    sent = decrypt_envelope(stub.last_payload["e"], "pw")
    assert sent == {"repo": "phonyx", "bases": ["master", "develop"],
                    "older_days": 30, "keep": ["keepme"], "apply": False}
    assert res == reply


def test_client_prune_branches_minimal_payload(monkeypatch):
    stub = _StubServer({"success": True})
    c = MirrorClient(base_url="https://localhost:1", sync_password="pw")
    monkeypatch.setattr(c, "_post_json", stub)

    c.prune_branches("r")
    sent = decrypt_envelope(stub.last_payload["e"], "pw")
    # Empty bases/keep are omitted; server applies its own defaults.
    assert sent == {"repo": "r", "older_days": 0, "apply": False}


def test_client_delete_ref_alias(monkeypatch):
    stub = _StubServer({"success": True, "repo": "r", "branch": "b"})
    c = MirrorClient(base_url="https://localhost:1", sync_password="pw")
    monkeypatch.setattr(c, "_post_json", stub)

    res = c.delete_ref("r", "b")
    assert stub.last_path == "/api/documents/delete-ref"
    sent = decrypt_envelope(stub.last_payload["e"], "pw")
    assert sent == {"repo": "r", "branch": "b"}
    assert res["success"] is True


# ── GitLab client methods ────────────────────────────────────────────────────

def test_gitlab_methods_error_when_unconfigured(monkeypatch):
    """No GITLAB_* config → clean LgmError, no network attempt."""
    from lgm_core import config as config_mod

    saved = config_mod._ENV
    monkeypatch.setattr(config_mod, "_ENV", {})
    monkeypatch.delenv("GITLAB_URL", raising=False)
    monkeypatch.delenv("GITLAB_TOKEN", raising=False)
    monkeypatch.delenv("GITLAB_PROJECT", raising=False)

    c = MirrorClient(base_url="https://localhost:1")
    for fn in (c.gitlab_list_mrs, lambda: c.gitlab_get_mr(42)):
        with pytest.raises(LgmError) as ei:
            fn()
        assert ei.value.code == "config"
        assert "GitLab not configured" in ei.value.message
        assert "GITLAB_URL" in ei.value.message

    monkeypatch.setattr(config_mod, "_ENV", saved)


def test_gitlab_list_mrs_builds_url_and_headers(monkeypatch):
    from lgm_core import config as config_mod

    monkeypatch.setattr(config_mod, "_ENV", {
        "GITLAB_URL": "https://gitlab.corp.example.com",
        "GITLAB_TOKEN": "tok123",
        "GITLAB_PROJECT": "group/sub/project",
    })
    monkeypatch.delenv("GITLAB_URL", raising=False)
    monkeypatch.delenv("GITLAB_TOKEN", raising=False)
    monkeypatch.delenv("GITLAB_PROJECT", raising=False)

    captured = {}

    class _Resp:
        def __init__(self, data):
            self._data = data

        def read(self):
            return json.dumps(self._data).encode()

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    def fake_urlopen(req, timeout=None):
        captured["url"] = req.full_url
        captured["headers"] = dict(req.header_items())
        return _Resp([{"iid": 7, "title": "Fix", "source_branch": "fix",
                       "updated_at": "2026-01-01T00:00:00Z"}])

    import urllib.request
    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)

    c = MirrorClient(base_url="https://localhost:1")
    mrs = c.gitlab_list_mrs()

    assert captured["url"] == (
        "https://gitlab.corp.example.com/api/v4/projects/"
        "group%2Fsub%2Fproject/merge_requests?state=opened"
    )
    assert captured["headers"].get("Private-token") == "tok123"
    assert mrs[0]["iid"] == 7


def test_gitlab_get_mr_builds_url(monkeypatch):
    from lgm_core import config as config_mod

    monkeypatch.setattr(config_mod, "_ENV", {
        "GITLAB_URL": "https://gitlab.example.com/",
        "GITLAB_TOKEN": "t",
        "GITLAB_PROJECT": "12345",
    })
    monkeypatch.delenv("GITLAB_URL", raising=False)
    monkeypatch.delenv("GITLAB_TOKEN", raising=False)
    monkeypatch.delenv("GITLAB_PROJECT", raising=False)

    captured = {}

    class _Resp:
        def read(self):
            return json.dumps({"iid": 9, "source_branch": "mr-9"}).encode()

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    import urllib.request
    monkeypatch.setattr(
        urllib.request, "urlopen",
        lambda req, timeout=None: (captured.setdefault("url", req.full_url), _Resp())[1],
    )

    c = MirrorClient(base_url="https://localhost:1")
    mr = c.gitlab_get_mr(9)
    assert captured["url"] == "https://gitlab.example.com/api/v4/projects/12345/merge_requests/9"
    assert mr["source_branch"] == "mr-9"


# ── renderers ────────────────────────────────────────────────────────────────

def test_render_prune_shows_candidates_vs_pruned():
    out = render("prune", {"repo": "r", "apply": True,
                           "candidates": ["a", "b"], "pruned": ["a", "b"],
                           "protected": ["master"], "message": "Pruned 2 branch(es)"})
    assert "[candidate] a" in out and "[pruned]    b" in out and "[protected] master" in out


def test_render_branch_delete_and_mr_list():
    out = render("branch_delete", {"repo": "r", "deleted": ["x"], "failed": [],
                                   "message": "deleted 1/1 branch(es)"})
    assert "[deleted] x" in out
    out = render("mr_list", {"count": 1, "items": [
        {"iid": 3, "title": "T", "source_branch": "b", "updated_at": "u"}]})
    assert "!3" in out and "T" in out


def test_render_mr_send():
    out = render("mr_send", {"repo": "r", "iid": 5, "source_branch": "b",
                             "project": "p", "send": {"bundle_size": 10,
                                                      "response": {"e": "x"}}})
    assert "!5" in out and "b" in out and "OK (envelope response)" in out
