"""Unit tests for multi-MR mr_send: target resolution, mirror-tip dedup,
single-bundle packing with ^exclusions. Git and HTTP are faked at the seams."""
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.ops import Ctx, get_op, op_mr_send
from lgm_core.render import render


def _ctx(sync_password: str = "pw") -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="",
                    sync_password=sync_password)
    client = MirrorClient(base_url=config.base_url, api_key="",
                          sync_password=sync_password)
    return Ctx(config=config, client=client)


class _FakeGit:
    """Dispatches the git subprocess calls mr_send makes.

    tips: branch -> sha after fetch; local_refs: branches already existing as
    refs/heads/* (others take the update-ref path); known_shas: commits
    `cat-file -e` confirms (exclusion candidates).
    """

    def __init__(self, tips: dict, local_refs: set, known_shas: set,
                 revlist_count: int = 5):
        self.tips = tips
        self.local_refs = local_refs
        self.known_shas = known_shas
        self.revlist_count = revlist_count
        self.fetch_cmds: list[list[str]] = []
        self.bundle_cmds: list[list[str]] = []
        self.update_refs: list[str] = []

    def __call__(self, cmd, cwd=None, capture_output=None, text=None, timeout=None):
        args = cmd[3:] if cmd[1] == "-C" else cmd[1:]
        proj = cmd[2] if cmd[1] == "-C" else cwd

        def ok(stdout=""):
            return subprocess.CompletedProcess(cmd, 0, stdout=stdout, stderr="")

        def fail(msg="err"):
            return subprocess.CompletedProcess(cmd, 1, stdout="", stderr=msg)

        if args[0] == "fetch":
            self.fetch_cmds.append(args)
            return ok()
        if args[0] == "update-ref":
            self.update_refs.append(args[1])
            return ok()
        if args[:2] == ["rev-parse", "--verify"]:
            ref = args[2]
            branch = ref.removeprefix("refs/heads/")
            return ok(self.tips[branch] + "\n") if branch in self.local_refs else fail()
        if args[0] == "rev-parse":
            branch = args[1].removeprefix("refs/heads/")
            return ok(self.tips[branch] + "\n") if branch in self.tips else fail()
        if args[0] == "cat-file" and args[1] == "-e":
            sha = args[2].removesuffix("^{commit}")
            return ok() if sha in self.known_shas else fail()
        if args[0] == "rev-list" and args[1] == "--count":
            return ok(str(self.revlist_count))
        if args[0] == "bundle" and args[1] == "create":
            self.bundle_cmds.append(args[3:])
            Path(proj, ".git").mkdir(exist_ok=True)
            Path(args[2]).write_bytes(b"fake-bundle")
            return ok()
        raise AssertionError(f"unexpected git call: {cmd}")


class _FakeClient:
    def __init__(self, mrs: dict, mirror_refs: dict, refs_error: bool = False):
        self.mrs = mrs  # iid -> source_branch
        self.mirror_refs = mirror_refs
        self.refs_error = refs_error
        self.sent_bundles: list[bytes] = []
        self.list_calls = 0
        self.get_calls: list[int] = []

    def gitlab_get_mr(self, iid):
        self.get_calls.append(iid)
        return {"iid": iid, "source_branch": self.mrs[iid]}

    def gitlab_list_mrs(self):
        self.list_calls += 1
        return [{"iid": i, "source_branch": b} for i, b in self.mrs.items()]

    def sync_refs(self, repo):
        if self.refs_error:
            raise LgmError("http", "Repository not found")
        return {"success": True, "refs": self.mirror_refs}

    def sync_send(self, repo, bundle):
        self.sent_bundles.append(bundle)
        return {"e": "ok"}


def _run(monkeypatch, tmp_path, fake_git, fake_client, args):
    args = {"repo": "r", "project": str(tmp_path), **args}
    ctx = _ctx()
    for name in ("gitlab_get_mr", "gitlab_list_mrs", "sync_refs", "sync_send"):
        monkeypatch.setattr(ctx.client, name, getattr(fake_client, name))
    monkeypatch.setattr("lgm_core.ops.subprocess.run", fake_git)
    return op_mr_send(ctx, args)


def test_multi_iids_dedup_one_fetch_one_bundle(monkeypatch, tmp_path):
    git = _FakeGit(
        tips={"b1": "t1", "b2": "t2", "b3": "t3"},
        local_refs={"b1", "b2"},  # b3 takes the update-ref path
        known_shas={"m1"},
    )
    client = _FakeClient(
        mrs={41: "b1", 42: "b2"},
        mirror_refs={"master": {"sha": "m1"}, "b2": {"sha": "t2"}},
    )
    res = _run(monkeypatch, tmp_path, git, client,
               {"iids": "41,42", "branch": "b2,b3"})

    # dedup by branch: b2 requested twice (iid 42 + --branch) but fetched once
    assert len(git.fetch_cmds) == 1
    refspecs = git.fetch_cmds[0][2:]
    assert refspecs == [f"+refs/heads/{b}:refs/remotes/origin/{b}"
                        for b in ("b1", "b2", "b3")]
    assert git.update_refs == ["refs/heads/b3"]

    # b2 tip already on mirror -> skipped, not bundled
    assert [t["branch"] for t in res["sent"]] == ["b1", "b3"]
    assert [t["branch"] for t in res["skipped"]] == ["b2"]

    # ONE bundle: both branches, shared mirror tip as the only exclusion
    assert len(git.bundle_cmds) == 1
    bundle_args = git.bundle_cmds[0]
    assert bundle_args == ["refs/heads/b1", "refs/heads/b3", "^m1"]
    assert len(client.sent_bundles) == 1
    assert res["send"]["excluded_bases"] == 1


