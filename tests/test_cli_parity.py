"""branches --project ahead/behind annotation and respond/apply --id selection."""
import io
import json
import subprocess
import sys
import zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.client import LgmError
from lgm_core.crypto import encrypt_bundle
from lgm_core.ops import Ctx, get_op, op_apply, op_branches, op_respond
from lgm_core.render import render

PWD = "test-sync-password"


def _git(repo: Path, *args: str) -> str:
    proc = subprocess.run(["git", "-C", str(repo), *args],
                          capture_output=True, text=True, timeout=60)
    assert proc.returncode == 0, proc.stderr
    return proc.stdout.strip()


def _commit(repo: Path, msg: str) -> str:
    _git(repo, "-c", "user.email=lgm@test", "-c", "user.name=lgm",
         "commit", "--allow-empty", "-m", msg)
    return _git(repo, "rev-parse", "HEAD")


@pytest.fixture()
def divergence_repo(tmp_path: Path) -> dict:
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init")
    default = _git(repo, "symbolic-ref", "--short", "HEAD")
    c1 = _commit(repo, "c1")
    _git(repo, "branch", "b-synced")
    _git(repo, "branch", "b-behind")
    _git(repo, "checkout", "-q", "-b", "b-ahead")
    c2 = _commit(repo, "c2")
    _git(repo, "branch", "b-div")
    _git(repo, "checkout", "-q", default)
    _git(repo, "checkout", "-q", "-b", "tmp")
    c3 = _commit(repo, "c3")
    _git(repo, "checkout", "-q", default)
    return {"path": repo, "default": default, "c1": c1, "c2": c2, "c3": c3}


class _RefsClient:
    def __init__(self, refs: dict, head: str = ""):
        self.sync_password = PWD
        self._refs = refs
        self._head = head

    def sync_refs(self, repo: str) -> dict:
        return {"repo": repo, "head": self._head, "refs": self._refs}


def _refs(r: dict) -> dict:
    return {
        r["default"]: {"sha": r["c1"], "updated": "u", "is_head": True},
        "b-synced": {"sha": r["c1"], "updated": "u", "is_head": False},
        "b-ahead": {"sha": r["c1"], "updated": "u", "is_head": False},
        "b-behind": {"sha": r["c2"], "updated": "u", "is_head": False},
        "b-div": {"sha": r["c3"], "updated": "u", "is_head": False},
        "b-ghost": {"sha": r["c1"], "updated": "u", "is_head": False},
    }


def test_branches_annotates_divergence(divergence_repo):
    r = divergence_repo
    ctx = Ctx(config=SimpleNamespace(sync_password=PWD),
              client=_RefsClient(_refs(r), head=r["c1"]))
    res = op_branches(ctx, {"repo": "r", "project": str(r["path"])})
    refs = res["refs"]
    assert refs["b-synced"]["divergence"] == "synced"
    assert refs["b-synced"]["ahead"] == 0 and refs["b-synced"]["behind"] == 0
    assert refs["b-ahead"]["divergence"] == "ahead"
    assert refs["b-ahead"]["ahead"] == 1 and refs["b-ahead"]["behind"] == 0
    assert refs["b-behind"]["divergence"] == "behind"
    assert refs["b-behind"]["ahead"] == 0 and refs["b-behind"]["behind"] == 1
    assert refs["b-div"]["divergence"] == "diverged"
    assert refs["b-div"]["ahead"] == 1 and refs["b-div"]["behind"] == 1
    assert refs["b-ghost"]["divergence"] == "local_only"
    assert "ahead" not in refs["b-ghost"] and "behind" not in refs["b-ghost"]


def test_branches_without_project_leaves_refs_untouched(divergence_repo):
    r = divergence_repo
    refs = {"b-synced": {"sha": r["c1"], "updated": "u", "is_head": False}}
    ctx = Ctx(config=SimpleNamespace(sync_password=PWD), client=_RefsClient(refs))
    res = op_branches(ctx, {"repo": "r"})
    assert res["refs"]["b-synced"] == {"sha": r["c1"], "updated": "u", "is_head": False}


