"""Kotlin<->Python parity contract for the mr_send dedup decision: one shared
fixture (tests/fixtures/mr_send_scenarios.json), two runners — this file and
idea-plugin MrSendPlannerTest.kt. See docs/mr-send-contract.md."""
import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.client import MirrorClient
from lgm_core.config import Config
from lgm_core.ops import Ctx, op_mr_send

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "mr_send_scenarios.json"
SCENARIOS = json.loads(FIXTURE.read_text(encoding="utf-8"))


def _ctx(sync_password: str = "pw") -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="",
                    sync_password=sync_password)
    client = MirrorClient(base_url=config.base_url, api_key="",
                          sync_password=sync_password)
    return Ctx(config=config, client=client)


class _FakeGit:
    """Dispatches the git subprocess calls mr_send makes; field semantics
    follow test_mr_send_multi.py (tips, local_refs, known_shas,
    revlist_count)."""

    def __init__(self, tips: dict, local_refs: set, known_shas: set,
                 revlist_count: int = 5):
        self.tips = tips
        self.local_refs = local_refs
        self.known_shas = known_shas
        self.revlist_count = revlist_count
        self.bundle_cmds: list[list[str]] = []

    def __call__(self, cmd, cwd=None, capture_output=None, text=None, timeout=None):
        args = cmd[3:] if cmd[1] == "-C" else cmd[1:]

        def ok(stdout=""):
            return subprocess.CompletedProcess(cmd, 0, stdout=stdout, stderr="")

        def fail(msg="err"):
            return subprocess.CompletedProcess(cmd, 1, stdout="", stderr=msg)

        if args[0] == "fetch":
            return ok()
        if args[0] == "update-ref":
            return ok()
        if args[:2] == ["rev-parse", "--verify"]:
            branch = args[2].removeprefix("refs/heads/")
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
            Path(args[2]).write_bytes(b"fake-bundle")
            return ok()
        raise AssertionError(f"unexpected git call: {cmd}")


class _FakeClient:
    def __init__(self, mirror_refs: dict):
        self.mirror_refs = mirror_refs
        self.sent_bundles: list[bytes] = []

    def gitlab_get_mr(self, iid):
        raise AssertionError(f"unexpected gitlab_get_mr({iid})")

    def gitlab_list_mrs(self):
        raise AssertionError("unexpected gitlab_list_mrs()")

    def sync_refs(self, repo):
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


@pytest.mark.parametrize("scenario", SCENARIOS, ids=lambda s: s["name"])
def test_mr_send_matches_shared_fixture(monkeypatch, tmp_path, scenario):
    git = _FakeGit(
        tips=dict(scenario["local_tips"]),
        local_refs=set(scenario["local_tips"]),
        known_shas=set(scenario["existing_shas"]),
        revlist_count=scenario["new_commit_count"],
    )
    client = _FakeClient(mirror_refs=scenario["mirror_refs"])
    res = _run(monkeypatch, tmp_path, git, client,
               {"branch": ",".join(scenario["branches"])})

    exp = scenario["expected"]
    assert [t["branch"] for t in res["sent"]] == exp["sent"]
    assert [t["branch"] for t in res["skipped"]] == exp["skipped"]
    if exp["sent"]:
        assert "message" not in res
        assert len(git.bundle_cmds) == 1
        assert git.bundle_cmds[0] == [f"refs/heads/{b}" for b in exp["sent"]] + \
            [f"^{s}" for s in exp["exclude_shas"]]
        assert len(client.sent_bundles) == 1
    else:
        assert "nothing new" in res["message"]
        assert git.bundle_cmds == []
        assert client.sent_bundles == []
