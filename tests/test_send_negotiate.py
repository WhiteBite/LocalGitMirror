"""op_send negotiates local commits against the mirror before bundling and
excludes the known ones; op_pull auto-collects local haves when none given.
Git and HTTP are faked at the seams."""
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.ops import Ctx, op_pull, op_send

T1 = "1" * 40
T2 = "2" * 40
C3 = "3" * 40
C4 = "4" * 40


def _ctx() -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="",
                    sync_password="pw")
    client = MirrorClient(base_url=config.base_url, api_key="",
                          sync_password="pw")
    return Ctx(config=config, client=client)


class _FakeGit:
    """Dispatches the git subprocess calls op_send/op_pull make."""

    def __init__(self, tips: dict, history: dict, all_history: list):
        self.tips = tips
        self.history = history
        self.all_history = all_history
        self.bundle_cmds: list[list[str]] = []
        self.collect_cmds: list[list[str]] = []

    def __call__(self, cmd, cwd=None, capture_output=None, text=None,
                 timeout=None):
        args = cmd[1:]

        def ok(stdout=""):
            return subprocess.CompletedProcess(cmd, 0, stdout=stdout,
                                               stderr="")

        def fail(code=128):
            return subprocess.CompletedProcess(cmd, code, stdout="",
                                               stderr="fatal")

        if args[0] == "rev-parse":
            branch = args[1].removeprefix("refs/heads/")
            return ok(self.tips[branch] + "\n") if branch in self.tips else fail()
        if args[0] == "rev-list":
            ref = args[-1]
            if ref == "--all":
                return ok("".join(s + "\n" for s in self.all_history))
            key = ref.removeprefix("refs/heads/")
            return ok("".join(s + "\n" for s in self.history[key])) \
                if key in self.history else fail()
        if args[0] == "for-each-ref":
            self.collect_cmds.append(args)
            return ok("".join(s + "\n" for s in self.tips.values()))
        if args[0] == "bundle" and args[1] == "create":
            self.bundle_cmds.append(args)
            Path(args[2]).write_bytes(b"fake-bundle")
            return ok()
        raise AssertionError(f"unexpected git call: {cmd}")


class _FakeClient:
    def __init__(self, known: list | None = None,
                 negotiate_error: LgmError | None = None):
        self.known = known or []
        self.negotiate_error = negotiate_error
        self.negotiate_calls: list[tuple[str, list[str]]] = []
        self.sent_bundles: list[bytes] = []

    def sync_negotiate(self, repo, commits):
        self.negotiate_calls.append((repo, list(commits)))
        if self.negotiate_error is not None:
            raise self.negotiate_error
        return {"success": True, "repo": repo, "created": False,
                "refs": {}, "head": "", "known": self.known}

    def sync_send(self, repo, bundle):
        self.sent_bundles.append(bundle)
        return {"success": True}


def _patch_send(monkeypatch, ctx, fake_client):
    monkeypatch.setattr(ctx.client, "sync_negotiate", fake_client.sync_negotiate)
    monkeypatch.setattr(ctx.client, "sync_send", fake_client.sync_send)


def test_send_excludes_mirror_known_shas(monkeypatch, tmp_path):
    git = _FakeGit(tips={"b1": T1}, history={"b1": [T1, C3]}, all_history=[])
    client = _FakeClient(known=[T1])
    ctx = _ctx()
    _patch_send(monkeypatch, ctx, client)
    monkeypatch.setattr("lgm_core.ops_git.subprocess.run", git)

    res = op_send(ctx, {"repo": "r", "project": str(tmp_path), "branch": "b1"})

    assert client.negotiate_calls == [("r", [T1, C3])]
    assert git.bundle_cmds[0][3:] == [f"refs/heads/{'b1'}", f"^{T1}"]
    assert res["excluded_bases"] == 1
    assert len(client.sent_bundles) == 1