def test_branches_rejects_missing_project(divergence_repo):
    ctx = Ctx(config=SimpleNamespace(sync_password=PWD), client=_RefsClient({}))
    with pytest.raises(LgmError) as ei:
        op_branches(ctx, {"repo": "r",
                          "project": str(divergence_repo["path"] / "nope")})
    assert "project not found" in ei.value.message


def test_render_branches_shows_divergence_columns():
    refs = {
        "b-synced": {"sha": "a" * 40, "updated": "u", "is_head": False,
                     "divergence": "synced", "ahead": 0, "behind": 0},
        "b-ahead": {"sha": "b" * 40, "updated": "u", "is_head": False,
                    "divergence": "ahead", "ahead": 2, "behind": 0},
        "b-behind": {"sha": "c" * 40, "updated": "u", "is_head": False,
                     "divergence": "behind", "ahead": 0, "behind": 3},
        "b-div": {"sha": "d" * 40, "updated": "u", "is_head": False,
                  "divergence": "diverged", "ahead": 1, "behind": 2},
        "b-ghost": {"sha": "e" * 40, "updated": "u", "is_head": False,
                    "divergence": "local_only"},
    }
    out = render("branches", {"repo": "r", "refs": refs})
    assert "u  synced" in out
    assert "u  ahead 2" in out
    assert "u  behind 3" in out
    assert "u  ahead 1 / behind 2" in out
    assert "u  local_only" in out


def test_render_branches_without_divergence_unchanged():
    refs = {"b-synced": {"sha": "a" * 40, "updated": "u", "is_head": False}}
    out = render("branches", {"repo": "r", "refs": refs})
    assert out.rstrip().endswith("u")


# ── respond / apply queue-item selection ─────────────────────────────────────

_ITEMS = [
    {"id": "aaaa1111aaaa1111aaaa1111aaaa1111", "size": 10, "mtime": 1},
    {"id": "bbbb2222bbbb2222bbbb2222bbbb2222", "size": 20, "mtime": 2},
    {"id": "cccc3333cccc3333cccc3333cccc3333", "size": 30, "mtime": 3},
]


def _zip_bytes() -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("gradle/ru/example/a/1.0/a-1.0.jar", b"x")
    return buf.getvalue()


class _QueueClient:
    def __init__(self, items, manifest=None, zip_bytes=None):
        self.sync_password = PWD
        self._items = items
        self._manifest = manifest
        self._zip = zip_bytes
        self.fetched = []

    def deps_pending(self, repo):
        return {"items": self._items}

    def deps_manifest(self, repo, item_id):
        self.fetched.append(item_id)
        return encrypt_bundle(json.dumps(self._manifest).encode("utf-8"), PWD)

    def deps_responses(self, repo):
        return {"items": self._items}

    def deps_fetch(self, repo, item_id):
        self.fetched.append(item_id)
        return encrypt_bundle(self._zip, PWD)


def test_respond_defaults_to_first_item():
    ctx = Ctx(config=SimpleNamespace(sync_password=PWD),
              client=_QueueClient(_ITEMS, manifest={"version": 1}))
    res = op_respond(ctx, {"repo": "r"})
    assert res["request_id"] == _ITEMS[0]["id"]
    assert res["remaining"] == 2


def test_respond_selects_non_first_item_by_id_prefix():
    ctx = Ctx(config=SimpleNamespace(sync_password=PWD),
              client=_QueueClient(_ITEMS, manifest={"version": 1}))
    res = op_respond(ctx, {"repo": "r", "id": "bbbb2222"})
    assert res["request_id"] == _ITEMS[1]["id"]
    assert ctx.client.fetched == [_ITEMS[1]["id"]]
    assert res["remaining"] == 2