def test_all_skipped_means_no_upload(monkeypatch, tmp_path):
    git = _FakeGit(tips={"b1": "t1"}, local_refs={"b1"}, known_shas=set())
    client = _FakeClient(mrs={41: "b1"},
                         mirror_refs={"b1": {"sha": "t1"}})
    res = _run(monkeypatch, tmp_path, git, client, {"iid": 41})
    assert res["sent"] == [] and [t["branch"] for t in res["skipped"]] == ["b1"]
    assert "nothing new" in res["message"]
    assert client.sent_bundles == [] and git.bundle_cmds == []


def test_single_iid_backward_compatible(monkeypatch, tmp_path):
    git = _FakeGit(tips={"b1": "t1"}, local_refs=set(), known_shas=set())
    client = _FakeClient(mrs={5: "b1"}, mirror_refs={})
    res = _run(monkeypatch, tmp_path, git, client, {"iid": 5})
    assert res["sent"] == [{"iid": 5, "branch": "b1", "tip": "t1"}]
    assert git.bundle_cmds[0] == ["refs/heads/b1"]
    assert len(client.sent_bundles) == 1


def test_all_open_uses_mr_list_without_per_mr_gets(monkeypatch, tmp_path):
    git = _FakeGit(tips={"b1": "t1", "b2": "t2"},
                   local_refs={"b1", "b2"}, known_shas=set())
    client = _FakeClient(mrs={41: "b1", 42: "b2"}, mirror_refs={})
    res = _run(monkeypatch, tmp_path, git, client, {"all_open": True})
    assert client.list_calls == 1 and client.get_calls == []
    assert [t["branch"] for t in res["sent"]] == ["b1", "b2"]


def test_mirror_shas_not_present_locally_are_not_excluded(monkeypatch, tmp_path):
    git = _FakeGit(tips={"b1": "t1"}, local_refs={"b1"}, known_shas=set())
    client = _FakeClient(mrs={41: "b1"},
                         mirror_refs={"master": {"sha": "ghost-sha"}})
    res = _run(monkeypatch, tmp_path, git, client, {"iid": 41})
    assert git.bundle_cmds[0] == ["refs/heads/b1"]
    assert res["send"]["excluded_bases"] == 0


def test_mirror_unreachable_sends_full_bundle(monkeypatch, tmp_path):
    git = _FakeGit(tips={"b1": "t1"}, local_refs={"b1"}, known_shas=set())
    client = _FakeClient(mrs={41: "b1"}, mirror_refs={}, refs_error=True)
    res = _run(monkeypatch, tmp_path, git, client, {"iid": 41})
    assert git.bundle_cmds[0] == ["refs/heads/b1"]
    assert [t["branch"] for t in res["sent"]] == ["b1"]


def test_branch_fully_covered_by_other_mirror_refs_is_skipped(monkeypatch, tmp_path):
    # tip differs from the mirror's ref of the same name, but every commit is
    # reachable from another mirror ref (rev-list count 0) -> nothing to bundle
    git = _FakeGit(tips={"b1": "t1"}, local_refs={"b1"}, known_shas={"m1"},
                   revlist_count=0)
    client = _FakeClient(mrs={41: "b1"},
                         mirror_refs={"b1": {"sha": "old1"}, "master": {"sha": "m1"}})
    res = _run(monkeypatch, tmp_path, git, client, {"iid": 41})
    assert res["sent"] == []
    assert [t["branch"] for t in res["skipped"]] == ["b1"]
    assert "nothing new" in res["message"]
    assert client.sent_bundles == [] and git.bundle_cmds == []


def test_iids_reject_garbage(monkeypatch, tmp_path):
    git = _FakeGit({}, set(), set())
    client = _FakeClient({}, {})
    with pytest.raises(LgmError) as ei:
        _run(monkeypatch, tmp_path, git, client, {"iids": "41,abc"})
    assert "--iids" in ei.value.message


def test_no_targets_is_a_config_error(monkeypatch, tmp_path):
    git = _FakeGit({}, set(), set())
    client = _FakeClient({}, {})
    with pytest.raises(LgmError) as ei:
        _run(monkeypatch, tmp_path, git, client, {})
    assert "--iid" in ei.value.message


def test_registry_params():
    op = get_op("mr_send")
    params = {p.name: p for p in op.params}
    assert params["iid"].type == "int" and params["iid"].default == 0
    assert params["iids"].type == "str" and params["iids"].default == ""
    assert params["all_open"].type == "bool" and params["all_open"].default is False
    assert params["project"].required is True and params["repo"].required is True


def test_render_mr_send_multi():
    out = render("mr_send", {
        "repo": "r",
        "sent": [{"iid": 41, "branch": "b1", "tip": "abc123def"}],
        "skipped": [{"iid": 42, "branch": "b2", "tip": "fed321"}],
        "send": {"bundle_size": 10, "excluded_bases": 2, "response": {"e": "x"}},
    })
    assert "[sent]    b1 !41" in out
    assert "[skipped] b2 !42" in out
    assert "excluded bases: 2" in out
    assert "OK (envelope response)" in out
    out = render("mr_send", {"repo": "r", "sent": [], "skipped": [
        {"iid": 1, "branch": "b", "tip": "x"}], "message": "nothing new"})
    assert "nothing new" in out