def test_send_dry_run_negotiates_and_reports_bases(monkeypatch, tmp_path):
    git = _FakeGit(tips={"b1": T1}, history={"b1": [T1, C3]}, all_history=[])
    client = _FakeClient(known=[T1, C3])
    ctx = _ctx()
    _patch_send(monkeypatch, ctx, client)
    monkeypatch.setattr("lgm_core.ops_git.subprocess.run", git)

    res = op_send(ctx, {"repo": "r", "project": str(tmp_path),
                        "branch": "b1", "dry_run": True})

    assert client.negotiate_calls == [("r", [T1, C3])]
    assert res["excluded_bases"] == 2
    assert client.sent_bundles == []


def test_send_without_branch_negotiates_all_history(monkeypatch, tmp_path):
    git = _FakeGit(tips={"b1": T1}, history={"b1": [T1, C3]},
                   all_history=[T2, T1, C3, C4])
    client = _FakeClient(known=[T2])
    ctx = _ctx()
    _patch_send(monkeypatch, ctx, client)
    monkeypatch.setattr("lgm_core.ops_git.subprocess.run", git)

    res = op_send(ctx, {"repo": "r", "project": str(tmp_path)})

    assert client.negotiate_calls[0][1] == [T2, T1, C3, C4]
    assert git.bundle_cmds[0][3:] == ["--all", f"^{T2}"]
    assert res["excluded_bases"] == 1


def test_negotiation_failure_sends_full_bundle(monkeypatch, tmp_path):
    git = _FakeGit(tips={"b1": T1}, history={"b1": [T1, C3]}, all_history=[])
    client = _FakeClient(negotiate_error=LgmError("network", "mirror is dead"))
    ctx = _ctx()
    _patch_send(monkeypatch, ctx, client)
    monkeypatch.setattr("lgm_core.ops_git.subprocess.run", git)

    res = op_send(ctx, {"repo": "r", "project": str(tmp_path), "branch": "b1"})

    assert client.negotiate_calls == [("r", [T1, C3])]
    assert git.bundle_cmds[0][3:] == ["refs/heads/b1"]
    assert res["excluded_bases"] == 0
    assert len(client.sent_bundles) == 1


def test_pull_auto_collects_haves(monkeypatch, tmp_path):
    git = _FakeGit(tips={"b1": T1, "b2": T2},
                   history={"HEAD": [T1, C3]}, all_history=[])
    received: dict = {}

    def fake_sync_pull(repo, branch="", since="", haves=""):
        received["haves"] = haves
        return {"status": "ok", "head": T1, "dump": b""}

    ctx = _ctx()
    monkeypatch.setattr(ctx.client, "sync_pull", fake_sync_pull)
    monkeypatch.setattr("lgm_core.ops_git.subprocess.run", git)

    op_pull(ctx, {"repo": "r", "project": str(tmp_path)})

    assert received["haves"].split(",") == [T1, T2, C3]


def test_pull_explicit_haves_skips_collection(monkeypatch, tmp_path):
    git = _FakeGit(tips={"b1": T1}, history={}, all_history=[])
    received: dict = {}

    def fake_sync_pull(repo, branch="", since="", haves=""):
        received["haves"] = haves
        return {"status": "ok", "head": T1, "dump": b""}

    ctx = _ctx()
    monkeypatch.setattr(ctx.client, "sync_pull", fake_sync_pull)
    monkeypatch.setattr("lgm_core.ops_git.subprocess.run", git)

    op_pull(ctx, {"repo": "r", "project": str(tmp_path), "haves": T2})

    assert received["haves"] == T2
    assert git.collect_cmds == []


def test_pull_non_git_project_collects_nothing(monkeypatch, tmp_path):
    git = _FakeGit(tips={}, history={}, all_history=[])
    received: dict = {}

    def fake_sync_pull(repo, branch="", since="", haves=""):
        received["haves"] = haves
        return {"status": "ok", "head": T1, "dump": b""}

    ctx = _ctx()
    monkeypatch.setattr(ctx.client, "sync_pull", fake_sync_pull)
    monkeypatch.setattr("lgm_core.ops_git.subprocess.run", git)

    op_pull(ctx, {"repo": "r", "project": str(tmp_path)})

    assert received["haves"] == ""


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