def test_respond_unknown_id_raises():
    ctx = Ctx(config=SimpleNamespace(sync_password=PWD),
              client=_QueueClient(_ITEMS, manifest={"version": 1}))
    with pytest.raises(LgmError) as ei:
        op_respond(ctx, {"repo": "r", "id": "dddd"})
    assert "no pending request matches" in ei.value.message


def test_respond_ambiguous_id_prefix_raises():
    dup = [{"id": "aaaa1111aaaa", "size": 1, "mtime": 1},
           {"id": "aaaa2222aaaa", "size": 2, "mtime": 2}]
    ctx = Ctx(config=SimpleNamespace(sync_password=PWD),
              client=_QueueClient(dup, manifest={"version": 1}))
    with pytest.raises(LgmError) as ei:
        op_respond(ctx, {"repo": "r", "id": "aaaa"})
    assert "longer prefix" in ei.value.message


def test_apply_defaults_to_first_item(monkeypatch, tmp_path):
    monkeypatch.setattr("lgm_core.ops_deps.maven_local_root", lambda: tmp_path)
    monkeypatch.setattr("lgm_core.ops_deps._ensure_mavenlocal_init_script",
                        lambda: None)
    ctx = Ctx(config=SimpleNamespace(sync_password=PWD),
              client=_QueueClient(_ITEMS, zip_bytes=_zip_bytes()))
    res = op_apply(ctx, {"repo": "r", "dry_run": True})
    assert res["response_id"] == _ITEMS[0]["id"]
    assert res["remaining"] == 2
    assert res["entry_count"] == 1


def test_apply_selects_non_first_item_by_id_prefix(monkeypatch, tmp_path):
    monkeypatch.setattr("lgm_core.ops_deps.maven_local_root", lambda: tmp_path)
    monkeypatch.setattr("lgm_core.ops_deps._ensure_mavenlocal_init_script",
                        lambda: None)
    ctx = Ctx(config=SimpleNamespace(sync_password=PWD),
              client=_QueueClient(_ITEMS, zip_bytes=_zip_bytes()))
    res = op_apply(ctx, {"repo": "r", "id": "cccc3333", "dry_run": True})
    assert res["response_id"] == _ITEMS[2]["id"]
    assert ctx.client.fetched == [_ITEMS[2]["id"]]
    assert res["remaining"] == 2
    assert res["entry_count"] == 1


def test_apply_unknown_id_raises(monkeypatch, tmp_path):
    monkeypatch.setattr("lgm_core.ops_deps.maven_local_root", lambda: tmp_path)
    monkeypatch.setattr("lgm_core.ops_deps._ensure_mavenlocal_init_script",
                        lambda: None)
    ctx = Ctx(config=SimpleNamespace(sync_password=PWD),
              client=_QueueClient(_ITEMS, zip_bytes=_zip_bytes()))
    with pytest.raises(LgmError) as ei:
        op_apply(ctx, {"repo": "r", "id": "dddd", "dry_run": True})
    assert "no response matches" in ei.value.message


def test_render_respond_shows_remaining_queue():
    out = render("respond", {"repo": "r", "request_id": "bbbb2222",
                             "manifest": {"version": 3, "missing": []},
                             "found": 0, "not_found": 0, "remaining": 2})
    assert "2 more request(s) in queue" in out


def test_render_apply_shows_remaining_queue():
    out = render("apply", {"repo": "r", "response_id": "bbbb2222",
                           "target": "t", "installed": 0, "skipped": 0,
                           "invalid": 0, "remaining": 1})
    assert "1 more response(s) waiting" in out


def test_new_params_are_optional():
    for op_name, param_name in (("branches", "project"),
                                ("respond", "id"), ("apply", "id")):
        params = {p.name: p for p in get_op(op_name).params}
        assert params[param_name].required is False
        assert params[param_name].default == ""


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
